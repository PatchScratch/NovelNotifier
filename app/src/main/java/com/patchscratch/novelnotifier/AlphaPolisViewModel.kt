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
import org.json.JSONArray
import org.json.JSONObject

/**
 * AlphaPolis workspace state (B4): local bookmarks + reading positions,
 * both in one JSON pref (`alphapolis_bookmarks`) so they ride device
 * backups. Login/bookshelf sync is the last B4 piece — until then the
 * position is LOCAL_ONLY (the SharedDetail contract's fallback case).
 *
 * Bookmark row shape: {id, title, author, cover, eps, pub, up, genre,
 * intro, tags, rp} — mirrors Kakuyomu's for symmetry.
 */
class AlphaPolisViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    init {
        AlpTocStore.install(app)
    }

    /** Viewer-ToC data for a series (dispOrder + availability per episode). */
    fun viewerTocOf(seriesUrl: String): Map<Long, Pair<Int, Boolean>> =
        AlpTocStore.forSeries(seriesUrl)

    var bookmarks by mutableStateOf(loadAlpBookmarks(prefs))
        private set

    /** Payload for the open detail screen. */
    var selected by mutableStateOf<AlpWork?>(null)
        private set

    // --- login (B4): the cookie jar is the session; this flag only gates UI ---

    var loggedIn by mutableStateOf(
        app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            .getBoolean("alphapolis_logged_in", false)
    )
        private set

    fun onLoggedIn() {
        loggedIn = true
        prefs.edit { putBoolean("alphapolis_logged_in", true) }
    }

    fun onLoggedOut() {
        loggedIn = false
        prefs.edit { putBoolean("alphapolis_logged_in", false) }
    }

    fun openDetail(work: AlpWork) { selected = work }
    fun closeDetail() { selected = null }

    fun toggleBookmark(work: AlpWork) {
        val adding = bookmarks.none { it.id == work.id }
        bookmarks = if (adding) listOf(work) + bookmarks else bookmarks.filterNot { it.id == work.id }
        save()
        // Site write-back (the sync doctrine): the site's own add/delete
        // endpoints with the content id — the second id of the URL pair.
        if (loggedIn) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val contentId = work.id.substringAfter('/')
                    val ok = alpSetFavorite(contentId, adding, workId = work.id)
                    DebugLog.log("ALP", "site favorite " + (if (adding) "add" else "delete") + " → " + ok)
                } catch (e: Exception) {
                    DebugLog.log("ALP", "site favorite skipped (offline?)", e)
                }
            }
        }
    }

    /**
     * Bookshelf sync (site-native follows): the site's list is master when
     * logged in — local-only rows survive only if just pushed (the Kakuyomu
     * merge rule). Pull-to-refresh and login call this.
     */
    var syncing by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun syncBookmarks() {
        DebugLog.log("ALP", "syncBookmarks() called; loggedIn=$loggedIn")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            syncing = true
            try {
                val before = bookmarks
                val site = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    fetchAlpBookshelf()
                }
                if (site.isEmpty()) return@launch
                // Enrich rows with cached detail where we have it (title-only
                // rows from the shelf get episode counts for badges).
                val enriched = site.map { fresh ->
                    bookmarks.firstOrNull { it.id == fresh.id }?.let { local ->
                        if (local.episodeCount > fresh.episodeCount) fresh.copy(
                            episodeCount = local.episodeCount, lastUpdate = local.lastUpdate
                        ) else fresh
                    } ?: fresh
                }
                bookmarks = enriched
                save()
                DebugLog.log("ALP", "bookshelf sync: ${enriched.size} works")
                // On-check offline catch-up: series with new episodes that
                // we already keep offline get a bulk job enqueued (stored
                // chapters skip instantly; only the new ones fetch).
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { alpOfflineCatchUp(getApplication(), before, enriched) }
                        .onFailure { DebugLog.log("ALP", "offline catch-up skipped", it) }
                }
            } catch (e: Exception) {
                DebugLog.log("ALP", "bookshelf sync failed", e)
            } finally {
                syncing = false
            }
        }
    }

    fun readPosOf(id: String): Int =
        bookmarks.firstOrNull { it.id == id }?.let { alpRowReadPos(it) } ?: 0

    fun setReadPos(id: String, pos: Int) {
        if (pos <= 0) return
        bookmarks = bookmarks.map {
            if (it.id == id) alpRowWithReadPos(it, pos) else it
        }
        save()
    }

    // --- client-side categories (flat-follow site; Kakuyomu pattern) ------
    // AlphaPolis has no native folders — the app owns the grouping layer,
    // namespaced to this site inside categories.json (see CategoryStore).

    /** Live mirror of the site's category state; mutators persist at once. */
    var categories by mutableStateOf(CategoryStore.load(app, "alphapolis"))
        private set

    private fun saveCats(next: CategoryState) {
        categories = next
        CategoryStore.save(getApplication(), "alphapolis", next)
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

    private fun save() {
        prefs.edit { putString("alphapolis_bookmarks", alpBookmarksJson(bookmarks)) }
    }
}

// --- row codec (kept simple: two small helpers, no full data-class field map) --

private const val ALP_RP = "rp"

private fun alpRowReadPos(w: AlpWork): Int = alpExtras[w.id] ?: w.let { 0 }

// In-memory position overlay — the JSON pref is the durable store; this
// map exists only inside the codec below.
private val alpExtras = mutableMapOf<String, Int>()

private fun alpRowWithReadPos(w: AlpWork, pos: Int): AlpWork {
    alpExtras[w.id] = pos
    return w
}

private fun alpBookmarksJson(works: List<AlpWork>): String {
    val arr = JSONArray()
    works.forEach { w ->
        arr.put(JSONObject().apply {
            put("id", w.id); put("title", w.title); put("author", w.author)
            put("cover", w.coverUrl); put("eps", w.episodeCount)
            put("pub", w.publishedAt); put("up", w.lastUpdate)
            put("genre", w.genre); put("intro", w.introduction); put("tags", w.tags)
            put(ALP_RP, alpExtras[w.id] ?: 0)
        })
    }
    return arr.toString()
}

private fun loadAlpBookmarks(prefs: android.content.SharedPreferences): List<AlpWork> = try {
    val arr = JSONArray(prefs.getString("alphapolis_bookmarks", "[]"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id")
        if (id.isBlank()) return@mapNotNull null
        alpExtras[id] = o.optInt(ALP_RP)
        AlpWork(
            id = id, title = o.optString("title"), author = o.optString("author"),
            coverUrl = o.optString("cover"), episodeCount = o.optInt("eps"),
            publishedAt = o.optString("pub"), lastUpdate = o.optString("up"),
            genre = o.optString("genre"), introduction = o.optString("intro"),
            tags = o.optString("tags")
        )
    }
} catch (e: Exception) {
    DebugLog.log("ALP", "bookmarks parse failed", e)
    emptyList()
}
