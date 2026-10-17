package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The Japanese sites' chapter checks, unified into the same background
 * worker as NU's (framework B3). Design constraints, for whoever touches
 * this next:
 *
 * - ONE pipeline: this runs inside UpdateCheckWorker and the app's
 *   Check-now button calls the same functions, so manual and automatic
 *   checks can never disagree. Anything added here is automatically
 *   covered by both.
 * - No WebViews. Both polls are plain HTTP with the WebView session's
 *   cookie jar — the worker promotes itself to a foreground service for
 *   NU's WebView checks, but these must stay cheap enough to run even
 *   when that promotion is refused.
 * - Detection is stamp-comparison, never content fetches: Syosetu reads
 *   authors' public Atom feeds (ep numbers only), Kakuyomu compares
 *   follows-query episode-count/publishedAt stamps against
 *   jp_notify_state.json. Neither ever downloads a chapter.
 * - First-seen baselines seed silently — a fresh install or a restore
 *   must not produce a notification storm for pre-existing episodes.
 *   The state file rides backups for exactly that reason.
 * - Hidden series never notify (SFW rule) — filtered before the update
 *   list is built, in both polls.
 * - A missing/expired session is a normal outcome, not an error: the
 *   polls return empty and the login flags gate them at the caller.
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
     *
     * Reviewer note: the stamp deliberately uses BOTH count and date —
     * count alone misses same-count rewrites (authors editing a published
     * episode), date alone would fire on unrelated metadata. If Kakuyomu
     * ever splits these, prefer keeping date changes silent (NU's rule:
     * only a HIGHER chapter number is an update).
     */
    suspend fun pollKakuyomu(context: Context): List<ChapterUpdate> {
        return try {
        val visitor = fetchKakuyomuVisitor() ?: return emptyList()   // not logged in
        val follows = fetchKakuyomuFollows(visitor.screenName)
        if (follows.isEmpty()) return emptyList()

        val hidden = NsfwFilter.hiddenKakuyomuWorkIds(
            CategoryStore.load(context, "kakuyomu")
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
