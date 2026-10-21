package com.patchscratch.novelnotifier

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.Streamer
import org.readium.r2.streamer.publication.content.Content
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.link.Link

/**
 * LOCAL LIBRARY provider (Wayne, 2026-10-21): an offline epub reader and
 * library that sits beside the sites with the same design language.
 *
 *   Library ≈ Site    — user-creates any number; a DEFAULT catch-all
 *                        "Library" exists from first run. Each library may
 *                        point its storage at a user-chosen SAF folder.
 *   Shelf   ≈ Category — the Kakuyomu CategoryStore pattern, namespaced
 *                        per library ("local:<libId>").
 *   Book    ≈ Series   — one imported .epub; chapters ride the RELEASES tab
 *                        and open in the shared reader via locallib:// URLs.
 *
 * EPUB ENGINE: Readium Kotlin toolkit (Wayne's directive, 2026-10-21) —
 * replaces hand-rolled OPF/spine/content parsing with the tested library.
 * Publication objects are cached by book id for fast chapter serving.
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

    // Readium publication cache — one open Publication per book.
    private val publications = HashMap<String, Publication>()
    private var streamer: Streamer? = null

    private fun getStreamer(context: Context): Streamer {
        if (streamer == null) {
            streamer = Streamer(context)
        }
        return streamer!!
    }

    /** Opens (or returns cached) the Readium Publication for a book. */
    fun openPublication(context: Context, book: LocalBook): Publication? {
        publications[book.id]?.let { return it }
        val entry = bookEntry(context, book.id) ?: return null
        val source = resolveSource(context, entry) ?: return null
        return runCatching {
            val url = when (source) {
                is File -> source.toAbsoluteUrl()
                is Uri -> source.toAbsoluteUrl()
                else -> return null
            }
            getStreamer(context).open(url)
                .getOrElse { e ->
                    DebugLog.log("LOCALLIB", "Readium open failed: ${e.message}")
                    null
                }
        }.getOrNull()?.also { pub ->
            publications[book.id] = pub
        }
    }

    private fun resolveSource(context: Context, entry: JSONObject): Any? {
        val uri = entry.optString("storedUri")
        val path = entry.optString("storedPath")
        return when {
            uri.startsWith("content://") -> Uri.parse(uri)
            path.isNotBlank() -> File(path)
            else -> null
        }
    }

    // --- index persistence ----------------------------------------------------

    private fun indexFile(context: Context) = File(context.filesDir, INDEX)

    private fun readIndex(context: Context): JSONObject = runCatching {
        val f = indexFile(context)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun writeIndex(context: Context, root: JSONObject) {
        runCatching { indexFile(context).writeText(root.toString()) }
    }

    // --- libraries ---------------------------------------------------------

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

    // --- books ---------------------------------------------------------------

    fun books(context: Context, libraryId: String? = null): List<LocalBook> {
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (libraryId != null && o.optString("libraryId") != libraryId) return@mapNotNull null
            LocalBook(
                id = o.optString("id"),
                libraryId = o.optString("libraryId"),
                title = o.optString("title"),
                author = o.optString("author"),
                description = o.optString("description"),
                fileName = o.optString("fileName"),
                chapterCount = o.optInt("chapters", 0),
                addedAt = o.optLong("addedAt", 0),
                translatedMeta = o.optString("translatedMeta")
            )
        }.sortedByDescending { it.addedAt }
    }

    fun book(context: Context, bookId: String): LocalBook? =
        books(context).firstOrNull { it.id == bookId }

    // --- import (Readium-powered) -----------------------------------------------

    /**
     * Import one .epub: read from SAF, open with Readium for metadata +
     * spine, copy into the library's storage, extract the cover.
     */
    fun import(context: Context, libraryId: String, source: Uri): LocalBook? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val lib = library(context, libraryId) ?: return null
        val bookId = UUID.randomUUID().toString().take(10)

        // Write to a temp file so Readium can open it.
        val tmp = File(context.cacheDir, "ll_import_$bookId.epub")
        tmp.writeBytes(bytes)

        // Open with Readium — the engine handles all epub variants.
        val pub = runCatching {
            getStreamer(context).open(tmp.toAbsoluteUrl()).getOrNull()
        }.getOrNull() ?: run {
            tmp.delete()
            DebugLog.log("LOCALLIB", "Readium could not open import")
            return null
        }

        val title = pub.metadata.title
        val author = pub.metadata.authors.firstOrNull()?.name ?: ""
        val description = pub.metadata.description ?: ""
        val chapterCount = pub.readingOrder.size

        val safeTitle = title.trim().replace(Regex("[^\\w\\-. \\u3040-\\u30ff\\u4e00-\\u9fff]"), "_").take(60)
            .ifBlank { "book-$bookId" }
        val fileName = "$safeTitle.epub"

        // Copy into the library's chosen storage (SAF tree) or app-private.
        val stored = storeBytes(context, lib, fileName, bytes, bookId)

        // Cover → internal cache for Coil.
        runCatching {
            pub.cover(context).getOrNull()?.let { bitmap ->
                val dir = File(context.filesDir, "local_covers").apply { mkdirs() }
                val f = File(dir, "$bookId.jpg")
                f.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        }

        // Cache the Publication for immediate reading.
        publications[bookId] = pub

        val book = LocalBook(
            id = bookId, libraryId = lib.id,
            title = title.ifBlank { fileName.removeSuffix(".epub") },
            author = author, description = description,
            fileName = fileName,
            chapterCount = chapterCount,
            addedAt = System.currentTimeMillis()
        )
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: JSONArray().also { root.put("books", it) }
        arr.put(
            JSONObject()
                .put("id", book.id).put("libraryId", book.libraryId)
                .put("title", book.title).put("author", book.author)
                .put("description", book.description)
                .put("fileName", book.fileName)
                .put("storedUri", stored.first).put("storedPath", stored.second)
                .put("chapters", book.chapterCount)
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

    private fun bookEntry(context: Context, bookId: String): JSONObject? {
        val arr = readIndex(context).optJSONArray("books") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == bookId) return o
        }
        return null
    }

    fun coverFile(context: Context, bookId: String): File? =
        File(File(context.filesDir, "local_covers"), "$bookId.jpg").takeIf { it.isFile }

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
                val uri = d.optString("storedUri")
                val path = d.optString("storedPath")
                when {
                    uri.startsWith("content://") ->
                        androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(context, Uri.parse(uri))?.delete()
                    path.isNotBlank() -> File(path).delete()
                }
            }
            coverFile(context, bookId)?.delete()
        }
        publications.remove(bookId)
    }

    fun setTranslatedMeta(context: Context, bookId: String, meta: String) {
        bookEntry(context, bookId)?.put("translatedMeta", meta)?.let {
            writeIndex(context, readIndex(context))
        }
    }

    // --- reading (Readium-powered) ------------------------------------------------

    /** Spine chapter titles for the RELEASES tab. */
    fun chapterTitles(context: Context, book: LocalBook): List<String> {
        val pub = openPublication(context, book) ?: return emptyList()
        return pub.readingOrder.mapIndexed { i, link ->
            link.title?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}"
        }
    }

    /**
     * Chapter body → paragraphs for the reader (locallib:// serving).
     * Returns (title, paragraphs). Readium reads the resource; we split
     * the XHTML into paragraphs.
     */
    fun chapterText(context: Context, book: LocalBook, index: Int): Pair<String, List<String>>? {
        val pub = openPublication(context, book) ?: run {
            DebugLog.log("LOCALLIB", "chapterText: no publication for ${book.id}")
            return null
        }
        val link = pub.readingOrder.getOrNull(index) ?: run {
            DebugLog.log("LOCALLIB", "chapterText: index $index out of ${pub.readingOrder.size}")
            return null
        }

        // Read the raw resource (XHTML content).
        val resource = pub.get(link) ?: run {
            DebugLog.log("LOCALLIB", "chapterText: no resource for ${link.href}")
            return null
        }
        val bytes = runCatching { resource.read().getOrNull() }.getOrNull() ?: run {
            DebugLog.log("LOCALLIB", "chapterText: resource read failed")
            return null
        }
        val html = bytes.toString(Charsets.UTF_8)
        val title = link.title?.trim().orEmpty()

        // Extract paragraphs from the XHTML — handles <p>, <div>, and
        // <br>-split text (the same logic as before, but on Readium's
        // correctly-resolved content).
        return title to extractParagraphs(html)
    }

    private fun extractParagraphs(html: String): List<String> {
        fun cleanXml(raw: String): String = raw
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&nbsp;", " ")
            .trim()

        val body = html.substringAfter("<body", html).substringAfter(">", html)

        // Primary: <p> tags (the standard paragraph element).
        var paras = Regex("<p[^>]*>([\\s\\S]*?)</p>").findAll(body)
            .map { m -> cleanXml(m.groupValues[1]) }
            .filter { it.isNotBlank() && it.length > 1 }
            .toList()

        // Fallback: <div> or bare text split on newlines.
        if (paras.isEmpty()) {
            val text = cleanXml(body)
            paras = text.split(Regex("\n\\s*\n|\\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 1 }
        }
        return paras
    }
}
