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

    /**
     * Layout-variant twin of a bucket book (`<hash>-v2-jp.epub`), written when
     * the saved layout is "both" so the library physically holds JP, EN and
     * interleaved copies (user request 2026-10-18). NOT part of the canonical
     * set — [bookFiles] skips them, so counts/badges/skip-sets never triple.
     */
    fun variantBookFile(context: Context, seriesUrl: String, bucket: String, variant: String): File =
        dir(context).resolve(seriesUrl.hashCode().toUInt().toString() + "-" + bucket + "-" + variant + ".epub")

    /** Legacy pre-split book, if any (read for absorption / migration reads). */
    fun legacyBookFiles(context: Context, seriesUrl: String): List<File> =
        prefixesOf(seriesUrl).map { dir(context).resolve(it.removeSuffix("-") + ".epub") }
            .filter { it.exists() }

    fun legacyBookFile(context: Context, seriesUrl: String): File =
        dir(context).resolve(seriesUrl.hashCode().toUInt().toString() + ".epub")

    /** Canonical bucket names only: `<hash>-(vN|pN).epub`. Layout variants
     * (`-jp`/`-en`) and the legacy book must not match. */
    internal val BOOK_NAME = Regex("""^\d+-(v\d+|p\d+)\.epub$""")

    /** All bucket files that exist for the series (legacy + variants excluded). */
    fun bookFiles(context: Context, seriesUrl: String): List<File> {
        val prefixes = prefixesOf(seriesUrl)
        return dir(context).listFiles().orEmpty()
            .filter { f -> prefixes.any { f.name.startsWith(it) } && BOOK_NAME.matches(f.name) }
            .sorted()
    }

    fun bucketsOf(context: Context, seriesUrl: String): List<String> =
        bookFiles(context, seriesUrl).map { it.name.removePrefix(prefixOf(seriesUrl)).removeSuffix(".epub") }

    private fun prefixOf(seriesUrl: String) = seriesUrl.hashCode().toUInt().toString() + "-"

    /**
     * All hash prefixes a series' books may live under (Wayne, 2026-10-20:
     * NU saved chapters were invisible — older saves hashed a different
     * seriesUrl FORM than the detail screen uses today, e.g. trailing
     * slash). Lookups match every variant; saves keep using the exact
     * string they were given.
     */
    private fun prefixesOf(seriesUrl: String): List<String> {
        val base = listOf(seriesUrl, seriesUrl.trimEnd('/'), seriesUrl.trimEnd('/') + "/")
        val schemes = base.flatMap {
            listOf(it, it.replaceFirst("https://", "http://"), it.replaceFirst("http://", "https://"))
        }
        return schemes.distinct().map { it.hashCode().toUInt().toString() + "-" }.distinct()
    }

    /**
     * Every stored chapter across the series' bucket books, chapter order —
     * the offline ToC of last resort. The reader appends each chapter it
     * successfully displays (2026-10-15: reading is the opt-in), so this is
     * "the chapters you've read/downloaded", servable with no network.
     */
    fun storedChapters(context: Context, seriesUrl: String): List<StoredChapter> =
        bookFiles(context, seriesUrl)
            .flatMap { readBookIndex(it) }
            .sortedBy { it.num }

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
        val translation: List<String>? = null,
        /** Inline illustrations, paragraph-anchored like ChapterExtract's:
         *  (paragraph index the image sits BEFORE, source URL). Persisted in
         *  chapters.json ("m"); the bytes live in EpubMediaStore and embed
         *  into the book's media/ at write time (user report 2026-10-18:
         *  "images don't get exported as rendered in reader"). */
        val images: List<Pair<Int, String>> = emptyList()
    )

    /** Index row for chapters.json (file + order metadata). */
    data class StoredChapter(
        val url: String,
        val label: String,
        val title: String,
        val file: String,
        val num: Int,            // chapterNum(label) when parseable, else 0
        /** Paragraph-anchored illustrations carried from ChapterDoc (the
         *  "m" column) — feeds the offline reader + rewrites. */
        val images: List<Pair<Int, String>> = emptyList()
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
        meta: BookMeta? = null,
        // Saved-file paragraph layout: both (JP+EN interleaved), jp, en —
        // mirrors the reader's xlate_layout pref at export/write time.
        layout: String = "both",
        // Inline-illustration bytes by source URL (EpubMediaStore on device,
        // a fake in tests). Null/sparse → those images are skipped, never a
        // failed write. Books are rewritten whole on every append, so bytes
        // resolve per write and stay out of ChapterDoc.
        mediaLookup: ((String) -> ByteArray?)? = null,
        // Incomplete-volume marker (Wayne, 2026-10-20): (stored, total) per
        // the site ToC — a closing page states the volume isn't complete
        // yet, so a reader knows more is coming and later appends belong.
        incomplete: Pair<Int, Int>? = null
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

        // --- media pass: resolve each distinct illustration once ----------
        val mediaNames = HashMap<String, String>()   // source URL → media/<hash>.<ext>
        if (mediaLookup != null) {
            chapters.flatMap { it.images }
                .distinctBy { it.second }
                .forEach { (_, url) ->
                    val bytes = mediaLookup(url) ?: return@forEach
                    val name = "media/" + EpubMediaStore.key(url) + "." + EpubMediaStore.ext(url)
                    entries["OEBPS/$name"] = bytes
                    mediaNames[url] = name
                }
        }
        mediaNames.values.sorted().forEachIndexed { i, name ->
            manifest.append(
                "    <item id=\"img-$i\" href=\"$name\" media-type=\"${mediaMime(name)}\"/>\n"
            )
        }

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
            entries["OEBPS/$file"] = chapterXhtml(doc, layout, mediaNames).toByteArray()
            val id = "c" + "%04d".format(index + 1)
            manifest.append("    <item id=\"$id\" href=\"$file\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("    <itemref idref=\"$id\"/>\n")
            val tocLabel = if (doc.title.isNotBlank()) "${doc.label} — ${doc.title}" else doc.label
            navItems.append("      <li><a href=\"$file\">${xmlEscape(tocLabel)}</a></li>\n")
            ncxNavPoints.append(ncxPoint(index + 1, id, tocLabel, file))
        }

        // Incomplete-volume closing page (Wayne, 2026-10-20): the site ToC
        // knows more chapters for this volume than are stored — say so.
        if (incomplete != null && incomplete.second > incomplete.first) {
            val (stored, total) = incomplete
            entries["OEBPS/incomplete.xhtml"] = (
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Volume incomplete</title>" +
                    "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body>" +
                    "<p class=\"note\">— Volume incomplete: $stored of $total chapters stored " +
                    "(the translator's ToC lists more). New downloads append here automatically. —</p>" +
                    "</body></html>"
                ).toByteArray()
            manifest.append("    <item id=\"incomplete\" href=\"incomplete.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("    <itemref idref=\"incomplete\"/>\n")
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
                    val imgs = row.optJSONArray("m")?.let { m ->
                        (0 until m.length()).mapNotNull { k ->
                            val pair = m.optJSONArray(k) ?: return@mapNotNull null
                            val idx = pair.optInt(0, -1)
                            val u = pair.optString(1)
                            if (idx >= 0 && u.startsWith("http")) idx to u else null
                        }
                    } ?: emptyList()
                    StoredChapter(
                        url = row.optString("u"),
                        label = row.optString("c"),
                        title = row.optString("t"),
                        file = row.optString("f"),
                        num = row.optInt("n"),
                        images = imgs
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
        // Paragraphs exclude nn-en lines (the interleaved English copies in
        // "both"-layout bodies) — originals stay pure JP for re-rendering.
        val paragraphs = Regex("<p([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .filterNot { it.groupValues[1].contains("nn-en") }
            .map { xmlUnescape(it.groupValues[2]).trim() }
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
        return ChapterDoc(row.url, row.label, title.ifBlank { row.title }, paragraphs, translation, row.images)
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
.titlepage .description { text-indent: 0; }
.nn-img { margin: 1em 0; text-indent: 0; text-align: center; }
.nn-img img { max-width: 100%; height: auto; }"""

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

    private fun chapterXhtml(
        doc: ChapterDoc,
        layout: String = "both",
        mediaNames: Map<String, String> = emptyMap()
    ): String = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${xmlEscape(doc.label)}</title></head>
<body>
${if (doc.title.isNotBlank()) "<h1>${xmlEscape(doc.title)}</h1>\n" else ""}${bodyParagraphs(doc, layout, mediaNames)}
${if (doc.translation != null) """<script type="application/json" id="nn-translation">${JSONArray(doc.translation)}</script>""" else ""}
</body>
</html>"""

    /** Visible body per the saved layout; the nn-translation block always
     *  keeps the full parallel data so the reader can re-render any layout.
     *  Inline illustrations sit BEFORE their anchor paragraph (the harvest
     *  contract: index = paragraphs.size when the <img> was passed). */
    private fun bodyParagraphs(
        doc: ChapterDoc,
        layout: String,
        mediaNames: Map<String, String>
    ): String {
        val jp = doc.paragraphs.map { xmlEscape(it) }
        val en = doc.translation?.map { xmlEscape(it) }
        fun imgDivs(idx: Int): String = doc.images
            .filter { it.first == idx }
            .mapNotNull { mediaNames[it.second] }
            .joinToString("\n") { n -> "<div class=\"nn-img\"><img src=\"$n\" alt=\"\"/></div>" }
        // anchors at/beyond the paragraph count render after the last line
        val tailImgs = ((jp.size)..(jp.size + 32)).mapNotNull { i ->
            imgDivs(i).ifEmpty { null }
        }.joinToString("\n")

        fun textOf(i: Int): String = when (layout) {
            "en" -> {
                val e = en?.getOrNull(i)
                if (!e.isNullOrBlank()) "<p>$e</p>"
                else "<p>${jp.getOrNull(i) ?: ""}</p>"   // no translation yet → original
            }
            "jp" -> "<p>${jp.getOrNull(i) ?: ""}</p>"
            else -> buildString {
                append("<p>").append(jp.getOrNull(i) ?: "").append("</p>")
                val e = en?.getOrNull(i)
                if (!e.isNullOrBlank()) append("\n<p class=\"nn-en\">").append(e).append("</p>")
            }
        }

        return buildString {
            imgDivs(0).ifEmpty { null }?.let { append(it).append("\n") }
            jp.forEachIndexed { i, _ ->
                append(textOf(i)).append("\n")
                imgDivs(i + 1).ifEmpty { null }?.let { append(it).append("\n") }
            }
            if (tailImgs.isNotEmpty()) append(tailImgs)
        }.trimEnd('\n')
    }

    private fun mediaMime(name: String): String = when (name.substringAfterLast('.')) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

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
                    .put(
                        "m",
                        JSONArray(doc.images.map { (i, u) -> JSONArray().put(i).put(u) })
                    )
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
            // Volume routing (Wayne, 2026-10-20): a stored site ToC's volume
            // structure overrides label-derived buckets — the site's own
            // Book/Volume split (with its per-volume cover) becomes the
            // book split; NU label volumes that agree keep landing in the
            // same "vN" file either way.
            val tocsVol = ReaderTocStore.volumeFor(context, seriesUrl, chapterUrl, label)
            val bucket = if (tocsVol > 0) "v$tocsVol" else chapterBucket(label)
            val volCoverUrl = if (tocsVol > 0) ReaderTocStore.volCover(context, seriesUrl, tocsVol) else null
            val volTotal = if (tocsVol > 0) ReaderTocStore.volCount(context, seriesUrl, tocsVol) else 0
            val volCoverBytes = volCoverUrl?.let {
                runCatching { EpubMediaStore.bytes(context, it) }.getOrNull()
            }
            val book = bookFile(context, seriesUrl, bucket)
            val legacy = legacyBookFile(context, seriesUrl)
            // Merge this bucket's chapters with the legacy book's (if this is
            // the first split write) — legacy chapters in OTHER buckets are
            // absorbed by their own first append.
            val existing = readAllChapterDocs(book) +
                (if (legacy.exists()) readAllChapterDocs(legacy).filter { chapterBucket(it.label) == bucket } else emptyList())
            val kept = existing.filter { it.url != chapterUrl }
            // Re-appending must not drop a stored AI translation (the doc
            // rebuild replaces the URL's row) — carry it across so a plain
            // re-read never wipes translated chapters.
            val priorTranslation = existing.firstOrNull { it.url == chapterUrl }?.translation
            val doc = ChapterDoc(chapterUrl, label, extract.title, extract.paragraphs, priorTranslation, extract.images)
            val docs = kept + doc
            val ordered = orderDocs(docs)
            val cover = volCoverBytes
                ?: CoverStore.localFile(context, seriesUrl)?.readBytes()
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
            // A re-label can MOVE this chapter's bucket (flat cN → volume
            // vNcM); sweep sibling books so the URL lives in exactly one.
            for (sibling in bookFiles(context, seriesUrl).filter { it != book }) {
                val sibAll = readAllChapterDocs(sibling)
                val sib = sibAll.filter { it.url != chapterUrl }
                if (sib.size != sibAll.size && sib.isEmpty()) {
                    // Re-bucketing emptied the old book — remove it + twins.
                    val oldBucket = sibling.name.removePrefix(prefixOf(seriesUrl)).removeSuffix(".epub")
                    sibling.delete()
                    for (v in listOf("jp", "en")) {
                        variantBookFile(context, seriesUrl, oldBucket, v).delete()
                    }
                    continue
                }
                if (sib.size != sibAll.size) {
                    val sbucket = sibling.name.removePrefix(prefixOf(seriesUrl)).removeSuffix(".epub")
                    val stitle = readBookMeta(sibling)?.title ?: seriesTitle
                    writeBook(sibling, bookDisplayTitle(stitle, sbucket, bucketsOf(context, seriesUrl) + sbucket),
                        cover, "image/jpeg", orderDocs(sib), readBookMeta(sibling),
                        mediaLookup = { u -> EpubMediaStore.bytes(context, u) })
                    syncLayoutVariants(context, seriesUrl, sbucket,
                        bookDisplayTitle(stitle, sbucket, bucketsOf(context, seriesUrl) + sbucket),
                        cover, orderDocs(sib), readBookMeta(sibling))
                }
            }
            val buckets = (bucketsOf(context, seriesUrl) + bucket).distinct()
            val displayTitle = bookDisplayTitle(seriesTitle, bucket, buckets)
            val incomplete = volTotal.takeIf { it > ordered.size }?.let { ordered.size to it }
            val n = writeBook(book, displayTitle, cover, "image/jpeg", ordered, bookMeta,
                mediaLookup = { u -> EpubMediaStore.bytes(context, u) }, incomplete = incomplete)
            syncLayoutVariants(context, seriesUrl, bucket, displayTitle, cover, ordered, bookMeta,
                incomplete = incomplete)
            n
        } catch (e: Exception) {
            DebugLog.log("EPUB", "appendChapter failed", e)
            -1
        }
    }

    /** Stored chapters across ALL of the series' books + legacy (bulk skip set). */
    fun readAllStoredDocs(context: Context, seriesUrl: String): List<ChapterDoc> {
        val files = bookFiles(context, seriesUrl) +
            legacyBookFiles(context, seriesUrl)
        return files.flatMap { f -> readAllChapterDocs(f) }
    }

    /**
     * Delete ONE chapter's stored copy (reader ⋮ menu, Wayne 2026-10-20):
     * rewrite every book holding the URL without it; a bucket that empties
     * is removed along with its layout twins. Returns copies removed.
     */
    fun deleteChapter(context: Context, seriesUrl: String, chapterUrl: String): Int {
        var removed = 0
        try {
            val files = bookFiles(context, seriesUrl) +
                legacyBookFiles(context, seriesUrl)
            for (f in files) {
                val docs = readAllChapterDocs(f)
                val kept = docs.filter { it.url != chapterUrl }
                if (kept.size == docs.size) continue
                removed += docs.size - kept.size
                val bucket = f.name.removePrefix(prefixOf(seriesUrl)).removeSuffix(".epub")
                if (kept.isEmpty()) {
                    f.delete()
                    for (v in listOf("jp", "en")) {
                        variantBookFile(context, seriesUrl, bucket, v).delete()
                    }
                    continue
                }
                val stitle = readBookMeta(f)?.title ?: seriesUrl
                val displayTitle = bookDisplayTitle(stitle, bucket, bucketsOf(context, seriesUrl))
                val cover = existingCover(f)
                val meta = readBookMeta(f)
                writeBook(f, displayTitle, cover, "image/jpeg", orderDocs(kept), meta,
                    mediaLookup = { u -> EpubMediaStore.bytes(context, u) })
                syncLayoutVariants(context, seriesUrl, bucket, displayTitle, cover, orderDocs(kept), meta)
            }
            DebugLog.log("EPUB", "deleteChapter $chapterUrl: $removed cop" + (if (removed == 1) "y" else "ies"))
        } catch (e: Exception) {
            DebugLog.log("EPUB", "deleteChapter failed", e)
        }
        return removed
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
        writeBook(book, displayTitle, cover, "image/jpeg", orderDocs(docs), readBookMeta(book),
            mediaLookup = { u -> EpubMediaStore.bytes(context, u) })
        syncLayoutVariants(context, seriesUrl, bucket, displayTitle, cover, orderDocs(docs), readBookMeta(book))
        return true
    }

    fun storedCount(context: Context, seriesUrl: String): Int =
        readAllStoredDocs(context, seriesUrl).size

    /**
     * Remove every stored book for the series (buckets, layout twins,
     * legacy). Translations and cached illustration bytes ride with the
     * books; the media cache itself is URL-keyed and shared, so it stays.
     */
    fun deleteSeries(context: Context, seriesUrl: String) {
        val prefixes = prefixesOf(seriesUrl)
        dir(context).listFiles().orEmpty()
            .filter { f -> prefixes.any { f.name.startsWith(it) } && f.name.endsWith(".epub") }
            .forEach { it.delete() }
        legacyBookFile(context, seriesUrl).takeIf { it.exists() }?.delete()
        DebugLog.log("EPUB", "deleted all stored books for $seriesUrl")
    }

    /**
     * Keep a bucket's layout-variant twins in step with the saved paragraph
     * layout (user request 2026-10-18): with "both" selected AND at least one
     * chapter translated, the library physically holds three books — the
     * canonical interleaved one plus single-language `-jp` / `-en` twins.
     * Any other layout (or nothing translated yet) keeps just the canonical
     * book, so untranslated series never pay triple storage. Twins carry the
     * same cover/meta but a " (JP)" / " (EN)" title suffix; the canonical
     * book is untouched and stays the reader's source of truth.
     */
    private fun syncLayoutVariants(
        context: Context,
        seriesUrl: String,
        bucket: String,
        displayTitle: String,
        cover: ByteArray?,
        docs: List<ChapterDoc>,
        meta: BookMeta?,
        incomplete: Pair<Int, Int>? = null
    ) {
        try {
            val layout = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString("xlate_layout", "both") ?: "both"
            val translated = docs.any { !it.translation.isNullOrEmpty() }
            for (variant in listOf("jp", "en")) {
                val file = variantBookFile(context, seriesUrl, bucket, variant)
                if (layout == "both" && translated) {
                    writeBook(
                        file, displayTitle + " (" + variant.uppercase() + ")",
                        cover, "image/jpeg", docs, meta, variant,
                        mediaLookup = { u -> EpubMediaStore.bytes(context, u) },
                    incomplete = incomplete
                    )
                } else if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            DebugLog.log("EPUB", "layout-variant sync failed", e)
        }
    }

    /**
     * Chapters of this series whose stored doc carries an AI translation.
     * Pairs with storedCount for the "N/M translated" surfaces (reader
     * menu + detail screens).
     */
    fun translatedCount(context: Context, seriesUrl: String): Int =
        readAllStoredDocs(context, seriesUrl).count { !it.translation.isNullOrEmpty() }

    /** The stored copy of a chapter, for offline reading (any bucket). */
    fun readChapter(context: Context, seriesUrl: String, chapterUrl: String): ChapterExtract? {
        val files = bookFiles(context, seriesUrl) +
            legacyBookFiles(context, seriesUrl)
        for (f in files) {
            readChapterDoc(f, chapterUrl)?.let {
                return ChapterExtract(title = it.title, paragraphs = it.paragraphs, images = it.images)
            }
        }
        return null
    }

    /** Copy ONE bucket's book to a SAF destination (file picker). The copy
     *  is re-rendered with the user's saved paragraph layout (both/jp/en) —
     *  the stored library book always keeps the full parallel data. */
    fun exportTo(context: Context, seriesUrl: String, bucket: String, seriesTitle: String, uri: Uri): String {
        return try {
            val book = bookFile(context, seriesUrl, bucket)
            if (!book.exists()) return "No book for this volume yet"
            val docs = readAllChapterDocs(book)
            val layout = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                .getString("xlate_layout", "both") ?: "both"
            val tmp = File(context.cacheDir, "export-${System.currentTimeMillis()}.epub")
            writeBook(
                tmp, bookDisplayTitle(seriesTitle, bucket, bucketsOf(context, seriesUrl)),
                existingCover(book), "image/jpeg", orderDocs(docs), readBookMeta(book), layout,
                mediaLookup = { u -> EpubMediaStore.bytes(context, u) }
            )
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: return "Could not open the destination"
            tmp.delete()
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

    /** Share sheet with ALL of the series' books (one per volume/part, plus
     *  the layout twins when the JP+EN layout saved them). */
    fun shareIntent(context: Context, seriesUrl: String): Intent? {
        val prefix = prefixOf(seriesUrl)
        val books = bookFiles(context, seriesUrl).flatMap { b ->
            val bucket = b.name.removePrefix(prefix).removeSuffix(".epub")
            listOf(b) + listOf("jp", "en").mapNotNull { v ->
                variantBookFile(context, seriesUrl, bucket, v).takeIf { it.exists() }
            }
        }
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

/** Stored chapter → reader chapter row (label keeps cN bucket semantics). */
fun EpubStore.StoredChapter.toRelease(): Release = Release(
    chapter = label.ifBlank { title },
    group = "",
    date = "",
    url = url
)
