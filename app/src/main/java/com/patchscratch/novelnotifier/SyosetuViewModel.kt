package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Syosetu workspace state (Phase 4b): bookmark store, renamed 分類
 * categories, sync status, login/R18 toggles — plus the bookmark-page sync
 * and しおり helpers, moved verbatim from NovelApp's local functions.
 */
class SyosetuViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    var loggedIn by mutableStateOf(prefs.getBoolean("syosetu_logged_in", false))
        private set
    var r18 by mutableStateOf(prefs.getBoolean("syosetu_r18", false))
        private set

    /** Which R18 sisters are visible (nocgenre 1/2/3); all on by default. */
    var subsites by mutableStateOf(
        prefs.getStringSet("syosetu_subsites", setOf("1", "2", "3"))
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1, 2, 3)
    )
        private set

    var bookmarks by mutableStateOf<List<SyosetuBookmark>>(emptyList())
        private set
    var categories by mutableStateOf<List<SyosetuCategory>>(emptyList())
        private set
    var status by mutableStateOf("")
    /** Pull-to-refresh spinner state (home panel sync). */
    var syncing by mutableStateOf(false)
        private set

    /** Payload for the open detail screen (Route.SyosetuDetail). */
    var selected by mutableStateOf<SyosetuNovel?>(null)
        private set

    init {
        // Same trigger as the old LaunchedEffect(syosetuLoggedIn), which
        // fired on first composition too: refresh + feed poll when logged in.
        if (loggedIn) viewModelScope.launch { resyncAndPollFeeds() }
    }

    /**
     * Shared bookmark-page sync: reads the (possibly renamed) category
     * selector, then fetches each category with bookmarks so rows carry the
     * user's real category names — the drawer lists them like NU lists.
     * Falls back to plain single-page parsing when no selector is found.
     */
    private suspend fun syncBookmarkPage(html: String): List<SyosetuBookmark> {
        if (html.isBlank()) return emptyList()
        val cats = parseSyosetuCategories(html)
        if (cats.isEmpty()) return parseSyosetuBookmarks(html)
        withContext(Dispatchers.IO) { saveSyosetuCategories(getApplication(), cats) }
        DebugLog.log("SYOSETU", "categories: " + cats.joinToString { c -> c.name + "(" + c.count + ")" })
        val rows = mutableListOf<SyosetuBookmark>()
        cats.filter { it.count > 0 }.forEach { c ->
            val page = fetchSyosetuWithSession(
                "https://syosetu.com/favnovelmain/list/?nowcategory=" + c.id + "&order=updated_at"
            )
            if (page.isNotBlank()) rows += parseSyosetuBookmarks(page, c.name)
        }
        // Nothing per-category (selector with all-zero counts / a redesign)
        // → keep the plain page's rows.
        val final = rows.ifEmpty { parseSyosetuBookmarks(html) }
        // Enrich the category list with every renamed name from ONE
        // settings page (its select lists all 分類; the list page's
        // selector only shows categories that hold bookmarks).
        final.firstOrNull { it.userfavId.isNotBlank() }?.let { first ->
            try {
                val page = fetchSyosetuWithSession(
                    "https://syosetu.com/favnovelmain/updateinput/useridfavncode/" + first.userfavId + "/"
                )
                parseSyosetuBookmarkSettings(page)?.categoryNames?.takeIf { it.isNotEmpty() }?.let { names ->
                    val merged = names.map { (id, name) ->
                        val known = cats.firstOrNull { it.id == id }
                        SyosetuCategory(id, name, known?.count ?: 0)
                    }
                    withContext(Dispatchers.IO) { saveSyosetuCategories(getApplication(), merged) }
                }
            } catch (e: Exception) {
                DebugLog.log("SYOSETU", "category name enrichment skipped", e)
            }
        }
        return final
    }

    suspend fun resyncBookmarks() {
        bookmarks = withContext(Dispatchers.IO) { loadSyosetuBookmarks(getApplication()) }
        categories = withContext(Dispatchers.IO) { loadSyosetuCategories(getApplication()) }
        try {
            val html = fetchSyosetuWithSession("https://syosetu.com/favnovelmain/list/")
            val fresh = syncBookmarkPage(html)
            if (fresh.isNotEmpty()) {
                withContext(Dispatchers.IO) { saveSyosetuBookmarks(getApplication(), fresh) }
                bookmarks = fresh
            }
            // reload AFTER the sync — it enriches categories.json with every
            // renamed 分類 from the settings page
            categories = withContext(Dispatchers.IO) { loadSyosetuCategories(getApplication()) }
        } catch (e: Exception) {
            DebugLog.log("SYOSETU", "bookmark refresh skipped", e)
        }
    }

    /** The old LaunchedEffect(syosetuLoggedIn) body: refresh, then poll
     *  author-Atom feeds (NU parity) so baselines see fresh rows. */
    suspend fun resyncAndPollFeeds() {
        resyncBookmarks()
        try {
            ensureChapterChannel(getApplication())
            val updates = SyosetuFeeds.pollBookmarks(getApplication(), bookmarks)
            // SFW: hidden 分類 never notify. Unlock state is in-memory and
            // polls run in the background (locked), so hidden = never.
            val hiddenCats = loadIdSet(prefs, "syosetu_hidden_cats")
            val visible = if (hiddenCats.isEmpty()) updates
            else updates.filter { u ->
                bookmarks.none { bm -> bm.title == u.novel.title && bm.category in hiddenCats }
            }
            if (visible.isNotEmpty()) {
                postChapterNotifications(getApplication(), visible)
                DebugLog.log("SYOSETU", "posted ${visible.size} update notifications (${updates.size - visible.size} hidden)")
            }
        } catch (e: Exception) {
            DebugLog.log("SYOSETU", "feed poll skipped", e)
        }
    }

    /** Settings ⋮ Sync now + home pull-to-refresh (verbose diagnostics). */
    fun syncNow() {
        viewModelScope.launch {
            status = "Syncing bookmarks…"
            syncing = true
            try {
                val html = fetchSyosetuWithSession("https://syosetu.com/favnovelmain/list/")
                val found = syncBookmarkPage(html)
                if (found.isNotEmpty()) {
                    withContext(Dispatchers.IO) { saveSyosetuBookmarks(getApplication(), found) }
                    bookmarks = found
                    status = "Synced ${found.size} bookmarked novels"
                    DebugLog.log("SYOSETU", "bookmark sync: ${found.size} rows")
                } else {
                    status = "No bookmarks found — check you are logged in"
                    // DebugLog strips non-ASCII (JP pages log empty) —
                    // dump raw hrefs instead for scraper tuning.
                    val hrefs = Regex("href=\"([^\"]{4,120})\"")
                        .findAll(html).map { it.groupValues[1] }.toList()
                    val ncodeHrefs = hrefs.filter { it.contains("ncode") }.take(12)
                    val ncodeCount = hrefs.count { it.contains("ncode") }
                    DebugLog.log("SYOSETU", "0 rows; total hrefs=${hrefs.size}, ncode=$ncodeCount")
                    // exact markup around the first novel link, non-ASCII
                    // folded to '.' so DebugLog keeps it
                    val i = html.indexOf("ncode.syosetu.com")
                    if (i >= 0) {
                        val ctx = html.substring(maxOf(0, i - 150), minOf(i + 250, html.length))
                        DebugLog.log("SYOSETU", "ctx: " + ctx.replace(Regex("[^\\x20-\\x7E]"), "."))
                    }
                }
            } catch (e: Exception) {
                status = "Sync failed: ${e.message}"
                DebugLog.log("SYOSETU", "bookmark sync failed", e)
            } finally {
                syncing = false
            }
        }
    }

    fun onLoggedIn() {
        loggedIn = true
        prefs.edit { putBoolean("syosetu_logged_in", true) }
        status = "Logged in to Syosetu"
        // The old LaunchedEffect(syosetuLoggedIn) re-fired on this change.
        viewModelScope.launch { resyncAndPollFeeds() }
    }

    fun openDetail(novel: SyosetuNovel) {
        selected = novel
    }

    fun closeDetail() {
        selected = null
    }

    fun onLoggedOut() {
        loggedIn = false
        bookmarks = emptyList()
        prefs.edit { putBoolean("syosetu_logged_in", false) }
    }

    fun updateR18(on: Boolean) {
        r18 = on
        prefs.edit { putBoolean("syosetu_r18", on) }
    }

    fun updateSubsites(set: Set<Int>) {
        subsites = set
        prefs.edit { putStringSet("syosetu_subsites", set.map { it.toString() }.toSet()) }
    }

    // --- detail / grid write-backs -------------------------------------------

    fun moveBookmark(userfavId: String, catId: Int) {
        viewModelScope.launch {
            status = "Moving bookmark…"
            val ok = syosetuMoveBookmarkCategory(userfavId, catId)
            status = if (ok) "Bookmark moved" else "Move failed — try again"
            if (ok) resyncBookmarks()
        }
    }

    fun removeBookmark(userfavId: String) {
        viewModelScope.launch {
            status = "Removing bookmark…"
            val ok = syosetuDeleteBookmark(userfavId)
            status = if (ok) "Bookmark removed" else "Remove failed — try again"
            if (ok) resyncBookmarks()
        }
    }

    // --- detail-screen bookmarking (★ button, NU CHANGE-LIST parity) --------

    /** The open detail's ★-modal payload (分類 ids+names, token, defaults). */
    var addForm by mutableStateOf<SyosetuAddBookmarkForm?>(null)
        private set
    var addFormLoading by mutableStateOf(false)
        private set

    /** Fetch the novel page once so the picker can offer the site's 分類 ids. */
    fun loadAddBookmarkForm(ncode: String) {
        if (addFormLoading) return
        viewModelScope.launch {
            addFormLoading = true
            addForm = null
            try {
                val html = fetchSyosetuWithSession("https://ncode.syosetu.com/${ncode.lowercase()}/")
                val parsed = parseSyosetuAddBookmarkForm(html)
                // Step 2 of the site's own flow: the addajax payload carries
                // the 分類 list, a FRESH token, and the novel's ids — the
                // page's select is JS-filled and always empty in raw HTML.
                addForm = parsed?.let { fetchSyosetuBookmarkPayload(it) }?.takeIf {
                    it.freshToken.isNotBlank()
                }
                if (addForm == null) {
                    status = t("Could not read the bookmark form — try again", "ブックマークフォームを読めませんでした — 再試行してください")
                } else if (addForm!!.categories.isEmpty()) {
                    status = t("Log in from Settings to bookmark on Syosetu.", "設定からログインするとSyosetuでブックマークできます。")
                }
            } catch (e: Exception) {
                DebugLog.log("SYOSETU", "add-form fetch failed", e)
                status = t("Could not read the bookmark form — try again", "ブックマークフォームを読めませんでした — 再試行してください")
            } finally {
                addFormLoading = false
            }
        }
    }

    /** ★ with a 分類 choice: add on the site, then pull the master list. */
    fun addBookmarkWithCategory(form: SyosetuAddBookmarkForm, categoryId: Int) {
        viewModelScope.launch {
            status = t("Adding bookmark…", "ブックマークを追加中…")
            val ok = syosetuAddBookmark(form, categoryId)
            status = if (ok) t("Bookmarked", "ブックマーク済み") else t("Add failed — try again", "追加に失敗しました — 再試行")
            if (ok) resyncBookmarks()
        }
    }

    /**
     * ★ tap on an already-bookmarked novel. The remove form needs the
     * userfavId; if the bookmark isn't synced yet (just added), sync first.
     */
    fun removeBookmarkByNcode(ncode: String) {
        viewModelScope.launch {
            var bm = bookmarks.firstOrNull { it.ncode.equals(ncode, ignoreCase = true) }
            if (bm == null || bm.userfavId.isBlank()) {
                resyncBookmarks()
                bm = bookmarks.firstOrNull { it.ncode.equals(ncode, ignoreCase = true) }
            }
            if (bm == null || bm.userfavId.isBlank()) {
                status = t("Not in your bookmarks", "ブックマークにありません")
                return@launch
            }
            removeBookmark(bm.userfavId)
        }
    }

    // --- しおり (read position), from the reader callbacks ---------------------

    /** Auto-advance on episode open: no status chatter, silent mirror update. */
    fun updateShioriAuto(bm: SyosetuBookmark, ep: Int) {
        if (bm.userfavId.isBlank()) return
        viewModelScope.launch {
            // Crash fix (2026-10-16): the auto-しおり fires on every chapter
            // display; offline it threw UnknownHostException out of this
            // un-caught launch and killed the app. Skip silently — the local
            // mirror stays behind, so the next ONLINE display of a later
            // chapter re-fires the advance to the site.
            try {
                val ok = syosetuUpdateShiori(bm.userfavId, ep)
                if (ok) {
                    // keep the local mirror in step so re-composition
                    // doesn't re-fire the same advance
                    mirrorShiori(bm.ncode, ep)
                }
            } catch (e: Exception) {
                DebugLog.log("SYOSETU", "shiori auto skipped (offline?)", e)
            }
        }
    }

    fun updateShioriManual(bm: SyosetuBookmark, ep: Int) {
        if (bm.userfavId.isBlank()) return
        viewModelScope.launch {
            status = "Setting しおり…"
            try {
                val ok = syosetuUpdateShiori(bm.userfavId, ep)
                status = if (ok) "しおり set to episode " + ep else "Set failed — try again"
                if (ok) mirrorShiori(bm.ncode, ep)
            } catch (e: Exception) {
                DebugLog.log("SYOSETU", "shiori manual failed", e)
                status = t("Offline — しおり sets when back online", "オフライン — しおりはオンライン復帰時に設定されます")
            }
        }
    }

    private suspend fun mirrorShiori(ncode: String, ep: Int) {
        bookmarks = bookmarks.map { if (it.ncode == ncode) it.copy(shioriPos = ep) else it }
        withContext(Dispatchers.IO) { saveSyosetuBookmarks(getApplication(), bookmarks) }
    }
}
