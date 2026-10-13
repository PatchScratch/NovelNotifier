package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder

/**
 * Update notifications for Syosetu bookmarks, NU-style. Syosetu has no
 * per-account feed, but every AUTHOR exposes an Atom feed of their recent
 * posts (linked from each novel page, verified live 2026-10-13):
 *
 *   https://api.syosetu.com/writernovel/<userId>.Atom
 *
 * Entries carry the episode URL (https://ncode.syosetu.com/nXXXX/3/) and a
 * published timestamp, max 100. Polling one feed per distinct author
 * covers every bookmark by that author; entries are matched to bookmark
 * ncodes. The writer's userId comes from the public novel API
 * (?ncode=…&of=t-u) on first sight and is cached in feed_state.json.
 *
 * Baseline semantics (NU parity): the first observation records the
 * current episode count silently; only episodes BEYOND the baseline (the
 * cached series' episode count, or the previous poll's max) notify. A
 * notified series gets its detail cache invalidated so the next visit
 * refetches the new ToC.
 */
object SyosetuFeeds {

    data class FeedEntry(val ncode: String, val episode: Int, val published: String)

    /** Entry links look like https://ncode.syosetu.com/n4180mw/3/. */
    fun parseAtom(xml: String): List<FeedEntry> {
        val out = mutableListOf<FeedEntry>()
        Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { e ->
            val body = e.groupValues[1]
            val link = Regex("""href="https://ncode\.syosetu\.com/(n[0-9a-z]+)/(\d+)/"""")
                .find(body)?.groupValues ?: return@forEach
            val published = Regex("""<published>([^<]+)</published>""").find(body)?.groupValues?.get(1) ?: ""
            out.add(FeedEntry(link[1].uppercase(), link[2].toIntOrNull() ?: return@forEach, published))
        }
        return out
    }

    // --- state ------------------------------------------------------------------

    private fun stateFile(context: Context): File =
        context.filesDir.resolve("syosetu").apply { mkdirs() }.resolve("feed_state.json")

    /** ncode → (writerId, lastNotifiedEpisode). */
    private fun loadState(context: Context): MutableMap<String, Pair<String, Int>> {
        val map = mutableMapOf<String, Pair<String, Int>>()
        try {
            val arr = JSONArray(stateFile(context).readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                map[o.optString("n")] = o.optString("w") to o.optInt("last")
            }
        } catch (e: Exception) { /* fresh install */ }
        return map
    }

    private fun saveState(context: Context, state: Map<String, Pair<String, Int>>) {
        try {
            val arr = JSONArray()
            state.forEach { (n, v) -> arr.put(JSONObject().put("n", n).put("w", v.first).put("last", v.second)) }
            stateFile(context).writeText(arr.toString())
        } catch (e: Exception) {
            DebugLog.log("SYOSETU", "feed state write failed", e)
        }
    }

    // --- network ----------------------------------------------------------------

    /** Small GET with real timeouts — a stalled socket must throw, not
     *  hang the whole poll forever (bit us live on a flaky network). */
    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Public API userid for an ncode (?ncode=…&of=t-u). */
    suspend fun writerIdFor(ncode: String): String? = withContext(Dispatchers.IO) {
        try {
            val raw = fetchText(
                "https://api.syosetu.com/novelapi/api/?ncode=" +
                    URLEncoder.encode(ncode, "UTF-8") + "&lim=1&out=json&of=t-u"
            )
            JSONArray(raw).optJSONObject(1)?.optString("userid")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            DebugLog.log("SYOSETU", "writerId lookup failed for $ncode", e)
            null
        }
    }

    /**
     * Poll every bookmark's author feed once; returns ChapterUpdates for
     * series with episodes beyond their baseline. Paced ~1.5s between
     * feed fetches; failures degrade to "no data" for that author.
     */
    suspend fun pollBookmarks(
        context: Context,
        bookmarks: List<SyosetuBookmark>
    ): List<ChapterUpdate> = withContext(Dispatchers.IO) {
        if (bookmarks.isEmpty()) return@withContext emptyList()
        val state = loadState(context)
        val updates = mutableListOf<ChapterUpdate>()
        val feedsByWriter = mutableMapOf<String, List<FeedEntry>>()
        bookmarks.distinctBy { it.ncode }.forEach { bm ->
            val writer = state[bm.ncode]?.first
                ?: writerIdFor(bm.ncode)?.also { state[bm.ncode] = it to 0 }
                ?: return@forEach
            val entries = feedsByWriter.getOrPut(writer) {
                try {
                    fetchText("https://api.syosetu.com/writernovel/" + writer + ".Atom")
                        .let(::parseAtom)
                } catch (e: Exception) {
                    DebugLog.log("SYOSETU", "feed fetch failed for writer $writer", e)
                    emptyList()
                }.also {
                    DebugLog.log("SYOSETU", "feed ok: " + it.size + " entries")
                    kotlinx.coroutines.delay(1500)
                }
            }
            val maxEp = entries.filter { it.ncode == bm.ncode }.maxOfOrNull { it.episode } ?: return@forEach
            val prev = state[bm.ncode]?.second
            val baseline = when {
                prev != null && prev > 0 -> prev
                else -> cachedEpisodeCount(context, bm.ncode) ?: run {
                    // first sight, no cache: record silently
                    state[bm.ncode] = writer to maxEp
                    return@forEach
                }
            }
            if (maxEp > baseline) {
                state[bm.ncode] = writer to maxEp
                updates.add(
                    ChapterUpdate(
                        novel = Novel(
                            title = bm.title,
                            latestChapter = "c$maxEp",
                            seriesUrl = bm.url
                        ),
                        incomingChapter = "c$maxEp",
                        previousChapter = "c$baseline"
                    )
                )
                // New episodes → the cached ToC is stale; drop it so the
                // next visit refetches (the API lastUpdate also moves, but
                // this is immediate).
                syosetuSeriesCacheFile(context, bm.ncode).delete()
            } else if (prev == null || prev < maxEp) {
                state[bm.ncode] = writer to maxEp
            }
        }
        saveState(context, state)
        if (updates.isNotEmpty()) {
            DebugLog.log("SYOSETU", "feed poll: ${updates.size} updated series")
        }
        updates
    }

    /** Episode count from the series cache without freshness gating. */
    private fun cachedEpisodeCount(context: Context, ncode: String): Int? = try {
        val f = syosetuSeriesCacheFile(context, ncode)
        if (!f.isFile) null
        else JSONObject(f.readText()).optJSONObject("novel")?.optInt("eps")?.takeIf { it > 0 }
    } catch (e: Exception) { null }
}
