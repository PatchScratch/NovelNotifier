package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AlphaPolis parsers against live-captured fixtures of the 2026 site
 * renewal (2026-10-17 recon; the work page is 異世界転生！ハイエイからの…
 * — 8 chapters / 252 episodes). Logic ported from the
 * novel_downloader-personal branch (alp_*.py) and re-verified live:
 * fully headless after login (cookie-jar HttpURLConnection), episode
 * bodies via POST /novel/episode_body with the page-issued CSRF +
 * per-episode token.
 */
class AlphaPolisTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().readText()

    @Test
    fun workPage_parsesTitleAuthorGenreDetailAndToc() {
        val d = parseAlpWorkPage(fixture("fixture-alphapolis-work.html"))!!
        val w = d.work
        assertEquals("異世界転生！ハイハイからの倍人生", w.title)
        assertTrue("author", w.author.contains("カムイイムカ"))
        assertEquals("243223524/173169133", w.id)
        assertEquals("https://www.alphapolis.co.jp/novel/243223524/173169133", w.url)
        assertEquals("ファンタジー", w.genre)
        // detail block: 初回完結日時 present → completed; 文字数 > 0
        assertTrue("completed", w.isCompleted)
        assertTrue("charCount ${w.charCount}", w.charCount > 100_000)
        assertTrue("published", w.publishedAt.startsWith("20"))
        assertTrue("updated", w.lastUpdate.startsWith("20"))
        // ToC: 8 chapters, 252 episodes, ordered, public-only count
        assertEquals(252, d.episodes.size)
        assertEquals(8, d.episodes.map { it.chapterTitle }.distinct().size)
        assertTrue(d.episodes.all { it.id.toLong() > 0 })
        assertEquals(252, w.episodeCount)   // all public in this fixture
        assertTrue(d.episodes[0].url.endsWith("/episode/943816"))
        // chapter heading carried (第一章 etc.)
        assertTrue(d.episodes.first().chapterTitle.isNotBlank())
        // mainTitle + subTitle join
        assertTrue(d.episodes.first().title.isNotBlank())
    }

    @Test
    fun workPage_synopsisKeepsParagraphs() {
        val d = parseAlpWorkPage(fixture("fixture-alphapolis-work.html"))!!
        assertTrue("intro non-trivial: ${d.work.introduction.length}", d.work.introduction.length > 40)
    }

    @Test
    fun workPage_rejectsNonWorkHtml() {
        assertNull(parseAlpWorkPage("<html><body>404</body></html>"))
    }

    @Test
    fun episodePage_extractsTitleAndPostTokens() {
        val (title, tokens) = parseAlpEpisodePage(fixture("fixture-alphapolis-episode.html"))
        assertTrue("title: $title", title.isNotBlank())
        assertNotNull("tokens (AJAX shape is authoritative post-2026)", tokens)
        val t = tokens!!
        assertEquals("943816", t.episodeId)
        assertEquals(32, t.episodeToken.length)   // md5-ish per-episode token
        assertTrue(t.csrfToken.length >= 16)
    }

    @Test
    fun ranking_parsesOrderedRowsWithCoverAndAuthor() {
        val rows = parseAlpRanking(fixture("fixture-alphapolis-ranking.html"))
        assertEquals(100, rows.size)
        assertEquals("世界十傑は全員俺の教え子でした ～最悪の砦に左遷された元奴隷ですが、世界最強の十人が集まってきたので建国することになりました～", rows[0].title)
        assertEquals("鷹の木", rows[0].author)
        assertEquals("195605754/55093625", rows[0].id)
        assertTrue(rows[0].coverUrl.startsWith("https://cdn-image"))
        assertTrue(rows[0].introduction.isNotBlank())
        // ranks arrive in order despite the DOM sequence
        assertEquals("629735639/79092895", rows[1].id)
    }

    @Test
    fun search_parsesRowsWithoutRank() {
        val rows = parseAlpSearch(fixture("fixture-alphapolis-search.html"))
        assertEquals(20, rows.size)
        assertEquals("花嫁の代わりはいくらでもいるそうなので、あなたの結婚式には出席しません", rows[0].title)
        assertEquals("サザンカ", rows[0].author)
        assertEquals("123768033/255094017", rows[0].id)
    }

    @Test
    fun bookshelf_parsesNestedPaginatorItems() {
        // The LIVE logged-in shape (recon 2026-10-17): a paginator object
        // deep inside an embedded JSON blob.
        val html = """xxx<script type="application/json">{"q":1,"paginator":{"total":2,
            "items":[{"content":{"citi_cont_id":55093625,"title":"世界十傑","url":"/novel/195605754/55093625","updated":"2026.10.17"}},
            {"content":{"citi_cont_id":524115920,"title":"二作目","url":"/novel/310314309/524115920","updated":"2026.10.01"}}]}}</script>"""
        val rows = parseAlpBookshelf(html)
        assertEquals(listOf("195605754/55093625", "310314309/524115920"), rows.map { it.id })
        assertEquals("世界十傑", rows[0].title)
        assertEquals("2026-10-17", rows[0].lastUpdate)
    }

    @Test
    fun bodyHtml_brsBecomeParagraphs() {
        val paras = parseAlpBodyHtml(fixture("fixture-alphapolis-body.html"))
        assertTrue("paragraphs: ${paras.size}", paras.size >= 5)
        // first paragraph from the live capture
        assertTrue(paras.first().contains("オギャ"))
        // no residual markup
        assertTrue(paras.none { it.contains('<') })
    }

    // --- illustration URL choice + embedded-body balance (2026-10-18) -----

    @Test
    fun illustration_prefersOpenAnchorUrlOverBlockedPcSrc() {
        // real AJAX shape: anchor = story_image (open, 200), src = /pc/ (403)
        val html = """<div class="story-image p-novel-episode__story-image">""" +
            """<a href="https://cdn-image.alphapolis.co.jp/story_image/99153/5acad5ac-bd60-4854-bdd6-17dbac113c05.jpeg" target="_blank">""" +
            """<img src="https://cdn-image.alphapolis.co.jp/story_images/99153/pc/5acad5ac-bd60-4854-bdd6-17dbac113c05.jpeg" alt=""></a></div>""" +
            """<br /> <br /> 　一章<br /> 　今日の天気は…<br />"""
        val (paras, imgs) = parseAlpBodyWithImages(html)
        assertTrue(imgs.size == 1)
        assertTrue(
            "harvests the OPEN story_image url, not the blocked /pc/ variant",
            imgs[0].second == "https://cdn-image.alphapolis.co.jp/story_image/99153/5acad5ac-bd60-4854-bdd6-17dbac113c05.jpeg"
        )
        assertTrue(paras.isNotEmpty())
    }

    @Test
    fun embeddedBody_survivesIllustrationDivAndKeepsProseAfterIt() {
        // Wayne's saved 1-1 page shape: nested story-image div inside
        // #novelBody, prose after it — the old lazy (.*?)</div> truncated
        // at the image's own closing tag.
        val html = """<html><body><div id="TextBody"><div class="p-novel-episode__page-count">1 / 64</div>""" +
            """<div class="p-novel-episode__text js-novel-body" id="novelBody">""" +
            """<div class="story-image p-novel-episode__story-image">""" +
            """<a href="https://cdn-image.alphapolis.co.jp/story_image/99153/x.jpeg" target="_blank">""" +
            """<img src="https://cdn-image.alphapolis.co.jp/story_images/99153/pc/x.jpeg" alt=""></a></div><br>""" +
            """　一章<br><br>　今日の天気は、曇り模様だ。<br><br>　二段落目。<br><br>　三段落目。""" +
            """</div></div><div class="c-like unauthenticated" id="likeArea"></div></body></html>"""
        val body = alpEmbeddedBody(html)
        assertTrue("keeps prose after the illustration", body.contains("三段落目。"))
        assertTrue("keeps the image markup", body.contains("story-image"))
        val (paras, imgs) = parseAlpBodyWithImages(body)
        assertTrue("3 paragraphs survive", paras.size >= 3)
        assertTrue(imgs.size == 1)
        assertTrue(imgs[0].second.endsWith("/story_image/99153/x.jpeg"))
    }

    @Test
    fun alpEpisodeLabel_volumeTitlesMapToVolumeKeys() {
        // Wayne's rule: "1-1" = Volume 1 Chapter 1, "2-1" = Volume 2 Ch 1
        assertEquals("v1c1", alpEpisodeLabel("1-1", 2))
        assertEquals("v2c1", alpEpisodeLabel("2-1", 17))
        assertEquals("v8c16", alpEpisodeLabel("8-16", 129))
        // flat 第N話 series (the other bookmark shape): the 話 number is
        // the label, not the ToC ordinal
        assertEquals("c1", alpEpisodeLabel("第1話　最悪の砦と、最強の教え子", 1))
        assertEquals("c30", alpEpisodeLabel("第30話　豊穣の女神、世界の食糧事情を変える", 31))
        // non-numbered titles keep the ToC ordinal
        assertEquals("c1", alpEpisodeLabel("書籍化記念", 1))
        assertEquals("c40", alpEpisodeLabel("おまけ", 40))
        // ordering + bucket split follow the volume key
        val ordered = EpubStore.orderDocs(listOf(
            EpubStore.ChapterDoc("https://h/a", "v2c1", "", listOf("p")),
            EpubStore.ChapterDoc("https://h/b", "v1c16", "", listOf("p")),
            EpubStore.ChapterDoc("https://h/c", "v1c1", "", listOf("p")),
            EpubStore.ChapterDoc("https://h/d", "c1", "", listOf("p"))
        ))
        assertEquals(listOf("c1", "v1c1", "v1c16", "v2c1"), ordered.map { it.label })
        assertEquals("v2", EpubStore.chapterBucket("v2c1"))
        assertEquals("v1", EpubStore.chapterBucket("v1c16"))
    }
}
