package com.patchscratch.novelnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
 * app came forward: the shared SiteClient WebView survives screen-off.
 * cannot be found"), and the whole queue failed (user report). This service
 * uses SiteClient's shared WebView (Phase 4a)
 * to download hundreds of pages overnight — so bulk downloads keep running
 * with the screen off.
 *
 * The download logic is the reader's proven flow, ported: ToC-first (harvest
 * chapter links from every page SiteClient loads, chapter dropdowns
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

    private var session: SiteClient.Session? = null
    private var job: BulkJob? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        session?.close()
        session = null
        super.onDestroy()
    }

    // --- the download loop ---------------------------------------------------

    private fun nuHosted(url: String) =
        url.startsWith("https://www.novelupdates.com/") || url.startsWith("http://www.novelupdates.com/")

    private fun sessionOrCreate(): SiteClient.Session {
        return session ?: SiteClient.openSession(SiteClient.Site.NU).also {
            session = it
        }
    }

    /** Load [url], wait for the main frame to finish, then run the extractor
     *  (with one retry for JS-rendering hosts). Null = load or extract failed. */
    private var lastLoadedUrl = ""
    private suspend fun loadAndExtract(url: String): ChapterExtract? {
        lastLoadedUrl = url
        val sess = sessionOrCreate()
        extractResult = null
        extractFailed = false
        // Session.loadUrl handles page load + CF detection + JS evaluation.
        // The extraction has a retry (some JS-rendering hosts need time).
        val raw = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<String> { cont ->
                sess.loadUrl(url, chapterExtractJs()) { res ->
                    cont.resume(res)
                }
            }
        } ?: return null // page never finished (locked/paused/dead host)
        if (raw == "CF") return null
        extractResult = parseChapterExtract(unwrapJsResult(raw))
        if (extractResult == null) {
            // Retry extraction at ~5s for JS-rendering hosts
            delay(3800)
            val retry = suspendCancellableCoroutine<String> { cont ->
                sess.evaluateJavascript(chapterExtractJs()) { res -> cont.resume(res) }
            }
            extractResult = parseChapterExtract(retry)
        }
        return extractResult
    }

    private suspend fun runBulk(job: BulkJob) {
        val context = this
        try {
            val known = withContext(Dispatchers.IO) {
                EpubStore.readAllStoredDocs(context, job.seriesUrl).map { it.url }.toSet()
            }
            // ToCs keyed by HOST: multi-host series (different chapters →
            // different translator sites) need each site's own chapter list;
            // hosts bootstrap lazily on first touch (see pass 2).
            val tocByHost = HashMap<String, HashMap<Pair<Int, Int>, String>>()
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

            var lastLoadedUrl = ""
            fun currentHost(): String? {
                val url = lastLoadedUrl.ifBlank { return null }
                return try {
                    java.net.URI(url).host
                } catch (e: Exception) {
                    null
                }
            }

            /** Harvest the loaded page's chapter list under its host. */
            suspend fun harvestCurrentPage(parentScope: Boolean) {
                val host = currentHost() ?: return
                val map = tocByHost.getOrPut(host) { HashMap() }
                val sess = session ?: return
                val raw = suspendCancellableCoroutine<String> { cont ->
                    sess.evaluateJavascript(tocHarvestJs(parentScope)) { res -> cont.resume(res) }
                }
                val before = map.size
                parseTocHarvest(raw).forEach { link ->
                    link.key?.let { k -> map.putIfAbsent(k, link.url) }
                }
                DebugLog.log("EPUB", "harvest[$host] +${map.size - before} (total ${map.size})")
            }

            /** Resolve a chapter key across host ToCs; a number claimed by
             *  exactly one host resolves, multiple hosts = ambiguous → null. */
            fun resolveToc(key: Pair<Int, Int>): String? {
                val hits = tocByHost.values.mapNotNull { it[key] ?: it[0 to key.second] }.distinct()
                return if (hits.size == 1) hits[0] else null
            }

            /** After a page load on some host: harvest its ToC, and when the
             *  page itself carries no chapter list, try the host's series page
             *  (derived from the page URL — same host, zero NU hits). */
            suspend fun bootstrapHostToC() {
                harvestCurrentPage(parentScope = true)
                delay(1500)
                harvestCurrentPage(parentScope = true)
                val host = currentHost() ?: return
                val map = tocByHost[host] ?: return
                if (map.isNotEmpty()) return
                val pageUrl = lastLoadedUrl.ifBlank { return }
                val seriesUrl = pageUrl.trimEnd('/').substringBeforeLast('/')
                if (!seriesUrl.startsWith("http") || seriesUrl == pageUrl) return
                status("reading ToC from the series page ($host)")
                loadAndExtract(seriesUrl)
                delay(2000)
                harvestCurrentPage(parentScope = false)
                delay(1500)
                harvestCurrentPage(parentScope = false)
            }

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
                    harvestCurrentPage(parentScope = true)
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
                        withContext(Dispatchers.IO) {
                            EpubStore.appendChapter(
                                context, job.seriesTitle, job.seriesUrl,
                                chapter.url, chapter.label, extract, job.meta
                            )
                        }
                        saved++; viaToc++
                        consecutiveFailures = 0
                    } else {
                        failed++; consecutiveFailures++
                    }
                    delay(4000)
                } else {
                    // First touch: the NU link's redirect reveals the chapter's
                    // real host; harvest its ToC so later siblings resolve.
                    status("NU first-touch · ${chapter.label}")
                    val extract = loadAndExtract(chapter.url)
                    if (extract == null) {
                        DebugLog.log("EPUB", "first-touch failed: ${chapter.label} at ${chapter.url}")
                    }
                    if (extract != null) {
                        withContext(Dispatchers.IO) {
                            EpubStore.appendChapter(
                                context, job.seriesTitle, job.seriesUrl,
                                chapter.url, chapter.label, extract, job.meta
                            )
                        }
                        saved++; viaNu++
                        consecutiveFailures = 0
                        bootstrapHostToC()
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
