package com.patchscratch.novelnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Translator-host hardening probe (Wayne's request, 2026-10-15): walk every
 * host the library's cached series actually link to, run the REAL reader
 * extraction (chapterExtractJs) on one chapter each, and record a verdict —
 * so copy-protection that would silently break reading/TTS/ePub gets found
 * and fixed before it bites.
 *
 * Runs as a foreground service on SiteClient's shared WebView (survives
 * screen-off — the bulk-ePub pattern). Politeness: one chapter per host
 * (plus a second only when the first fails), 10 s between hosts. The report
 * lands in filesDir/host_probe_report.json, rewritten after every entry so
 * a morning pull has partial data even if the run dies.
 */
class HostProbeService : Service() {

    data class Target(val series: String, val url: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: SiteClient.Session? = null

    companion object {
        const val REPORT = "host_probe_report.json"
        var probeAlive = false
            private set

        /**
         * ONE chapter per cached SERIES — the releases tables store NU's
         * /extnu redirect links, so the real host only reveals itself
         * after the redirect lands; the run loop dedupes by final host.
         */
        fun buildTargets(context: Context): List<Target> {
            val out = mutableListOf<Target>()
            val dir = context.filesDir.resolve("series_cache")
            dir.listFiles().orEmpty().forEach { f ->
                try {
                    val json = JSONObject(f.readText())
                    val page = json.optJSONObject("page") ?: return@forEach
                    val title = page.optString("title").ifBlank { "series" }
                    val rel = page.optJSONArray("releases") ?: return@forEach
                    // Most recent release is the best sample (live group).
                    for (i in rel.length() - 1 downTo 0) {
                        val url = rel.optJSONObject(i)?.optString("url").orEmpty()
                        if (url.startsWith("http")) {
                            out.add(Target(title, url))
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return out
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, HostProbeService::class.java)
            )
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("host_probe", "Translator-site probe", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        probeAlive = true
        startForeground(
            47,
            NotificationCompat.Builder(this, "host_probe")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Translator-site probe")
                .setContentText("starting")
                .setOngoing(true)
                .setSilent(true)
                .addAction(0, "Stop", android.app.PendingIntent.getService(
                    this, 5, Intent(this, HostProbeService::class.java).setAction("STOP"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                ))
                .build()
        )
        scope.launch { runProbe() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        probeAlive = false
        scope.cancel()
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun note(text: String) {
        DebugLog.log("PROBE", text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            47,
            NotificationCompat.Builder(this, "host_probe")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Translator-site probe")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    private suspend fun runProbe() {
        val targets = withContext(Dispatchers.IO) { buildTargets(this@HostProbeService) }
        if (targets.isEmpty()) {
            note("no cached chapters to probe")
            stopSelf()
            return
        }
        val sess = session ?: SiteClient.openSession(SiteClient.Site.NU).also { session = it }
        val report = JSONArray()
        val seenHosts = mutableSetOf<String>()
        var consecutiveFailures = 0
        try {
            for ((i, t) in targets.withIndex()) {
                val entry = probeOne(sess, t, seenHosts)
                withContext(Dispatchers.IO) {
                    report.put(entry)
                    File(filesDir, REPORT).writeText(report.toString())
                }
                note("${i + 1}/${targets.size} ${entry.optString("host")}: ${entry.optString("verdict")}")
                val verdict = entry.optString("verdict")
                consecutiveFailures = if (verdict.startsWith("OK") || verdict == "SKIP") 0 else consecutiveFailures + 1
                if (consecutiveFailures >= 40) {
                    DebugLog.log("PROBE", "aborting: 40 consecutive failures (network died?)")
                    break
                }
                delay(10_000) // one page per host, politely spaced
            }
        } finally {
            withContext(Dispatchers.IO) {
                File(filesDir, REPORT).writeText(report.toString())
            }
            note("done — ${report.length()} sites probed")
            DebugLog.log("PROBE", "run complete: ${report.length()} entries in $REPORT")
        }
    }

    /** Load the chapter through the shared WebView and run the real extractor.
     *  [seenHosts] dedupes after the extnu redirect reveals the real host. */
    private suspend fun probeOne(sess: SiteClient.Session, t: Target, seenHosts: MutableSet<String>): JSONObject {
        val entry = JSONObject().put("series", t.series).put("url", t.url)
        val raw = withTimeoutOrNull(75_000) {
            suspendCancellableCoroutine<String> { cont ->
                sess.loadUrl(t.url, chapterExtractJs()) { res ->
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(res))
                }
            }
        }
        val finalUrl = sess.currentUrl().orEmpty()
        val host = try {
            java.net.URI(finalUrl).host ?: ""
        } catch (_: Exception) {
            ""
        }
        entry.put("host", host)
        if (host.endsWith("novelupdates.com")) {
            return entry.put("verdict", "SKIP").put("note", "landed on NU itself")
        }
        if (host.isNotBlank()) seenHosts.add(host)
        when {
            raw == null -> return entry.put("verdict", "TIMEOUT").put("note", "page never finished")
            raw == "CF" -> return entry.put("verdict", "CLOUDFLARE")
        }
        val parsed = parseChapterExtract(unwrapJsResult(raw))
        if (parsed != null) {
            return entry.put("verdict", "OK")
                .put("paragraphs", parsed.paragraphs.size)
                .put("chars", parsed.paragraphs.sumOf { it.length })
        }
        // Failed — the WHY matters. Give the page one more beat (JS hosts),
        // then run the stage diagnostics.
        delay(6000)
        val retry = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String> { cont ->
                sess.evaluateJavascript(chapterExtractJs()) { res ->
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(res))
                }
            }
        }
        val parsed2 = retry?.let { parseChapterExtract(unwrapJsResult(it)) }
        if (parsed2 != null) {
            return entry.put("verdict", "OK-SLOW") // needed the extra beat — TTS/ePub must too
                .put("paragraphs", parsed2.paragraphs.size)
                .put("chars", parsed2.paragraphs.sumOf { it.length })
        }
        val diag = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String> { cont ->
                sess.evaluateJavascript(probeDiagJs()) { res ->
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(res))
                }
            }
        } ?: ""
        return entry.put("verdict", "FAIL")
            .put("extractSaid", (raw.take(40)))
            .put("note", unwrapJsResult(diag).take(220))
    }
}

/**
 * Stage diagnostics for a failing host — the reader-debugging probes from
 * the foxteller session, distilled: container candidates, visible prose
 * volume, fixed-position decoy count, cipher-suspicion ratio, image-chapter
 * hint, and the page title (Cloudflare splashes announce themselves).
 */
fun probeDiagJs(): String = """
(function() {
  try {
    var title = (document.title || '').slice(0, 60);
    var known = '';
    var sels = ['div.js-episode-body','div.chapter-inner','.cha-words','.novel-content',
      '#chr-content','.chapter-content','#chapter-c','.post-content','.entry-content'];
    for (var i = 0; i < sels.length && !known; i++) {
      if (document.querySelector(sels[i])) known = sels[i];
    }
    var ps = document.querySelectorAll('p');
    var vis = 0, chars = 0, fixed = 0;
    for (var j = 0; j < ps.length && j < 400; j++) {
      var p = ps[j];
      var st = window.getComputedStyle(p);
      var r = p.getBoundingClientRect();
      if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) <= 0.01 || r.height <= 0) continue;
      var t = (p.innerText || '').trim();
      if (t.length > 1) { vis++; chars += t.length; }
    }
    var all = document.querySelectorAll('*');
    for (var k = 0; k < all.length && k < 4000; k++) {
      try { if (window.getComputedStyle(all[k]).position === 'fixed') fixed++; } catch (e) {}
    }
    var imgs = document.querySelectorAll('img');
    var bigImgs = 0;
    for (var m = 0; m < imgs.length; m++) {
      var ir = imgs[m].getBoundingClientRect();
      if (ir.height > 300) bigImgs++;
    }
    var body = (document.body ? document.body.innerText || '' : '');
    var sus = 0;
    for (var c = 0; c < body.length; c++) {
      var code = body.charCodeAt(c);
      if ((code >= 0x2F00 && code <= 0x2FDF) || (code >= 0xE000 && code <= 0xF8FF)) sus++;
    }
    return 'title=' + title + ' known=' + (known || 'none') + ' p=' + ps.length +
      ' vis=' + vis + ' chars=' + chars + ' fixed=' + fixed +
      ' bigImgs=' + bigImgs + ' sus%=' + Math.round(100 * sus / Math.max(1, body.length)) +
      ' bodyChars=' + body.length;
  } catch (e) {
    return 'diag-error ' + (e && e.message ? e.message : e);
  }
})()
""".trimIndent()
