package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.launch

/**
 * Kakuyomu workspace state (Phase 4b): follows (bookmarks), login flag,
 * R18 gate, sync status — moved from NovelApp's remember blocks and the
 * syncKakuyomuBookmarks local function.
 *
 * Sync model (user's rule): bookmarks made in-app are ADDED to the site
 * (push local-only follows), then the site's list is MASTER — it replaces
 * local state. Site-side removals therefore win; in-app unfollows already
 * removed both sides.
 */
class KakuyomuViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    var r18 by mutableStateOf(prefs.getBoolean("kakuyomu_r18", false))
        private set
    var bookmarks by mutableStateOf(loadKakuyomuBookmarks(prefs))
        private set
    var loggedIn by mutableStateOf(prefs.getBoolean("kakuyomu_logged_in", false))
        private set
    var status by mutableStateOf("")
    /** Pull-to-refresh spinner state (home panel sync). */
    var syncing by mutableStateOf(false)
        private set

    /** Payload for the open detail screen (Route.KakuyomuDetail). */
    var selectedWork by mutableStateOf<KakuyomuWork?>(null)
        private set

    fun openDetail(work: KakuyomuWork) {
        selectedWork = work
    }

    fun closeDetail() {
        selectedWork = null
    }

    init {
        // Bookmark self-heal, one quiet sync per open when logged in:
        // pushes bookmarks made offline to the site, pulls the site's master
        // list (same trigger as the old LaunchedEffect(kakuyomuLoggedIn)).
        if (loggedIn) syncBookmarks(quiet = true)
    }

    fun syncBookmarks(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) status = t("Syncing bookmarks…", "ブックマークを同期中…")
            syncing = true
            try {
                val visitor = fetchKakuyomuVisitor()
                if (visitor == null) {
                    loggedIn = false
                    prefs.edit { putBoolean("kakuyomu_logged_in", false) }
                    status = t("Not logged in — session expired, log in again.", "ログインしていません — セッション失効、再ログインしてください。")
                    return@launch
                }
                val site = fetchKakuyomuFollows(visitor.screenName)
                val local = loadKakuyomuBookmarks(prefs)
                val siteIds = site.map { it.id }.toSet()
                val toPush = local.filter { it.id !in siteIds }
                var pushed = 0
                toPush.forEach { work ->
                    if (kakuyomuFollow(work.id)) pushed++
                    DebugLog.log("KAKUYOMU", "pushed follow ${work.id}")
                }
                val merged = mergeKakuyomuBookmarks(local, site, toPush.map { it.id }.toSet())
                saveKakuyomuBookmarks(prefs, merged)
                bookmarks = merged
                status = t(
                    "Synced ${merged.size} bookmarked works (${pushed} followed on Kakuyomu)",
                    "${merged.size}作品を同期（${pushed}件をカクヨムでフォロー）"
                )
                DebugLog.log("KAKUYOMU", "bookmark sync: ${merged.size} works, pushed $pushed")
            } catch (e: Exception) {
                DebugLog.log("KAKUYOMU", "bookmark sync failed", e)
                if (!quiet) status = t("Sync failed: ", "同期失敗: ") + (e.message ?: "")
            } finally {
                syncing = false
            }
        }
    }

    fun onLoggedIn() {
        loggedIn = true
        prefs.edit { putBoolean("kakuyomu_logged_in", true) }
        status = t("Logged in to Kakuyomu", "カクヨムにログインしました")
        syncBookmarks()
    }

    fun onLoggedOut() {
        loggedIn = false
        prefs.edit { putBoolean("kakuyomu_logged_in", false) }
        status = t("Logged out — bookmarks stay on this device.", "ログアウトしました — ブックマークは端末に残ります。")
    }

    // --- client-side categories (flat-follow sites; see CategoryStore) ---

    /** Live mirror of categories.json; every mutator persists immediately. */
    var categories by mutableStateOf(CategoryStore.load(getApplication()))
        private set

    private fun saveCats(next: CategoryState) {
        categories = next
        CategoryStore.save(getApplication(), next)
    }

    fun addCategory(name: String) = saveCats(CategoryStore.add(categories, name))
    fun renameCategory(id: String, name: String) = saveCats(CategoryStore.rename(categories, id, name))
    fun deleteCategory(id: String) = saveCats(CategoryStore.delete(categories, id))
    fun setCategoryHidden(id: String, hidden: Boolean) =
        saveCats(CategoryStore.setHidden(categories, id, hidden))

    fun moveToCategory(workId: String, catId: String?) =
        saveCats(CategoryStore.move(categories, workId, catId))

    fun categoryOf(workId: String): LocalCategory? =
        categories.assign[workId]?.let { id -> categories.cats.firstOrNull { it.id == id } }


    fun updateR18(on: Boolean) {
        r18 = on
        prefs.edit { putBoolean("kakuyomu_r18", on) }
    }

    /**
     * Detail-screen ⚑: flip local membership + persist, and when logged in
     * mirror to the site (adding ⚑ follows on Kakuyomu, removing unfollows).
     * Fire-and-forget with the outcome logged; the next sync reconciles.
     */
    fun toggleBookmark(work: KakuyomuWork) {
        val id = work.id
        val adding = bookmarks.none { it.id == id }
        bookmarks =
            if (adding) listOf(work) + bookmarks
            else bookmarks.filterNot { it.id == id }
        saveKakuyomuBookmarks(prefs, bookmarks)
        if (loggedIn) {
            viewModelScope.launch {
                // Crash fix (2026-10-16): un-caught network failure here
                // killed the app offline (same class as the shiori crash).
                try {
                    val ok = if (adding) kakuyomuFollow(id) else kakuyomuUnfollow(id)
                    DebugLog.log("KAKUYOMU", "site ${if (adding) "follow" else "unfollow"} $id → $ok")
                } catch (e: Exception) {
                    DebugLog.log("KAKUYOMU", "site follow/unfollow skipped (offline?)", e)
                    // The next sync reconciles site state with local.
                }
            }
        }
    }

    /** Reading-history callback from the reader (manual sets show status). */
    fun recordReadingHistory(episodeId: String, ordinal: Int, manual: Boolean) {
        viewModelScope.launch {
            // Crash fix (2026-10-16): un-caught network failure here
            // killed the app offline (same class as the shiori crash).
            try {
                val ok = kakuyomuRecordReadingHistory(episodeId, "p" + ordinal)
                if (manual) {
                    status = if (ok) "Reading position set" else "Set failed — try again"
                }
                mirrorPosition(ordinal)
            } catch (e: Exception) {
                DebugLog.log("KAKUYOMU", "history record skipped (offline?)", e)
                status = t("Offline — position sets when back online", "オフライン — オンライン復帰時に設定されます")
            }
        }
    }

    /**
     * B2 unread engine: persist the reading position into the local
     * bookmark mirror (site rows don't carry it; mergeKakuyomuBookmarks
     * preserves it across syncs). Drives badges + Next Chapter.
     */
    fun mirrorPosition(readPos: Int) {
        val id = selectedWork?.id ?: return
        bookmarks = bookmarks.map { if (it.id == id) it.copy(readPos = readPos) else it }
        saveKakuyomuBookmarks(prefs, bookmarks)
    }
}
