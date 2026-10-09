package com.patchscratch.novelnotifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The library bulk-download queue: what remains to fetch, in what order, and
 * how long it will conservatively take.
 *
 * Job order is the whole design (user decision, 2026-10-09):
 *   1. hydrate lists we have no rows for (priority lists first)
 *   2. ALL covers — priority lists first (visual reassurance early)
 *   3. ALL chapter-page sets — priority lists first (read-state sooner for
 *      the lists the user actually reads)
 * The queue is JSON on disk so a kill / reboot / battery death resumes exactly
 * where it stopped. Series on Update-off lists are skipped entirely.
 */

private const val TYPE_LIST = "list"
private const val TYPE_COVER = "cover"
private const val TYPE_PAGES = "pages"
private const val TYPE_CHECK = "check"

data class SyncJob(
    val type: String,
    val key: String,
    val title: String = "",
    val seriesUrl: String = "",
    val coverUrl: String = "",
    val nextPage: Int = 1,
    val maxPage: Int = 1,
    val done: Boolean = false
) {
    val id: String get() = "$type:$key"
}

object SyncQueue {

    private fun file(context: Context) = context.filesDir.resolve("sync_queue.json")

    fun load(context: Context): List<SyncJob> {
        return try {
            val raw = file(context).takeIf { it.exists() }?.readText().orEmpty()
            if (raw.isBlank()) return emptyList()
            val arr = JSONObject(raw).optJSONArray("jobs") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SyncJob(
                    type = o.optString("type"),
                    key = o.optString("key"),
                    title = o.optString("title"),
                    seriesUrl = o.optString("seriesUrl"),
                    coverUrl = o.optString("coverUrl"),
                    nextPage = o.optInt("nextPage", 1).coerceAtLeast(1),
                    maxPage = o.optInt("maxPage", 1).coerceAtLeast(1),
                    done = o.optBoolean("done", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, jobs: List<SyncJob>) {
        try {
            val arr = JSONArray()
            jobs.forEach { job ->
                arr.put(
                    JSONObject()
                        .put("type", job.type)
                        .put("key", job.key)
                        .put("title", job.title)
                        .put("seriesUrl", job.seriesUrl)
                        .put("coverUrl", job.coverUrl)
                        .put("nextPage", job.nextPage)
                        .put("maxPage", job.maxPage)
                        .put("done", job.done)
                )
            }
            file(context).writeText(JSONObject().put("jobs", arr).toString())
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    fun pending(context: Context): List<SyncJob> = load(context).filterNot { it.done }

    /**
     * The periodic chapter check for devices without an RSS feed (rssnovelupdates.com
     * stopped accepting new sign-ups, so RSS cannot be a hard requirement): one
     * paced scrape per active list; the engine compares rows to the cache and
     * appends series-page fetches for anything that changed.
     */
    fun buildCheckQueue(context: Context): List<SyncJob> {
        val snap = loadListSnapshot(context)
        val updateOff = loadIdSet(
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE),
            "list_update_off"
        )
        return snap.lists
            .filter { it.id.isNotBlank() && it.id !in updateOff }
            .map { SyncJob(TYPE_CHECK, it.id, it.name) }
    }

    /** True when no RSS feed is configured — the engine list-check path is used. */
    fun needsListChecks(context: Context): Boolean {
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        return prefs.getString("rss_url", "").isNullOrBlank()
    }

    /**
     * Build the full setup queue for the current library snapshot.
     * [priorityListIds] are the lists the user marked as "load first" during
     * onboarding. [coversOnly] drives Settings → Prefetch missing covers.
     */
    fun build(
        context: Context,
        priorityListIds: Set<String>,
        coversOnly: Boolean = false
    ): List<SyncJob> {
        val snap = loadListSnapshot(context)
        val updateOff = loadIdSet(
            context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE),
            "list_update_off"
        )
        val rowsByList = snap.novelsByList

        val rank: (String) -> Int = { id -> if (id in priorityListIds) 0 else 1 }
        val listsToScrape = snap.lists
            .filter { it.id.isNotBlank() && it.id !in updateOff }
            .sortedBy { rank(it.id) }

        val jobs = mutableListOf<SyncJob>()

        // 1. Lists we have never read. On a fresh device we do not even know the
        //    list IDs yet — one "discover" job scrapes the base reading-list page
        //    and the engine appends the per-list jobs it finds there.
        if (snap.lists.none { it.id.isNotBlank() }) {
            jobs += SyncJob(TYPE_LIST, "", "Reading lists")
        } else {
            listsToScrape
                .filter { list -> rowsByList[list.id].orEmpty().none { it.seriesUrl.isNotBlank() } }
                .forEach { list -> jobs += SyncJob(TYPE_LIST, list.id, list.name) }
        }

        // 2. Covers for every known series — priority lists first.
        val allRows = listsToScrape
            .flatMap { list -> rowsByList[list.id].orEmpty().map { list.id to it } }
            .sortedBy { (listId, _) -> rank(listId) }
            .distinctBy { (_, row) -> row.seriesUrl.ifBlank { row.title } }
        allRows.forEach { (_, row) ->
            if (row.seriesUrl.isNotBlank()) {
                jobs += SyncJob(
                    TYPE_COVER,
                    row.seriesUrl,
                    row.title,
                    row.seriesUrl,
                    coverUrl = usableCoverUrl(row.coverUrl)
                )
            }
        }

        // 3. Chapter pages for every series — priority lists first.
        if (!coversOnly) {
            allRows.forEach { (_, row) ->
                if (row.seriesUrl.isNotBlank()) {
                    val cached = loadSeriesCache(context, row.seriesUrl)
                    val next = (cached?.second ?: 0) + 1
                    val max = cached?.first?.maxPage ?: 1
                    jobs += SyncJob(
                        TYPE_PAGES,
                        row.seriesUrl,
                        row.title,
                        row.seriesUrl,
                        nextPage = next,
                        maxPage = max
                    )
                }
            }
        }
        return jobs
    }
}

/**
 * Conservative setup-time estimate. 9 seconds per site hit (8s pace + slack),
 * 5 pages per series — deliberately on the high side so reality beats the
 * promise instead of missing it.
 */
data class SetupEstimate(
    val seriesCount: Int,
    val listCount: Int,
    val coverMillis: Long,
    val chapterMillis: Long
) {
    val totalMillis: Long get() = coverMillis + chapterMillis
}

fun estimateSetup(seriesCount: Int, listCount: Int): SetupEstimate {
    val msPerHit = 9_000L
    val listHits = listCount.coerceAtLeast(1)
    return SetupEstimate(
        seriesCount = seriesCount,
        listCount = listCount,
        coverMillis = (seriesCount * msPerHit) + (listHits * msPerHit),
        chapterMillis = seriesCount * 5 * msPerHit
    )
}

/** "≈ 4 hours", "≈ 18 hours", "about 1 day", "about 2.5 days". */
fun formatEstimate(millis: Long): String {
    val hours = millis / 3_600_000.0
    return when {
        hours < 1f -> "under an hour"
        hours < 24f -> "≈ ${if (hours < 10) trimZero(roundHalf(hours)) else hours.toInt().toString()} hours"
        else -> {
            val days = roundHalf(hours / 24.0)
            "about ${trimZero(days)} day" + if (days == 1.0) "" else "s"
        }
    }
}

private fun roundHalf(v: Double): Double = kotlin.math.round(v * 2) / 2
private fun trimZero(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
