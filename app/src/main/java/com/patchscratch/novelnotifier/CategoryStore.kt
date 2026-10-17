package com.patchscratch.novelnotifier

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Client-side categories for flat-follow sites (SFW/NSFW feature,
 * 2026-10-14). Kakuyomu's API has no folders, so the app owns this layer:
 * a list of categories plus one assignment per work id, stored in
 * filesDir/categories.json and carried in backups.
 *
 * Sites WITH native groupings (NU reading lists, Syosetu 分類) never use
 * this store — their hiding keys off the site-native ids/names instead
 * (prefs hidden_lists / syosetu_hidden_cats). This store is the template
 * for future flat-follow sites.
 *
 * Membership is single and move-only, like Syosetu 分類: one work, one
 * category; the only mutation is moving it (or clearing with null).
 */
data class LocalCategory(
    val id: String,
    val name: String,
    val hidden: Boolean = false
)

data class CategoryState(
    val cats: List<LocalCategory> = emptyList(),
    /** Kakuyomu work id → category id. */
    val assign: Map<String, String> = emptyMap()
)

object CategoryStore {

    private const val FILE = "categories.json"

    // --- pure JSON round-trip (unit-tested; no Android types) -------------

    fun parse(raw: String): CategoryState {
        if (raw.isBlank()) return CategoryState()
        return try {
            val root = JSONObject(raw)
            val cats = buildList {
                val arr = root.optJSONArray("cats") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val name = o.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        add(LocalCategory(id, name, o.optBoolean("hidden", false)))
                    }
                }
            }
            val assign = buildMap {
                val a = root.optJSONObject("assign") ?: return@buildMap
                a.keys().forEach { workId ->
                    val catId = a.optString(workId)
                    if (catId.isNotBlank()) put(workId, catId)
                }
            }
            CategoryState(cats, assign)
        } catch (_: Exception) {
            CategoryState()
        }
    }

    fun toJson(state: CategoryState): String {
        val root = JSONObject()
        root.put("cats", org.json.JSONArray().apply {
            state.cats.forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("hidden", c.hidden))
            }
        })
        root.put("assign", JSONObject().apply {
            state.assign.forEach { (workId, catId) -> put(workId, catId) }
        })
        return root.toString()
    }

    // --- mutations (pure; return the next state) ---------------------------

    fun add(state: CategoryState, name: String): CategoryState {
        val clean = name.trim()
        if (clean.isBlank()) return state
        // Ids only need uniqueness; time-based keeps them stable across adds.
        val id = "c" + System.currentTimeMillis().toString(36) +
            (0 until 4).map { ('a'..'z').random() }.joinToString("")
        return state.copy(cats = state.cats + LocalCategory(id, clean))
    }

    fun rename(state: CategoryState, id: String, name: String): CategoryState {
        val clean = name.trim()
        if (clean.isBlank()) return state
        return state.copy(cats = state.cats.map {
            if (it.id == id) it.copy(name = clean) else it
        })
    }

    /** Deleting a category clears its assignments (works fall to "All"). */
    fun delete(state: CategoryState, id: String): CategoryState =
        state.copy(
            cats = state.cats.filterNot { it.id == id },
            assign = state.assign.filterValues { it != id }
        )

    fun setHidden(state: CategoryState, id: String, hidden: Boolean): CategoryState =
        state.copy(cats = state.cats.map {
            if (it.id == id) it.copy(hidden = hidden) else it
        })

    /** Move a work; null clears the assignment. Unknown ids are ignored. */
    fun move(state: CategoryState, workId: String, catId: String?): CategoryState {
        if (catId != null && state.cats.none { it.id == catId }) return state
        val assign = if (catId == null) state.assign - workId
        else state.assign + (workId to catId)
        return state.copy(assign = assign)
    }

    // --- device I/O ---------------------------------------------------------
    // File shape v2 (site-namespaced; AlphaPolis joined 2026-10-18):
    //   {"v":2,"sites":{"kakuyomu":{cats,assign},"alphapolis":{…}}}
    // v1 (flat {cats,assign}, Kakuyomu-only) migrates to the "kakuyomu"
    // entry on first read — parse/toJson keep the per-site shape.

    fun parseSites(raw: String): Map<String, CategoryState> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            if (root.has("sites")) {
                val sites = root.optJSONObject("sites") ?: return emptyMap()
                buildMap {
                    sites.keys().forEach { site ->
                        put(site, parse(sites.optJSONObject(site).toString()))
                    }
                }
            } else {
                // v1: the whole file was Kakuyomu's state
                mapOf("kakuyomu" to parse(raw))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun sitesToJson(sites: Map<String, CategoryState>): String =
        JSONObject().apply {
            put("v", 2)
            put("sites", JSONObject().apply {
                sites.forEach { (site, state) ->
                    put(site, JSONObject(toJson(state)))
                }
            })
        }.toString()

    private fun readSites(context: Context): MutableMap<String, CategoryState> =
        parseSites(
            runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("")
        ).toMutableMap()

    /** The site's category state (empty when the site has none yet). */
    fun load(context: Context, site: String): CategoryState =
        readSites(context)[site] ?: CategoryState()

    fun save(context: Context, site: String, state: CategoryState) {
        val sites = readSites(context)
        sites[site] = state
        File(context.filesDir, FILE).writeText(sitesToJson(sites))
    }
}
