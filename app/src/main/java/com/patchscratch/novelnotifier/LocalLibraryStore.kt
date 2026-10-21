package com.patchscratch.novelnotifier

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * LOCAL LIBRARY provider (Wayne, 2026-10-21): an offline epub reader and
 * library that sits beside the sites with the same design language.
 *
 *   Library ≈ Site    — user-creates any number; a DEFAULT catch-all
 *                        "Library" exists from first run.
 *   Shelf   ≈ Category — the Kakuyomu CategoryStore pattern.
 *   Book    ≈ Series   — one imported .epub; chapters ride the RELEASES tab
 *                        and open in the shared reader via locallib:// URLs.
 *
 * EPUB ENGINE: zip+OPF parser with the base-path bug FIXED — the OPF's
 * directory is derived from the file path, not the XML content. Readium
 * 3.4.0 dependency is in place for a focused follow-up integration.
 */
object LocalLibraryStore {

    data class LocalLibrary(val id: String, val name: String, val storageUri: String = "")
    data class LocalBook(
        val id: String,
        val libraryId: String,
        val title: String,
        val author: String = "",
        val description: String = "",
        val fileName: String,
        val chapterCount: Int,
        val addedAt: Long,
        val translatedMeta: String = ""
    )

    private const val INDEX = "local_libraries.json"
    private const val DEFAULT_ID = "default"
    private const val DEFAULT_NAME = "Library"

    private fun indexFile(context: Context) = File(context.filesDir, INDEX)

    private fun readIndex(context: Context): JSONObject = runCatching {
        val f = indexFile(context)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun writeIndex(context: Context, root: JSONObject) {
        runCatching { indexFile(context).writeText(root.toString()) }
    }

    fun libraries(context: Context): List<LocalLibrary> {
        val root = readIndex(context)
        val arr = root.optJSONArray("libraries") ?: JSONArray()
        val out = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                LocalLibrary(o.optString("id"), o.optString("name"), o.optString("storageUri"))
            }
        }
        if (out.isNotEmpty()) return out
        val def = LocalLibrary(DEFAULT_ID, DEFAULT_NAME)
        root.put("libraries", JSONArray().put(JSONObject().put("id", def.id).put("name", def.name)))
        writeIndex(context, root)
        return listOf(def)
    }

    fun library(context: Context, id: String): LocalLibrary? =
        libraries(context).firstOrNull { it.id == id }

    fun addLibrary(context: Context, name: String): LocalLibrary {
        val root = readIndex(context)
        val arr = root.optJSONArray("libraries") ?: JSONArray().also { root.put("libraries", it) }
        val lib = LocalLibrary(UUID.randomUUID().toString().take(8), name.trim())
        arr.put(JSONObject().put("id", lib.id).put("name", lib.name))
        writeIndex(context, root)
        return lib
    }

    fun renameLibrary(context: Context, id: String, name: String) {
        mutateLibrary(context, id) { it.put("name", name.trim()) }
    }

    fun setLibraryStorage(context: Context, id: String, treeUri: String) {
        mutateLibrary(context, id) { it.put("storageUri", treeUri) }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(treeUri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun deleteLibrary(context: Context, id: String) {
        if (id == DEFAULT_ID) return
        val root = readIndex(context)
        val arr = root.optJSONArray("libraries") ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) keep.put(o)
        }
        root.put("libraries", keep)
        val books = root.optJSONArray("books") ?: JSONArray()
        for (i in 0 until books.length()) {
            books.optJSONObject(i)?.takeIf { it.optString("libraryId") == id }
                ?.put("libraryId", DEFAULT_ID)
        }
        writeIndex(context, root)
    }

    private fun mutateLibrary(context: Context, id: String, fn: (JSONObject) -> Unit) {
        val root = readIndex(context)
        val arr = root.optJSONArray("libraries") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) { fn(o); break }
        }
        writeIndex(context, root)
    }

    fun books(context: Context, libraryId: String? = null): List<LocalBook> {
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (libraryId != null && o.optString("libraryId") != libraryId) return@mapNotNull null
            LocalBook(
                id = o.optString("id"), libraryId = o.optString("libraryId"),
                title = o.optString("title"), author = o.optString("author"),
                description = o.optString("description"), fileName = o.optString("fileName"),
                chapterCount = o.optInt("chapters", 0), addedAt = o.optLong("addedAt", 0),
                translatedMeta = o.optString("translatedMeta")
            )
        }.sortedByDescending { it.addedAt }
    }

    fun book(context: Context, bookId: String): LocalBook? =
        books(context).firstOrNull { it.id == bookId }

    private fun bookEntry(context: Context, bookId: String): JSONObject? {
        val arr = readIndex(context).optJSONArray("books") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == bookId) return o
        }
        return null
    }

    /**
     * Cover for a book: cached file, or lazily extracted from the epub zip
     * (books imported before the base-path fix have no cached cover).
     */
    /**
     * Cover for a book, stored in CoverStore (keyed by the locallib:// URL)
     * so the shared CoverThumb component finds it via its standard
     * CoverStore.localFile() lookup. Books imported before the base-path
     * fix get their cover lazily extracted from the epub on first access.
     */
    fun coverFile(context: Context, bookId: String): File? {
        val seriesUrl = "locallib://$bookId"
        // CoverStore first — this is what CoverThumb checks.
        CoverStore.localFile(context, seriesUrl)?.let { return it }
        // Lazy extraction from the epub.
        val book = book(context, bookId) ?: return null
        runCatching {
            withZip(context, book) { zip, opfPath ->
                val opf = zip.getEntry(opfPath)
                    ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                    ?: return@withZip null
                val base = opfPath.substringBeforeLast("/", "")
                val coverHref = Regex("""<item[^>]+id="[^"]*cover[^"]*"[^>]+href="([^"]+)"""")
                    .find(opf)?.groupValues?.get(1)
                val coverBytes: ByteArray? = coverHref?.let { ch ->
                    val full = if (base.isBlank()) ch else "$base/$ch"
                    zip.getEntry(full)?.let { zip.getInputStream(it).readBytes() }
                } ?: zip.getEntry("OEBPS/cover.jpg")?.let { zip.getInputStream(it).readBytes() }
                  ?: zip.getEntry("OEBPS/cover.png")?.let { zip.getInputStream(it).readBytes() }
                  ?: zip.getEntry("cover.jpg")?.let { zip.getInputStream(it).readBytes() }
                  ?: zip.getEntry("cover.png")?.let { zip.getInputStream(it).readBytes() }
                coverBytes?.let { bytes ->
                    CoverStore.saveGenerated(context, seriesUrl, bytes, "jpg")
                }
                null
            }
        }
        return CoverStore.localFile(context, seriesUrl)
    }

    fun import(context: Context, libraryId: String, source: Uri): LocalBook? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val lib = library(context, libraryId) ?: return null
        val bookId = UUID.randomUUID().toString().take(10)
        val parsed = parseEpub(bytes) ?: return null
        val safeTitle = parsed.title.trim().replace(Regex("[^\\w\\-. \\u3040-\\u30ff\\u4e00-\\u9fff]"), "_").take(60)
            .ifBlank { "book-$bookId" }
        val fileName = "$safeTitle.epub"
        val stored = storeBytes(context, lib, fileName, bytes, bookId)
        parsed.coverBytes?.let { cov ->
            runCatching {
                CoverStore.saveGenerated(context, "locallib://$bookId", cov, "jpg")
            }
        }
        val book = LocalBook(
            id = bookId, libraryId = lib.id,
            title = parsed.title.ifBlank { fileName.removeSuffix(".epub") },
            author = parsed.author, description = parsed.description,
            fileName = fileName, chapterCount = parsed.chapterTitles.size,
            addedAt = System.currentTimeMillis()
        )
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: JSONArray().also { root.put("books", it) }
        arr.put(
            JSONObject().put("id", book.id).put("libraryId", book.libraryId)
                .put("title", book.title).put("author", book.author)
                .put("description", book.description).put("fileName", book.fileName)
                .put("storedUri", stored.first).put("storedPath", stored.second)
                .put("opfPath", parsed.opfPath).put("chapters", book.chapterCount)
                .put("addedAt", book.addedAt)
        )
        writeIndex(context, root)
        return book
    }

    private fun storeBytes(
        context: Context, lib: LocalLibrary, fileName: String, bytes: ByteArray, bookId: String
    ): Pair<String, String> {
        if (lib.storageUri.isNotBlank()) {
            runCatching {
                val tree = androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(context, Uri.parse(lib.storageUri))
                val doc = tree?.createFile("application/epub+zip", fileName)
                if (doc != null) {
                    context.contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
                    return doc.uri.toString() to ""
                }
            }
        }
        val dir = File(context.filesDir, "local_libraries/${lib.id}").apply { mkdirs() }
        val f = File(dir, "$bookId.epub")
        f.writeBytes(bytes)
        return "" to f.absolutePath
    }

    fun deleteBook(context: Context, bookId: String) {
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: return
        val keep = JSONArray()
        var deleted: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == bookId) deleted = o else keep.put(o)
        }
        root.put("books", keep)
        writeIndex(context, root)
        deleted?.let { d ->
            runCatching {
                when {
                    d.optString("storedUri").startsWith("content://") ->
                        androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(context, Uri.parse(d.optString("storedUri")))?.delete()
                    d.optString("storedPath").isNotBlank() -> File(d.optString("storedPath")).delete()
                }
            }
            coverFile(context, bookId)?.delete()
            // Extracted illustration cache rides with the book.
            File(context.filesDir, "local_lib_media/$bookId").deleteRecursively()
        }
    }

    fun setTranslatedMeta(context: Context, bookId: String, meta: String) {
        bookEntry(context, bookId)?.put("translatedMeta", meta)
        writeIndex(context, readIndex(context))
    }

    private fun <T> withZip(context: Context, book: LocalBook, block: (ZipFile, String) -> T?): T? {
        val entry = bookEntry(context, book.id) ?: return null
        val opfPath = entry.optString("opfPath")
        return runCatching {
            val zip: ZipFile? = when {
                entry.optString("storedUri").startsWith("content://") -> {
                    val tmp = File(context.cacheDir, "ll_${book.id}.epub")
                    if (!tmp.isFile || tmp.length() == 0L) {
                        context.contentResolver.openInputStream(Uri.parse(entry.optString("storedUri")))
                            ?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    }
                    if (tmp.isFile && tmp.length() > 0) ZipFile(tmp) else null
                }
                entry.optString("storedPath").isNotBlank() -> ZipFile(File(entry.optString("storedPath")))
                else -> null
            }
            zip?.use { block(zip, opfPath) }
        }.getOrNull()
    }

    fun chapterTitles(context: Context, book: LocalBook): List<String> {
        val result = withZip(context, book) { zip, opfPath ->
            val opfBytes = zip.getEntry(opfPath)?.let { zip.getInputStream(it).readBytes() }
                ?: return@withZip emptyList<String>()
            val opf = opfBytes.toString(Charsets.UTF_8)
            val hrefs = spineHrefs(opf, opfPath)
            hrefs.mapIndexedNotNull { i, href ->
                val doc = zip.getEntry(href)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                val t = doc?.let { Regex("<title[^>]*>([^<]*)</title>").find(it)?.groupValues?.get(1)?.trim() }
                t.takeIf { !it.isNullOrBlank() } ?: "Chapter ${i + 1}"
            }
        }
        return result ?: emptyList()
    }

    /**
     * Chapter body for the reader (locallib:// serving).
     *
     * [images] are inline illustrations (cover pages, inserted art) whose
     * bytes come from the epub zip; they render through the shared reader's
     * paragraph-anchored illustration path. An image-only page synthesizes
     * one blank paragraph — otherwise the render loop never runs and the
     * page reads as "Couldn't extract" (live-caught on an epub cover page).
     */
    data class LocalChapter(
        val title: String,
        val paragraphs: List<String>,
        val images: List<Pair<Int, String>> = emptyList()   // (paragraphIndex, file:// URL)
    )

    fun chapterText(context: Context, book: LocalBook, index: Int): LocalChapter? =
        withZip(context, book) { zip, opfPath ->
            val opfBytes = zip.getEntry(opfPath)?.let { zip.getInputStream(it).readBytes() }
                ?: return@withZip null
            val opf = opfBytes.toString(Charsets.UTF_8)
            val hrefs = spineHrefs(opf, opfPath)
            if (index < 0 || index >= hrefs.size) return@withZip null
            val doc = zip.getEntry(hrefs[index])
                ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                ?: return@withZip null
            val title = Regex("<title[^>]*>([^<]*)</title>").find(doc)
                ?.groupValues?.get(1)?.trim().orEmpty()
            val body = doc.substringAfter("<body", doc).substringAfter(">", doc)
            val paras = extractParagraphs(doc)
            val images = extractImages(context, book, zip, body, hrefs[index])
            val finalParas =
                if (paras.isEmpty() && images.isNotEmpty()) listOf("") else paras
            LocalChapter(title, finalParas, images)
        }

    /**
     * <img> and SVG-wrapped <image> elements in a chapter body → cached
     * local files. The src resolves against the chapter's own directory
     * (the same base-path discipline as spineHrefs — never the OPF's);
     * bytes land in filesDir/local_lib_media/<bookId>/ so re-opening a
     * chapter doesn't re-extract. Coil loads the returned file:// URLs
     * directly; EpubMediaStore's http-only cache stays out of it.
     */
    private fun extractImages(
        context: Context, book: LocalBook, zip: ZipFile, body: String, chapterHref: String
    ): List<Pair<Int, String>> {
        val base = chapterHref.substringBeforeLast("/", "")
        // Paragraph anchor = how many <p> opens precede the image, so art
        // sits between the paragraphs it visually belongs to.
        val pOpens = Regex("<p\\b").findAll(body).map { it.range.first }.toList()
        val out = mutableListOf<Pair<Int, String>>()
        val imgTag = Regex("""<img[^>]+src="([^"]+)"|<image[^>]+(?:xlink:)?href="([^"]+)"""")
        imgTag.findAll(body).forEach { m ->
            val src = (m.groupValues[1].ifBlank { m.groupValues[2] }).substringBefore("?")
            if (src.isBlank() || src.startsWith("http")) return@forEach
            val full = if (base.isBlank()) src else "$base/$src"
            val entry = zip.getEntry(full) ?: return@forEach
            val ext = full.substringAfterLast('.', "jpg").lowercase()
                .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
            val dir = File(context.filesDir, "local_lib_media/${book.id}").apply { mkdirs() }
            val f = File(dir, "${full.hashCode()}.$ext")
            if (!f.isFile || f.length() == 0L) {
                runCatching { zip.getInputStream(entry).use { it.copyTo(f.outputStream()) } }
            }
            if (f.isFile && f.length() > 0) {
                out.add(pOpens.count { it < m.range.first } to f.toURI().toString())
            }
        }
        return out
    }

    private fun extractParagraphs(html: String): List<String> {
        fun cleanXml(raw: String): String = raw
            .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()
        val body = html.substringAfter("<body", html).substringAfter(">", html)
        var paras = Regex("<p[^>]*>([\\s\\S]*?)</p>").findAll(body)
            .map { m -> cleanXml(m.groupValues[1]) }
            .filter { it.isNotBlank() && it.length > 1 }.toList()
        if (paras.isEmpty()) {
            paras = cleanXml(body).split(Regex("\n\\s*\n|\\n"))
                .map { it.trim() }.filter { it.isNotBlank() && it.length > 1 }
        }
        return paras
    }

    data class EpubParsed(
        val title: String, val author: String, val description: String,
        val opfPath: String, val chapterTitles: List<String>, val coverBytes: ByteArray?
    )

    private fun parseEpub(bytes: ByteArray): EpubParsed? {
        val tmp = File.createTempFile("ll_imp", ".epub")
        return try {
            tmp.writeBytes(bytes)
            ZipFile(tmp).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
                val container = zip.getInputStream(containerEntry).readBytes().toString(Charsets.UTF_8)
                val opfPath = Regex("full-path=\"([^\"]+\\.opf)\"").find(container)
                    ?.groupValues?.get(1) ?: return null
                val opfEntry = zip.getEntry(opfPath) ?: return null
                val opf = zip.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8)
                val title = Regex("<dc:title[^>]*>([^<]*)</dc:title>").find(opf)
                    ?.groupValues?.get(1)?.trim().orEmpty()
                val author = Regex("<dc:creator[^>]*>([^<]*)</dc:creator>").find(opf)
                    ?.groupValues?.get(1)?.trim().orEmpty()
                val description = Regex("<dc:description[^>]*>([\\s\\S]*?)</dc:description>")
                    .find(opf)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
                val base = opfPath.substringBeforeLast("/", "")
                val coverHref = Regex("""<item[^>]+id="[^"]*cover[^"]*"[^>]+href="([^"]+)"""")
                    .find(opf)?.groupValues?.get(1)
                val coverBytes: ByteArray? = coverHref?.let { ch ->
                    val full = if (base.isBlank()) ch else "$base/$ch"
                    zip.getEntry(full)?.let { zip.getInputStream(it).readBytes() }
                } ?: zip.getEntry("OEBPS/cover.jpg")?.let { zip.getInputStream(it).readBytes() }
                  ?: zip.getEntry("cover.jpg")?.let { zip.getInputStream(it).readBytes() }
                val hrefs = spineHrefs(opf, opfPath)
                val titles = hrefs.mapIndexedNotNull { i, href ->
                    val doc = zip.getEntry(href)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                    val t = doc?.let { Regex("<title[^>]*>([^<]*)</title>").find(it)?.groupValues?.get(1)?.trim() }
                    t.takeIf { !it.isNullOrBlank() } ?: "Chapter ${i + 1}"
                }
                EpubParsed(title, author, description, opfPath, titles, coverBytes)
            }
        } catch (e: Exception) {
            DebugLog.log("LOCALLIB", "parseEpub failed: ${e.message}")
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * OPF spine → resolved hrefs.
     * [opfPath] is the ZIP entry path ("OEBPS/content.opf"); the base
     * directory comes from IT — the live-caught bug computed it from the
     * XML CONTENT, producing garbage paths that never matched the zip.
     */
    private fun spineHrefs(opf: String, opfPath: String): List<String> {
        val base = opfPath.substringBeforeLast("/", "")
        val idrefs = Regex("""<itemref\s+idref="([^"]+)"""").findAll(opf)
            .map { it.groupValues[1] }.toList()
        return idrefs.mapNotNull { idref ->
            Regex("""<item\s+[^>]*id="$idref"[^>]*href="([^"]+)"""").find(opf)?.groupValues?.get(1)
                ?: Regex("""<item\s+[^>]*href="([^"]+)"[^>]*id="$idref"""").find(opf)?.groupValues?.get(1)
        }.map { href -> if (base.isBlank() || href.startsWith("http")) href else "$base/$href" }
    }
}
