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
        const val HARVEST = "host_probe_targets.json"
        var probeAlive = false
            private set

        const val EXTRA_MODE = "mode"
        const val MODE_LIBRARY = "library"     // one chapter per cached series
        const val MODE_DIRECTORY = "directory" // the full NU groups directory
        const val TARGETS_CHECKPOINT = "host_probe_targets.json"
        /** Groups per batch: ~12 min of NU walking, then a probe stretch
         *  (mostly non-NU hosts) that lets NU cool down. */
        const val GROUP_BATCH = 100

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

        fun start(context: Context, mode: String = MODE_LIBRARY) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HostProbeService::class.java).putExtra(EXTRA_MODE, mode)
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
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_LIBRARY
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
        scope.launch { if (mode == MODE_DIRECTORY) runDirectoryProbe() else runProbe() }
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
        runTargetList(targets)
    }

    /**
     * Full-directory sweep (Wayne's 2026-10-15 directive): harvest NU's
     * groups directory (list pages -> group pages -> a live release link),
     * then probe one chapter per group.
     */
    private suspend fun runDirectoryProbe() {
        val sess = session ?: SiteClient.openSession(SiteClient.Site.NU).also { session = it }
        // --- phase 1: walk the groups list pages -------------------------
        val groups = LinkedHashMap<String, String>()   // groupPageUrl -> title
        var page = 1
        while (page in 1..200) {
            val url = "https://www.novelupdates.com/groupslist/?pg=$page"
            val raw = loadJs(sess, url, harvestListJs()) ?: break
            if (raw == "CF") {
                DebugLog.log("PROBE", "groupslist page $page CF-blocked — waiting 30s")
                delay(30_000)
                continue
            }
            val found = parseHarvest(raw)
            if (found.isEmpty()) {
                DebugLog.log("PROBE", "groupslist page $page yielded no groups — end of list")
                break
            }
            val before = groups.size
            found.forEach { (u, t) -> if (u !in groups) groups[u] = t }
            note("harvest list page $page: +${groups.size - before} groups (total ${groups.size})")
            if (groups.size == before) break // repeat page — done
            page++
            delay(6_000)
        }

        // --- phase 2+3 interleaved: batched harvest → probe → harvest -----
        // Wayne's design (2026-10-16): never run a monolithic 14h NU walk
        // followed by a 22h probe pass. Walk GROUP_BATCH group pages, then
        // probe the chapters those pages yielded, then walk the next batch.
        // NU traffic gets a cooldown window during every probe stretch
        // (probes hit the translator hosts, not NU), and a batch boundary
        // is a natural place for a device restart or a minor-version
        // update install — the checkpoint + report skip pick up exactly
        // where the run stopped.
        val ckptFile = File(filesDir, TARGETS_CHECKPOINT)
        val ckpt = withContext(Dispatchers.IO) {
            runCatching {
                if (ckptFile.isFile) JSONObject(ckptFile.readText()) else JSONObject()
            }.getOrDefault(JSONObject())
        }
        var harvested = 0
        fun saveCkpt(groupUrl: String, title: String, chapter: String?) {
            ckpt.put(groupUrl, JSONArray().put(title).put(chapter ?: ""))
            harvested++
            if (harvested % 10 == 0) {
                runCatching { ckptFile.writeText(ckpt.toString()) }
            }
        }
        val batch = mutableListOf<Target>()
        var batchLinks = 0
        var walkedInBatch = 0   // checkpoint reuses count too — batches stay
                                // regular even on a resumed run's backlog
        for ((i, g) in groups.entries.withIndex()) {
            val done = ckpt.optJSONArray(g.key)
            val chapter: String?
            if (done != null) {
                // already harvested on a previous run — reuse, no fetch
                chapter = done.optString(1).takeIf { it.startsWith("http") }
            } else {
                // CF retries are CAPPED and logged: the pre-fix loop sat
                // silent for 2+ days on one challenge (rig 2026-10-20 —
                // service alive, zero log lines, notification blocked).
                // A CF-walled or dead group is skipped WITHOUT a checkpoint
                // so the next run retries it once.
                var raw: String? = null
                var cfTries = 0
                while (cfTries < 5) {
                    raw = loadJs(sess, g.key, harvestGroupJs())
                    if (raw != "CF") break
                    cfTries++
                    DebugLog.log("PROBE", "group ${g.value} CF-blocked (try $cfTries/5)")
                    delay(30_000)
                }
                if (raw == null) {
                    DebugLog.log("PROBE", "group ${g.value} load timed out — skipped this run")
                    continue
                }
                if (raw == "CF") {
                    DebugLog.log("PROBE", "group ${g.value} CF-walled 5× — skipped this run")
                    continue
                }
                chapter = parseHarvest(raw).entries.firstOrNull()?.key
                saveCkpt(g.key, g.value, chapter)
                delay(6_000)
            }
            if (chapter != null) batch.add(Target(g.value, chapter))
            batchLinks = if (chapter != null) batchLinks + 1 else batchLinks
            walkedInBatch++
            if (i % 10 == 0) note("groups ${i + 1}/${groups.size}: $batchLinks chapter links")
            val last = i == groups.size - 1
            if (batch.isNotEmpty() && (walkedInBatch >= GROUP_BATCH || last)) {
                note("batch: probing ${batch.size} chapters (group ${i + 1}/${groups.size})")
                runTargetList(batch, final = last)
                batch.clear()
                walkedInBatch = 0
            }
        }
        withContext(Dispatchers.IO) { runCatching { ckptFile.writeText(ckpt.toString()) } }
    }

    /**
     * Shared probe loop used by both modes. [final] = the caller has no more
     * batches (library mode, or the directory sweep's last group batch):
     * the service stops itself at the end. Directory batches keep running.
     */
    private suspend fun runTargetList(targets: List<Target>, final: Boolean = true) {
        if (targets.isEmpty()) {
            if (final) {
                note("nothing to probe")
                stopSelf()
            }
            return
        }
        val sess = session ?: SiteClient.openSession(SiteClient.Site.NU).also { session = it }
        // Resume: the report from a previous (interrupted) run is the input —
        // targets whose URL is already recorded are skipped without a fetch.
        // This is what makes a multi-day directory sweep movable between
        // devices (2026-10-16: tablet → dedicated rig) and restart-safe.
        val prior = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(filesDir, REPORT)
                if (f.isFile) JSONArray(f.readText()) else null
            }.getOrNull()
        }
        // Fix-on-the-fly (Wayne, 2026-10-20): ONLY OK/OK-SLOW/SKIP entries
        // count as done — FAIL/TIMEOUT/CLOUDFLARE hosts re-probe on every
        // run, so a batch's failures retry automatically once an extractor
        // fix ships in a minor-version install.
        val doneUrls = mutableSetOf<String>()
        if (prior != null) for (i in 0 until prior.length()) {
            val o = prior.optJSONObject(i) ?: continue
            val v = o.optString("verdict")
            val u = o.optString("url")
            if (u.isNotBlank() && (v.startsWith("OK") || v == "SKIP")) doneUrls.add(u)
        }
        val report = prior ?: JSONArray()
        val seenHosts = mutableSetOf<String>()
        var consecutiveFailures = 0
        var skipped = 0
        try {
            for ((i, t) in targets.withIndex()) {
                if (t.url in doneUrls) {
                    skipped++
                    continue   // no fetch, no pacing delay
                }
                val entry = probeOne(sess, t, seenHosts)
                withContext(Dispatchers.IO) {
                    report.put(entry)
                    File(filesDir, REPORT).writeText(report.toString())
                }
                note("${i + 1}/${targets.size} ${entry.optString("host")}: ${entry.optString("verdict")}")
                // Full entry on logcat — the release build's file is only
                // reachable via backup, so the analysis can run from adb.
                DebugLog.log("PROBE-ENTRY", entry.toString().take(400))
                val verdict = entry.optString("verdict")
                consecutiveFailures = if (verdict.startsWith("OK") || verdict == "SKIP") 0 else consecutiveFailures + 1
                if (consecutiveFailures >= 40) {
                    DebugLog.log("PROBE", "aborting: 40 consecutive failures (network died?)")
                    break
                }
                delay(10_000)
            }
        } finally {
            withContext(Dispatchers.IO) { File(filesDir, REPORT).writeText(report.toString()) }
            if (final) {
                note("done — ${report.length()} sites probed")
                DebugLog.log("PROBE", "run complete: ${report.length()} entries in $REPORT (skipped $skipped already-recorded targets)")
            } else {
                DebugLog.log("PROBE", "batch done — ${report.length()} entries so far (skipped $skipped recorded)")
            }
        }
    }

    private suspend fun loadJs(sess: SiteClient.Session, url: String, js: String): String? =
        withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<String> { cont ->
                sess.loadUrl(url, js) { res ->
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(res))
                }
            }
        }

    /** "url|title,url|title,…" -> pairs. */
    private fun parseHarvest(raw: String): Map<String, String> {
        val body = unwrapJsResult(raw).trim()
        if (body.isBlank() || body == "CF") return emptyMap()
        return body.split(',')
            .mapNotNull { item ->
                val parts = item.split('|')
                if (parts.size == 2 && parts[0].startsWith("http")) parts[0] to parts[1] else null
            }
            .toMap(LinkedHashMap())
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
        val parsed = parseChapterExtract(raw)
        if (parsed != null) {
            return entry.put("verdict", "OK")
                .put("paragraphs", parsed.paragraphs.size)
                .put("chars", parsed.paragraphs.sumOf { it.length })
                .put("quality", proseQuality(parsed.paragraphs))
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
        val parsed2 = retry?.let { parseChapterExtract(it) }
        if (parsed2 != null) {
            return entry.put("verdict", "OK-SLOW") // needed the extra beat — TTS/ePub must too
                .put("paragraphs", parsed2.paragraphs.size)
                .put("chars", parsed2.paragraphs.sumOf { it.length })
                .put("quality", proseQuality(parsed2.paragraphs))
        }
        val diag = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String> { cont ->
                sess.evaluateJavascript(probeDiagJs()) { res ->
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(res))
                }
            }
        } ?: ""
        // Parse forensics: the extractor returned SOMETHING — if it looks
        // like JSON, find out exactly why the Kotlin parse rejected it.
        val unwrapped = unwrapJsResult(raw)
        if (unwrapped.trimStart().startsWith("{")) {
            try {
                val obj = org.json.JSONObject(unwrapped)
                val arr = obj.optJSONArray("paragraphs")
                // Stage-by-stage: mirror parseChapterExtract exactly, but
                // record WHERE it would drop out.
                val n = arr?.length() ?: -1
                val nonBlank = (0 until (arr?.length() ?: 0)).count { i ->
                    arr!!.optString(i).trim().isNotBlank()
                }
                val viaRealParser = parseChapterExtract(unwrapped)
                entry.put(
                    "parseNote",
                    "jsonOk n=$n nonBlank=$nonBlank realParser=${viaRealParser?.paragraphs?.size ?: "null"}" +
                        " first=" + (arr?.optString(0) ?: "").take(40)
                )
            } catch (e: Exception) {
                entry.put("parseNote", "jsonThrow " + e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
            }
        }
        return entry.put("verdict", "FAIL")
            .put("extractSaid", (raw.take(120)))
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

/**
 * Groups-directory row harvester: every group-page link on a list page,
 * as "url|title,…" (absorbs NU's markup changes better than one selector).
 */
fun harvestListJs(): String = """
(function() {
  try {
    var out = [];
    var seen = {};
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var h = anchors[i].getAttribute('href') || '';
      if (h.indexOf('/group/') < 0) continue;
      var abs = anchors[i].href;
      if (seen[abs]) continue;
      seen[abs] = 1;
      var t = (anchors[i].innerText || '').trim().replace(/[,|]/g, ' ').slice(0, 60);
      if (t) out.push(abs + '|' + t);
    }
    return out.join(',');
  } catch (e) {
    return '';
  }
})()
""".trimIndent()

/**
 * Group-page harvester: the group's own website link AND its newest
 * release link (external or NU /extnu — both land on a probeable chapter).
 */
fun harvestGroupJs(): String = """
(function() {
  try {
    var out = [];
    var seen = {};
    // Release rows first — the extnu/external links lead to chapters.
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length && out.length < 3; i++) {
      var h = anchors[i].getAttribute('href') || '';
      var isChapter = h.indexOf('/extnu/') >= 0 || h.indexOf('ch-release') >= 0;
      if (!isChapter) continue;
      var abs = anchors[i].href;
      if (seen[abs]) continue;
      seen[abs] = 1;
      out.push(abs + '|release');
    }
    return out.join(',');
  } catch (e) {
    return '';
  }
})()
""".trimIndent()

/**
 * Content-quality flags for the census (Wayne: "make sure the translations
 * make sense and have no rubbish added in"). Heuristics over the extracted
 * paragraphs: duplicate-paragraph spam (foxteller's filler), watermark
 * density, and residual cipher codepoints. Pure — unit-testable.
 */
fun proseQuality(paragraphs: List<String>): String {
    if (paragraphs.isEmpty()) return "empty"
    val counts = HashMap<String, Int>()
    paragraphs.forEach { p -> counts[p] = (counts[p] ?: 0) + 1 }
    val maxDup = counts.values.max()
    val repeatRatio = maxDup.toFloat() / paragraphs.size
    val watermark = paragraphs.count {
        it.contains("contact@", true) || it.contains("read only at", true) ||
            it.contains("if you see this", true) || it.contains("content is owned", true) ||
            it.contains("support the", true) && it.length < 120
    }
    var sus = 0
    var total = 0
    paragraphs.forEach { p ->
        total += p.length
        p.forEach { c ->
            val code = c.code
            if (code in 0x2F00..0x2FDF || code in 0xE000..0xF8FF || code in 0x3400..0x4DBF) sus++
        }
    }
    return "repeat=%.2f wm=%d sus%%=%d".format(repeatRatio, watermark, 100 * sus / total.coerceAtLeast(1))
}
