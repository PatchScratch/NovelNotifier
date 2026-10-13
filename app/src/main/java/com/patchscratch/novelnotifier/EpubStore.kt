package com.patchscratch.novelnotifier

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Phase 3.3 — the offline library as standard ePub files (user decision,
 * 2026-10-11): one book per series, chapters appended as the reader caches
 * them, readable in-app AND by any other e-reader.
 *
 * The format is written by hand — java.util.zip plus our own OPF/nav/NCX —
 * no external dependency. Structure (EPUB 3 with EPUB-2 NCX for old readers):
 *
 *   mimetype                 FIRST entry, STORED (uncompressed) — spec rule
 *   META-INF/container.xml
 *   OEBPS/content.opf        metadata + manifest + spine
 *   OEBPS/nav.xhtml          EPUB3 table of contents
 *   OEBPS/toc.ncx            EPUB2 table of contents
 *   OEBPS/style.css          gentle reading typography
 *   OEBPS/cover.jpg + cover.xhtml (when the series has a cached cover)
 *   OEBPS/chapters/cNNNN.xhtml
 *   OEBPS/chapters.json      OUR index (url → file), tolerated absent on read
 *
 * Update strategy: read the whole book into memory (books are a few MB),
 * merge, rewrite the zip — cheap at these sizes and keeps files renumbered.
 *
 * The zip build/parse core takes plain Files so unit tests run on the JVM;
 * the Context wrappers only resolve paths, covers, and sharing.
 */
object EpubStore {

    fun dir(context: Context): File = context.filesDir.resolve("epubs").apply { mkdirs() }

    // --- volume/part split (user decision 2026-10-12) -----------------------
    // Books split per VOLUME when labels carry one ("v2c220"), else into
    // 100-chapter PARTS by chapter number. One file per bucket:
    //   epubs/<hash>-v2.epub | epubs/<hash>-p1.epub
    // The pre-split single file (<hash>.epub) is a LEGACY book — absorbed and
    // deleted by the first append under the new scheme.

    /** "v2" when the label has a volume; else "p<N>" = ceil(chapter/100). */
    fun chapterBucket(label: String): String {
        val key = chapterKey(label) ?: return "p1"
        val (vol, ch) = key
        if (vol > 0) return "v$vol"
        if (ch > 0) return "p${(ch - 1) / 100 + 1}"
        return "p1"
    }

    /** Display title for a bucket book ("— Vol 2" / "— Part 2"); plain when
     *  it is the series' only bucket and a plain p1. */
    fun bookDisplayTitle(seriesTitle: String, bucket: String, buckets: Collection<String>): String {
        val plainOk = bucket == "p1" && buckets.none { it.startsWith("v") } && buckets.none { it != "p1" }
        if (plainOk) return seriesTitle
        return if (bucket.startsWith("v")) {
            seriesTitle + " — Vol " + bucket.removePrefix("v")
        } else {
            seriesTitle + " — Part " + bucket.removePrefix("p")
        }
    }

    fun bookFile(context: Context, seriesUrl: String, bucket: String): File =
        dir(context).resolve(seriesUrl.hashCode().toUInt().toString() + "-" + bucket + ".epub")

    /** Legacy pre-split book, if any (read for absorption / migration reads). */
    fun legacyBookFile(context: Context, seriesUrl: String): File =
        dir(context).resolve(seriesUrl.hashCode().toUInt().toString() + ".epub")

    /** All bucket files that exist for the series (legacy excluded). */
    fun bookFiles(context: Context, seriesUrl: String): List<File> {
        val prefix = seriesUrl.hashCode().toUInt().toString() + "-"
        return dir(context).listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".epub") }
            .sorted()
    }

    fun bucketsOf(context: Context, seriesUrl: String): List<String> =
        bookFiles(context, seriesUrl).map { it.name.removePrefix(prefixOf(seriesUrl)).removeSuffix(".epub") }

    private fun prefixOf(seriesUrl: String) = seriesUrl.hashCode().toUInt().toString() + "-"

    // --- data shapes ---------------------------------------------------------

    /**
     * Title-page metadata drawn from the Novel Updates series page (3.3):
     * title, description, associated names, type, genre, tags, author,
     * artist, year, completely-translated flag, original publisher. Blank
     * fields are omitted from the title page rows.
     */
    data class BookMeta(
        val title: String,
        val description: String = "",
        val associatedNames: String = "",
        val type: String = "",
        val genres: String = "",
        val tags: String = "",
        val author: String = "",
        val artist: String = "",
        val year: String = "",
        val completelyTranslated: String = "",
        val originalPublisher: String = ""
    ) {
        companion object {
            fun from(page: SeriesPage, fallbackTitle: String): BookMeta = BookMeta(
                title = page.title.ifBlank { fallbackTitle },
                description = page.description,
                associatedNames = page.associatedNames,
                type = page.type,
                genres = page.genres,
                tags = page.tags,
                author = page.author,
                artist = page.artist,
                year = page.year,
                completelyTranslated = page.completelyTranslated,
                originalPublisher = page.originalPublisher
            )
        }
    }

    /** A chapter as stored in the book (also the in-memory merge unit). */
    data class ChapterDoc(
        val url: String,        // source chapter URL — the dedupe key
        val label: String,      // e.g. "c673" (NU table label)
        val title: String,      // extracted chapter title (may be blank)
        val paragraphs: List<String>,
        /** Optional AI translation, paragraph-for-paragraph. Renders in our
         *  reader (toggle); external e-readers ignore the embedded JSON. */
        val translation: List<String>? = null
    )

    /** Index row for chapters.json (file + order metadata). */
    data class StoredChapter(
        val url: String,
        val label: String,
        val title: String,
        val file: String,
        val num: Int            // chapterNum(label) when parseable, else 0
    )

    // --- write (pure JVM) ----------------------------------------------------

    /**
     * Write (or rewrite) a complete book. [chapters] must already be merged
     * and ordered by reading order. Returns the number of chapters written.
     */
    fun writeBook(
        bookFile: File,
        title: String,
        coverBytes: ByteArray?,
        coverMime: String,
        chapters: List<ChapterDoc>,
        meta: BookMeta? = null
    ): Int {
        val metaTitle = meta?.title?.ifBlank { title } ?: title
        val uuid = UUID.nameUUIDFromBytes(metaTitle.toByteArray()).toString()
        val entries = LinkedHashMap<String, ByteArray>()

        entries["mimetype"] = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        entries["META-INF/container.xml"] = CONTAINER_XML.toByteArray()
        entries["OEBPS/style.css"] = STYLE_CSS.toByteArray()

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navItems = StringBuilder()
        val ncxNavPoints = StringBuilder()

        if (coverBytes != null && coverBytes.isNotEmpty()) {
            entries["OEBPS/cover.jpg"] = coverBytes
            entries["OEBPS/cover.xhtml"] = COVER_XHTML.toByteArray()
            manifest.append(
                "    <item id=\"cover-img\" href=\"cover.jpg\" media-type=\"${mimeAttr(coverMime)}\"/>\n" +
                    "    <item id=\"cover-page\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
            )
            spine.append("    <itemref idref=\"cover-page\"/>\n")
        }
        if (meta != null) {
            entries["OEBPS/titlepage.xhtml"] = titlepageXhtml(meta).toByteArray()
            manifest.append(
                "    <item id=\"titlepage\" href=\"titlepage.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
            )
            spine.append("    <itemref idref=\"titlepage\"/>\n")
        }

        chapters.forEachIndexed { index, doc ->
            val file = "chapters/c" + "%04d".format(index + 1) + ".xhtml"
            entries["OEBPS/$file"] = chapterXhtml(doc).toByteArray()
            val id = "c" + "%04d".format(index + 1)
            manifest.append("    <item id=\"$id\" href=\"$file\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("    <itemref idref=\"$id\"/>\n")
            val tocLabel = if (doc.title.isNotBlank()) "${doc.label} — ${doc.title}" else doc.label
            navItems.append("      <li><a href=\"$file\">${xmlEscape(tocLabel)}</a></li>\n")
            ncxNavPoints.append(ncxPoint(index + 1, id, tocLabel, file))
        }

        entries["OEBPS/nav.xhtml"] = navXhtml(metaTitle, navItems.toString()).toByteArray()
        entries["OEBPS/toc.ncx"] = ncxDoc(uuid, metaTitle, ncxNavPoints.toString()).toByteArray()
        entries["OEBPS/content.opf"] = opfDoc(uuid, metaTitle, manifest.toString(), spine.toString(), meta).toByteArray()
        entries["OEBPS/chapters.json"] = chaptersJson(chapters).toByteArray()
        if (meta != null) entries["OEBPS/meta.json"] = metaJson(meta).toByteArray()

        bookFile.parentFile?.mkdirs()
        ZipOutputStream(bookFile.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    // EPUB spec: mimetype must be the first entry, uncompressed.
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return chapters.size
    }

    // --- read (pure JVM, tolerant) -------------------------------------------

    fun readBookIndex(bookFile: File): List<StoredChapter> {
        if (!bookFile.exists()) return emptyList()
        return try {
            ZipFile(bookFile).use { zip ->
                val entry = zip.getEntry("OEBPS/chapters.json") ?: return emptyList()
                val arr = JSONArray(zip.getInputStream(entry).bufferedReader().readText())
                (0 until arr.length()).mapNotNull { i ->
                    val row = arr.optJSONObject(i) ?: return@mapNotNull null
                    StoredChapter(
                        url = row.optString("u"),
                        label = row.optString("c"),
                        title = row.optString("t"),
                        file = row.optString("f"),
                        num = row.optInt("n")
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun readChapterDoc(bookFile: File, url: String): ChapterDoc? {
        val index = readBookIndex(bookFile)
        val row = index.firstOrNull { it.url == url } ?: return null
        return try {
            ZipFile(bookFile).use { zip ->
                val entry = zip.getEntry("OEBPS/${row.file}") ?: return null
                val html = zip.getInputStream(entry).bufferedReader().readText()
                parseChapterXhtml(html, row)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Our own xhtml is predictable; parse defensively anyway (older files). */
    private fun parseChapterXhtml(html: String, row: StoredChapter): ChapterDoc {
        val title = Regex("<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.let(::xmlUnescape).orEmpty()
        val paragraphs = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { xmlUnescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        // Sidecar translation: a JSON array in a <script> block. External
        // e-readers ignore it; our reader reads it back on re-parse.
        val translation = Regex(
            """<script type="application/json" id="nn-translation">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1)?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.optString(it) }.takeIf { it.isNotEmpty() }
            } catch (e: Exception) { null }
        }
        return ChapterDoc(row.url, row.label, title.ifBlank { row.title }, paragraphs, translation)
    }

    // --- document templates --------------------------------------------------

    private val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private val STYLE_CSS = """body { font-family: serif; line-height: 1.6; margin: 1em; }
h1 { font-size: 1.3em; margin: 1.5em 0 1em 0; }
p { margin: 0 0 0.75em 0; text-indent: 1.2em; }
body.titlepage { margin: 1.5em 1em; }
.titlepage h1 { font-size: 1.8em; text-align: center; margin: 2em 0 0.5em 0; text-indent: 0; }
.titlepage .author { text-align: center; font-style: italic; margin: 0 0 2.5em 0; text-indent: 0; }
.titlepage table.meta { width: 100%; border-collapse: collapse; margin-bottom: 2em; }
.titlepage table.meta th { text-align: left; vertical-align: top; width: 34%; padding: 0.3em 0.6em 0.3em 0; font-weight: bold; }
.titlepage table.meta td { padding: 0.3em 0; }
.titlepage h2 { font-size: 1.2em; }
.titlepage .description { text-indent: 0; }"""

    private val COVER_XHTML = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Cover</title></head>
<body style="margin:0;text-align:center;"><img src="cover.jpg" alt="cover" style="max-width:100%;"/></body>
</html>"""

    /**
     * The visible title page (right after the cover): series title, author,
     * a metadata table from the NU series page, then the description. Blank
     * fields are omitted row by row.
     */
    internal fun titlepageXhtml(meta: BookMeta): String {
        val rows = listOf(
            "Associated Names" to meta.associatedNames,
            "Type" to meta.type,
            "Genre" to meta.genres,
            "Tags" to meta.tags,
            "Author" to meta.author,
            "Artist" to meta.artist,
            "Year" to meta.year,
            "Completely Translated" to meta.completelyTranslated,
            "Original Publisher" to meta.originalPublisher
        ).filter { it.second.isNotBlank() }
            .joinToString("\n") { (k, v) ->
                "  <tr><th>${xmlEscape(k)}</th><td>${xmlEscape(v)}</td></tr>"
            }
        val authorLine = if (meta.author.isNotBlank()) "<p class=\"author\">${xmlEscape(meta.author)}</p>\n" else ""
        val description = if (meta.description.isNotBlank()) {
            "<h2>Description</h2>\n<p class=\"description\">${xmlEscape(meta.description)}</p>"
        } else ""
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${xmlEscape(meta.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body class="titlepage">
<h1>${xmlEscape(meta.title)}</h1>
$authorLine<table class="meta">
$rows</table>
$description</body>
</html>"""
    }

    private fun metaJson(meta: BookMeta): String = JSONObject()
        .put("title", meta.title)
        .put("description", meta.description)
        .put("associatedNames", meta.associatedNames)
        .put("type", meta.type)
        .put("genres", meta.genres)
        .put("tags", meta.tags)
        .put("author", meta.author)
        .put("artist", meta.artist)
        .put("year", meta.year)
        .put("completelyTranslated", meta.completelyTranslated)
        .put("originalPublisher", meta.originalPublisher)
        .toString()

    /** Read the stored meta back so later appends keep the title page. */
    fun readBookMeta(bookFile: File): BookMeta? = try {
        ZipFile(bookFile).use { zip ->
            val entry = zip.getEntry("OEBPS/meta.json") ?: return null
            val j = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
            BookMeta(
                title = j.optString("title"),
                description = j.optString("description"),
                associatedNames = j.optString("associatedNames"),
                type = j.optString("type"),
                genres = j.optString("genres"),
                tags = j.optString("tags"),
                author = j.optString("author"),
                artist = j.optString("artist"),
                year = j.optString("year"),
                completelyTranslated = j.optString("completelyTranslated"),
                originalPublisher = j.optString("originalPublisher")
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun opfDoc(
        uuid: String,
        title: String,
        manifest: String,
        spine: String,
        meta: BookMeta? = null
    ): String {
        val dcMeta = buildString {
            if (meta != null) {
                if (meta.author.isNotBlank()) append("    <dc:creator>${xmlEscape(meta.author)}</dc:creator>\n")
                if (meta.description.isNotBlank()) append("    <dc:description>${xmlEscape(meta.description)}</dc:description>\n")
                // Genres and tags become library subjects — how Calibre, Moon+
                // etc. classify books.
                (meta.genres.split(',') + meta.tags.split(','))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(24)
                    .forEach { append("    <dc:subject>${xmlEscape(it)}</dc:subject>\n") }
                if (meta.year.isNotBlank()) append("    <dc:date>${xmlEscape(meta.year)}</dc:date>\n")
                if (meta.originalPublisher.isNotBlank()) append("    <dc:publisher>${xmlEscape(meta.originalPublisher)}</dc:publisher>\n")
            }
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="en">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:$uuid</dc:identifier>
    <dc:title>${xmlEscape(title)}</dc:title>
    <dc:language>en</dc:language>
$dcMeta    <meta property="dcterms:modified">${modifiedNow()}</meta>
    <meta name="generator" content="NovelNotifier"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
$manifest  </manifest>
  <spine toc="ncx">
$spine  </spine>
</package>"""
    }

    private fun navXhtml(title: String, items: String) = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${xmlEscape(title)}</title></head>
<body>
<nav epub:type="toc"><h1>Contents</h1>
<ol>
$items</ol>
</nav>
</body>
</html>"""

    private fun ncxDoc(uuid: String, title: String, navPoints: String) = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:$uuid"/>
    <meta name="dtb:depth" content="1"/>
  </head>
  <docTitle><text>${xmlEscape(title)}</text></docTitle>
  <navMap>
$navPoints  </navMap>
</ncx>"""

    private fun ncxPoint(order: Int, id: String, label: String, file: String) = """    <navPoint id="np-$id" playOrder="$order">
      <navLabel><text>${xmlEscape(label)}</text></navLabel>
      <content src="$file"/>
    </navPoint>
"""

    private fun chapterXhtml(doc: ChapterDoc) = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${xmlEscape(doc.label)}</title></head>
<body>
${if (doc.title.isNotBlank()) "<h1>${xmlEscape(doc.title)}</h1>\n" else ""}${doc.paragraphs.joinToString("\n") { "<p>${xmlEscape(it)}</p>" }}
${if (doc.translation != null) """<script type="application/json" id="nn-translation">${JSONArray(doc.translation)}</script>""" else ""}
</body>
</html>"""

    private fun chaptersJson(chapters: List<ChapterDoc>): String {
        val arr = JSONArray()
        chapters.forEachIndexed { index, doc ->
            arr.put(
                JSONObject()
                    .put("u", doc.url)
                    .put("c", doc.label)
                    .put("t", doc.title)
                    .put("f", "chapters/c" + "%04d".format(index + 1) + ".xhtml")
                    .put("n", chapterNum(doc.label))
            )
        }
        return arr.toString()
    }

    private fun modifiedNow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }

    private fun mimeAttr(coverMime: String) = when {
        coverMime.contains("png") -> "image/png"
        else -> "image/jpeg"
    }

    internal fun xmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    internal fun xmlUnescape(s: String) = s
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /**
     * Reading order: by chapter number when the label parses ("c673"),
     * unparseable labels ("v2 part7") sorted last, ties and unknowns stable
     * in their given order.
     */
    fun orderDocs(docs: List<ChapterDoc>): List<ChapterDoc> = docs
        .mapIndexed { i, doc -> i to doc }
        .sortedWith { (i1, d1), (i2, d2) ->
            // full (volume, chapter) key — chapter-number-only sorting
            // scrambled volume-labeled series (v2c1 sorted before v1c100)
            val k1 = chapterKey(d1.label) ?: (1_000_000 to 1_000_000)
            val k2 = chapterKey(d2.label) ?: (1_000_000 to 1_000_000)
            val byKey = k1.first.compareTo(k2.first).takeIf { it != 0 }
                ?: k1.second.compareTo(k2.second)
            byKey.takeIf { it != 0 } ?: i1.compareTo(i2)
        }
        .map { it.second }

    // --- Context wrappers ----------------------------------------------------

    /** All chapter docs from the book, in reading order (empty when absent). */
    fun readAllChapterDocs(bookFile: File): List<ChapterDoc> {
        val index = readBookIndex(bookFile)
        if (index.isEmpty()) return emptyList()
        return try {
            ZipFile(bookFile).use { zip ->
                index.mapNotNull { row ->
                    val entry = zip.getEntry("OEBPS/${row.file}") ?: return@mapNotNull null
                    val html = zip.getInputStream(entry).bufferedReader().readText()
                    parseChapterXhtml(html, row)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun existingCover(bookFile: File): ByteArray? = try {
        ZipFile(bookFile).use { zip ->
            zip.getEntry("OEBPS/cover.jpg")?.let { zip.getInputStream(it).readBytes() }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Add (or replace) a chapter in the series' book, kept in reading order
     * by chapter number (unparseable labels sort last, stably). Safe to call
     * repeatedly for the same chapter. Returns the chapter count after the
     * write, or -1 on failure.
     */
    fun appendChapter(
        context: Context,
        seriesTitle: String,
        seriesUrl: String,
        chapterUrl: String,
        label: String,
        extract: ChapterExtract,
        meta: BookMeta? = null
    ): Int {
        return try {
            val bucket = chapterBucket(label)
            val book = bookFile(context, seriesUrl, bucket)
            val legacy = legacyBookFile(context, seriesUrl)
            // Merge this bucket's chapters with the legacy book's (if this is
            // the first split write) — legacy chapters in OTHER buckets are
            // absorbed by their own first append.
            val existing = readAllChapterDocs(book) +
                (if (legacy.exists()) readAllChapterDocs(legacy).filter { chapterBucket(it.label) == bucket } else emptyList())
            val kept = existing.filter { it.url != chapterUrl }
            val docs = kept + ChapterDoc(chapterUrl, label, extract.title, extract.paragraphs)
            val ordered = orderDocs(docs)
            val cover = CoverStore.localFile(context, seriesUrl)?.readBytes()
                ?: existingCover(book) ?: if (legacy.exists()) existingCover(legacy) else null
            // Title-page metadata: use the freshest provided; a later append
            // without meta keeps the stored one instead of dropping the page.
            val bookMeta = meta ?: readBookMeta(book) ?: if (legacy.exists()) readBookMeta(legacy) else null
            // Delete the legacy book ONLY when every one of its chapters is
            // covered by a BUCKET file (this write included). Coverage must be
            // counted from bucket files alone — including legacy in the check
            // made it self-covering, deleting v2/v3 chapters before their
            // buckets existed.
            if (legacy.exists()) {
                val bucketCovered = (bookFiles(context, seriesUrl).filter { it != book }
                    .flatMap { readAllChapterDocs(it) } + ordered)
                    .map { it.url }.toSet()
                val legacyDocs = readAllChapterDocs(legacy)
                if (legacyDocs.all { it.url in bucketCovered }) legacy.delete()
            }
            val buckets = (bucketsOf(context, seriesUrl) + bucket).distinct()
            val displayTitle = bookDisplayTitle(seriesTitle, bucket, buckets)
            writeBook(book, displayTitle, cover, "image/jpeg", ordered, bookMeta)
        } catch (e: Exception) {
            DebugLog.log("EPUB", "appendChapter failed", e)
            -1
        }
    }

    /** Stored chapters across ALL of the series' books + legacy (bulk skip set). */
    fun readAllStoredDocs(context: Context, seriesUrl: String): List<ChapterDoc> {
        val files = bookFiles(context, seriesUrl) +
            listOfNotNull(legacyBookFile(context, seriesUrl).takeIf { it.exists() })
        return files.flatMap { f -> readAllChapterDocs(f) }
    }

    /**
     * Attach (or replace) the AI translation of one stored chapter. Rewrites
     * the bucket book holding that chapter; original paragraphs are untouched.
     * Returns false when the chapter isn't stored yet — translate-then-save
     * flows store the original first.
     */
    fun setTranslation(
        context: Context,
        seriesTitle: String,
        seriesUrl: String,
        chapterUrl: String,
        paragraphs: List<String>
    ): Boolean {
        val book = (bookFiles(context, seriesUrl) + listOfNotNull(
            legacyBookFile(context, seriesUrl).takeIf { it.exists() }
        )).firstOrNull { f -> readAllChapterDocs(f).any { it.url == chapterUrl } } ?: return false
        val docs = readAllChapterDocs(book).map {
            if (it.url == chapterUrl) it.copy(translation = paragraphs) else it
        }
        // Same rewrite inputs as appendChapter so cover/title page survive.
        val bucket = chapterBucket(docs.first { it.url == chapterUrl }.label)
        val displayTitle = bookDisplayTitle(seriesTitle, bucket, bucketsOf(context, seriesUrl))
        val cover = CoverStore.localFile(context, seriesUrl)?.readBytes()
            ?: existingCover(book)
        writeBook(book, displayTitle, cover, "image/jpeg", orderDocs(docs), readBookMeta(book))
        return true
    }

    fun storedCount(context: Context, seriesUrl: String): Int =
        readAllStoredDocs(context, seriesUrl).size

    /** The stored copy of a chapter, for offline reading (any bucket). */
    fun readChapter(context: Context, seriesUrl: String, chapterUrl: String): ChapterExtract? {
        val files = bookFiles(context, seriesUrl) +
            listOfNotNull(legacyBookFile(context, seriesUrl).takeIf { it.exists() })
        for (f in files) {
            readChapterDoc(f, chapterUrl)?.let { return ChapterExtract(title = it.title, paragraphs = it.paragraphs) }
        }
        return null
    }

    /** Copy ONE bucket's book to a SAF destination (file picker). */
    fun exportTo(context: Context, seriesUrl: String, bucket: String, seriesTitle: String, uri: Uri): String {
        return try {
            val book = bookFile(context, seriesUrl, bucket)
            if (!book.exists()) return "No book for this volume yet"
            context.contentResolver.openOutputStream(uri)?.use { out ->
                book.inputStream().use { it.copyTo(out) }
            } ?: return "Could not open the destination"
            val n = readBookIndex(book).size
            "Exported ${bookDisplayTitle(seriesTitle, bucket, bucketsOf(context, seriesUrl))} ($n chapters)"
        } catch (e: Exception) {
            DebugLog.log("EPUB", "export failed", e)
            "Export failed: ${e.message}"
        }
    }

    /** Export filename suggestion for a bucket. */
    fun exportName(seriesTitle: String, bucket: String, buckets: Collection<String>): String =
        bookDisplayTitle(seriesTitle, bucket, buckets).replace(" — ", " - ") + ".epub"

    /** Share sheet with ALL of the series' books (one per volume/part). */
    fun shareIntent(context: Context, seriesUrl: String): Intent? {
        val books = bookFiles(context, seriesUrl)
        if (books.isEmpty()) return null
        val shared = books.map {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
        }
        return if (shared.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, shared[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/epub+zip"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shared))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
