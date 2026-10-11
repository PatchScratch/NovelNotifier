package com.patchscratch.novelnotifier

import android.webkit.CookieManager
import androidx.core.content.edit

/**
 * Tiny key/value store helpers. SharedPreferences file name is "novel_prefs".
 *
 * Keys you will see:
 *   cover_cache        — title|||https://…cover.jpg
 *   last_read          — title|||chapter|||url
 *   last_list_sync     — epoch millis of last successful list scrape
 *   list_notify_off    — list ids that must not alert
 *   list_update_off    — list ids that skip site hits (archives)
 *   series_notify_off  — series titles with the bell off
 *   rss_url, check_hours, nu_cookies, read_titles
 *
 * Larger data (full lists, series pages) lives in Cache.kt as JSON files.
 */

/** Load title → cover URL map. */
fun loadCoverCache(prefs: android.content.SharedPreferences): Map<String, String> {
    val stored = prefs.getStringSet("cover_cache", emptySet()) ?: emptySet()
    return stored.mapNotNull { row ->
        val parts = row.split("|||", limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
}

/** Persist title → cover URL map as a StringSet of "title|||url" rows. */
fun saveCoverCache(prefs: android.content.SharedPreferences, cache: Map<String, String>) {
    prefs.edit {
        putStringSet("cover_cache", cache.map { "${it.key}|||${it.value}" }.toSet())
    }
}

/** SharedPreferences StringSet helper. Missing key → empty set, never null. */
fun loadIdSet(prefs: android.content.SharedPreferences, key: String): Set<String> {
    return prefs.getStringSet(key, emptySet()) ?: emptySet()
}

/** True if we have never synced, or the last list pull is older than [checkHours]. */
fun listSyncIsStale(prefs: android.content.SharedPreferences, checkHours: Int): Boolean {
    val last = prefs.getLong("last_list_sync", 0L)
    if (last == 0L) return true
    val maxAgeMs = checkHours.coerceAtLeast(1).toLong() * 60L * 60L * 1000L
    return System.currentTimeMillis() - last > maxAgeMs
}

fun markListSynced(prefs: android.content.SharedPreferences) {
    prefs.edit { putLong("last_list_sync", System.currentTimeMillis()) }
}

fun markSiteCheckNeeded(prefs: android.content.SharedPreferences) {
    prefs.edit { putBoolean("cf_needed", true) }
}

fun clearSiteCheckNeeded(prefs: android.content.SharedPreferences) {
    prefs.edit { putBoolean("cf_needed", false) }
}

fun siteCheckNeeded(prefs: android.content.SharedPreferences): Boolean {
    return prefs.getBoolean("cf_needed", false)
}

/** Human line for the list header, e.g. "synced 3m ago". */
fun lastListSyncLabel(prefs: android.content.SharedPreferences): String {
    val last = prefs.getLong("last_list_sync", 0L)
    if (last == 0L) return "never synced"
    val hours = (System.currentTimeMillis() - last) / (60L * 60L * 1000L)
    val minutes = ((System.currentTimeMillis() - last) / (60L * 1000L)) % 60L
    return if (hours >= 1) "synced ${hours}h ${minutes}m ago" else "synced ${minutes}m ago"
}

/** title → (chapter, chapterUrl). */
fun loadLastRead(prefs: android.content.SharedPreferences): Map<String, Pair<String, String>> {
    return loadIdSet(prefs, "last_read").mapNotNull { row ->
        val parts = row.split("|||", limit = 3)
        if (parts.size == 3) parts[0] to (parts[1] to parts[2]) else null
    }.toMap()
}

fun saveLastRead(prefs: android.content.SharedPreferences, lastRead: Map<String, Pair<String, String>>) {
    prefs.edit {
        putStringSet(
            "last_read",
            lastRead.map { "${it.key}|||${it.value.first}|||${it.value.second}" }.toSet()
        )
    }
}

/** Keep the higher chapter when the site and the app both have a last-read. */
fun mergeLastReads(
    current: Map<String, Pair<String, String>>,
    incoming: Map<String, Pair<String, String>>
): Map<String, Pair<String, String>> {
    if (incoming.isEmpty()) return current
    val next = current.toMutableMap()
    incoming.forEach { (title, pair) ->
        val existing = next[title]
        val incomingNum = chapterNum(pair.first)
        val existingNum = chapterNum(existing?.first.orEmpty())
        if (existing == null || (incomingNum > 0 && incomingNum >= existingNum) || existingNum < 0) {
            next[title] = pair
        }
    }
    return next
}

/**
 * Merge the read-activity feed (rss_read_url, the type=read feed NU exposes)
 * into last_read — cross-device read sync: mark a chapter read on the phone,
 * the tablet picks it up on its next check. mergeLastReads keeps the higher
 * chapter, so this can never rewind progress. Items are skipped when they are
 * older than a deliberate back-track ([loadReadFloors] explains why).
 * Returns titles advanced.
 */
fun applyReadFeed(
    prefs: android.content.SharedPreferences,
    readItems: List<Pair<Novel, Long>>
): Int {
    if (readItems.isEmpty()) return 0
    val floors = loadReadFloors(prefs)
    val current = loadLastRead(prefs)
    val incoming = readItems.mapNotNull { (novel, date) ->
        if (novel.title.isBlank()) return@mapNotNull null
        val num = chapterNum(novel.latestChapter)
        if (num <= 0) return@mapNotNull null
        val floor = floors[novel.title]
        if (floor != null && !readFeedItemApplies(floor.first, floor.second, num, date)) {
            return@mapNotNull null // stale mark from before a deliberate back-track
        }
        novel.title to (novel.latestChapter to "")
    }.toMap()
    if (incoming.isEmpty()) return 0
    val merged = mergeLastReads(current, incoming)
    val advanced = incoming.count { (title, pair) ->
        val before = chapterNum(current[title]?.first.orEmpty())
        val after = chapterNum(pair.first)
        after > 0 && after > before
    }
    if (advanced > 0) saveLastRead(prefs, merged)
    return advanced
}

/**
 * May a read-feed item raise the position above a manual back-track floor?
 * Only if it was marked AFTER the correction — otherwise it is one of the
 * stale, higher marks the user deliberately moved away from. Pure for tests.
 */
fun readFeedItemApplies(floorNum: Int, floorTime: Long, itemNum: Int, itemTime: Long): Boolean {
    if (floorNum <= 0) return true
    if (itemNum <= floorNum) return true
    return itemTime > 0 && itemTime >= floorTime
}

/**
 * Manual read-position floors: title → (chapterNum, epochMillis). Written
 * whenever the user back-tracks or clears the tick in the RELEASES tab, so
 * the read-activity feed cannot silently undo the correction.
 */
fun recordReadFloor(prefs: android.content.SharedPreferences, title: String, chapterNum: Int) {
    val floors = loadReadFloors(prefs).toMutableMap()
    floors[title] = chapterNum to System.currentTimeMillis()
    prefs.edit {
        putStringSet("read_floor", floors.map { "${it.key}|||${it.value.first}|||${it.value.second}" }.toSet())
    }
}

fun loadReadFloors(prefs: android.content.SharedPreferences): Map<String, Pair<Int, Long>> {
    return loadIdSet(prefs, "read_floor").mapNotNull { row ->
        val parts = row.split("|||")
        val num = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val time = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
        parts[0] to (num to time)
    }.toMap()
}

const val LAYOUT_LIST = "list"
const val LAYOUT_GRID = "grid"
const val SORT_ALPHA = "alpha"
const val SORT_UNREAD_COUNT = "unread_count"
const val SORT_UNREAD_AGE = "unread_age"
const val SORT_UPDATE_TIME = "update_time"

fun loadLayoutMode(prefs: android.content.SharedPreferences): String {
    return prefs.getString("layout_mode", LAYOUT_GRID) ?: LAYOUT_GRID
}

fun saveLayoutMode(prefs: android.content.SharedPreferences, mode: String) {
    prefs.edit { putString("layout_mode", mode) }
}

/** Stack chapter alerts under one banner (default) or post them individually. */
fun loadStackAlerts(prefs: android.content.SharedPreferences): Boolean {
    return prefs.getBoolean("stack_notifications", true)
}

fun saveStackAlerts(prefs: android.content.SharedPreferences, stack: Boolean) {
    prefs.edit { putBoolean("stack_notifications", stack) }
}

fun loadSortMode(prefs: android.content.SharedPreferences): String {
    return prefs.getString("sort_mode", SORT_UNREAD_AGE) ?: SORT_UNREAD_AGE
}

fun saveSortMode(prefs: android.content.SharedPreferences, mode: String) {
    prefs.edit { putString("sort_mode", mode) }
}

fun sortModeLabel(mode: String): String = when (mode) {
    SORT_ALPHA -> "Alphabetical"
    SORT_UNREAD_COUNT -> "Unread (Count)"
    SORT_UPDATE_TIME -> "Update Time"
    else -> "Unread (Update Age)"
}

/**
 * Push the saved NU cookie header back into CookieManager.
 * Hidden WebViews read CookieManager, not SharedPreferences. After a process
 * kill or "clear cookies", login would look saved in Settings but scrapes
 * would run logged-out. Call once on app start.
 */
fun applySavedCookies(prefs: android.content.SharedPreferences) {
    val header = prefs.getString("nu_cookies", "").orEmpty()
    if (header.isBlank()) return
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    header.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { cookie ->
        manager.setCookie("https://www.novelupdates.com", cookie)
    }
    manager.flush()
}
