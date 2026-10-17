package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
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

    var bookmarks by mutableStateOf(loadAlpBookmarks(prefs))
        private set

    /** Payload for the open detail screen. */
    var selected by mutableStateOf<AlpWork?>(null)
        private set

    fun openDetail(work: AlpWork) { selected = work }
    fun closeDetail() { selected = null }

    fun toggleBookmark(work: AlpWork) {
        val adding = bookmarks.none { it.id == work.id }
        bookmarks = if (adding) listOf(work) + bookmarks else bookmarks.filterNot { it.id == work.id }
        save()
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
