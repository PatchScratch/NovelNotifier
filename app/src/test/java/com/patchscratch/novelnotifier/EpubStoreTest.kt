package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Tests for the offline-ePub writer/reader (Phase 3.3). Rules under test:
 * EPUB structure rules (mimetype first + stored), round-trip fidelity
 * including XML-special characters, merge-by-URL, reading order, and
 * tolerance for corrupt/absent books.
 */
class EpubStoreTest {

    private fun tempBook(name: String): File =
        File.createTempFile("book", ".epub").apply { deleteOnExit() }

    private fun doc(url: String, label: String, title: String = "", vararg paragraphs: String) =
        EpubStore.ChapterDoc(url, label, title, paragraphs.toList())

    @Test
    fun writesValidEpubStructure() {
        val book = tempBook("struct")
        val n = EpubStore.writeBook(book, "Test Series", null, "image/jpeg", listOf(
            doc("https://h/1", "c1", "One", "a", "b", "c")
        ))
        assertEquals(1, n)
        ZipFile(book).use { zip ->
            val names = zip.entries().toList().map { it.name }
            // mimetype FIRST and STORED — the one hard EPUB rule
            assertEquals("mimetype first entry", "mimetype", names.first())
            assertEquals("application/epub+zip",
                zip.getInputStream(zip.getEntry("mimetype")).readBytes().decodeToString())
            assertEquals("mimetype stored", ZipEntry_METHOD_STORED, zip.getEntry("mimetype").method)
            assertTrue("META-INF/container.xml" in names)
            assertTrue("OEBPS/content.opf" in names)
            assertTrue("OEBPS/nav.xhtml" in names)
            assertTrue("OEBPS/toc.ncx" in names)
            assertTrue("OEBPS/chapters/c0001.xhtml" in names)
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
            assertTrue("dc:title" in opf && "Test Series" in opf)
            assertTrue("properties=\"nav\"" in opf)
        }
    }

    @Test
    fun roundTrip_preservesTextAndXmlCharacters() {
        val book = tempBook("roundtrip")
        val tricky = "Quotes \" apostrophe ' ampersand & angle <brackets> — em—dash"
        EpubStore.writeBook(book, "T", null, "image/jpeg", listOf(
            doc("https://h/1", "c1", "Chapter & One", tricky, "plain paragraph")
        ))
        val back = EpubStore.readChapterDoc(book, "https://h/1")
        assertNotNull(back)
        assertEquals(listOf(tricky, "plain paragraph"), back!!.paragraphs)
        assertEquals("Chapter & One", back.title)
    }

    @Test
    fun orderDocs_sortsByChapterNumber() {
        val ordered = EpubStore.orderDocs(listOf(
            doc("https://h/2", "c2", "Two", "p2"),
            doc("https://h/1", "c1", "One", "p1"),
            doc("https://h/3", "c3", "Three", "p3")
        ))
        assertEquals(listOf("c1", "c2", "c3"), ordered.map { it.label })
    }

    @Test
    fun orderDocs_volumeKeyBeatsBareChapterNumber() {
        // v2c1 reads AFTER v1c100 — chapter-number-only sorting put v2c1
        // first (the "chapters download out of order" report)
        val ordered = EpubStore.orderDocs(listOf(
            doc("https://h/a", "v2c1", "V2C1", "p"),
            doc("https://h/b", "v1c100", "V1C100", "p"),
            doc("https://h/c", "v1c2", "V1C2", "p"),
            doc("https://h/d", "v3c10", "V3C10", "p")
        ))
        assertEquals(listOf("v1c2", "v1c100", "v2c1", "v3c10"), ordered.map { it.label })
    }

    @Test
    fun merge_replacesSameUrlKeepsOrder() {
        val book = tempBook("merge")
        EpubStore.writeBook(book, "T", null, "image/jpeg", EpubStore.orderDocs(listOf(
            doc("https://h/2", "c2", "Two", "p2"),
            doc("https://h/1", "c1", "One", "p1"),
            doc("https://h/3", "c3", "Three", "p3")
        )))
        val index = EpubStore.readBookIndex(book)
        assertEquals(listOf("c1", "c2", "c3"), index.map { it.label })
        // replace c2's content by re-adding its URL
        EpubStore.writeBook(book, "T", null, "image/jpeg", listOf(
            doc("https://h/1", "c1", "One", "p1"),
            doc("https://h/2", "c2", "Two REVISED", "p2-new"),
            doc("https://h/3", "c3", "Three", "p3")
        ))
        val updated = EpubStore.readChapterDoc(book, "https://h/2")!!
        assertEquals("Two REVISED", updated.title)
        assertEquals(listOf("p2-new"), updated.paragraphs)
        assertEquals(3, EpubStore.readBookIndex(book).size)
    }

    @Test
    fun unparseableLabelsSortLastButKept() {
        val ordered = EpubStore.orderDocs(listOf(
            doc("https://h/x", "v2 part7", "Extra", "px"),
            doc("https://h/1", "c1", "One", "p1"),
            doc("https://h/2", "c2", "Two", "p2")
        ))
        assertEquals(listOf("c1", "c2", "v2 part7"), ordered.map { it.label })
    }

    @Test
    fun missingBookAndCorruptZipAreEmpty() {
        val missing = File("/tmp/definitely-not-here-xyz.epub")
        assertEquals(0, EpubStore.readBookIndex(missing).size)
        val corrupt = tempBook("corrupt").apply { writeText("this is not a zip") }
        assertEquals(0, EpubStore.readBookIndex(corrupt).size)
        assertEquals(null, EpubStore.readChapterDoc(corrupt, "https://h/1"))
    }

    @Test
    fun coverEmbedsWhenProvided() {
        val book = tempBook("cover")
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        EpubStore.writeBook(book, "T", fakeJpeg, "image/jpeg", listOf(doc("https://h/1", "c1", "One", "p")))
        ZipFile(book).use { zip ->
            assertNotNull(zip.getEntry("OEBPS/cover.jpg"))
            assertNotNull(zip.getEntry("OEBPS/cover.xhtml"))
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
            assertTrue("cover-img" in opf)
        }
    }

    private val ZipEntry_METHOD_STORED = java.util.zip.ZipEntry.STORED

    // --- title page + metadata (3.3, user request 2026-10-11) ---

    private val meta = EpubStore.BookMeta(
        title = "Kuro no Maou",
        description = "A dark fantasy about a boy <reborn> with black magic & a gun.",
        associatedNames = "Black Demon Lord, 黒の魔王",
        type = "Web Novel",
        genres = "Action, Fantasy",
        tags = "Anti-Hero, Demons",
        author = "Hishi Kage Dairi",
        artist = "Miki",
        year = "2014",
        completelyTranslated = "No",
        originalPublisher = "Shousetsuka ni Narou"
    )

    @Test
    fun titlePageWrittenWithMeta() {
        val book = tempBook("meta")
        EpubStore.writeBook(book, "T", null, "image/jpeg", listOf(doc("https://h/1", "c1", "One", "p")), meta)
        ZipFile(book).use { zip ->
            val tp = zip.getInputStream(zip.getEntry("OEBPS/titlepage.xhtml")).readBytes().decodeToString()
            assertTrue("title" in tp && "Kuro no Maou" in tp)
            assertTrue("Associated Names" in tp && "Black Demon Lord" in tp)
            assertTrue("Original Publisher" in tp && "Shousetsuka ni Narou" in tp)
            assertTrue("Completely Translated" in tp && "No" in tp)
            assertTrue("Year" in tp && "2014" in tp)
            assertTrue("Artist" in tp && "Miki" in tp)
            // XML escaping on the hostile description
            assertTrue("&amp;" in tp && "&lt;reborn&gt;" in tp)
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
            assertTrue("<dc:creator>Hishi Kage Dairi</dc:creator>" in opf)
            assertTrue("<dc:publisher>Shousetsuka ni Narou</dc:publisher>" in opf)
            assertTrue("<dc:subject>Anti-Hero</dc:subject>" in opf)
            // spine order: cover absent here, titlepage BEFORE chapters
            val spine = Regex("""<itemref idref="([\w-]+)"""").findAll(opf).map { it.groupValues[1] }.toList()
            assertTrue(spine.indexOf("titlepage") < spine.indexOf("c0001"))
        }
    }

    @Test
    fun bookMetaRoundTripsThroughMetaJson() {
        val book = tempBook("keepmeta")
        EpubStore.writeBook(book, "T", null, "image/jpeg", listOf(doc("https://h/1", "c1", "One", "p")), meta)
        // appendChapter passes meta=null when the series page was never
        // scraped — it reuses THIS stored snapshot (verified on-device);
        // here we assert the snapshot read-back that powers it.
        val readBack = EpubStore.readBookMeta(book)
        assertEquals("Kuro no Maou", readBack?.title)
        assertEquals("Hishi Kage Dairi", readBack?.author)
        assertEquals("2014", readBack?.year)
        assertEquals("No", readBack?.completelyTranslated)
        assertEquals("Shousetsuka ni Narou", readBack?.originalPublisher)
        // a book written WITHOUT meta (pre-title-page books) reads as null
        val legacy = tempBook("legacy")
        EpubStore.writeBook(legacy, "T", null, "image/jpeg", listOf(doc("https://h/1", "c1", "One", "p")))
        assertEquals(null, EpubStore.readBookMeta(legacy))
    }

    @Test
    fun coverPageIsFirstSpineItem() {
        val book = tempBook("coverfirst")
        EpubStore.writeBook(book, "T", byteArrayOf(1, 2, 3), "image/jpeg",
            listOf(doc("https://h/1", "c1", "One", "p")), meta)
        ZipFile(book).use { zip ->
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
            val spine = Regex("""<itemref idref="([\w-]+)"""").findAll(opf).map { it.groupValues[1] }.toList()
            assertEquals("cover-page", spine.first())
            assertTrue(spine.indexOf("cover-page") < spine.indexOf("titlepage"))
            assertTrue(spine.indexOf("titlepage") < spine.indexOf("c0001"))
        }
    }

    // --- volume/part split (user decision 2026-10-12) ---

    @Test
    fun chapterBucket_volumesAndParts() {
        assertEquals("v2", EpubStore.chapterBucket("v2c220"))
        assertEquals("v10", EpubStore.chapterBucket("v10c595"))
        assertEquals("v1", EpubStore.chapterBucket("Vol. 1 Ch. 12"))
        assertEquals("p7", EpubStore.chapterBucket("c673"))          // (673-1)/100+1 = 7
        assertEquals("p1", EpubStore.chapterBucket("c50"))
        assertEquals("p2", EpubStore.chapterBucket("c101"))
        assertEquals("p1", EpubStore.chapterBucket("no numbers"))
        assertEquals("p6", EpubStore.chapterBucket("595 「Title」"))   // bare 595 → part 6
    }

    @Test
    fun bookDisplayTitle_suffixRules() {
        // lone p1 → plain series title
        assertEquals("Kuro no Maou", EpubStore.bookDisplayTitle("Kuro no Maou", "p1", listOf("p1")))
        // multiple parts → suffixed
        assertEquals("Star — Part 2", EpubStore.bookDisplayTitle("Star", "p2", listOf("p1", "p2")))
        assertEquals("Star — Part 1", EpubStore.bookDisplayTitle("Star", "p1", listOf("p1", "p2")))
        // volumes always suffixed
        assertEquals("Star — Vol 2", EpubStore.bookDisplayTitle("Star", "v2", listOf("v1", "v2")))
        // p1 alongside volumes gets its part name too (it's a real split)
        assertEquals("Star — Part 1", EpubStore.bookDisplayTitle("Star", "p1", listOf("p1", "v2")))
    }

    @Test
    fun translation_survivesBookRoundTrip() {
        val book = tempBook("xlate")
        EpubStore.writeBook(
            book, "T", null, "image/jpeg",
            EpubStore.orderDocs(listOf(
                EpubStore.ChapterDoc("https://h/1", "c1", "One", listOf("元の文", "二番目"), listOf("The first line", "The second"))
            ))
        )
        val back = EpubStore.readAllChapterDocs(book)
        assertEquals(1, back.size)
        assertEquals(listOf("The first line", "The second"), back[0].translation)
        assertEquals(listOf("元の文", "二番目"), back[0].paragraphs)   // original untouched
    }

    @Test
    fun translation_absentIsNull() {
        val book = tempBook("noxlate")
        EpubStore.writeBook(book, "T", null, "image/jpeg",
            EpubStore.orderDocs(listOf(doc("https://h/1", "c1", "One", "p1"))))
        assertNull(EpubStore.readAllChapterDocs(book)[0].translation)
    }
}
