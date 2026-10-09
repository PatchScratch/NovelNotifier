package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * The library bulk downloader. One hidden WebView walks the job queue in
 * filesDir/sync_queue.json (see SyncQueue for the ordering contract):
 * lists → all covers → all chapter pages, priority lists first.
 *
 * Rules carried over from the CoverPrefetchService era:
 *   8s between site hits, 20s after a Cloudflare page, one retry, then the
 *   queue pauses and the notification asks the user to open the app (a
 *   windowless WebView cannot complete a challenge). Cover *downloads* hit
 *   NU's CDN directly — unpaced — so they run on a background executor.
 *
 * The persistent notification is the progress counter the user asked for:
 * "Lists 3/15 · Covers 120/1419 · Chapters 12/1419 · next: <title>".
 */
class SyncEngineService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var webView: WebView? = null
    private var stopped = false
    private var pausedByUser = false
    private var cfRetries = 0

    @Volatile private var lastSiteHitAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pausedByUser = true
                writeStatus(this, "Paused — resume from Settings")
                updateNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                pausedByUser = false
                handler.post { scheduleNext() }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopWork(complete = false)
                return START_NOT_STICKY
            }
        }
        stopped = false
        engineAlive = true
        applySavedCookies(getSharedPreferences("novel_prefs", MODE_PRIVATE))
        ensureChannel()
        startInForeground()
        handler.post { scheduleNext() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        engineAlive = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification(progressLine()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---- pacing + scheduling -------------------------------------------------
    //
    // Invariant: at most ONE Novel Updates page hit per GAP_MS, tracked by
    // lastSiteHitAt. Everything else — cover bytes from the CDN, queue
    // bookkeeping, cache writes on the executor — is unpaced, because the
    // image CDN is a plain file server, not the scrape-protected site.

    private fun scheduleNext() {
        if (stopped || pausedByUser) return
        val jobs = SyncQueue.load(this)
        val job = jobs.firstOrNull { !it.done }
        if (job == null) {
            stopWork(complete = true)
            return
        }
        cfRetries = 0
        if (job.type == "cover" && usableCoverUrl(job.coverUrl).isNotBlank()) {
            // Known URL: CDN download only — no site hit, so no pacing wait.
            updateNotification(progressLine(job))
            executor.execute {
                runBlocking { CoverStore.download(this@SyncEngineService, job.seriesUrl, job.coverUrl) }
                saveOneCover(this@SyncEngineService, job.title, job.coverUrl)
                markDone(job)
                handler.post {
                    if (!stopped && !pausedByUser) scheduleNext()
                }
            }
            return
        }
        val wait = (lastSiteHitAt + GAP_MS) - System.currentTimeMillis()
        updateNotification(progressLine(job))
        if (wait > 0) handler.postDelayed({ executeSiteJob(job) }, wait) else executeSiteJob(job)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun executeSiteJob(job: SyncJob) {
        if (stopped || pausedByUser) return
        val view = webViewOrCreate()
        // Reserve this hit's slot in the pacing window before the load starts.
        lastSiteHitAt = System.currentTimeMillis()
        // One WebViewClient per job: onPageFinished picks the script from the
        // job type, so a single shared WebView can serve every kind of job.
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView?, url: String?) {
                val script = when (job.type) {
                    "list", "check" -> listPageJs()
                    "cover" -> coverJs()
                    else -> seriesPageJs()
                }
                page?.evaluateJavascript(script) { result ->
                    val raw = unwrapJsResult(result)
                    if (raw == "CF" || isCloudflareUrl(url)) {
                        handleCloudflare(job)
                        return@evaluateJavascript
                    }
                    executor.execute { processResult(job, raw) }
                }
            }
        }
        when (job.type) {
            "list", "check" -> view.loadUrl(
                if (job.key.isBlank()) "https://www.novelupdates.com/reading-list/"
                else "https://www.novelupdates.com/reading-list/?list=${job.key}"
            )
            "cover" -> view.loadUrl(job.seriesUrl)
            else -> {
                val base = job.seriesUrl.trimEnd('/')
                view.loadUrl(if (job.nextPage <= 1) "$base/" else "$base/?pg=${job.nextPage}")
            }
        }
    }

    /**
     * Cloudflare policy: one quick retry after CF_WAIT_MS (managed challenges
     * often clear themselves inside a real WebView), then park the queue and
     * ask the user to open the app — a windowless service WebView cannot
     * complete an interactive challenge. While parked we keep retrying every
     * CF_RESUME_MS in case the cookie clears itself; any successful parse
     * resumes normal work.
     */
    private fun handleCloudflare(job: SyncJob) {
        cfRetries += 1
        if (cfRetries <= 1) {
            writeStatus(this, "Site check — waiting")
            updateNotification()
            DebugLog.log("SYNC", "CF on ${job.type} '${job.title}' — 20s retry")
            handler.postDelayed({ executeSiteJob(job) }, CF_WAIT_MS)
        } else {
            markSiteCheckNeeded(getSharedPreferences("novel_prefs", MODE_PRIVATE))
            writeStatus(this, "Site check needed — open the app")
            updateNotification()
            DebugLog.log("SYNC", "CF persisted on '${job.title}' — paused, will retry in 60s")
            handler.postDelayed({
                cfRetries = 0
                if (!stopped && !pausedByUser) scheduleNext()
            }, CF_RESUME_MS)
        }
    }

    // ---- job processing (background executor) --------------------------------

    private fun processResult(job: SyncJob, raw: String) {
        when (job.type) {
            "list" -> processList(job, raw)
            "check" -> processCheck(job, raw)
            "cover" -> processCover(job, raw)
            "pages" -> processPages(job, raw)
        }
    }

    /**
     * Periodic chapter check (RSS-free devices): scrape one list, diff rows
     * against the cache, then for every higher chapter number: write the list
     * row, queue a page-1 series fetch so releases merge, and notify — the
     * same "higher c-number only" rule the RSS path uses.
     */
    private fun processCheck(job: SyncJob, raw: String) {
        val prefs = getSharedPreferences("novel_prefs", MODE_PRIVATE)
        val coverCache = loadCoverCache(prefs)
        val snap = loadListSnapshot(this)
        val oldCoverByUrl = snap.novelsByList[job.key].orEmpty()
            .mapNotNull { n -> n.seriesUrl.takeIf { it.isNotBlank() }?.let { it to usableCoverUrl(n.coverUrl) } }
            .toMap()
        val parsed = parseListPageResult(raw, coverCache, oldCoverByUrl)
        maybeSaveRss(parsed.rssUrl, parsed.readRssUrl)
        val oldByUrl = snap.novelsByList[job.key].orEmpty()
            .filter { it.seriesUrl.isNotBlank() }
            .associateBy { it.seriesUrl }

        val changed = mutableListOf<ChapterUpdate>()
        parsed.novels.forEach { row ->
            if (row.seriesUrl.isBlank()) return@forEach
            val previous = oldByUrl[row.seriesUrl]
            val incoming = chapterNum(row.latestChapter)
            val saved = listOf(
                chapterNum(cachedChapter(this, row.seriesUrl)),
                chapterNum(previous?.latestChapter.orEmpty()),
                chapterNum(loadLastRead(prefs)[row.title]?.first.orEmpty())
            ).maxOrNull() ?: -1
            if (incoming > 0 && incoming > saved) {
                changed += ChapterUpdate(
                    novel = row,
                    incomingChapter = row.latestChapter,
                    previousChapter = if (saved > 0) "c$saved" else previous?.latestChapter.orEmpty()
                )
            }
        }

        // Merge the scrape into the cache exactly like a "list" job.
        val stampedRows = stampChapterTimes(snap.novelsByList[job.key].orEmpty(), parsed.novels)
        val withCovers = stampedRows.map { row ->
            if (usableCoverUrl(row.coverUrl).isBlank()) {
                row.copy(coverUrl = oldCoverByUrl[row.seriesUrl].orEmpty())
            } else row
        }
        val nextMap = putExclusiveList(snap.novelsByList, job.key, withCovers)
        saveListSnapshot(this, snap.copy(novelsByList = nextMap))

        if (changed.isNotEmpty()) {
            val notifyOff = loadIdSet(prefs, "list_notify_off")
            val seriesOff = loadIdSet(prefs, "series_notify_off")
            val known = SyncQueue.load(this).map { it.id }.toSet()
            val followUps = changed
                .filter { "pages:${it.novel.seriesUrl}" !in known }
                .map {
                    SyncJob("pages", it.novel.seriesUrl, it.novel.title, it.novel.seriesUrl, nextPage = 1, maxPage = 1)
                }
            if (followUps.isNotEmpty()) SyncQueue.save(this, SyncQueue.load(this) + followUps)
            changed.forEach { applyListChapter(this, it.novel.title, it.incomingChapter) }
            val toAlert = changed.filter { shouldNotify(this, it.novel.title, notifyOff, seriesOff) }
            if (toAlert.isNotEmpty()) postChapterNotifications(this, toAlert)
            DebugLog.log("CHECK", "list '${job.title}' changed=${changed.size} notified=${toAlert.size}")
        } else {
            DebugLog.log("CHECK", "list '${job.title}' no changes")
        }
        markDone(job)
        handler.post { if (!stopped && !pausedByUser) scheduleNext() }
    }

    /** Capture the personal feed links NU shows on reading-list pages (once). */
    private fun maybeSaveRss(rssUrl: String, readRssUrl: String = "") {
        val prefs = getSharedPreferences("novel_prefs", MODE_PRIVATE)
        if (rssUrl.isNotBlank() && prefs.getString("rss_url", "").isNullOrBlank()) {
            prefs.edit { putString("rss_url", rssUrl) }
            DebugLog.log("SYNC", "captured releases feed: $rssUrl")
        }
        if (readRssUrl.isNotBlank() && prefs.getString("rss_read_url", "").isNullOrBlank()) {
            prefs.edit { putString("rss_read_url", readRssUrl) }
            DebugLog.log("SYNC", "captured read-activity feed: $readRssUrl")
        }
    }

    private fun processList(job: SyncJob, raw: String) {
        val prefs = getSharedPreferences("novel_prefs", MODE_PRIVATE)
        val coverCache = loadCoverCache(prefs)
        val snap = loadListSnapshot(this)
        val oldCoverByUrl = snap.novelsByList[job.key].orEmpty()
            .mapNotNull { n -> n.seriesUrl.takeIf { it.isNotBlank() }?.let { it to usableCoverUrl(n.coverUrl) } }
            .toMap()
        val parsed = parseListPageResult(raw, coverCache, oldCoverByUrl)
        maybeSaveRss(parsed.rssUrl, parsed.readRssUrl)

        if (job.key.isBlank()) {
            // Discovery: learn the user's list IDs from the base reading-list page,
            // attribute the displayed rows to the first list, queue the rest.
            if (parsed.lists.isEmpty()) {
                DebugLog.log("SYNC", "list discovery found nothing — retrying")
                handler.post { handleCloudflare(job) }
                return
            }
            val firstList = parsed.lists.first()
            val stamped = stampChapterTimes(emptyList(), parsed.novels)
            val nextMap = putExclusiveList(snap.novelsByList, firstList.id, stamped)
            val mergedLists = parsed.lists.fold(snap.lists) { acc, l ->
                if (acc.any { it.id == l.id }) acc else acc + l
            }
            saveListSnapshot(this, snap.copy(lists = mergedLists, novelsByList = nextMap))
            DebugLog.log("SYNC", "discovered ${parsed.lists.size} lists; first '${firstList.name}' rows=${parsed.novels.size}")
            val known = SyncQueue.load(this).map { it.id }.toSet()
            val followUps = parsed.lists
                .filter { it.id.isNotBlank() && "list:${it.id}" !in known }
                .map { SyncJob("list", it.id, it.name) }
            if (followUps.isNotEmpty()) {
                SyncQueue.save(this, SyncQueue.load(this) + followUps)
                DebugLog.log("SYNC", "queued ${followUps.size} per-list jobs")
            }
        } else {
            val stampedRows = stampChapterTimes(snap.novelsByList[job.key].orEmpty(), parsed.novels)
            val withCovers = stampedRows.map { row ->
                if (usableCoverUrl(row.coverUrl).isBlank()) {
                    row.copy(coverUrl = oldCoverByUrl[row.seriesUrl].orEmpty())
                } else row
            }
            val nextMap = putExclusiveList(snap.novelsByList, job.key, withCovers)
            val lists = if (snap.lists.any { it.id == job.key }) snap.lists else snap.lists + ReadingList(job.key, job.title)
            saveListSnapshot(this, snap.copy(lists = lists, novelsByList = nextMap))
            // Stash every usable cover URL we just learned.
            val learned = withCovers.filter { usableCoverUrl(it.coverUrl).isNotBlank() }
            val updatedCovers = coverCache.toMutableMap()
            learned.forEach { updatedCovers[it.title] = it.coverUrl }
            if (updatedCovers != coverCache) saveCoverCache(prefs, updatedCovers)
            DebugLog.log("SYNC", "hydrated list '${job.title}' rows=${withCovers.size}")
        }
        // Queue cover downloads for rows that learned a URL (unpaced CDN).
        updateCoverJobsWithKnownUrls()
        markDone(job)
        handler.post { if (!stopped && !pausedByUser) scheduleNext() }
    }

    private fun processCover(job: SyncJob, raw: String) {
        val cover = unwrapJsResult(raw)
        if (usableCoverUrl(cover).isNotBlank()) {
            saveOneCover(this, job.title, cover)
            runBlocking { CoverStore.download(this@SyncEngineService, job.seriesUrl, cover) }
            DebugLog.log("SYNC", "cover ok '${job.title}'")
        } else {
            DebugLog.log("SYNC", "cover unavailable '${job.title}'")
        }
        markDone(job)
        handler.post { if (!stopped && !pausedByUser) scheduleNext() }
    }

    private fun processPages(job: SyncJob, raw: String) {
        val parsed = parseSeriesPage(raw)
        val cachedPair = loadSeriesCache(this, job.seriesUrl)
        val old = cachedPair?.first ?: SeriesPage()
        val merged = mergeReleases(old.releases, parsed.releases)
        val maxPage = maxOf(old.maxPage, parsed.maxPage, job.maxPage)
        val page = old.copy(
            title = parsed.title.ifBlank { old.title },
            author = parsed.author.ifBlank { old.author },
            rating = parsed.rating.ifBlank { old.rating },
            description = parsed.description.ifBlank { old.description },
            genres = parsed.genres.ifBlank { old.genres },
            tags = parsed.tags.ifBlank { old.tags },
            type = parsed.type.ifBlank { old.type },
            status = parsed.status.ifBlank { old.status },
            coverUrl = usableCoverUrl(parsed.coverUrl).ifBlank { old.coverUrl },
            sid = parsed.sid.ifBlank { old.sid },
            releases = merged,
            maxPage = maxPage
        )
        saveSeriesCache(this, job.seriesUrl, page, maxOf(job.nextPage, cachedPair?.second ?: 0))
        // Carry the site's read ticks into last_read (merge keeps the higher chapter).
        val siteRead = highestReadRelease(merged)
        if (siteRead != null) {
            val prefs = getSharedPreferences("novel_prefs", MODE_PRIVATE)
            saveLastRead(
                prefs,
                mergeLastReads(loadLastRead(prefs), mapOf(job.title to (siteRead.chapter to siteRead.url)))
            )
        }
        if (parsed.releases.isEmpty()) {
            // Empty parse for a page we expected: count it as far as it went.
            DebugLog.log("SYNC", "pages empty pg=${job.nextPage} '${job.title}'")
        } else {
            clearSiteCheckNeeded(getSharedPreferences("novel_prefs", MODE_PRIVATE))
        }
        DebugLog.log("SYNC", "pages pg=${job.nextPage}/$maxPage rows=${parsed.releases.size} '${job.title}'")
        // A "pages" job advances (nextPage+1) until the table's last page;
        // an empty parse means the site served nothing for that page, so stop
        // with what we have instead of looping on it forever.
        if (job.nextPage >= maxPage || parsed.releases.isEmpty()) {
            markDone(job)
        } else {
            advance(job, job.nextPage + 1, maxPage)
        }
        handler.post { if (!stopped && !pausedByUser) scheduleNext() }
    }

    /** Cover jobs whose URL we learned from list scrapes can skip site hits. */
    private fun updateCoverJobsWithKnownUrls() {
        val prefs = getSharedPreferences("novel_prefs", MODE_PRIVATE)
        val covers = loadCoverCache(prefs)
        val jobs = SyncQueue.load(this).map { job ->
            if (job.type == "cover" && job.coverUrl.isBlank()) {
                val url = usableCoverUrl(covers[job.title])
                if (url.isNotBlank()) job.copy(coverUrl = url) else job
            } else job
        }
        SyncQueue.save(this, jobs)
    }

    private fun markDone(job: SyncJob) {
        val jobs = SyncQueue.load(this)
        SyncQueue.save(this, jobs.map { if (it.id == job.id) it.copy(done = true) else it })
    }

    private fun advance(job: SyncJob, nextPage: Int, maxPage: Int) {
        val jobs = SyncQueue.load(this)
        SyncQueue.save(
            this,
            jobs.map { if (it.id == job.id) it.copy(nextPage = nextPage, maxPage = maxPage) else it }
        )
    }

    private fun stopWork(complete: Boolean) {
        stopped = true
        engineAlive = false
        handler.removeCallbacksAndMessages(null)
        if (complete) {
            SyncQueue.clear(this)
            writeStatus(this, "Library sync complete")
            DebugLog.log("SYNC", "queue complete")
        } else {
            writeStatus(this, "Stopped")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- notification ---------------------------------------------------------

    private fun progressLine(current: SyncJob? = null): String {
        val jobs = SyncQueue.load(this)
        fun part(type: String): String? {
            val ofType = jobs.filter { it.type == type }
            if (ofType.isEmpty()) return null
            val done = ofType.count { it.done }
            val label = when (type) {
                "list" -> "Lists"
                "cover" -> "Covers"
                else -> "Chapters"
            }
            return "$label $done/${ofType.size}"
        }
        val parts = listOfNotNull(part("list"), part("cover"), part("pages"))
        val next = current?.title ?: jobs.firstOrNull { !it.done }?.title.orEmpty()
        val body = if (parts.isEmpty()) "finishing…" else parts.joinToString(" · ")
        return if (next.isBlank()) body else "$body · next: ${next.take(28)}"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Library sync", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): NotificationCompat.Builder {
        val pauseAction = if (pausedByUser) "Resume" else "Pause"
        val pauseIntent = if (pausedByUser) ACTION_RESUME else ACTION_PAUSE
 return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NovelNotifier library sync")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, pauseAction, serviceIntent(pauseIntent))
            .addAction(0, "Stop", serviceIntent(ACTION_STOP))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
    }

    private fun serviceIntent(action: String): PendingIntent {
        return PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, SyncEngineService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(text: String = progressLine()) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text).build())
    }

    private fun webViewOrCreate(): WebView {
        return webView ?: WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webView = this
        }
    }

    companion object {
        const val CHANNEL_ID = "library_sync"
        const val ACTION_PAUSE = "sync_pause"
        const val ACTION_RESUME = "sync_resume"
        const val ACTION_STOP = "sync_stop"
        private const val NOTIF_ID = 43
        private const val GAP_MS = 8_000L
        private const val CF_WAIT_MS = 20_000L
        private const val CF_RESUME_MS = 60_000L
        private const val STATUS_KEY = "sync_status"

        /** True while the service process is alive — distinguishes a paused
         *  engine from one that died (reboot / kill) and should auto-resume. */
        @Volatile var engineAlive: Boolean = false

        fun statusText(context: Context): String {
            return context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString(STATUS_KEY, "Idle") ?: "Idle"
        }

        fun writeStatus(context: Context, text: String) {
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE).edit {
                putString(STATUS_KEY, text)
            }
        }

        fun running(context: Context): Boolean =
            statusText(context).let { it.startsWith("Running") || it.startsWith("Lists") || it.startsWith("Covers") || it.startsWith("Chapters") || it.startsWith("Site check — waiting") }

        /** Replace the queue and start. Empty queue is a no-op. */
        fun startWith(context: Context, jobs: List<SyncJob>) {
            if (jobs.isEmpty()) {
                writeStatus(context, "Nothing to sync")
                return
            }
            SyncQueue.save(context, jobs)
            writeStatus(context, "Running — queued ${jobs.size} jobs")
            ContextCompat.startForegroundService(context, Intent(context, SyncEngineService::class.java))
        }

        /** Resume an existing persisted queue (after reboot, CF pause, or stop). */
        fun resume(context: Context) {
            val pending = SyncQueue.pending(context)
            if (pending.isEmpty()) {
                writeStatus(context, "Idle")
                return
            }
            writeStatus(context, "Running — resuming ${pending.size} jobs")
            ContextCompat.startForegroundService(context, Intent(context, SyncEngineService::class.java))
        }

        fun stop(context: Context) {
            writeStatus(context, "Stopped")
            context.stopService(Intent(context, SyncEngineService::class.java))
        }

        /** Save one usable cover URL to the prefs cache (placeholder rejected). */
        fun saveOneCover(context: Context, title: String, url: String) {
            if (!url.startsWith("http") || isPlaceholderCover(url)) return
            val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            val updated = loadCoverCache(prefs) + (title to url)
            saveCoverCache(prefs, updated)
        }
    }
}
