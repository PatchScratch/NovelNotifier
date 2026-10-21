package com.patchscratch.novelnotifier

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * LOCAL LIBRARY provider (Wayne, 2026-10-21): an offline epub reader and
 * library that sits beside the sites with the same design language.
 *
 *   Library ≈ Site    — user-creates any number (Fiction, Light Novel,
 *                        Non-fiction…); a DEFAULT catch-all "Library" exists
 *                        from first run. Each library may point its storage
 *                        at a user-chosen SAF folder (persisted permission);
 *                        unset = app-private storage.
 *   Shelf   ≈ Category — client-side, the Kakuyomu CategoryStore pattern,
 *                        namespaced per library ("local:<libId>").
 *   Book    ≈ Series   — one imported .epub; chapters ride the RELEASES tab
 *                        and open in the shared reader via locallib:// URLs.
 *
 * Index: filesDir/local_libraries.json. Epubs live under the library's
 * storage location; covers cache to filesDir/local_covers for Coil.
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
        // First touch: the catch-all.
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
        if (id == DEFAULT_ID) return  // the catch-all stays
        val root = readIndex(context)
        val arr = root.optJSONArray("libraries") ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) keep.put(o)
        }
        root.put("libraries", keep)
        // Books of the deleted library move to the catch-all.
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

    // --- import -----------------------------------------------------------------

    /**
     * Import one .epub: read from the SAF uri, parse metadata, copy into
     * the library's storage (tree or app-private), extract the cover.
     */
    fun import(context: Context, libraryId: String, source: Uri): LocalBook? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val parsed = parseEpub(bytes) ?: return null
        val lib = library(context, libraryId) ?: return null
        val bookId = UUID.randomUUID().toString().take(10)

        val safeTitle = parsed.title.trim().replace(Regex("[^\\w\\-. \\u3040-\\u30ff\\u4e00-\\u9fff]"), "_").take(60)
            .ifBlank { "book-$bookId" }
        val fileName = "$safeTitle.epub"

        // Copy into the library's chosen storage (SAF tree) or app-private.
        val stored = storeBytes(context, lib, fileName, bytes, bookId)

        // Cover → internal cache for Coil.
        parsed.coverBytes?.let { cov ->
            runCatching {
                val dir = File(context.filesDir, "local_covers").apply { mkdirs() }
                File(dir, "$bookId.jpg").writeBytes(cov)
            }
        }

        val book = LocalBook(
            id = bookId, libraryId = lib.id,
            title = parsed.title.ifBlank { fileName.removeSuffix(".epub") },
            author = parsed.author, description = parsed.description,
            fileName = fileName,
            chapterCount = parsed.chapterTitles.size,
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
                .put("opfPath", parsed.opfPath)
                .put("chapters", book.chapterCount)
                .put("addedAt", book.addedAt)
        )
        writeIndex(context, root)
        return book
    }

    private fun storeBytes(
        context: Context, lib: LocalLibrary, fileName: String, bytes: ByteArray, bookId: String
    ): Pair<String, String> {
        // SAF tree: create + write via DocumentFile.
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
        // Fallback: app-private.
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
        // Remove the epub + cover.
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
    }

    fun setTranslatedMeta(context: Context, bookId: String, meta: String) {
        bookEntry(context, bookId)?.put("translatedMeta", meta)?.let {
            writeIndex(context, readIndex(context)) // rewrite with the mutated entry
        }
    }

    // --- reading ------------------------------------------------------------------

    /** Spine chapter titles for the RELEASES tab. */
    fun chapterTitles(context: Context, book: LocalBook): List<String> {
        val result = withZip(context, book) { zip, opfPath ->
            val opf = zip.getEntry(opfPath)
                ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                ?: return@withZip emptyList<String>()
            val hrefs = spineHrefs(opf)
            hrefs.mapIndexedNotNull { i, href ->
                val doc = zip.getEntry(href)
                    ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                val t = doc?.let {
                    Regex("<title[^>]*>([^<]*)</title>").find(it)?.groupValues?.get(1)?.trim()
                }
                t.takeIf { !it.isNullOrBlank() } ?: "Chapter ${i + 1}"
            }
        }
        return result ?: emptyList()
    }

    /**
     * Chapter body → paragraphs for the reader (locallib:// serving).
     * Returns (title, paragraphs). Handles <p>, <div>, <span>, and <br>
     * markup — epubs vary widely; the extraction tries <p> first (the
     * cleanest signal) then falls back to <br>-split div text.
     */
    fun chapterText(context: Context, book: LocalBook, index: Int): Pair<String, List<String>>? =
        withZip(context, book) { zip, opfPath ->
            DebugLog.log("LOCALLIB", "withZip: opfPath=$opfPath")
            val opfBytes = zip.getEntry(opfPath)?.let { zip.getInputStream(it).readBytes() }
            if (opfBytes == null) {
                DebugLog.log("LOCALLIB", "OPF entry not found: $opfPath; entries=${zip.entries().toList().take(5).map { it.name }}")
                return@withZip null
            }
            val opf = opfBytes.toString(Charsets.UTF_8)
            val hrefs = spineHrefs(opf)
            DebugLog.log("LOCALLIB", "spine: ${hrefs.size} hrefs; first=${hrefs.firstOrNull()?.take(60)}")
            if (index < 0 || index >= hrefs.size) {
                DebugLog.log("LOCALLIB", "index $idx out of range (0..${hrefs.size - 1})")
                return@withZip null
            }
            val entry = zip.getEntry(hrefs[index])
            if (entry == null) {
                DebugLog.log("LOCALLIB", "chapter entry not in zip: ${hrefs[index]}")
                DebugLog.log("LOCALLIB", "zip has: ${zip.entries().asSequence().take(8).map { it.name }.joinToString(", ")}")
                return@withZip null
            }
            val doc = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            val title = Regex("<title[^>]*>([^<]*)</title>").find(doc)
                ?.groupValues?.get(1)?.trim().orEmpty()

            fun cleanXml(raw: String): String = raw
                .replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ")
                .trim()

            val body = doc.substringAfter("<body", doc).substringAfter(">", doc)

            // Primary: <p> tags (the standard paragraph element).
            var paras = Regex("<p[^>]*>([\\s\\S]*?)</p>").findAll(body)
                .map { m -> cleanXml(m.groupValues[1]) }
                .filter { it.isNotBlank() && it.length > 1 }
                .toList()

            // Fallback: epubs using <div> or <span> for paragraphs — split
            // the body text on <br> boundaries or double newlines.
            if (paras.isEmpty()) {
                val text = cleanXml(body)
                paras = text.split(Regex("\n\\s*\n|\\n"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 1 }
                if (paras.size == 1 && paras[0].length > 5000) {
                    // One giant block: split on sentence-like boundaries.
                    paras = paras[0].split(Regex("(?<=[.!?。！？])\\s+"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
            }
            title to paras
        }

    /** Opens the book's zip (SAF → cached temp copy; file → direct). */
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
                entry.optString("storedPath").isNotBlank() ->
                    ZipFile(File(entry.optString("storedPath")))
                else -> null
            }
            zip?.use { block(zip, opfPath) }
        }.getOrNull()
    }

    // --- epub parse (pure JVM) -----------------------------------------------------

    data class EpubParsed(
        val title: String, val author: String, val description: String,
        val opfPath: String, val chapterTitles: List<String>, val coverBytes: ByteArray?
    )

    fun parseEpub(bytes: ByteArray): EpubParsed? {
        val tmp = File.createTempFile("ll_import", ".epub")
        tmp.writeBytes(bytes)
        val result = runCatching {
        val zip = ZipFile(tmp)
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return@runCatching null
        val container = zip.getInputStream(containerEntry).readBytes().toString(Charsets.UTF_8)
        val opfPath = Regex("full-path=\"([^\"]+\\.opf)\"").find(container)
            ?.groupValues?.get(1) ?: return@runCatching null
        val opfEntry = zip.getEntry(opfPath) ?: return@runCatching null
        val opf = zip.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8)

        val title = Regex("<dc:title[^>]*>([^<]*)</dc:title>").find(opf)
            ?.groupValues?.get(1)?.trim().orEmpty()
        val author = Regex("<dc:creator[^>]*>([^<]*)</dc:creator>").find(opf)
            ?.groupValues?.get(1)?.trim().orEmpty()
        val description = Regex("<dc:description[^>]*>([\\s\\S]*?)</dc:description>")
            .find(opf)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()

        // Cover: the manifest's cover-image, or cover.jpg convention.
        val coverHref = Regex("""<item[^>]+id="[^"]*cover[^"]*"[^>]+href="([^"]+)"""")
            .find(opf)?.groupValues?.get(1)
        val base = opfPath.substringBeforeLast("/", "")
        val coverBytes: ByteArray? = coverHref?.let { ch ->
            val full = if (base.isBlank()) ch else "$base/$ch"
            zip.getEntry(full)?.let { e ->
                ByteArrayOutputStream().use { out -> zip.getInputStream(e).copyTo(out); out.toByteArray() }
            }
        } ?: zip.getEntry("OEBPS/cover.jpg")?.let { e ->
            ByteArrayOutputStream().use { out -> zip.getInputStream(e).copyTo(out); out.toByteArray() }
        } ?: zip.getEntry("cover.jpg")?.let { e ->
            ByteArrayOutputStream().use { out -> zip.getInputStream(e).copyTo(out); out.toByteArray() }
        }

        // Spine titles.
        val hrefs = spineHrefs(opf)
        val titles = hrefs.mapIndexedNotNull { i, href ->
            val doc = zip.getEntry(href)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
            val t = doc?.let { Regex("<title[^>]*>([^<]*)</title>").find(it)?.groupValues?.get(1)?.trim() }
            t.takeIf { !it.isNullOrBlank() } ?: "Chapter ${i + 1}"
        }

        zip.close()
        tmp.delete()
        EpubParsed(title, author, description, opfPath, titles, coverBytes)
        }.getOrNull()
        tmp.delete()
        return result
    }

    /** OPF spine → resolved hrefs (relative to the OPF's directory). */
    private fun spineHrefs(opf: String): List<String> {
        val base = opf.substringBeforeLast("/", "")
        val idrefs = Regex("""<itemref\s+idref="([^"]+)"""").findAll(opf)
            .map { it.groupValues[1] }.toList()
        return idrefs.mapNotNull { idref ->
            Regex("""<item\s+[^>]*id="$idref"[^>]*href="([^"]+)"""").find(opf)?.groupValues?.get(1)
                ?: Regex("""<item\s+[^>]*href="([^"]+)"[^>]*id="$idref"""").find(opf)?.groupValues?.get(1)
        }.map { href -> if (base.isBlank() || href.startsWith("http")) href else "$base/$href" }
    }
}
