package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
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


// --- reader-mode extraction (parseChapterExtract) ---

@Test
fun chapterExtract_parsesTitleAndParagraphs() {
    val raw = "\"{\\\"title\\\":\\\"Chapter 500\\\",\\\"paragraphs\\\":[\\\"One.\\\",\\\"Two.\\\",\\\"Three.\\\"]}\""
    val extract = parseChapterExtract(raw)
    assertEquals("Chapter 500", extract?.title)
    assertEquals(listOf("One.", "Two.", "Three."), extract?.paragraphs)
}

@Test
fun chapterExtract_failReturnsNull() {
    assertNull(parseChapterExtract("\"FAIL\""))
}

@Test
fun chapterExtract_badJsonReturnsNull() {
    assertNull(parseChapterExtract("\"{not json\""))
}

@Test
fun chapterExtract_tooFewParagraphsReturnsNull() {
    val raw = "\"{\\\"title\\\":\\\"x\\\",\\\"paragraphs\\\":[\\\"One.\\\",\\\"Two.\\\"]}\""
    assertNull(parseChapterExtract(raw))
}

    // --- ToC-first bulk (3.3.1): chapter keys + harvest parsing ---

    @Test
    fun chapterKey_parsesVolumeAndChapter() {
        assertEquals(0 to 673, chapterKey("c673"))
        assertEquals(2 to 220, chapterKey("v2c220"))
        assertEquals(0 to 673, chapterKey("Chapter 673: The Arrow of Judgment"))
        assertEquals(1 to 12, chapterKey("Vol. 1 Ch. 12 - Somewhere"))
        assertEquals(0 to 45, chapterKey("Episode 45"))
        assertEquals(null, chapterKey("no numbers here"))
        assertEquals(null, chapterKey(""))
        // bare numbered labels (custom reader platforms)
        assertEquals(0 to 1, chapterKey("001- First Battle"))
        assertEquals(0 to 12, chapterKey("012 – Shopping with Mimi (Part 1)"))
        assertEquals(0 to 595, chapterKey("595 「Is It Really That Simple…」"))
        assertEquals(0 to 7, chapterKey("7. The Escape"))
        // bare numbers WITHOUT separator+title stay unknown (nav junk like "0")
        assertEquals(null, chapterKey("0"))
        assertEquals(null, chapterKey("12"))
    }

    @Test
    fun chapterKey_volumeZeroDoesNotMatchVolumeChapters() {
        // the conservative-match rule: plain c673 must not claim v2c673
        assertEquals(2 to 673, chapterKey("v2c673"))
        assertNotEquals(chapterKey("c673"), chapterKey("v2c673"))
    }

    @Test
    fun parseTocHarvest_roundTripsAndFilters() {
        val raw = """"[{\"u\":\"https://host/chapter-673\",\"t\":\"Chapter 673\"},{\"u\":\"https://host/chapter-672\",\"t\":\"c672\"},{\"u\":\"javascript:void(0)\",\"t\":\"c1\"},{\"u\":\"https://host/next\",\"t\":\"»\"}]""""
        val links = parseTocHarvest(raw)
        // non-http dropped at parse; nav text (») has no chapter key
        assertEquals(3, links.size)
        assertEquals(0 to 673, links[0].key)
        assertEquals(0 to 672, links[1].key)
        assertEquals(null, links[2].key)
    }

    @Test
    fun parseTocHarvest_badInputIsEmpty() {
        assertEquals(0, parseTocHarvest("\"not json\"").size)
        assertEquals(0, parseTocHarvest("").size)
    }

    // --- Syosetu provider (slice 1) ---

    @Test
    fun syosetuApi_parsesRowsAndSkipsAllcount() {
        val json = """[
            {"allcount": 2},
            {"title": "無職転生", "ncode": "N9669BK", "writer": "理不尽な孫の手", "genre": 201,
             "keyword": "R15 異世界転生", "end": 1, "noveltype": 1, "general_all_no": 286,
             "general_lastup": "2015-04-03 23:00:00", "yearly_point": 84410},
            {"title": "プロローグのみ", "ncode": "N0001AA", "writer": "誰か", "genre": 101,
             "keyword": "", "end": 0, "noveltype": 2, "general_all_no": 1,
             "general_lastup": "2026-01-01 00:00:00", "daily_point": 5}
        ]"""
        val novels = parseSyosetuApi(json)
        assertEquals(2, novels.size)
        assertEquals("N9669BK", novels[0].ncode)
        assertTrue(novels[0].isCompleted)
        assertEquals(286, novels[0].episodeCount)
        assertEquals(84410, novels[0].points)
        assertTrue(novels[1].isShortStory)
        assertFalse(novels[1].isCompleted)
        // episode URL is deterministic from the ncode
        assertEquals("https://ncode.syosetu.com/n9669bk/", novels[0].url)
    }

    @Test
    fun syosetuToc_parsesLiveFixture() {
        val html = java.io.File(
            System.getProperty("user.dir") + "/src/test/resources/fixture-syosetu-toc.html"
        ).readText()
        val toc = parseSyosetuToc(html)
        assertEquals("Ｒｅ：ゼロから始める異世界生活", toc.title)
        assertEquals("鼠色猫/長月達平", toc.author)
        assertEquals(100, toc.episodes.size)   // page 1 of a 105-episode novel
        assertEquals(1, toc.episodes.first().number)
        assertEquals(100, toc.episodes.last().number)
        assertTrue(toc.episodes.first().title.contains("プロローグ"))
    }

    @Test
    fun syosetuToc_emptyHtmlIsHarmless() {
        val toc = parseSyosetuToc("<html><body>error</body></html>")
        assertEquals(0, toc.episodes.size)
        assertFalse(toc.hasMorePages)
    }

    @Test
    fun syosetuBookMeta_mapsFields() {
        val novel = SyosetuNovel(
            ncode = "N9669BK", title = "無職転生", writer = "理不尽な孫の手", story = "あらすじ",
            genreCode = 201, keywords = "R15 異世界転生", isCompleted = true, isShortStory = false,
            episodeCount = 286, lastUpdate = "2015-04-03 23:00:00", points = 0
        )
        val meta = novel.toBookMeta()
        assertEquals("無職転生", meta.title)
        assertEquals("理不尽な孫の手", meta.author)
        assertEquals(syosetuGenreNameNative(201), meta.genres)  // epub records the SITE term, toggle never leaks
        assertEquals("完結", meta.completelyTranslated)
        assertEquals("小説家になろう", meta.originalPublisher)
        assertEquals("2015", meta.year)
    }

    // --- Kakuyomu -----------------------------------------------------------

    /** A {data:…} searchWorks envelope shaped exactly like the live endpoint. */
    private val kakuSearchJson = """{"data":{"searchWorks":{"totalCount":16463,"nodes":[
        {"id":"1177354054891139802","title":"転移したら山の中だった。","catchphrase":"【書籍化】勇者には極力近づきません！",
         "genre":"FANTASY","serialStatus":"RUNNING","publicEpisodeCount":763,"totalReviewPoint":73819,
         "totalCharacterCount":1597523,"publishedAt":"2019-10-12T11:36:32Z","lastEpisodePublishedAt":"2026-10-11T04:16:59Z",
         "adminCoverImageUrl":"https://cdn-ak.f.st-hatena.com/images/fotolife/k/kadokawa-toko/20200603/20200603182952.jpg",
         "author":{"activityName":"じゃがバター"}},
        {"id":"16818093087479543721","title":"極悪貴族、謙虚堅実に無双する","catchphrase":"才能に恵まれ過ぎた極悪貴族が",
         "genre":"FANTASY","serialStatus":"COMPLETED","publicEpisodeCount":174,"totalReviewPoint":99246,
         "totalCharacterCount":831302,"publishedAt":"2024-10-27T03:04:11Z","lastEpisodePublishedAt":"2026-09-07T14:59:08Z",
         "adminCoverImageUrl":"","author":{"activityName":"誰か"}},
        {"id":"","title":"blank id row","genre":"FANTASY","author":{}}
        ]}}}"""

    @Test
    fun kakuyomuSearch_parsesRowsTotalAndSkipsBlankIds() {
        val res = parseKakuyomuSearchJson(kakuSearchJson)
        assertEquals(2, res.works.size)
        assertEquals(16463, res.totalCount)
        assertEquals("じゃがバター", res.works[0].author)
        assertEquals(763, res.works[0].episodeCount)
        assertFalse(res.works[0].isCompleted)
        assertTrue(res.works[1].isCompleted)
        assertEquals("https://kakuyomu.jp/works/1177354054891139802", res.works[0].url)
    }

    /** Work page __NEXT_DATA__ Apollo cache, minimal but structurally real:
     *  ROOT_QUERY names the work, tableOfContentsV2 chains through chapters
     *  (one WITH a heading, one without) to episode refs. */
    private fun kakuWorkPage(withToc: Boolean = true): String {
        val workExtra = if (withToc)
            """"tableOfContentsV2":[{"__ref":"TableOfContentsChapter:1"},{"__ref":"TableOfContentsChapter:2"}],"""
        else ""
        return """<html><body><script id="__NEXT_DATA__" type="application/json">{
          "props":{"pageProps":{"__APOLLO_STATE__":{
            "ROOT_QUERY":{
              "visitor":null,
              "work({\"id\":\"1177354054891139802\"})":{"__ref":"Work:1177354054891139802"}
            },
            "Work:1177354054891139802":{
              "__typename":"Work","id":"1177354054891139802",
              "title":"転移したら山の中だった。","catchphrase":"PRコピー","introduction":"あらすじ本文。",
              "genre":"FANTASY","serialStatus":"COMPLETED","publicEpisodeCount":4,
              "totalReviewPoint":73819,"totalCharacterCount":8000,
              "publishedAt":"2019-10-12T11:36:32Z","lastEpisodePublishedAt":"2020-01-02T03:04:05Z",
              "adminCoverImageUrl":"https://example.jp/cover.jpg",
              "tagLabels":["異世界転移","スローライフ"],
              "author":{"__ref":"UserAccount:1"},
              $workExtra
              "editedAt":"x"
            },
            "UserAccount:1":{"__typename":"UserAccount","id":"1","activityName":"じゃがバター"},
            "TableOfContentsChapter:1":{
              "__typename":"TableOfContentsChapter","id":"1",
              "chapter":{"__ref":"Chapter:9"},
              "episodeUnions":[{"__ref":"Episode:101"},{"__ref":"Episode:102"}]
            },
            "Chapter:9":{"__typename":"Chapter","id":"9","title":"第一章"},
            "TableOfContentsChapter:2":{
              "__typename":"TableOfContentsChapter","id":"2","chapter":null,
              "episodeUnions":[{"__ref":"Episode:103"},{"__ref":"Episode:104"}]
            },
            "Episode:101":{"__typename":"Episode","id":"101","title":"第1話　山の中","publishedAt":"2019-10-12T11:36:32.000Z"},
            "Episode:102":{"__typename":"Episode","id":"102","title":"第2話","publishedAt":"2019-10-13T11:36:32.000Z"},
            "Episode:103":{"__typename":"Episode","id":"103","title":"第3話","publishedAt":"2019-10-14T11:36:32.000Z"},
            "Episode:104":{"__typename":"Episode","id":"104","title":"第4話","publishedAt":"2019-10-15T11:36:32.000Z"}
          }}}
        }</script></body></html>"""
    }

    @Test
    fun kakuyomuWorkPage_parsesMetaTagsAndOrderedToc() {
        val d = parseKakuyomuWorkPage(kakuWorkPage())!!
        assertEquals("転移したら山の中だった。", d.work.title)
        assertEquals("じゃがバター", d.work.author)
        assertTrue(d.work.isCompleted)
        assertEquals("あらすじ本文。", d.work.introduction)
        assertEquals("異世界転移 スローライフ", d.work.tags)
        // ToC order follows tableOfContentsV2, not publishedAt or id sort
        assertEquals(listOf("101", "102", "103", "104"), d.episodes.map { it.id })
        assertEquals("第一章", d.episodes[0].chapterTitle)
        assertEquals("", d.episodes[2].chapterTitle)
        assertEquals("https://kakuyomu.jp/works/1177354054891139802/episodes/103", d.episodes[2].url(d.work.id))
    }

    @Test
    fun kakuyomuWorkPage_fallsBackToPublishOrderWithoutTocChain() {
        val d = parseKakuyomuWorkPage(kakuWorkPage(withToc = false))!!
        // fallback sorts by publishedAt — ids are chronological here
        assertEquals(listOf("101", "102", "103", "104"), d.episodes.map { it.id })
    }

    @Test
    fun kakuyomuWorkPage_rejectsPagesWithoutNextData() {
        assertNull(parseKakuyomuWorkPage("<html>404</html>"))
    }

    @Test
    fun kakuyomuEpisode_extractsTitleAndParagraphs() {
        val html = """<html><body>
          <header><p class="widget-episodeTitle js-vertical-composition-item">第1話　山の中</p></header>
          <div class="widget-episode js-episode-body-container"><div class="widget-episode-inner">
          <div class="widget-episodeBody js-episode-body" data-viewer-history-path="/x">
          <p id="p1"> 意味がわからない。</p>
          <p id="p2" class="blank"><br /></p>
          <p id="p3"> 振り返ったら見慣れない風景</p>
          </div></div></div>
          </body></html>"""
        val (title, paras) = parseKakuyomuEpisode(html)
        assertEquals("第1話　山の中", title)
        assertEquals(listOf("意味がわからない。", "振り返ったら見慣れない風景"), paras)
    }

    @Test
    fun kakuyomuBookMeta_mapsFieldsNatively() {
        val work = parseKakuyomuSearchJson(kakuSearchJson).works[0]
        val meta = work.toBookMeta()
        assertEquals("カクヨム", meta.originalPublisher)
        assertEquals("ファンタジー", meta.genres)      // site term, never the toggle
        assertEquals("じゃがバター", meta.author)
        assertEquals("2019", meta.year)
        assertEquals("連載中", meta.completelyTranslated)
    }

    @Test
    fun kakuyomuBookmarks_roundTripKeepsR18AndCoreFields() {
        val works = parseKakuyomuSearchJson(kakuSearchJson).works +
            parseKakuyomuSearchJson(kakuSearchJson).works[1].copy(isSexual = true)
        val restored = parseKakuyomuBookmarks(kakuyomuBookmarksJson(works))
        assertEquals(works.size, restored.size)
        assertEquals(works.map { it.id }, restored.map { it.id })
        assertTrue(restored.last().isSexual)
        assertFalse(restored.first().isSexual)
        assertEquals("じゃがバター", restored.first().author)
        assertEquals(763, restored.first().episodeCount)
    }

    @Test
    fun kakuyomuBookmarks_garbageIsHarmless() {
        assertTrue(parseKakuyomuBookmarks("not json").isEmpty())
        assertTrue(parseKakuyomuBookmarks("""[{"id":""}]""").isEmpty())
    }

    @Test
    fun syosetuBookmarks_strictRowsKeepSidebarJunkOut() {
        // Real row shape (verified live): anchor starts with the c-up-label
        // span. Sidebar/ranking links are plain-text ncode anchors and must
        // NOT become bookmarks (user report: long junk list vs 3 real).
        val html = """
            <div class="bookmark-list">
            <a href="https://ncode.syosetu.com/n2267be/"><span class="c-up-label c-up-label--novel-long">連載</span>&nbsp;Re:ゼロから始める異世界生活</a>
            <a href="https://ncode.syosetu.com/n9669bk/"><span class="c-up-label">短編</span>&nbsp;無職転生</a>
            </div>
            <div class="ranking-sidebar">
            <a href="https://ncode.syosetu.com/n1234ab/">ランキング作品A</a>
            <a href="https://ncode.syosetu.com/n5678cd/">ランキング作品B</a>
            </div>
        """.trimIndent()
        val rows = parseSyosetuBookmarks(html)
        assertEquals(listOf("N2267BE", "N9669BK"), rows.map { it.ncode })
        assertEquals("Re:ゼロから始める異世界生活", rows[0].title)
        assertEquals("無職転生", rows[1].title)
    }

    @Test
    fun syosetuBookmarks_broadFallbackWhenSignatureAbsent() {
        // A redesigned page without the label span still yields the plain
        // anchors (degrade, don't lose bookmarks).
        val html = """<a href="https://ncode.syosetu.com/n2267be/">Re:ゼロから始める異世界生活</a>"""
        val rows = parseSyosetuBookmarks(html)
        assertEquals(1, rows.size)
        assertEquals("N2267BE", rows[0].ncode)
    }
}
