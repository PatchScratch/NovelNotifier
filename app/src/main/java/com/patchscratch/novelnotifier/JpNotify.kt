package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * B3 unified notifications: the Japanese sites' chapter checks that run in
 * the shared background worker (UpdateCheckWorker), so alerts arrive like
 * NU's instead of only while the app is open.
 *
 * - Syosetu: the author-Atom pipeline (SyosetuFeeds.pollBookmarks) moves
 *   here unchanged — it only needed a caller in the background. The
 *   worker path is quieter than the foreground one: no status text.
 * - Kakuyomu: ordinal polling of the logged-in follows via the open
 *   GraphQL endpoint — the follows query already carries each work's
 *   lastEpisodePublishedAt + episode count, so "update detected" is a
 *   state-file comparison, zero scraping. Baseline state rides backups
 *   alongside Syosetu's (filesDir/jp_notify_state.json).
 *
 * Gates (both sites): hidden categories never notify (the SFW rule);
 * per-series toggles arrive with the notify-toggle wiring on details.
 */
object JpNotify {

    private fun stateFile(context: Context): File =
        File(context.filesDir, "jp_notify_state.json")

    /** { "<site>:<seriesUrl|ncode>": "<stamp>" } — stamp = episode count
     *  + publishedAt (Kakuyomu) or max episode seen (Syosetu handled by
     *  SyosetuFeeds' own state). */
    private fun loadState(context: Context): JSONObject = try {
        val f = stateFile(context)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun saveState(context: Context, state: JSONObject) {
        runCatching { stateFile(context).writeText(state.toString()) }
    }

    /**
     * Syosetu background poll. Returns update rows for the shared
     * notification pipeline (already hidden-分類-filtered inside).
     */
    suspend fun pollSyosetu(context: Context): List<ChapterUpdate> {
        if (loadSyosetuBookmarks(context).isEmpty()) return emptyList()
        return try {
        val bookmarks = loadSyosetuBookmarks(context)
        val hiddenCats = loadIdSet(
            context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE),
            "syosetu_hidden_cats"
        )
        val visible = if (hiddenCats.isEmpty()) bookmarks
        else bookmarks.filter { it.category !in hiddenCats }
        SyosetuFeeds.pollBookmarks(context, visible)
        } catch (e: Exception) {
            DebugLog.log("SYOSETU", "background feed poll failed", e)
            emptyList()
        }
    }

    /**
     * Kakuyomu background poll: follows → per-work stamp; a changed stamp
     * (new episode count or newer lastEpisodePublishedAt) vs the state
     * file = update. First run only seeds baselines (no notification
     * storm for pre-existing episodes).
     */
    suspend fun pollKakuyomu(context: Context): List<ChapterUpdate> {
        return try {
        val visitor = fetchKakuyomuVisitor() ?: return emptyList()   // not logged in
        val follows = fetchKakuyomuFollows(visitor.screenName)
        if (follows.isEmpty()) return emptyList()

        val hidden = NsfwFilter.hiddenKakuyomuWorkIds(
            CategoryStore.load(context)
        )
        val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
        val state = withContext(Dispatchers.IO) { loadState(context) }
        val updates = mutableListOf<ChapterUpdate>()
        for (work in follows) {
            if (work.id in hidden) continue
            val key = "kakuyomu:" + work.id
            val stamp = "${work.episodeCount}|${work.lastUpdate}"
            val prev = state.optString(key, "")
            if (prev.isBlank()) {
                state.put(key, stamp)   // baseline seed
                continue
            }
            if (prev != stamp) {
                state.put(key, stamp)
                updates += ChapterUpdate(
                    novel = Novel(
                        title = work.title,
                        latestChapter = "ep " + work.episodeCount,
                        seriesUrl = work.url
                    ),
                    incomingChapter = "ep " + work.episodeCount,
                    previousChapter = prev.substringBefore("|").let { "ep $it" }
                )
            }
        }
        withContext(Dispatchers.IO) { saveState(context, state) }
        DebugLog.log("KAKUYOMU", "background poll: ${follows.size} follows, ${updates.size} updates")
        updates
        } catch (e: Exception) {
            DebugLog.log("KAKUYOMU", "background poll failed", e)
            emptyList()
        }
    }
}
