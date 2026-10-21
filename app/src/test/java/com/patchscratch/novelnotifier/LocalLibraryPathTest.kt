package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * zipPath: epub href resolution against the chapter/OPF base — "../" and
 * "./" segments must normalize to the zip's real entry path or images and
 * covers silently vanish (Wayne, 2026-10-21: illustrations missing).
 */
class LocalLibraryPathTest {

    @Test
    fun `relative image in subfolder resolves up`() {
        assertEquals(
            "OEBPS/images/01.jpg",
            LocalLibraryStore.zipPath("OEBPS/text", "../images/01.jpg")
        )
    }

    @Test
    fun `dot-slash resolves clean`() {
        assertEquals(
            "OEBPS/images/01.jpg",
            LocalLibraryStore.zipPath("OEBPS", "./images/01.jpg")
        )
    }

    @Test
    fun `same-folder src keeps base`() {
        assertEquals(
            "OEBPS/text/illust01.png",
            LocalLibraryStore.zipPath("OEBPS/text", "illust01.png")
        )
    }

    @Test
    fun `blank base passes src through`() {
        assertEquals("images/01.jpg", LocalLibraryStore.zipPath("", "images/01.jpg"))
    }

    @Test
    fun `double dot-slash collapses`() {
        assertEquals(
            "images/a.jpg",
            LocalLibraryStore.zipPath("OEBPS/text", "../../images/a.jpg")
        )
    }

    @Test
    fun `query string kept by caller is not our business`() {
        // substringBefore("?") happens before zipPath — path parts only here.
        assertEquals("OEBPS/i.jpg", LocalLibraryStore.zipPath("OEBPS", "i.jpg"))
    }

    @Test
    fun `strippedTitle drops novel qualifiers after volume`() {
        assertEquals(
            "By the Grace of the Gods",
            LocalLibraryStore.strippedTitle("By the Grace of the Gods (Light Novel) Vol. 6")
        )
    }

    @Test
    fun `strippedTitle keys match with and without qualifier`() {
        val plain = LocalLibraryStore.seriesKey("By the Grace of the Gods")
        val qualified = LocalLibraryStore.seriesKey("By the Grace of the Gods (LN) Vol. 6")
        assertEquals(plain, qualified)
    }
}
