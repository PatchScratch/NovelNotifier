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

/**
 * Volume+chapter key from a label like "v2c220" / "c673" / "Chapter 673: …".
 * Volume 0 when absent. Returns null when no chapter number parses. Used by
 * the ToC-first bulk downloader to match harvested links against RELEASES
 * labels — (0, 673) must NOT match "v2c673", only "c673".
 */
fun chapterKey(text: String): Pair<Int, Int>? {
    if (text.isBlank()) return null
    val src = text.lowercase()
    val vol = Regex("""v(?:ol(?:ume)?)?\s*\.?\s*(\d+)""").find(src)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val ch = chapterNum(src)
    if (ch >= 0) return vol to ch
    // Bare numbered labels ("001 – First Battle", "12. Shopping") — used by
    // custom reader platforms; NOT added to chapterNum because bare numbers
    // are ambiguous in its other call sites (labels, last-read comparisons).
    // separators incl. CJK brackets (「595 「Title」」 style); a bare SPACE
    // separator needs 2+ digits so nav junk like "0 cart" stays unknown.
    val bare = Regex("""^(\d{1,4})\s*[-–—.:)）「（]""").find(src)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""^(\d{2,4})\s+\D""").find(src)?.groupValues?.get(1)?.toIntOrNull()
    if (bare != null) return 0 to bare
    return null
}

/** One same-host chapter link harvested from a loaded page (tocHarvestJs). */
data class HarvestedLink(val url: String, val text: String) {
    val key: Pair<Int, Int>? get() = chapterKey(text)
}

/**
 * Parse the JSON from tocHarvestJs(). Bad input → empty; the bulk downloader
 * treats every entry as a candidate, matched conservatively by chapter key.
 */
fun parseTocHarvest(raw: String): List<HarvestedLink> {
    val unwrapped = unwrapJsResult(raw).trim()
    if (unwrapped.isBlank() || unwrapped == "[]") return emptyList()
    return try {
        val arr = org.json.JSONArray(unwrapped)
        (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("u")
            if (url.startsWith("http")) HarvestedLink(url, item.optString("t")) else null
        }
    } catch (e: Exception) {
        emptyList()
    }
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

/**
 * Reading order for a releases list. Sorts by the FULL (volume, chapter)
 * key — a chapter-number-only sort puts v2c1 before v1c100, scrambling
 * volume-labeled series (user report 2026-10-13: chapters "download out
 * of order"). Unparseable labels keep their relative position at the end
 * (ascending) / start (descending), as before.
 */
fun sortedReleases(releases: List<Release>, newestFirst: Boolean): List<Release> {
    // Unparseable labels pin LAST in both directions (sentinels differ).
    fun key(chapter: String): Pair<Int, Int> = chapterKey(chapter)
        ?: if (newestFirst) (Int.MIN_VALUE to Int.MIN_VALUE) else (Int.MAX_VALUE to Int.MAX_VALUE)
    val cmp = Comparator<Release> { a, b ->
        val ka = key(a.chapter); val kb = key(b.chapter)
        val byKey = ka.first.compareTo(kb.first).takeIf { it != 0 }
            ?: ka.second.compareTo(kb.second)
        if (newestFirst) -byKey else byKey
    }
    return releases.sortedWith(cmp)
}

fun cacheHasAllPages(maxPage: Int, loadedPage: Int, releaseCount: Int): Boolean {
    if (releaseCount <= 0) return false
    if (maxPage <= 1) return true
    return loadedPage >= maxPage
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

/**
 * NU serves this grey placeholder whenever a cover has not (yet) loaded.
 * It must never be treated as a real cover: caching it marks the series
 * "done" forever and the grid shows a grey tile that nothing repairs.
 */
fun isPlaceholderCover(url: String?): Boolean {
    return url != null && url.contains("noimagefound", ignoreCase = true)
}

/** A cover URL we can actually show: non-blank and not the site placeholder. */
fun usableCoverUrl(url: String?): String {
    return if (url.isNullOrBlank() || isPlaceholderCover(url)) "" else url
}

/** What parseListPageResult() extracted from one reading-list page. */
data class ListPageResult(
    val lists: List<ReadingList>,
    val novels: List<Novel>,
    val lastReads: Map<String, Pair<String, String>>,
    /** The releases feed NU shows on the reading-list page, if any. */
    val rssUrl: String = "",
    /** The read-activity feed (type=read) — powers cross-device read sync. */
    val readRssUrl: String = ""
)

/** From the feed links on a reading-list page: the chapter-releases one. */
fun pickReleasesFeedUrl(urls: List<String>): String {
    return urls.firstOrNull {
        it.contains("rss", ignoreCase = true) && !it.contains("type=read", ignoreCase = true)
    }.orEmpty()
}

/** From the feed links on a reading-list page: the read-activity one. */
fun pickReadFeedUrl(urls: List<String>): String {
    return urls.firstOrNull { it.contains("type=read", ignoreCase = true) }.orEmpty()
}

/**
 * Parse the raw result of listPageJs() (already unwrapped from evaluateJavascript):
 * "listId===listName|||…:::NOVELS:::rows…:::RSS:::feedUrl|||feedUrl".
 * Cover resolution order: real cached URL → site img (placeholder rejected) →
 * previous row cover ([oldCoverByUrl], so a re-sync can never wipe a cover).
 */
fun parseListPageResult(
    raw: String,
    coverCache: Map<String, String> = emptyMap(),
    oldCoverByUrl: Map<String, String> = emptyMap()
): ListPageResult {
    val parts = raw.split(":::NOVELS:::")
    val listPart = parts.getOrNull(0).orEmpty()
    val novelAndRss = parts.getOrNull(1).orEmpty()
    val novelPart = novelAndRss.substringBefore(":::RSS:::")
    val feedUrls = novelAndRss.substringAfter(":::RSS:::", "")
        .split("|||").map { it.trim() }.filter { it.startsWith("http") }
    val lists = listPart.split("|||").mapNotNull { item ->
        val bits = item.split("===", limit = 2)
        if (bits.size == 2 && isRealListName(bits[1])) ReadingList(bits[0], bits[1]) else null
    }.distinctBy { it.name }
    val lastReads = mutableMapOf<String, Pair<String, String>>()
    // Wayne's rule (2026-10-14): the NU section ignores anything from the
    // Scribble Hub domain. SH-hosted series ride NU's lists with chapter
    // links pointing at scribblehub.com; their unread badges (NU tick vs
    // SH releases) ran into the hundreds. Detection = the row's latest-
    // release link (7th payload field, added with the filter).
    var droppedScribblehub = 0
    val novels = novelPart.split("|||").mapNotNull { item ->
        val bits = item.split("===", limit = 7)
        if (bits.size >= 2) {
            val title = bits[0]
            val seriesUrl = bits.getOrNull(2).orEmpty()
            val siteLast = bits.getOrNull(4).orEmpty().trim()
            if (isScribblehubUrl(bits.getOrNull(6)) || isScribblehubUrl(bits.getOrNull(5))) {
                droppedScribblehub++
                return@mapNotNull null
            }
            val skipLast = siteLast.isBlank() ||
                siteLast == "—" ||
                siteLast == "-" ||
                siteLast.equals("none", true) ||
                siteLast.equals("n/a", true)
            if (!skipLast && chapterNum(siteLast) > 0) {
                lastReads[title] = siteLast to bits.getOrNull(5).orEmpty()
            }
            Novel(
                title = title,
                latestChapter = bits[1],
                seriesUrl = seriesUrl,
                coverUrl = usableCoverUrl(coverCache[title])
                    .ifBlank { usableCoverUrl(bits.getOrNull(3)) }
                    .ifBlank { oldCoverByUrl[seriesUrl].orEmpty() }
            )
        } else null
    }.distinctBy { it.title }
        .filter { novel ->
            val title = novel.title.trim()
            title.isNotBlank() &&
                !title.equals("Series Information", ignoreCase = true) &&
                novel.seriesUrl.contains("/series/")
        }
    // The payload carries NU's own row count ("Series (195)" in the list
    // header). Panel/widget rows append AFTER the real list — trim to the
    // count and they cannot survive (Wayne's rule: ignore the Latest
    // Series panel). No count on the page (redesign) → keep everything
    // and let the guard above carry the day.
    val expectedCount = raw.substringAfter(":::COUNT:::", "")
        .substringBefore(":::").toIntOrNull() ?: -1
    val trimmed = if (expectedCount > 0 && novels.size > expectedCount) novels.take(expectedCount) else novels
    if (trimmed.size < novels.size) {
        DebugLog.log("SYNC", "trimmed ${novels.size - trimmed.size} panel rows (NU says $expectedCount)")
    }
    if (droppedScribblehub > 0) {
        DebugLog.log("SYNC", "dropped $droppedScribblehub Scribble Hub rows from list")
    }
    return ListPageResult(lists, trimmed, lastReads, pickReleasesFeedUrl(feedUrls), pickReadFeedUrl(feedUrls))
}

/** Host check for the SH filter: any *.scribblehub.com link. */
private fun isScribblehubUrl(url: String?): Boolean {
    val u = url.orEmpty()
    if (!u.startsWith("http")) return false
    val host = try {
        java.net.URI(u).host?.lowercase() ?: return false
    } catch (e: Exception) {
        return false
    }
    return host == "scribblehub.com" || host.endsWith(".scribblehub.com")
}

/** Strip the extra quoting evaluateJavascript adds around a JS string.
 *  Chromium JSON-encodes the result, escaping < > as \u003C \u003E and
 *  newlines/quotes/backslashes — all four must be decoded or HTML payloads
 *  arrive as literal "\u003Cul\u003E…" and never parse. */
fun unwrapJsResult(result: String?): String {
    if (result.isNullOrBlank() || result == "null") return ""
    return result
        .removeSurrounding("\"")
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}

/** True for challenge / turnstile URLs. Not a bypass — we still show the check. */
fun isCloudflareUrl(url: String?): Boolean {
    val u = url.orEmpty().lowercase()
    return u.contains("challenges.cloudflare.com") ||
        u.contains("cf-browser-verification") ||
        (u.contains("/cdn-cgi/") && (u.contains("challenge") || u.contains("turnstile")))
}

/**
 * True when a scrape landed on NU's login page — the session cookie expired.
 * Callers must stop scraping (logged-out pages burn the request budget and
 * risk tripping Cloudflare) and send the user to log in.
 */
fun isLoginRedirect(url: String?): Boolean {
    val u = url.orEmpty()
    return u.contains("wp-login.php", ignoreCase = true) ||
        (u.contains("novelupdates.com", ignoreCase = true) && u.contains("/login", ignoreCase = true))
}

fun isCloudflareTitle(title: String?): Boolean {
    val t = title.orEmpty()
    return t.contains("just a moment", ignoreCase = true) ||
        t.contains("attention required", ignoreCase = true) ||
        t.contains("verify you are human", ignoreCase = true)
}

/**
 * Drop the "; wv" WebView token. Cloudflare often hangs a wv UA instead of
 * showing the checkbox. This is still a WebView — the user must complete the check.
 */
fun chromeMobileUserAgent(current: String): String {
    val trimmed = current.replace("; wv", "").replace(";wv", "")
    return trimmed.ifBlank {
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}

/** JS snippet: return 'CF' if this document is a Cloudflare interstitial. → ScrapeJs.kt */

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
            sid = json.optString("sid"),
            associatedNames = json.optString("associatedNames"),
            artist = json.optString("artist"),
            year = json.optString("year"),
            originalPublisher = json.optString("originalPublisher"),
            completelyTranslated = json.optString("completelyTranslated")
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
    return parseRssItems(xml).map { it.title }
}

/** One RSS item: its title and pubDate as epoch millis (0 when undated). */
data class RssItem(val title: String, val pubDateMillis: Long)

/**
 * Titles + pubDates from an RSS feed. Dates matter for read sync: after a
 * deliberate back-track, only marks made AFTER the correction may raise the
 * position again (see readFeedItemApplies).
 */
fun parseRssItems(xml: String): List<RssItem> {
    val items = mutableListOf<RssItem>()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(xml.reader())
    var insideItem = false
    var title = ""
    var date = 0L
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "item") {
                    insideItem = true
                    title = ""
                    date = 0L
                }
                if (insideItem && parser.name == "title") title = parser.nextText()
                if (insideItem && parser.name == "pubDate") {
                    date = parseRssDate(parser.nextText())
                }
            }
            XmlPullParser.END_TAG -> {
                if (parser.name == "item") {
                    if (title.isNotBlank()) items.add(RssItem(title, date))
                    insideItem = false
                }
            }
        }
        event = parser.next()
    }
    return items
}

/** One row of the site's live search (nd_ajaxsearchmain) response. */
data class SearchResult(
    val title: String,
    val seriesUrl: String,
    val coverUrl: String
)

/**
 * Parse the HTML returned by NU's live search: an unordered list of
 * li.search_li_results rows, each with an a.a_search (series link) containing
 * an img.search_profile_image and a title span whose `search_hl` mark
 * highlights the match (see fixture-search-response.html). Tolerant of the
 * trailing character the endpoint appends.
 */
fun parseSearchResults(html: String): List<SearchResult> {
    if (html.isBlank() || !html.contains("search_li_results")) return emptyList()
    val rowPattern = Regex(
        """<li[^>]*>\s*<a[^>]*class="a_search"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    return rowPattern.findAll(html).mapNotNull { match ->
        val seriesUrl = match.groupValues[1].trim()
        val inner = match.groupValues[2]
        val cover = Regex("""src="([^"]+)"""").find(inner)?.groupValues?.get(1).orEmpty()
        val title = inner
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (seriesUrl.contains("/series/") && title.isNotBlank()) {
            SearchResult(
                title = title,
                seriesUrl = seriesUrl,
                coverUrl = cover.let { if (it.startsWith("//")) "https:$it" else it }
            )
        } else null
    }.take(12).toList()
}

/** One page of series-finder results (the desktop full search). */
data class FinderPage(
    val results: List<SearchResult>,
    val maxPage: Int
)

/** Extracted chapter text for reader mode (chapterExtractJs). */
data class ChapterExtract(
    val title: String,
    val paragraphs: List<String>
)

/**
 * Parse the JSON from chapterExtractJs(). 'FAIL', bad JSON, or a suspiciously
 * short result → null: the caller falls back to the web view. Tolerant by
 * design — this parser meets whatever a translator host looked like the day
 * the WebView ran it.
 */
fun parseChapterExtract(raw: String): ChapterExtract? {
    val unwrapped = unwrapJsResult(raw).trim()
    if (unwrapped.isBlank() || unwrapped == "FAIL") return null
    return try {
        val json = org.json.JSONObject(unwrapped)
        val arr = json.optJSONArray("paragraphs") ?: return null
        val paragraphs = (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).trim().ifBlank { null }
        }
        if (paragraphs.size < 3) return null
        ChapterExtract(title = json.optString("title"), paragraphs = paragraphs)
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse the JSON from finderPageJs() (series-finder grid rows). Bad or empty
 * input → an empty page with maxPage 1; callers show a retry, never an
 * empty-handed "no results".
 */
fun parseFinderResults(json: String): FinderPage {
    if (json.isBlank() || json == "CF") return FinderPage(emptyList(), 1)
    return try {
        val obj = org.json.JSONObject(json)
        val arr = obj.optJSONArray("rows") ?: return FinderPage(emptyList(), 1)
        val rows = (0 until arr.length()).mapNotNull { i ->
            val row = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = row.optString("u")
            val title = row.optString("t").trim().replace(Regex("\\s+"), " ")
            if (url.contains("/series/") && title.isNotBlank()) {
                SearchResult(
                    title = title,
                    seriesUrl = url,
                    coverUrl = row.optString("i").let { if (it.startsWith("//")) "https:$it" else it }
                )
            } else null
        }
        FinderPage(rows, obj.optInt("maxPage", 1).coerceAtLeast(1))
    } catch (_: Exception) {
        FinderPage(emptyList(), 1)
    }
}

/** RFC-822 pubDate ("Fri, 09 Oct 2026 16:22:05 +0800") → epoch millis, or 0. */
fun parseRssDate(raw: String): Long {
    if (raw.isBlank()) return 0L
    val trimmed = raw.trim()
    // Strip the leading day name some feeds repeat ("Fri, Fri, 09 Oct …").
    val normalized = if (trimmed.count { it == ',' } > 1) trimmed.substringAfter(",") else trimmed
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm zzz"
    )
    for (format in formats) {
        try {
            return java.text.SimpleDateFormat(format, java.util.Locale.US)
                .parse(normalized.trim())?.time ?: 0L
        } catch (_: Exception) {
        }
    }
    return 0L
}
