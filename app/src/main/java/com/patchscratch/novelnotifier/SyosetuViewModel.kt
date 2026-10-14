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

    /** Settings ⋮ Sync now (manual, with the verbose empty-page diagnostics). */
    fun syncNow() {
        viewModelScope.launch {
            status = "Syncing bookmarks…"
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

    // --- しおり (read position), from the reader callbacks ---------------------

    /** Auto-advance on episode open: no status chatter, silent mirror update. */
    fun updateShioriAuto(bm: SyosetuBookmark, ep: Int) {
        if (bm.userfavId.isBlank()) return
        viewModelScope.launch {
            val ok = syosetuUpdateShiori(bm.userfavId, ep)
            if (ok) {
                // keep the local mirror in step so re-composition
                // doesn't re-fire the same advance
                mirrorShiori(bm.ncode, ep)
            }
        }
    }

    fun updateShioriManual(bm: SyosetuBookmark, ep: Int) {
        if (bm.userfavId.isBlank()) return
        viewModelScope.launch {
            status = "Setting しおり…"
            val ok = syosetuUpdateShiori(bm.userfavId, ep)
            status = if (ok) "しおり set to episode " + ep else "Set failed — try again"
            if (ok) mirrorShiori(bm.ncode, ep)
        }
    }

    private suspend fun mirrorShiori(ncode: String, ep: Int) {
        bookmarks = bookmarks.map { if (it.ncode == ncode) it.copy(shioriPos = ep) else it }
        withContext(Dispatchers.IO) { saveSyosetuBookmarks(getApplication(), bookmarks) }
    }
}
