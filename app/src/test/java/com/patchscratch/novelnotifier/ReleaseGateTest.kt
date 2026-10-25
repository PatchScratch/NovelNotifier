package com.patchscratch.novelnotifier

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RELEASE GATE (Wayne, 2026-10-24: "you're giving me alpha code as release
 * ready code"): regression tests for every data-destroying bug shipped
 * during the 5.7K-library week. A change that turns any of these red
 * does not ship.
 *
 * Covered failures:
 *   1. The 21,638-deletion massacre — dupScan merging DIFFERENT series.
 *   2. The phone-backup-on-tablet erase — restore clobbering a stocked
 *      device's local-library index.
 *   3. The comma-named re-rip split — strippedTitle/seriesKey collisions.
 *   4. The bare-title volume collapse — seriesKey must not fuse volumes
 *      of one series into one key.
 */
class ReleaseGateTest {

    // ---- 1. dupScan never merges different series -----------------------

    @Test
    fun `dupScan does not pair different series sharing a title prefix`() {
        val root = indexWithBooks(
            book("b1", "L1", "Alpha Series", 10, vol = 1),
            book("b2", "L1", "Alpha Series Beta", 10, vol = 1),  // DIFFERENT series
            book("b3", "L1", "Alpha Series", 12, vol = 2)
        )
        val pairs = LocalLibraryStore.dupScanForTest(root)
        // b1/b3 are the same series (same stripped title, different vols)
        // — they are NOT duplicates of each other; b2 is its own series.
        // None of the three is a duplicate: distinct (title, vol) each.
        assertTrue("expected no duplicate pairs", pairs.isEmpty())
    }

    @Test
    fun `dupScan pairs the same volume linked twice`() {
        val root = indexWithBooks(
            book("b1", "L1", "Same Title Vol. 1", 10, vol = 1),
            book("b2", "L1", "Same Title Vol. 1", 10, vol = 1)  // identical (title, vol)
        )
        val pairs = LocalLibraryStore.dupScanForTest(root)
        assertEquals(1, pairs.size)
        assertEquals("b2", pairs.first().first)
    }

    // ---- 2. restore guard ------------------------------------------------

    @Test
    fun `restore skips local index when device holds more books`() {
        // The tablet had 5,772; the phone's backup carried 4.
        assertFalse(shouldApplyLocalIndex(currentBooks = 5772, incomingBooks = 4))
    }

    @Test
    fun `restore applies local index on an empty device`() {
        assertTrue(shouldApplyLocalIndex(currentBooks = 0, incomingBooks = 4))
    }

    @Test
    fun `restore applies when backup is a superset`() {
        assertTrue(shouldApplyLocalIndex(currentBooks = 4, incomingBooks = 5772))
    }

    // ---- 3. comma re-rips collapse to one key ---------------------------

    @Test
    fun `comma-named re-rips share one series key`() {
        assertEquals(
            LocalLibraryStore.seriesKey("Welcome to Japan, Ms. Elf! Vol. 2"),
            LocalLibraryStore.seriesKey("Welcome to Japan, Ms. Elf!, Vol. 2")
        )
    }

    // ---- 4. volumes of one series keep distinct keys ---------------------

    @Test
    fun `volumes of one series do not fuse`() {
        assertFalse(
            LocalLibraryStore.dupScanForTest(
                indexWithBooks(
                    book("b1", "L1", "Reborn as a Space Mercenary Vol. 1", 41, vol = 1),
                    book("b2", "L1", "Reborn as a Space Mercenary Vol. 2", 40, vol = 2)
                )
            ).isNotEmpty()
        )
    }

    @Test
    fun `same volume with different chapter splits still pairs`() {
        // Different rips split chapters differently — chapters must not
        // block the match when the volume identity is known.
        assertTrue(
            LocalLibraryStore.dupScanForTest(
                indexWithBooks(
                    book("b1", "L1", "Omnibus Vol. 3", 30, vol = 3),
                    book("b2", "L1", "Omnibus Vol. 3", 31, vol = 3)
                )
            ).isNotEmpty()
        )
    }

    // ---- helpers ----------------------------------------------------------

    private fun book(
        id: String, libraryId: String, title: String, chapters: Int, vol: Int
    ): JSONObject = JSONObject()
        .put("id", id).put("libraryId", libraryId).put("title", title)
        .put("chapters", chapters).put("storedUri", "content://x/$id")

    private fun indexWithBooks(vararg books: JSONObject): JSONObject =
        JSONObject().put("books", JSONArray().apply { books.forEach { put(it) } })

// ---- 5. NU landing-page gate (2026-10-24) -------------------------------

private val extnu = "https://www.novelupdates.com/extnu/20567431/"
    private val nuHome = "https://www.novelupdates.com/"

@Test
fun `NU landing page is detected for an extnu chapter`() {
    assertTrue(isNuLanding(extnu, nuHome))
}

@Test
fun `the extnu page itself is not a landing`() {
    assertFalse(isNuLanding(extnu, extnu))
}

@Test
fun `translator page is not a landing`() {
    assertFalse(
        isNuLanding(extnu, "https://www.shmtranslations.com/2020/12/06/chapter-2-gift/")
    )
}

@Test
fun `non-extnu chapters never gate`() {
    assertFalse(isNuLanding("https://example.com/c1", nuHome))
}

// ---- 6. rss-overview + bridge poisoning (2026-10-25, live-proven) ------

@Test
fun `foxteller rss overview is detected and never extracts`() {
    assertTrue(isRssOverview("https://www.foxteller.com/rss/37883"))
    assertFalse(isRssOverview("https://www.foxteller.com/novel/724/234/some-chapter"))
}

@Test
fun `bridge never records an rss overview as a permalink`() {
    assertFalse(ReaderTocStore.isValidPermalink("https://www.foxteller.com/rss/37883"))
    assertTrue(ReaderTocStore.isValidPermalink("https://www.foxteller.com/novel/724/234/chapter"))
    assertFalse(ReaderTocStore.isValidPermalink("not-a-url"))
}

@Test
fun `redirect stub on NU mid-chain is detected`() {
    assertTrue(isRedirectStub(extnu, nuHome))
    assertFalse(isRedirectStub(extnu, "https://stabbingwithasyringe.home.blog/2026/10/02/v12c6/"))
}
}