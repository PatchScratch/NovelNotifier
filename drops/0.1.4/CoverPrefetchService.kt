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

/**
 * Downloads missing covers one series page at a time while a notification is showing.
 *
 * Why a Service, not a Composable WebView:
 *   CoverPrefetcher in CoverUi.kt dies when you leave the list screen.
 *   This class is a *foreground service* — Android keeps it alive after you
 *   leave the app, but it MUST show a notification the whole time.
 *
 * Timing:
 *   8 seconds between successful hits (GAP_MS).
 *   20 seconds after a "Just a moment" Cloudflare page (CF_WAIT_MS).
 *   Queue is stored in SharedPreferences so a process kill can resume.
 *
 * Manifest (already documented in README):
 *   FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC permissions,
 *   and a <service android:foregroundServiceType="dataSync"> entry.
 *   Those attributes go on the <service> tag, not on <application>.
 */
class CoverPrefetchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var stopped = false
    private var tries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWork()
            return START_NOT_STICKY
        }
        stopped = false
        applySavedCookies(getSharedPreferences("novel_prefs", MODE_PRIVATE))
        ensureChannel()
        val notification = buildNotification(statusText(this))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        handler.post { processNext() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun processNext() {
        if (stopped) return
        val lists = loadListQueue(this)
        if (lists.isNotEmpty()) {
            processList(lists.first())
            return
        }
        val queue = loadQueue(this)
        if (queue.isEmpty()) {
            writeStatus(this, "Cover prefetch finished")
            stopWork()
            return
        }
        processCover(queue.first())
    }

    private fun webViewOrCreate(): WebView {
        return webView ?: WebView(applicationContext).also { created ->
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)
            webView = created
        }
    }

    /** Load one reading-list page so series URLs exist for lists the user never opened. */
    private fun processList(list: ReadingList) {
        val remaining = loadListQueue(this).size
        writeStatus(this, "Reading list ${list.name} · $remaining left")
        updateNotification(statusText(this))
        tries = 0
        val view = webViewOrCreate()
        val target = "https://www.novelupdates.com/reading-list/?list=${list.id}"
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView?, url: String?) {
                val script = """
                    (function() {
                      if (document.body && document.body.innerText.indexOf('Just a moment') !== -1) {
                        return 'CF';
                      }
                      var novels = [];
                      function isJunkTitle(title) {
                        return /^(series information|description|latest release|status|notes|latest series)$/i.test(title);
                      }
                      document.querySelectorAll('a[href*="/series/"]').forEach(function(a) {
                        var title = (a.textContent || '').replace(/\s+/g, ' ').trim();
                        var href = a.href || '';
                        if (!title || isJunkTitle(title)) return;
                        if (!/\/series\/[a-z0-9-]+/i.test(href)) return;
                        var chapter = '';
                        var img = '';
                        var hasListMark = false;
                        var node = a.parentElement;
                        for (var k = 0; k < 8 && node; k++) {
                          var text = node.textContent || '';
                          var m = text.match(/\[\s*([^\]]+)\]/);
                          if (!chapter && m && /c\d+|v\d+|ch\s*\d+/i.test(m[1])) chapter = m[1].trim();
                          if (node.querySelector && (
                            node.querySelector('input[id^="chklist"]') ||
                            node.querySelector('input[type="checkbox"]')
                          )) hasListMark = true;
                          var image = node.querySelector ? node.querySelector('img') : null;
                          if (image && image.src && image.src.indexOf('data:') !== 0 && image.src.indexOf('icon') === -1) {
                            if (!img) img = image.src;
                          }
                          node = node.parentElement;
                        }
                        if (!chapter && !hasListMark) return;
                        novels.push(title + '===' + (chapter || 'No chapter info') + '===' + href + '===' + img);
                      });
                      return novels.join('|||');
                    })();
                """.trimIndent()
                page?.evaluateJavascript(script) { raw ->
                    val result = unwrapJsResult(raw)
                    when {
                        result == "CF" && tries < 1 -> {
                            tries += 1
                            writeStatus(this@CoverPrefetchService, "Site check — waiting")
                            updateNotification(statusText(this@CoverPrefetchService))
                            handler.postDelayed({ page.loadUrl(target) }, CF_WAIT_MS)
                        }
                        result == "CF" -> {
                            rotateFirstList(this@CoverPrefetchService)
                            handler.postDelayed({ processNext() }, CF_WAIT_MS)
                        }
                        else -> {
                            mergeHydratedList(this@CoverPrefetchService, list, result)
                            dropFirstList(this@CoverPrefetchService)
                            rebuildCoverQueue(this@CoverPrefetchService)
                            handler.postDelayed({ processNext() }, GAP_MS)
                        }
                    }
                }
            }
        }
        view.loadUrl(target)
    }

    private fun processCover(next: Novel) {
        writeStatus(this, "${loadQueue(this).size} covers left")
        updateNotification(statusText(this))
        tries = 0
        val view = webViewOrCreate()
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView?, url: String?) {
                val script = """
                    (function() {
                      if (document.body && document.body.innerText.indexOf('Just a moment') !== -1) {
                        return 'CF';
                      }
                      var img = document.querySelector('.seriesimg img, .serieseditimg img');
                      return img ? (img.src || '') : '';
                    })();
                """.trimIndent()
                page?.evaluateJavascript(script) { raw ->
                    val cover = unwrapJsResult(raw)
                    when {
                        cover.startsWith("http") -> {
                            saveOneCover(this@CoverPrefetchService, next.title, cover)
                            dropFirst(this@CoverPrefetchService)
                            handler.postDelayed({ processNext() }, GAP_MS)
                        }
                        cover == "CF" && tries < 1 -> {
                            tries += 1
                            writeStatus(this@CoverPrefetchService, "Site check — waiting")
                            updateNotification(statusText(this@CoverPrefetchService))
                            handler.postDelayed({
                                page.loadUrl(next.seriesUrl)
                            }, CF_WAIT_MS)
                        }
                        else -> {
                            rotateFirst(this@CoverPrefetchService)
                            handler.postDelayed({ processNext() }, CF_WAIT_MS)
                        }
                    }
                }
            }
        }
        view.loadUrl(next.seriesUrl)
    }

    private fun stopWork() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cover prefetch",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("NovelNotifier")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setSilent(true)
        .addAction(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, CoverPrefetchService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "cover_prefetch"
        const val ACTION_STOP = "cover_prefetch_stop"
        private const val NOTIF_ID = 42
        private const val GAP_MS = 8_000L
        private const val CF_WAIT_MS = 20_000L
        private const val QUEUE_KEY = "cover_prefetch_queue"
        private const val LIST_QUEUE_KEY = "cover_prefetch_lists"
        private const val STATUS_KEY = "cover_prefetch_status"

        /**
         * Walk every Update-on list that has no series URLs yet, then fetch
         * missing covers for the whole account (not just the open list).
         */
        fun startAll(context: Context, coverCache: Map<String, String>, updateOff: Set<String>) {
            val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            applySavedCookies(prefs)
            val lists = listsToHydrate(context, updateOff)
            val covers = missingCoverQueue(context, coverCache, updateOff)
            saveListQueue(context, lists)
            saveQueue(context, covers)
            writeStatus(
                context,
                when {
                    lists.isNotEmpty() -> "Queued ${lists.size} lists then ${covers.size} covers · 8s apart"
                    covers.isNotEmpty() -> "Queued ${covers.size} covers · 8s apart"
                    else -> "All cached series already have covers"
                }
            )
            if (lists.isEmpty() && covers.isEmpty()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CoverPrefetchService::class.java)
            )
        }

        /** Save [queue] then start the service. Empty queue is a no-op. */
        fun start(context: Context, queue: List<Novel>) {
            saveListQueue(context, emptyList())
            saveQueue(context, queue)
            writeStatus(context, if (queue.isEmpty()) "All cached series already have covers" else "Queued ${queue.size} covers · 8s apart")
            if (queue.isEmpty()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CoverPrefetchService::class.java)
            )
        }

        fun stop(context: Context) {
            writeStatus(context, "Stopped")
            context.stopService(Intent(context, CoverPrefetchService::class.java))
        }

        fun statusText(context: Context): String {
            return context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString(STATUS_KEY, "Idle") ?: "Idle"
        }

        fun writeStatus(context: Context, text: String) {
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE).edit {
                putString(STATUS_KEY, text)
            }
        }

        fun saveQueue(context: Context, queue: List<Novel>) {
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE).edit {
                putString(QUEUE_KEY, queue.joinToString("\n") { "${it.title}|||${it.seriesUrl}" })
            }
        }

        fun loadQueue(context: Context): List<Novel> {
            val stored = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString(QUEUE_KEY, "") ?: ""
            if (stored.isBlank()) return emptyList()
            return stored.split("\n").mapNotNull { row ->
                val parts = row.split("|||", limit = 2)
                if (parts.size == 2 && parts[1].isNotBlank()) {
                    Novel(parts[0], "", parts[1], "")
                } else null
            }
        }

        fun dropFirst(context: Context) {
            val next = loadQueue(context).drop(1)
            saveQueue(context, next)
        }

        /** Move the first queued series to the end after a failed / CF skip. */
        fun rotateFirst(context: Context) {
            val queue = loadQueue(context)
            if (queue.isEmpty()) return
            saveQueue(context, queue.drop(1) + queue.first())
        }

        fun saveOneCover(context: Context, title: String, url: String) {
            val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            val updated = loadCoverCache(prefs) + (title to url)
            saveCoverCache(prefs, updated)
        }

        fun saveListQueue(context: Context, lists: List<ReadingList>) {
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE).edit {
                putString(LIST_QUEUE_KEY, lists.joinToString("\n") { "${it.id}|||${it.name}" })
            }
        }

        fun loadListQueue(context: Context): List<ReadingList> {
            val stored = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString(LIST_QUEUE_KEY, "") ?: ""
            if (stored.isBlank()) return emptyList()
            return stored.split("\n").mapNotNull { row ->
                val parts = row.split("|||", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) ReadingList(parts[0], parts[1]) else null
            }
        }

        fun dropFirstList(context: Context) {
            saveListQueue(context, loadListQueue(context).drop(1))
        }

        fun rotateFirstList(context: Context) {
            val queue = loadListQueue(context)
            if (queue.isEmpty()) return
            saveListQueue(context, queue.drop(1) + queue.first())
        }

        fun mergeHydratedList(context: Context, list: ReadingList, raw: String) {
            if (raw.isBlank()) return
            val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            val covers = loadCoverCache(prefs).toMutableMap()
            val parsed = raw.split("|||").mapNotNull { item ->
                val bits = item.split("===", limit = 4)
                if (bits.size < 3) return@mapNotNull null
                val title = bits[0].trim()
                val seriesUrl = bits.getOrNull(2).orEmpty()
                val cover = bits.getOrNull(3).orEmpty()
                if (title.isBlank() || !seriesUrl.contains("/series/")) return@mapNotNull null
                if (cover.startsWith("http")) covers[title] = cover
                Novel(
                    title = title,
                    latestChapter = bits[1],
                    seriesUrl = seriesUrl,
                    coverUrl = covers[title].orEmpty().ifBlank { cover }
                )
            }.distinctBy { it.title }
            if (parsed.isEmpty()) return
            saveCoverCache(prefs, covers)
            val snap = loadListSnapshot(context)
            val nextMap = putExclusiveList(snap.novelsByList, list.id, parsed)
            saveListSnapshot(
                context,
                snap.copy(
                    lists = if (snap.lists.any { it.id == list.id }) snap.lists else snap.lists + list,
                    novelsByList = nextMap
                )
            )
        }

        fun rebuildCoverQueue(context: Context) {
            val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            val updateOff = loadIdSet(prefs, "list_update_off")
            saveQueue(context, missingCoverQueue(context, loadCoverCache(prefs), updateOff))
        }
    }
}
