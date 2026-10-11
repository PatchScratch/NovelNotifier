package com.patchscratch.novelnotifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk JSON so lists and series pages open without hitting Novel Updates.
 *
 * list_cache.json     — all reading lists + the novels in each list.
 * series_cache/<hash> — one series page (INFO + every release row we have loaded).
 *
 * Rules:
 *   Incoming chapter number must be HIGHER than cache before we treat it as new.
 *   Never save an empty scrape over a cache that already has real c-numbers.
 *   Update-off lists are skipped by missingCoverQueue.
 */

/** Whole-library snapshot written after a successful list scrape. */
data class ListSnapshot(
    val lists: List<ReadingList> = emptyList(),
    val selectedId: String = "",
    val selectedName: String = "",
    val novelsByList: Map<String, List<Novel>> = emptyMap()
)

private fun listFile(context: Context) = context.filesDir.resolve("list_cache.json")
private fun seriesDir(context: Context) = context.filesDir.resolve("series_cache").apply { mkdirs() }

private fun seriesFile(context: Context, seriesUrl: String) =
    seriesDir(context).resolve(seriesUrl.hashCode().toUInt().toString() + ".json")

/**
 * NU allows a series on only one reading list. When we learn it is on [listId],
 * drop it from every other cached list so a CHANGE LIST does not leave a ghost row.
 */
fun putExclusiveList(
    novelsByList: Map<String, List<Novel>>,
    listId: String,
    rows: List<Novel>
): Map<String, List<Novel>> {
    val titles = rows.map { it.title.lowercase() }.toSet()
    val urls = rows.map { it.seriesUrl }.filter { it.isNotBlank() }.toSet()
    val next = novelsByList.mapValues { (id, existing) ->
        if (id == listId) rows
        else existing.filterNot { row ->
            row.title.lowercase() in titles ||
                (row.seriesUrl.isNotBlank() && row.seriesUrl in urls)
        }
    }.toMutableMap()
    next[listId] = rows
    return next
}

fun moveTitleToList(
    context: Context,
    title: String,
    seriesUrl: String,
    listId: String,
    listName: String
) {
    if (listId.isBlank() || title.isBlank()) return
    val snap = loadListSnapshot(context)
    val row = snap.novelsByList.values.flatten().firstOrNull { novel ->
        novel.title.equals(title, ignoreCase = true) ||
            (seriesUrl.isNotBlank() && novel.seriesUrl == seriesUrl)
    } ?: Novel(title = title, latestChapter = "", seriesUrl = seriesUrl)
    val next = putExclusiveList(snap.novelsByList, listId, listOf(row) + snap.novelsByList[listId].orEmpty().filterNot {
        it.title.equals(title, ignoreCase = true) ||
            (seriesUrl.isNotBlank() && it.seriesUrl == seriesUrl)
    })
    val lists = if (snap.lists.any { it.id == listId }) snap.lists else snap.lists + ReadingList(listId, listName)
    saveListSnapshot(context, snap.copy(lists = lists, novelsByList = next))
}

/** Which reading list contains this title. One list only on the site. */
fun listNameForTitle(snapshot: ListSnapshot, title: String, seriesUrl: String = ""): String {
    if (title.isBlank() && seriesUrl.isBlank()) return ""
    for (list in snapshot.lists) {
        val rows = snapshot.novelsByList[list.id].orEmpty()
        val hit = rows.any { row ->
            row.title.equals(title, ignoreCase = true) ||
                (seriesUrl.isNotBlank() && row.seriesUrl == seriesUrl)
        }
        if (hit && isRealListName(list.name)) return list.name
    }
    return ""
}

/** Read list_cache.json. Missing or corrupt file → empty snapshot, never a crash. */
fun loadListSnapshot(context: Context): ListSnapshot {
    return try {
        val raw = listFile(context).takeIf { it.exists() }?.readText().orEmpty()
        if (raw.isBlank()) return ListSnapshot()
        val json = JSONObject(raw)
        val lists = mutableListOf<ReadingList>()
        val listsJson = json.optJSONArray("lists")
        if (listsJson != null) {
            for (i in 0 until listsJson.length()) {
                val item = listsJson.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isNotBlank()) lists.add(ReadingList(item.optString("id"), name))
            }
        }
        val novelsByList = mutableMapOf<String, List<Novel>>()
        val novelsJson = json.optJSONObject("novels") ?: JSONObject()
        novelsJson.keys().forEach { listId ->
            val arr = novelsJson.optJSONArray(listId) ?: return@forEach
            val rows = mutableListOf<Novel>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                rows.add(
                    Novel(
                        title = item.optString("title"),
                        latestChapter = item.optString("latestChapter"),
                        seriesUrl = item.optString("seriesUrl"),
                        coverUrl = item.optString("coverUrl"),
                        updatedAt = item.optLong("updatedAt")
                    )
                )
            }
            novelsByList[listId] = rows
        }
        ListSnapshot(
            lists = lists,
            selectedId = json.optString("selectedId"),
            selectedName = json.optString("selectedName"),
            novelsByList = novelsByList
        )
    } catch (_: Exception) {
        ListSnapshot()
    }
}

/** Overwrite list_cache.json with the current lists + every series row we know. */
fun saveListSnapshot(context: Context, snapshot: ListSnapshot) {
    try {
        val listsJson = JSONArray()
        snapshot.lists.forEach { list ->
            listsJson.put(JSONObject().put("id", list.id).put("name", list.name))
        }
        val novelsJson = JSONObject()
        snapshot.novelsByList.forEach { (listId, novels) ->
            val arr = JSONArray()
            novels.forEach { novel ->
                arr.put(
                    JSONObject()
                        .put("title", novel.title)
                        .put("latestChapter", novel.latestChapter)
                        .put("seriesUrl", novel.seriesUrl)
                        .put("coverUrl", novel.coverUrl)
                        .put("updatedAt", novel.updatedAt)
                )
            }
            novelsJson.put(listId, arr)
        }
        val json = JSONObject()
            .put("lists", listsJson)
            .put("selectedId", snapshot.selectedId)
            .put("selectedName", snapshot.selectedName)
            .put("novels", novelsJson)
        listFile(context).writeText(json.toString())
    } catch (_: Exception) {
    }
}

/** Pair is (page, last table page we successfully loaded). Null = no file. */
fun loadSeriesCache(context: Context, seriesUrl: String): Pair<SeriesPage, Int>? {
    if (seriesUrl.isBlank()) return null
    return try {
        val raw = seriesFile(context, seriesUrl).takeIf { it.exists() }?.readText().orEmpty()
        if (raw.isBlank()) return null
        val json = JSONObject(raw)
        val page = parseSeriesPage(json.optJSONObject("page")?.toString().orEmpty())
        if (page.title.isBlank() && page.releases.isEmpty()) return null
        page to json.optInt("loadedPage", 1).coerceAtLeast(1)
    } catch (_: Exception) {
        null
    }
}

fun saveSeriesCache(context: Context, seriesUrl: String, page: SeriesPage, loadedPage: Int) {
    if (seriesUrl.isBlank()) return
    if (page.releases.isEmpty() && page.description.isBlank()) return
    try {
        val pageJson = JSONObject()
            .put("title", page.title)
            .put("author", page.author)
            .put("rating", page.rating)
            .put("description", page.description)
            .put("genres", page.genres)
            .put("tags", page.tags)
            .put("type", page.type)
            .put("status", page.status)
            .put("cover", page.coverUrl)
            .put("maxPage", page.maxPage)
            .put("currentList", page.currentList)
            .put("sid", page.sid)
            .put("associatedNames", page.associatedNames)
            .put("artist", page.artist)
            .put("year", page.year)
            .put("originalPublisher", page.originalPublisher)
            .put("completelyTranslated", page.completelyTranslated)
        val releases = JSONArray()
        page.releases.forEach { release ->
            releases.put(
                JSONObject()
                    .put("chapter", release.chapter)
                    .put("group", release.group)
                    .put("date", release.date)
                    .put("url", release.url)
                    .put("rid", release.rid)
                    .put("read", release.read)
            )
        }
        pageJson.put("releases", releases)
        val lists = JSONArray()
        page.lists.forEach { list ->
            lists.put(JSONObject().put("id", list.id).put("name", list.name))
        }
        pageJson.put("lists", lists)
        val json = JSONObject()
            .put("page", pageJson)
            .put("loadedPage", loadedPage)
            .put("savedChapter", page.releases.firstOrNull()?.chapter.orEmpty())
        seriesFile(context, seriesUrl).writeText(json.toString())
    } catch (_: Exception) {
    }
}

fun cachedChapter(context: Context, seriesUrl: String): String {
    if (seriesUrl.isBlank()) return ""
    return try {
        val raw = seriesFile(context, seriesUrl).takeIf { it.exists() }?.readText().orEmpty()
        if (raw.isBlank()) "" else JSONObject(raw).optString("savedChapter")
    } catch (_: Exception) {
        ""
    }
}

/** True only when the list's latest c-number is higher than the cached series page. */
fun seriesNeedsRefresh(context: Context, novel: Novel): Boolean {
    if (novel.seriesUrl.isBlank()) return true
    val cached = loadSeriesCache(context, novel.seriesUrl) ?: return true
    if (cached.first.releases.isEmpty()) return true
    val incoming = chapterNum(novel.latestChapter)
    val saved = chapterNum(cachedChapter(context, novel.seriesUrl))
    if (incoming < 0 || saved < 0) return false
    return incoming > saved
}

/** Keep old rows, add new ones. Same chapter+url is not duplicated. */
fun mergeReleases(old: List<Release>, incoming: List<Release>): List<Release> {
    val seen = mutableSetOf<String>()
    val merged = mutableListOf<Release>()
    (incoming + old).forEach { release ->
        val key = release.chapter + "|" + release.url
        val existing = merged.indexOfFirst { it.chapter + "|" + it.url == key }
        if (existing < 0) {
            if (seen.add(key)) merged.add(release)
        } else {
            val prev = merged[existing]
            merged[existing] = prev.copy(
                rid = release.rid.ifBlank { prev.rid },
                read = prev.read || release.read
            )
        }
    }
    return merged
}

fun applyListChapter(context: Context, title: String, chapter: String) {
    val snap = loadListSnapshot(context)
    if (snap.novelsByList.isEmpty()) return
    val next = snap.novelsByList.mapValues { (_, rows) ->
        rows.map { row ->
            if (row.title == title && chapter.isNotBlank()) row.copy(latestChapter = chapter) else row
        }
    }
    saveListSnapshot(context, snap.copy(novelsByList = next))
}

/**
 * RSS items whose c-number is higher than both the list row and the series cache.
 * Used to queue SeriesChapterFetcher — not for notifications (see ChapterAlerts).
 */
fun rssUpdatesToFetch(context: Context, rssNovels: List<Novel>): List<Novel> {
    val known = loadListSnapshot(context).novelsByList.values.flatten()
        .distinctBy { it.title }
        .associateBy { it.title.lowercase() }
    return rssNovels.mapNotNull { rss ->
        val match = known[rss.title.lowercase()] ?: return@mapNotNull null
        if (match.seriesUrl.isBlank()) return@mapNotNull null
        val incoming = chapterNum(rss.latestChapter)
        if (incoming < 0) return@mapNotNull null
        val savedPage = chapterNum(cachedChapter(context, match.seriesUrl))
        val savedList = chapterNum(match.latestChapter)
        val saved = maxOf(savedPage, savedList)
        if (saved > 0 && incoming <= saved) return@mapNotNull null
        match.copy(latestChapter = rss.latestChapter)
    }.distinctBy { it.seriesUrl }
}

/**
 * Lists we have never scraped (no series URLs on disk).
 * Prefetch must load these before it can find covers outside the open list.
 */
fun listsToHydrate(context: Context, updateOff: Set<String>): List<ReadingList> {
    val snap = loadListSnapshot(context)
    return snap.lists.filter { list ->
        list.id.isNotBlank() &&
            list.id !in updateOff &&
            snap.novelsByList[list.id].orEmpty().none { it.seriesUrl.isNotBlank() }
    }
}

/** Series that still need a cover and belong to lists that are allowed to update. */
fun missingCoverQueue(
    context: Context,
    coverCache: Map<String, String>,
    updateOff: Set<String>
): List<Novel> {
    val snap = loadListSnapshot(context)
    return snap.novelsByList
        .filterKeys { id -> id.isBlank() || id !in updateOff }
        .values
        .flatten()
        .distinctBy { it.title }
        .filter { novel ->
            novel.seriesUrl.isNotBlank() &&
                usableCoverUrl(novel.coverUrl).isBlank() &&
                usableCoverUrl(coverCache[novel.title]).isBlank()
        }
}

/** Keep prior updatedAt when the chapter string did not change. */
fun stampChapterTimes(oldRows: List<Novel>, newRows: List<Novel>): List<Novel> {
    val old = oldRows.associateBy { it.title }
    val now = System.currentTimeMillis()
    return newRows.map { row ->
        val prev = old[row.title]
        val changed = prev == null || prev.latestChapter != row.latestChapter
        row.copy(updatedAt = if (changed) now else prev?.updatedAt ?: 0L)
    }
}

/** How many series changed their latest-chapter string. Used for the list status line. */
fun chapterChangeCount(oldRows: List<Novel>, newRows: List<Novel>): Int {
    val oldMap = oldRows.associateBy { it.seriesUrl.ifBlank { it.title } }
    return newRows.count { row ->
        val key = row.seriesUrl.ifBlank { row.title }
        val previous = oldMap[key] ?: return@count false
        previous.latestChapter != row.latestChapter
    }
}
