package com.patchscratch.novelnotifier

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * String → data. No UI, no network.
 *
 * chapterNum        — best chapter-like number in a string (c / ch / chapter / ep / v#c#).
 * unwrapJsResult    — WebView.evaluateJavascript wraps strings in extra quotes/escapes.
 * parseSeriesPage   — JSON produced by the series-page JavaScript scrape.
 * parseRssTitles    — item <title> values from the personal NU RSS feed.
 * novelFromRssTitle — splits "Book Name c520" into title + chapter.
 *
 * Returns -1 when there is no chapter number. Callers treat that as "unknown", never as zero.
 */

/**
 * Best chapter-like number in [text], or -1 if none.
 * Prefers chapter over volume: "v12c34" → 34, "Chapter 12" → 12, "c45" → 45.
 */
fun chapterNum(text: String): Int {
    if (text.isBlank()) return -1
    val src = text.lowercase()
    val patterns = listOf(
        """v(?:ol(?:ume)?)?\s*\.?\s*\d+\s*[-:]?\s*c(?:h(?:apter)?)?\s*\.?\s*(\d+)""",
        """c(?:h(?:apter)?)?\s*\.?\s*(\d+)""",
        """ep(?:isode)?\s*\.?\s*(\d+)""",
        """#\s*(\d+)"""
    )
    for (pattern in patterns) {
        val match = Regex(pattern).find(src) ?: continue
        val value = match.groupValues[1].toIntOrNull()
        if (value != null) return value
    }
    return -1
}

/** Unread chapters: latest minus last-read. Unknown last-read → 0, not the whole series. */
fun unreadCount(latestChapter: String, lastReadChapter: String?): Int {
    val latest = chapterNum(latestChapter)
    if (latest < 0) return 0
    val read = chapterNum(lastReadChapter.orEmpty())
    if (read < 0) return 0
    return (latest - read).coerceAtLeast(0)
}

/** Highest chapter the site has ticked as read, or null. */
fun highestReadRelease(releases: List<Release>): Release? {
    return releases
        .filter { it.read }
        .maxByOrNull { chapterNum(it.chapter) }
        ?.takeIf { chapterNum(it.chapter) > 0 }
}

fun isRealListName(name: String): Boolean {
    val t = name.trim()
    if (t.isBlank()) return false
    val lower = t.lowercase()
    return !lower.startsWith("select") &&
        !lower.startsWith("choose") &&
        lower != "---" &&
        lower != "--"
}

fun prettyRating(raw: String): String {
    val score = Regex("""(\d+(?:\.\d+)?)""").find(raw)?.groupValues?.get(1)
    return score ?: raw.trim()
}

/** Strip the extra quoting evaluateJavascript adds around a JS string. */
fun unwrapJsResult(result: String?): String {
    if (result.isNullOrBlank() || result == "null") return ""
    return result
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}

/** Parse the JSON blob the series WebView returns. Bad JSON → empty SeriesPage. */
fun parseSeriesPage(raw: String): SeriesPage {
    if (raw.isBlank()) return SeriesPage()
    return try {
        val json = JSONObject(raw)
        val releasesJson = json.optJSONArray("releases")
        val releases = mutableListOf<Release>()
        if (releasesJson != null) {
            for (i in 0 until releasesJson.length()) {
                val item = releasesJson.optJSONObject(i) ?: continue
                releases.add(
                    Release(
                        chapter = item.optString("chapter"),
                        group = item.optString("group"),
                        date = item.optString("date"),
                        url = item.optString("url"),
                        rid = item.optString("rid"),
                        read = item.optBoolean("read", false)
                    )
                )
            }
        }
        val listsJson = json.optJSONArray("lists")
        val lists = mutableListOf<ReadingList>()
        if (listsJson != null) {
            for (i in 0 until listsJson.length()) {
                val item = listsJson.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (isRealListName(name)) lists.add(ReadingList(item.optString("id"), name))
            }
        }
        SeriesPage(
            title = json.optString("title"),
            author = json.optString("author"),
            rating = json.optString("rating"),
            description = json.optString("description"),
            genres = json.optString("genres"),
            tags = json.optString("tags"),
            type = json.optString("type"),
            status = json.optString("status"),
            coverUrl = json.optString("cover"),
            releases = releases,
            maxPage = json.optInt("maxPage", 1).coerceAtLeast(1),
            currentList = json.optString("currentList").let { if (isRealListName(it)) it else "" },
            lists = lists,
            sid = json.optString("sid")
        )
    } catch (_: Exception) {
        SeriesPage()
    }
}

/** RSS titles look like "The Book Title c186". */
fun novelFromRssTitle(rawTitle: String, coverUrl: String = ""): Novel {
    val chapterMatch = Regex("""\s+c\d+\s*$""", RegexOption.IGNORE_CASE).find(rawTitle)
    val chapter = chapterMatch?.value?.trim() ?: "New release"
    val title = rawTitle.removeSuffix(chapterMatch?.value ?: "").trim()
    return Novel(title = title, latestChapter = chapter, coverUrl = coverUrl)
}

/** Walk RSS XML and collect every <item><title>. Uses Android's XmlPullParser. */
fun parseRssTitles(xml: String): List<String> {
    val titles = mutableListOf<String>()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(xml.reader())
    var insideItem = false
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "item") insideItem = true
                if (insideItem && parser.name == "title") titles.add(parser.nextText())
            }
            XmlPullParser.END_TAG -> {
                if (parser.name == "item") insideItem = false
            }
        }
        event = parser.next()
    }
    return titles
}
