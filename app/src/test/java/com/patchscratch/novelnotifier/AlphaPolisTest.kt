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
    fun bodyHtml_brsBecomeParagraphs() {
        val paras = parseAlpBodyHtml(fixture("fixture-alphapolis-body.html"))
        assertTrue("paragraphs: ${paras.size}", paras.size >= 5)
        // first paragraph from the live capture
        assertTrue(paras.first().contains("オギャ"))
        // no residual markup
        assertTrue(paras.none { it.contains('<') })
    }
}
