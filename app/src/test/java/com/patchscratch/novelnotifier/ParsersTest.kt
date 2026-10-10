package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for Parsers.kt. No Android framework, no network.
 * These functions decide "is this chapter new?", so their edge cases are the
 * app's most important invariants: -1 means unknown, never zero.
 */
class ParsersTest {

    // --- chapterNum ---

    @Test
    fun chapterNum_prefersChapterOverVolume() {
        assertEquals(34, chapterNum("v12c34"))
    }

    @Test
    fun chapterNum_volumeChapterWords() {
        assertEquals(12, chapterNum("Volume 2 Chapter 12"))
    }

    @Test
    fun chapterNum_abbreviations() {
        assertEquals(7, chapterNum("Vol. 2 Ch. 7"))
        assertEquals(12, chapterNum("ch. 12"))
        assertEquals(45, chapterNum("c45"))
        assertEquals(99, chapterNum("Chapter 99"))
    }

    @Test
    fun chapterNum_episodes() {
        assertEquals(5, chapterNum("ep5"))
        assertEquals(12, chapterNum("Episode 12"))
    }

    @Test
    fun chapterNum_hashNumber() {
        assertEquals(7, chapterNum("#7"))
    }

    @Test
    fun chapterNum_volumeAloneIsUnknown() {
        assertEquals(-1, chapterNum("Rebirth v3"))
    }

    @Test
    fun chapterNum_bareNumberIsUnknown() {
        assertEquals(-1, chapterNum("45"))
    }

    @Test
    fun chapterNum_blankAndTextAreUnknown() {
        assertEquals(-1, chapterNum(""))
        assertEquals(-1, chapterNum("No numbers here"))
    }

    @Test
    fun chapterNum_decimalTruncates() {
        // Documented behavior: the regex captures the integer part only.
        assertEquals(12, chapterNum("c12.5"))
    }

    // --- unreadCount ---

    @Test
    fun unreadCount_simpleDifference() {
        assertEquals(6, unreadCount("c10", "c4"))
    }

    @Test
    fun unreadCount_unknownLastReadIsZero() {
        assertEquals(0, unreadCount("c10", null))
        assertEquals(0, unreadCount("c10", "no number"))
    }

    @Test
    fun unreadCount_unknownLatestIsZero() {
        assertEquals(0, unreadCount("nothing", "c4"))
    }

    @Test
    fun unreadCount_neNegative() {
        assertEquals(0, unreadCount("c4", "c10"))
    }

    // --- highestReadRelease ---

    @Test
    fun highestReadRelease_picksHighestNumberedReadRow() {
        val releases = listOf(
            Release("c3", "g", "d", "u", rid = "3", read = true),
            Release("c9", "g", "d", "u", rid = "9", read = false),
            Release("c7", "g", "d", "u", rid = "7", read = true)
        )
        assertEquals("c7", highestReadRelease(releases)?.chapter)
    }

    @Test
    fun highestReadRelease_noneReadIsNull() {
        val releases = listOf(Release("c9", "g", "d", "u", rid = "9", read = false))
        assertNull(highestReadRelease(releases))
    }

    @Test
    fun highestReadRelease_unparseableReadIsIgnored() {
        val releases = listOf(Release("Side story", "g", "d", "u", rid = "1", read = true))
        assertNull(highestReadRelease(releases))
    }

    // --- sortedReleases ---

    @Test
    fun sortedReleases_newestFirstPutsUnparseableLast() {
        val releases = listOf(
            Release("c10", "g", "d", "u"),
            Release("junk", "g", "d", "u"),
            Release("c2", "g", "d", "u")
        )
        assertEquals(
            listOf("c10", "c2", "junk"),
            sortedReleases(releases, newestFirst = true).map { it.chapter }
        )
    }

    @Test
    fun sortedReleases_oldestFirstPutsUnparseableLast() {
        val releases = listOf(
            Release("c10", "g", "d", "u"),
            Release("junk", "g", "d", "u"),
            Release("c2", "g", "d", "u")
        )
        assertEquals(
            listOf("c2", "c10", "junk"),
            sortedReleases(releases, newestFirst = false).map { it.chapter }
        )
    }

    // --- misc helpers ---

    @Test
    fun cacheHasAllPages_matrix() {
        assertTrue_(cacheHasAllPages(maxPage = 1, loadedPage = 1, releaseCount = 5))
        assertFalse_(cacheHasAllPages(maxPage = 3, loadedPage = 2, releaseCount = 10))
        assertTrue_(cacheHasAllPages(maxPage = 3, loadedPage = 3, releaseCount = 10))
        assertFalse_(cacheHasAllPages(maxPage = 5, loadedPage = 2, releaseCount = 0))
    }

    @Test
    fun isRealListName_filtersJunk() {
        assertEquals(true, isRealListName("Reading List"))
        assertEquals(false, isRealListName("Select a list"))
        assertEquals(false, isRealListName("Choose list"))
        assertEquals(false, isRealListName("---"))
        assertEquals(false, isRealListName("--"))
        assertEquals(false, isRealListName("  "))
    }

    @Test
    fun prettyRating_extractsScore() {
        assertEquals("4.6", prettyRating("4.6 (3,400 votes)"))
        assertEquals("no digits", prettyRating("no digits"))
    }

    @Test
    fun isLoginRedirect_detectsNuLoginPage() {
        assertEquals(true, isLoginRedirect("https://www.novelupdates.com/login/?redirect_to=x"))
        assertEquals(true, isLoginRedirect("https://www.novelupdates.com/wp-login.php?action=login"))
        assertEquals(false, isLoginRedirect("https://www.novelupdates.com/reading-list/?list=0"))
        assertEquals(false, isLoginRedirect(null))
        assertEquals(false, isLoginRedirect(""))
    }

    @Test
    fun parseSearchResults_fromLiveFixture() {
        val fixture = javaClass.getResourceAsStream("/fixture-search-response.html")!!
            .bufferedReader().readText()
        val results = parseSearchResults(fixture)
        assertEquals(4, results.size)
        assertEquals("alchemy Master", results[0].title)
        assertEquals("https://www.novelupdates.com/series/alchemy-master/", results[0].seriesUrl)
        assertEquals("https://cdn.novelupdates.com/img/series_85144.jpg", results[0].coverUrl)
        // Match highlighting is stripped; protocol-relative covers are fixed;
        // non-search HTML yields nothing.
        assertEquals("Suddenly Learned alchemy", results[2].title)
        assertEquals(emptyList<SearchResult>(), parseSearchResults("<html><body>nothing</body></html>"))
    }

    @Test
    fun parseFinderResults_fromFinderJson() {
        val json = """
            {"rows":[
              {"t":"Harem Master","u":"https://www.novelupdates.com/series/harem-master/","i":""},
              {"t":"  Spaced  Title  ","u":"https://www.novelupdates.com/series/spaced/","i":"//cdn.novelupdates.com/img/series_1.jpg"},
              {"t":"junk","u":"https://example.com/not-series/","i":""}
            ],"maxPage":42}
        """.trimIndent()
        val page = parseFinderResults(json)
        assertEquals(2, page.results.size)
        assertEquals(42, page.maxPage)
        assertEquals("Spaced Title", page.results[1].title)
        assertEquals("https://cdn.novelupdates.com/img/series_1.jpg", page.results[1].coverUrl)
        // Broken input never crashes and never lies about pages.
        assertEquals(1, parseFinderResults("CF").maxPage)
        assertEquals(0, parseFinderResults("not json").results.size)
    }

    @Test
    fun placeholderCovers_areNeverUsable() {
        assertEquals(true, isPlaceholderCover("https://www.novelupdates.com/img/noimagefound.jpg"))
        assertEquals(false, isPlaceholderCover("https://cdn.novelupdates.com/images/a.jpg"))
        assertEquals("", usableCoverUrl(null))
        assertEquals("", usableCoverUrl(""))
        assertEquals("", usableCoverUrl("https://www.novelupdates.com/img/noimagefound.jpg"))
        assertEquals(
            "https://cdn.novelupdates.com/images/a.jpg",
            usableCoverUrl("https://cdn.novelupdates.com/images/a.jpg")
        )
    }

    @Test
    fun unwrapJsResult_stripsQuoting() {
        assertEquals("", unwrapJsResult(null))
        assertEquals("", unwrapJsResult("null"))
        assertEquals("quoted", unwrapJsResult("\"quoted\""))
        assertEquals("line1\nline2", unwrapJsResult("\"line1\\nline2\""))
        assertEquals("a\"b", unwrapJsResult("\"a\\\"b\""))
    }

    @Test
    fun unwrapJsResult_decodesUnicodeEscapes() {
        // Chromium escapes < and > in evaluateJavascript results — an HTML
        // payload arrives as \u003Cul\u003E… and must come back as real tags
        // (this exact case broke live search while the fixture test passed).
        assertEquals("<ul>", unwrapJsResult("\"\\u003Cul\\u003E\""))
        assertEquals("harem「1」", unwrapJsResult("\"harem\\u300C1\\u300D\""))
        // …and a whole search response round-trips into the parser.
        val fixture = javaClass.getResourceAsStream("/fixture-search-response.html")!!
            .bufferedReader().readText()
        val jsQuoted = "\"" + fixture.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        assertEquals(4, parseSearchResults(unwrapJsResult(jsQuoted)).size)
    }

    // --- RSS ---

    @Test
    fun novelFromRssTitle_splitsTitleAndChapter() {
        val novel = novelFromRssTitle("The Book c186")
        assertEquals("The Book", novel.title)
        assertEquals("c186", novel.latestChapter)
    }

    @Test
    fun novelFromRssTitle_withoutChapter() {
        val novel = novelFromRssTitle("Just A Title")
        assertEquals("Just A Title", novel.title)
        assertEquals("New release", novel.latestChapter)
    }

    @Test
    fun parseRssTitles_onlyItemTitles() {
        val xml = """
            <?xml version="1.0"?>
            <rss><channel>
              <title>Feed header (must not appear)</title>
              <item><title>Alpha c1</title><pubDate>Fri, 09 Oct 2026 16:22:05 +0800</pubDate></item>
              <item><title>Beta c2</title></item>
            </channel></rss>
        """.trimIndent()
        assertEquals(listOf("Alpha c1", "Beta c2"), parseRssTitles(xml))
        val items = parseRssItems(xml)
        assertEquals(2, items.size)
        assertEquals("Alpha c1", items[0].title)
        assertTrue_(items[0].pubDateMillis > 0L)
        // Undated items carry 0 — callers treat them conservatively.
        assertEquals(0L, items[1].pubDateMillis)
    }

    @Test
    fun parseRssDate_handlesRfc822() {
        assertTrue_(parseRssDate("Fri, 09 Oct 2026 16:22:05 +0800") > 0L)
        assertTrue_(parseRssDate("09 Oct 2026 16:22:05 +0800") > 0L)
        assertEquals(0L, parseRssDate(""))
        assertEquals(0L, parseRssDate("not a date"))
    }

    @Test
    fun parseRssTitles_emptyFeed() {
        assertEquals(emptyList<String>(), parseRssTitles("<rss><channel></channel></rss>"))
    }

    // --- parseSeriesPage ---

    @Test
    fun parseSeriesPage_fullPayload() {
        val json = """
            {
              "title": "T", "author": "A", "rating": "4.5", "description": "D",
              "genres": "G", "tags": "T1", "type": "Web Novel", "status": "Ongoing",
              "cover": "http://x/c.jpg", "maxPage": 3, "currentList": "Reading", "sid": "99",
              "releases": [
                {"chapter": "c1", "group": "g", "date": "2020", "url": "http://u", "rid": "5", "read": true}
              ],
              "lists": [
                {"id": "1", "name": "Reading"},
                {"id": "", "name": "---"},
                {"id": "2", "name": "Choose list"}
              ]
            }
        """.trimIndent()
        val page = parseSeriesPage(json)
        assertEquals("T", page.title)
        assertEquals(3, page.maxPage)
        assertEquals("Reading", page.currentList)
        assertEquals("99", page.sid)
        assertEquals(1, page.lists.size)
        assertEquals("Reading", page.lists[0].name)
        assertEquals(1, page.releases.size)
        assertEquals(true, page.releases[0].read)
        assertEquals("5", page.releases[0].rid)
    }

    @Test
    fun parseSeriesPage_junkCurrentListBecomesBlank() {
        val page = parseSeriesPage("""{"title":"T","currentList":"Select a list"}""")
        assertEquals("", page.currentList)
    }

    @Test
    fun parseSeriesPage_badJsonIsEmpty() {
        val page = parseSeriesPage("not json at all")
        assertEquals("", page.title)
        assertEquals(0, page.releases.size)
    }

    // JUnit's assertTrue/assertFalse with underscores to keep asserts uniform above.
    private fun assertTrue_(v: Boolean) = org.junit.Assert.assertTrue(v)
    private fun assertFalse_(v: Boolean) = org.junit.Assert.assertFalse(v)
}
