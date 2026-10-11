package com.patchscratch.novelnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Phase 3.3.1 — bulk ePub download as a FOREGROUND SERVICE (2026-10-12).
 *
 * The reader-hosted bulk loop died the moment the screen locked or another
 * app came forward: Activity WebViews pause, loads stall mid-flight ("page
 * cannot be found"), and the whole queue failed (user report). This service
 * hosts its own WebView — the same pattern the library sync engine has used
 * to download hundreds of pages overnight — so bulk downloads keep running
 * with the screen off.
 *
 * The download logic is the reader's proven flow, ported: ToC-first (harvest
 * chapter links from every page the WebView loads, chapter dropdowns
 * included), single-NU-visit bootstrap for all-NU series, series-page
 * fallback when chapter pages carry no ToC, volume-tolerant (vol,ch)→key
 * matching with per-chapter ambiguity, extraction retry for JS-rendering
 * hosts, 4 s courtesy gaps to translator hosts, 10 s between NU failovers.
 *
 * The reader starts this via start(); progress shows in the notification and
 * in prefs (statusText) for the reader UI to display.
 */
class EpubBulkService : Service() {

    data class BulkChapter(val url: String, val label: String)
    data class BulkJob(
        val seriesTitle: String,
        val seriesUrl: String,
        val chapters: List<BulkChapter>,
        val meta: EpubStore.BookMeta?
    )

    private var webView: WebView? = null
    private var job: BulkJob? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadTick = 0          // main-frame onPageFinished count
    private var extractResult: ChapterExtract? = null
    private var extractFailed = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        when (intent?.action) {
            ACTION_STOP -> {
                writeStatus(this, "Bulk stopped")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val parsed = readBulkJob(this)
        if (parsed == null || parsed.chapters.isEmpty()) {
            writeStatus(this, "Nothing to download")
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT < 33
        ) {
            // pre-13 always has permission; on 13+ the notification is simply hidden
        }
        job = parsed
        serviceAlive = true
        startInForeground(parsed.seriesTitle)
        scope.launch { runBulk(parsed) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceAlive = false
        scope.cancel()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    // --- the download loop ---------------------------------------------------

    private fun nuHosted(url: String) =
        url.startsWith("https://www.novelupdates.com/") || url.startsWith("http://www.novelupdates.com/")

    private fun webViewOrCreate(): WebView {
        return webView ?: WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // bulk never needs popups; ad hosts stay blocked like the reader
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loadTick++
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val host = request?.url?.host ?: return false
                    return isBlockedReaderHost(host)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val host = request?.url?.host ?: return null
                    if (!isBlockedReaderHost(host)) return null
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            webView = this
        }
    }

    /** Load [url], wait for the main frame to finish, then run the extractor
     *  (with one retry for JS-rendering hosts). Null = load or extract failed. */
    private suspend fun loadAndExtract(url: String): ChapterExtract? {
        val wv = webViewOrCreate()
        extractResult = null
        extractFailed = false
        val tickBefore = loadTick
        wv.loadUrl(url)
        val loaded = withTimeoutOrNull(60_000) {
            while (loadTick <= tickBefore && scope.isActive) delay(500)
            true
        }
        if (loaded == null) return null // page never finished (locked/paused/dead host)
        // extraction attempt 1 at ~1.2 s, retry at ~5 s
        extractResult = null
        delay(1200)
        extractResult = evaluateExtract()
        if (extractResult == null) {
            delay(3800)
            extractResult = evaluateExtract()
        }
        return extractResult
    }

    private suspend fun evaluateExtract(): ChapterExtract? {
        val wv = webView ?: return null
        return suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(chapterExtractJs()) { res ->
                cont.resume(parseChapterExtract(res))
            }
        }
    }

    /** Harvest same-series chapter links from the currently loaded page. */
    private suspend fun harvestInto(toc: HashMap<Pair<Int, Int>, String>, parentScope: Boolean) {
        val wv = webView ?: return
        val raw = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(tocHarvestJs(parentScope)) { res -> cont.resume(res) }
        }
        parseTocHarvest(raw).forEach { link ->
            link.key?.let { k -> toc.putIfAbsent(k, link.url) }
        }
    }

    private suspend fun runBulk(job: BulkJob) {
        val context = this
        try {
            val known = withContext(Dispatchers.IO) {
                EpubStore.readAllStoredDocs(context, job.seriesUrl).map { it.url }.toSet()
            }
            val toc = HashMap<Pair<Int, Int>, String>()
            var saved = 0
            var failed = 0
            var skipped = 0
            var viaToc = 0
            var viaNu = 0
            var consecutiveFailures = 0
            val total = job.chapters.size

            fun status(line: String) {
                writeStatus(context, line)
                updateNotification("$saved/$total saved · $line")
                // logcat mirror doubles as the adb progress window when the
                // app's notifications are off (fresh installs until granted)
                DebugLog.log("EPUB", "bulk $saved/$total: $line")
            }
            status("starting")

            // ---- pass 1: direct links, 4 s apart, harvesting as we go -------
            val pendingNu = mutableListOf<BulkChapter>()
            for (chapter in job.chapters) {
                if (!scope.isActive) return
                if (chapter.url in known) { skipped++; continue }
                if (nuHosted(chapter.url)) { pendingNu.add(chapter); continue }
                status("direct · ${chapter.label}")
                val extract = loadAndExtract(chapter.url)
                if (extract != null) {
                    val count = withContext(Dispatchers.IO) {
                        EpubStore.appendChapter(
                            context, job.seriesTitle, job.seriesUrl,
                            chapter.url, chapter.label, extract, job.meta
                        )
                    }
                    if (count > 0) saved++
                    consecutiveFailures = 0
                    // keep enriching the ToC only while it can't cover the rest
                    if (toc.size < pendingNu.size + 8) {
                        harvestInto(toc, parentScope = true)
                        delay(3000)
                        harvestInto(toc, parentScope = true)
                    }
                } else {
                    failed++; consecutiveFailures++
                }
                if (consecutiveFailures >= 8) {
                    status("stopped — too many failures (site check needed?)")
                    DebugLog.log("EPUB", "bulk ${job.seriesTitle}: aborting, 8 consecutive failures")
                    stopSelf()
                    return
                }
                delay(4000)
            }

            // ---- bootstrap: ONE NU visit when everything routes through NU --
            val volsByNum = HashMap<Int, MutableSet<Int>>()
            pendingNu.forEach { ch ->
                val k = chapterKey(ch.label) ?: return@forEach
                volsByNum.getOrPut(k.second) { mutableSetOf() }.add(k.first)
            }

            if (pendingNu.isNotEmpty() && toc.size < pendingNu.size) {
                val boot = pendingNu.first()
                status("reading ToC from ${boot.label} (single NU visit)")
                val extract = loadAndExtract(boot.url)
                if (extract != null) {
                    val count = withContext(Dispatchers.IO) {
                        EpubStore.appendChapter(
                            context, job.seriesTitle, job.seriesUrl,
                            boot.url, boot.label, extract, job.meta
                        )
                    }
                    if (count > 0) { saved++; viaNu++ }
                } else {
                    failed++
                }
                harvestInto(toc, parentScope = true)
                delay(3000)
                harvestInto(toc, parentScope = true)
                // series-page fallback when chapter pages carry no ToC
                if (toc.size < pendingNu.size) {
                    val chapterUrl = webView?.url ?: ""
                    val seriesUrl = chapterUrl.trimEnd('/').substringBeforeLast('/')
                    if (seriesUrl.startsWith("http") && seriesUrl != chapterUrl) {
                        status("reading ToC from the series page")
                        loadAndExtract(seriesUrl) // ignore the extract; we want the page loaded
                        delay(2000)
                        harvestInto(toc, parentScope = false)
                        if (toc.size < pendingNu.size) harvestInto(toc, parentScope = false)
                    }
                }
            }

            // volume-tolerant ToC resolution with per-chapter ambiguity
            fun resolveToc(key: Pair<Int, Int>): String? {
                toc[key]?.let { return it }
                val (vol, ch) = key
                val vols = volsByNum[ch] ?: return null
                return if (vols == setOf(vol)) toc[0 to ch] else null
            }

            // ---- pass 2: NU-routed chapters — ToC direct, else slow NU ------
            for (chapter in pendingNu) {
                if (!scope.isActive) return
                if (chapter.url in known) { skipped++; continue }
                val key = chapterKey(chapter.label)
                val directUrl = key?.let { resolveToc(it) }
                if (directUrl != null && !nuHosted(directUrl)) {
                    status("ToC-resolved · ${chapter.label}")
                    val extract = loadAndExtract(directUrl)
                    if (extract != null) {
                        val count = withContext(Dispatchers.IO) {
                            EpubStore.appendChapter(
                                context, job.seriesTitle, job.seriesUrl,
                                chapter.url, chapter.label, extract, job.meta
                            )
                        }
                        if (count > 0) { saved++; viaToc++ }
                        consecutiveFailures = 0
                    } else {
                        failed++; consecutiveFailures++
                    }
                    delay(4000)
                } else {
                    status("NU failover · ${chapter.label}")
                    val extract = loadAndExtract(chapter.url)
                    if (extract != null) {
                        val count = withContext(Dispatchers.IO) {
                            EpubStore.appendChapter(
                                context, job.seriesTitle, job.seriesUrl,
                                chapter.url, chapter.label, extract, job.meta
                            )
                        }
                        if (count > 0) { saved++; viaNu++ }
                        consecutiveFailures = 0
                    } else {
                        failed++; consecutiveFailures++
                    }
                    delay(10_000) // engine-grade courtesy gap between NU hits
                }
                if (consecutiveFailures >= 8) {
                    status("stopped — too many failures (site check needed?)")
                    DebugLog.log("EPUB", "bulk ${job.seriesTitle}: aborting, 8 consecutive failures")
                    stopSelf()
                    return
                }
            }

            val summary = "Bulk done · saved $saved (ToC $viaToc · NU $viaNu) · skipped $skipped · failed $failed"
            writeStatus(context, summary)
            DebugLog.log("EPUB", "bulk ${job.seriesTitle}: $summary")
            updateNotification("done · $saved chapters")
            stopSelf()
        } catch (e: Exception) {
            DebugLog.log("EPUB", "bulk service crashed", e)
            writeStatus(context, "Bulk failed: ${e.message}")
            stopSelf()
        }
    }

    // --- notification --------------------------------------------------------

    private fun startInForeground(seriesTitle: String) {
        val notification = buildNotification("starting · $seriesTitle").build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Offline library")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .addAction(0, "Stop", stopPendingIntent())
            .setContentIntent(openAppPendingIntent())

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text).build())
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, ACTION_STOP.hashCode(),
        Intent(this, EpubBulkService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val CHANNEL_ID = "epub_bulk"
        const val ACTION_STOP = "epub_bulk_stop"
        private const val NOTIF_ID = 45
        private const val STATUS_KEY = "epub_bulk_status"
        private const val JOB_FILE = "epub_bulk_job.json"

        fun statusText(context: Context): String =
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString(STATUS_KEY, "") ?: ""

        /** True only while the service process is alive — a killed service
         *  leaves stale status text behind (no onDestroy guarantee), and the
         *  reader must not offer "Stop" for a dead download. */
        @Volatile var serviceAlive: Boolean = false

        fun running(context: Context): Boolean = serviceAlive

        private fun writeStatus(context: Context, text: String) {
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE).edit()
                .putString(STATUS_KEY, text).apply()
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EpubBulkService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Write the job file and (re)start the service. */
        fun start(context: Context, job: BulkJob) {
            saveBulkJob(context, job)
            writeStatus(context, "starting · ${job.seriesTitle}")
            val intent = Intent(context, EpubBulkService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+ background-start guard — from the reader we are
                // foreground, so this only fires on odd entry points.
                DebugLog.log("EPUB", "could not start bulk service", e)
                writeStatus(context, "Could not start download (open the app and retry)")
            }
        }

        fun saveBulkJob(context: Context, job: BulkJob) {
            val json = JSONObject()
                .put("title", job.seriesTitle)
                .put("url", job.seriesUrl)
            job.meta?.let { m ->
                json.put("meta", JSONObject()
                    .put("title", m.title).put("description", m.description)
                    .put("associatedNames", m.associatedNames).put("type", m.type)
                    .put("genres", m.genres).put("tags", m.tags).put("author", m.author)
                    .put("artist", m.artist).put("year", m.year)
                    .put("completelyTranslated", m.completelyTranslated)
                    .put("originalPublisher", m.originalPublisher))
            }
            val arr = JSONArray()
            job.chapters.forEach { arr.put(JSONObject().put("u", it.url).put("c", it.label)) }
            json.put("chapters", arr)
            context.filesDir.resolve(JOB_FILE).writeText(json.toString())
        }

        fun readBulkJob(context: Context): BulkJob? {
            return try {
            val file = context.filesDir.resolve(JOB_FILE)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val meta = json.optJSONObject("meta")?.let { m ->
                EpubStore.BookMeta(
                    title = m.optString("title"), description = m.optString("description"),
                    associatedNames = m.optString("associatedNames"), type = m.optString("type"),
                    genres = m.optString("genres"), tags = m.optString("tags"),
                    author = m.optString("author"), artist = m.optString("artist"),
                    year = m.optString("year"), completelyTranslated = m.optString("completelyTranslated"),
                    originalPublisher = m.optString("originalPublisher")
                )
            }
            val arr = json.optJSONArray("chapters") ?: return null
            BulkJob(
                seriesTitle = json.optString("title"),
                seriesUrl = json.optString("url"),
                chapters = (0 until arr.length()).mapNotNull { i ->
                    val row = arr.optJSONObject(i) ?: return@mapNotNull null
                    BulkChapter(row.optString("u"), row.optString("c"))
                },
                meta = meta
            )
            } catch (e: Exception) {
                null
            }
        }
    }
}
