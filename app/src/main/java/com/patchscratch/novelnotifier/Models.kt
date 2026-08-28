package com.patchscratch.novelnotifier

/**
 * Shared data types. Start here if you are new to this project.
 *
 * Novel        — one row on a reading list (title, latest chapter, NU series URL, cover).
 * ReadingList  — one of the user's NU lists (id from the site, display name).
 * Release      — one row in the series RELEASES table (chapter, group, date, link, rid).
 * SeriesPage   — everything scraped from a series page for INFO + RELEASES.
 *
 * rid = Novel Updates checkbox id used to mark a chapter read on the site.
 * sid = series post id (#mypostid) used with readinglist_update.php.
 */

/** One series as it appears on a reading list. */
data class Novel(
    val title: String,
    val latestChapter: String,
    val seriesUrl: String = "",
    val coverUrl: String = "",
    val updatedAt: Long = 0L
)

/** One Novel Updates reading list. id comes from ?list= on the site. */
data class ReadingList(
    val id: String,
    val name: String
)

/** One chapter row from the series page table. */
data class Release(
    val chapter: String,
    val group: String,
    val date: String,
    val url: String,
    val rid: String = ""
)

/** Cached / scraped series detail. Empty strings mean "not loaded yet". */
data class SeriesPage(
    val title: String = "",
    val author: String = "",
    val rating: String = "",
    val description: String = "",
    val genres: String = "",
    val tags: String = "",
    val type: String = "",
    val status: String = "",
    val coverUrl: String = "",
    val releases: List<Release> = emptyList(),
    val maxPage: Int = 1,
    val currentList: String = "",
    val lists: List<ReadingList> = emptyList(),
    val sid: String = ""
)
