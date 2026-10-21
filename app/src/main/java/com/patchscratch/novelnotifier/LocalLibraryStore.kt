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
        val translatedMeta: String = "",
        // Manual series grouping override (v3.0 B3): blank = auto-key from
        // the title; "solo:<id>" = pinned out of every group; otherwise the
        // effective series key of the book it was grouped with.
        val seriesOverride: String = "",
        // Linked book: the epub is READ IN PLACE from its on-device URI —
        // never copied, and deleteBook must NOT remove the original file.
        val linked: Boolean = false
    )

    /** A series: the unit the grid shows and shelves hold (Wayne, 2026-10-21:
     *  "Library = Sites, Shelves = Categories, Series = Series"). [key] is
     *  the auto-match key imports join on; [description] is a display
     *  override — epub files are never rewritten. */
    data class LocalSeries(
        val id: String,
        val libraryId: String,
        val name: String,
        val key: String,
        val description: String = ""
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
        // Any write can change what the caches vouch for — drop them all.
        invalidateCaches()
    }

    // libraries() runs on every drawer open (SiteSwitcherChips) — cached
    // in-process; library mutations invalidate.
    @Volatile private var librariesCache: List<LocalLibrary>? = null

    fun libraries(context: Context): List<LocalLibrary> {
        librariesCache?.let { return it }
        val root = readIndex(context)
        val out = parseLibraries(root)
        if (out.isNotEmpty()) {
            librariesCache = out
            return out
        }
        val def = LocalLibrary(DEFAULT_ID, DEFAULT_NAME)
        root.put("libraries", JSONArray().put(JSONObject().put("id", def.id).put("name", def.name)))
        writeIndex(context, root)
        librariesCache = listOf(def)
        return listOf(def)
    }

    fun invalidateCaches() {
        librariesCache = null
        migrated = false
        metaCleaned = false
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

    // Structural one-shots (migration, metadata cleanup) re-scan the whole
    // index on every call — fine once, deadly per keystroke on a library of
    // thousands. Process-level done-flags: after the first pass they no-op.
    @Volatile private var migrated = false
    @Volatile private var metaCleaned = false

    fun books(context: Context, libraryId: String? = null): List<LocalBook> =
        parseBooks(readIndexOnce(context), libraryId)

    /** Migration/cleanup + ONE index parse — the common read path. */
    private fun readIndexOnce(context: Context): JSONObject {
        if (!migrated) { ensureSeriesMigration(context); migrated = true }
        if (!metaCleaned) { cleanIndexMeta(context); metaCleaned = true }
        return readIndex(context)
    }

    internal fun parseBooks(root: JSONObject, libraryId: String?): List<LocalBook> {
        val arr = root.optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (libraryId != null && o.optString("libraryId") != libraryId) return@mapNotNull null
            LocalBook(
                id = o.optString("id"), libraryId = o.optString("libraryId"),
                title = o.optString("title"), author = o.optString("author"),
                description = o.optString("description"), fileName = o.optString("fileName"),
                chapterCount = o.optInt("chapters", 0), addedAt = o.optLong("addedAt", 0),
                translatedMeta = o.optString("translatedMeta"),
                seriesOverride = o.optString("seriesOverride"),
                linked = o.optBoolean("ref", false)
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
                    val full = zipPath(base, ch)
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

    /**
     * Import an epub. Copy mode stores bytes in the library's storage
     * (SAF tree or app-private dir); [linkInPlace] leaves the file where
     * it is and reads it through a persistable URI grant — the parse
     * bytes double as withZip's cache copy so the first open is warm.
     */
    fun import(
        context: Context, libraryId: String, source: Uri, linkInPlace: Boolean = false
    ): LocalBook? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val lib = library(context, libraryId) ?: return null
        val bookId = UUID.randomUUID().toString().take(10)
        val parsed = parseEpub(bytes) ?: return null
        val safeTitle = parsed.title.trim().replace(Regex("[^\\w\\-. \\u3040-\\u30ff\\u4e00-\\u9fff]"), "_").take(60)
            .ifBlank { "book-$bookId" }
        val fileName = "$safeTitle.epub"
        val stored: Pair<String, String> = if (linkInPlace) {
            // Per-file persistable grants cap at ~128 system-wide — mass
            // linking through the file picker dies there. Folder links
            // hold ONE tree grant that covers every child, so there is
            // nothing to take per file here.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    source, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            source.toString() to ""
        } else {
            storeBytes(context, lib, fileName, bytes, bookId)
        }
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
            addedAt = System.currentTimeMillis(), linked = linkInPlace
        )
        ensureSeriesMigration(context)
        val rootNow = readIndex(context)
        // Join the series whose key matches (auto-grouping); otherwise this
        // import founds a new one. Solo pins are gone — membership is direct.
        val key = seriesKey(book.title)
        val seriesArr = rootNow.optJSONArray("series") ?: JSONArray().also { rootNow.put("series", it) }
        var seriesId: String? = null
        for (i in 0 until seriesArr.length()) {
            val o = seriesArr.optJSONObject(i) ?: continue
            if (o.optString("libraryId") == lib.id && o.optString("key") == key) {
                seriesId = o.optString("id"); break
            }
        }
        if (seriesId == null) {
            seriesId = "s" + UUID.randomUUID().toString().take(8)
            seriesArr.put(
                JSONObject().put("id", seriesId).put("libraryId", lib.id)
                    .put("name", strippedTitle(book.title).ifBlank { book.title })
                    .put("key", key)
            )
        }
        val arr = rootNow.optJSONArray("books") ?: JSONArray().also { rootNow.put("books", it) }
        arr.put(
            JSONObject().put("id", book.id).put("libraryId", book.libraryId)
                .put("title", book.title).put("author", book.author)
                .put("description", book.description).put("fileName", book.fileName)
                .put("storedUri", stored.first).put("storedPath", stored.second)
                .put("opfPath", parsed.opfPath).put("chapters", book.chapterCount)
                .put("addedAt", book.addedAt).put("ref", linkInPlace)
                .put("seriesId", seriesId)
        )
        writeIndex(context, rootNow)
        return book
    }

    /**
     * Link every .epub under [treeUri] (recursive). ONE persistable tree
     * grant covers all descendants — per-file grants from the multi-picker
     * cap at ~128 and broke large libraries (Wayne, 2026-10-21). Files
     * already linked are skipped so the folder can be re-run safely.
     * Returns (linked, skipped). Runs on the caller's IO dispatcher.
     */
    fun linkFolder(
        context: Context, libraryId: String, treeUri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Pair<Int, Int> {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: return 0 to 0
        val files = mutableListOf<androidx.documentfile.provider.DocumentFile>()
        fun collect(dir: androidx.documentfile.provider.DocumentFile) {
            dir.listFiles().forEach { f ->
                when {
                    f.isDirectory -> collect(f)
                    f.name?.endsWith(".epub", ignoreCase = true) == true -> files.add(f)
                }
            }
        }
        runCatching { collect(tree) }
        if (files.isEmpty()) return 0 to 0
        val seen = run {
            val arr = readIndex(context).optJSONArray("books") ?: JSONArray()
            val set = HashSet<String>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("storedUri")?.takeIf { it.isNotBlank() }
                    ?.let { set.add(it) }
            }
            set
        }
        var linked = 0
        var skipped = 0
        files.forEachIndexed { i, f ->
            onProgress(i + 1, files.size)
            val uri = f.uri.toString()
            if (uri in seen || import(context, libraryId, f.uri, linkInPlace = true) == null) {
                skipped++
            } else {
                seen.add(uri)
                linked++
            }
        }
        return linked to skipped
    }

    /**
     * ePub3 metadata can carry HTML — titles like "Volume 2 &lt;strong&gt;
     * The Quest&lt;/strong&gt;" leaked into rows verbatim (Wayne's tablet,
     * 2026-10-21). Unescape entities, strip any tags, collapse whitespace.
     */
    internal fun cleanMeta(raw: String): String {
        var t = raw
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
        t = Regex("&#(\\d+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        t = t.replace("&amp;", "&")   // last — avoids double-unescaping
        t = t.replace(Regex("<[^>]+>"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Idempotent index cleanup: books imported before cleanMeta() carry
     * escaped HTML in title/author/description, and series names/keys
     * inherited it. Keys recompute from volume 1's cleaned title so future
     * imports still auto-join. Writes only when something changed.
     */
    private fun cleanIndexMeta(context: Context) {
        val root = readIndex(context)
        var dirty = false
        val books = root.optJSONArray("books")
        val titlesBySeries = HashMap<String, MutableList<String>>()
        for (i in 0 until (books?.length() ?: 0)) {
            val o = books!!.optJSONObject(i) ?: continue
            listOf("title", "author", "description").forEach { f ->
                val v = o.optString(f)
                val c = cleanMeta(v)
                if (c != v) { o.put(f, c); dirty = true }
            }
            titlesBySeries.getOrPut(o.optString("seriesId")) { mutableListOf() }
                .add(o.optString("title"))
        }
        val series = root.optJSONArray("series")
        for (i in 0 until (series?.length() ?: 0)) {
            val o = series!!.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val cleaned = cleanMeta(name)
            if (cleaned != name) { o.put("name", cleaned); dirty = true }
            val vol1 = titlesBySeries[o.optString("id")]
                ?.sortedWith(compareBy({ volumeNumber(it) }, { it }))?.firstOrNull()
            if (vol1 != null) {
                val k = seriesKey(vol1)
                if (k != o.optString("key")) { o.put("key", k); dirty = true }
            }
        }
        if (dirty) writeIndex(context, root)
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
            // LINKED books: the epub is the user's own file somewhere on the
            // device — delete only OUR cache and release the read grant.
            // Copied books: remove the library's copy as before.
            val linked = d.optBoolean("ref", false)
            runCatching {
                if (!linked) {
                    when {
                        d.optString("storedUri").startsWith("content://") ->
                            androidx.documentfile.provider.DocumentFile
                                .fromSingleUri(context, Uri.parse(d.optString("storedUri")))?.delete()
                        d.optString("storedPath").isNotBlank() -> File(d.optString("storedPath")).delete()
                    }
                } else if (d.optString("storedUri").startsWith("content://")) {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(d.optString("storedUri")),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            File(context.cacheDir, "ll_$bookId.epub").delete()
            coverFile(context, bookId)?.delete()
            // Extracted illustration cache rides with the book.
            File(context.filesDir, "local_lib_media/$bookId").deleteRecursively()
        }
    }

    // Both setters below hold ONE parsed index root across read-modify-write
    // — re-reading inside writeIndex() would parse a fresh tree and drop
    // the mutation (shipped silently: translatedMeta never persisted).
    fun setTranslatedMeta(context: Context, bookId: String, meta: String) {
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == bookId) { o.put("translatedMeta", meta); break }
        }
        writeIndex(context, root)
    }

    /** Volume metadata edit (index-level — epubs on disk never change). */
    fun setBookMeta(
        context: Context, bookId: String,
        title: String? = null, author: String? = null, description: String? = null
    ) {
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == bookId) {
                title?.takeIf { it.isNotBlank() }?.let { o.put("title", cleanMeta(it)) }
                author?.let { o.put("author", cleanMeta(it)) }
                description?.let { o.put("description", cleanMeta(it)) }
                break
            }
        }
        writeIndex(context, root)
    }

    /**
     * Index v3 migration (one-shot): every book lands in an explicit series
     * (grouped by the old effective key; solo-override books get their own).
     * Shelf assignments re-key bookId → seriesId in the same pass so nothing
     * already shelved falls off. Detected by the absence of "series".
     */
    private fun ensureSeriesMigration(context: Context) {
        val root = readIndex(context)
        if (root.has("series")) return
        val booksArr = root.optJSONArray("books") ?: JSONArray().also { root.put("books", it) }
        val bookObjs = (0 until booksArr.length()).mapNotNull { booksArr.optJSONObject(it) }
        if (bookObjs.isEmpty()) { root.put("series", JSONArray()); writeIndex(context, root); return }
        val byKey = bookObjs.groupBy { o ->
            val override = o.optString("seriesOverride")
            when {
                override.startsWith("solo:") -> "solo:" + o.optString("id")
                override.isNotBlank() -> override
                else -> o.optString("libraryId") + "|||" + seriesKey(o.optString("title"))
            }
        }
        val seriesArr = JSONArray()
        val bookToSeries = HashMap<String, String>()
        byKey.values.forEach { group ->
            val first = group.first()
            val sid = "s" + UUID.randomUUID().toString().take(8)
            seriesArr.put(
                JSONObject().put("id", sid).put("libraryId", first.optString("libraryId"))
                    .put("name", strippedTitle(first.optString("title")).ifBlank { first.optString("title") })
                    .put("key", seriesKey(first.optString("title")))
            )
            group.forEach { it.put("seriesId", sid); bookToSeries[it.optString("id")] = sid }
        }
        root.put("series", seriesArr)
        writeIndex(context, root)
        // Re-key shelf assignments to series ids.
        val libs = root.optJSONArray("libraries") ?: JSONArray()
        for (i in 0 until libs.length()) {
            val libId = libs.optJSONObject(i)?.optString("id") ?: continue
            val st = CategoryStore.load(context, "local:$libId")
            if (st.assign.isEmpty()) continue
            val rekeyed = st.assign.mapKeys { (bookId, _) -> bookToSeries[bookId] ?: bookId }
            if (rekeyed != st.assign) CategoryStore.save(context, "local:$libId", st.copy(assign = rekeyed))
        }
    }

    /**
     * Everything the library screen renders from, in ONE index read plus
     * cover/read-position resolution — built on the caller's IO dispatcher
     * and held in Compose state. The screen (and search) then filters pure
     * in-memory lists: zero store IO per recomposition or keystroke
     * (Wayne, 2026-10-21: thousands of books froze the grid for seconds).
     * Covers with no cached file get their one-time zip extraction HERE,
     * never mid-scroll.
     */
    data class LibrarySnapshot(
        val library: LocalLibrary?,
        val books: List<LocalBook>,
        val series: List<LocalSeries>,
        val seriesIdOf: Map<String, String>,    // bookId → seriesId
        val coverPathOf: Map<String, String>,   // bookId → absolute path ("" = none)
        val readPosOf: Map<String, Int>         // bookId → saved reader position
    )

    fun snapshot(context: Context, libraryId: String): LibrarySnapshot {
        val root = readIndexOnce(context)
        val books = parseBooks(root, libraryId)
        val series = parseSeries(root, libraryId)
        val sidMap = HashMap<String, String>()
        val bArr = root.optJSONArray("books")
        for (i in 0 until (bArr?.length() ?: 0)) {
            val o = bArr!!.optJSONObject(i) ?: continue
            val sid = o.optString("seriesId").takeIf { it.isNotBlank() } ?: continue
            sidMap[o.optString("id")] = sid
        }
        val coverPaths = HashMap<String, String>(books.size)
        books.forEach { b ->
            coverPaths[b.id] = coverFile(context, b.id)?.absolutePath ?: ""
        }
        val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
        val positions = books.associate { it.id to prefs.getInt("local_pos_${it.id}", 0) }
        return LibrarySnapshot(
            library = parseLibraries(root).firstOrNull { it.id == libraryId },
            books = books, series = series, seriesIdOf = sidMap,
            coverPathOf = coverPaths, readPosOf = positions
        )
    }

    fun seriesList(context: Context, libraryId: String? = null): List<LocalSeries> =
        parseSeries(readIndexOnce(context), libraryId)

    internal fun parseSeries(root: JSONObject, libraryId: String?): List<LocalSeries> {
        val arr = root.optJSONArray("series") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                if (libraryId != null && o.optString("libraryId") != libraryId) return@mapNotNull null
                LocalSeries(
                    o.optString("id"), o.optString("libraryId"),
                    o.optString("name"), o.optString("key"), o.optString("description")
                )
            }
        }
    }

    private fun parseLibraries(root: JSONObject): List<LocalLibrary> {
        val arr = root.optJSONArray("libraries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                LocalLibrary(o.optString("id"), o.optString("name"), o.optString("storageUri"))
            }
        }
    }

    fun seriesById(context: Context, id: String): LocalSeries? =
        seriesList(context).firstOrNull { it.id == id }

    /** The series' volumes in volume order (volume number, then addedAt). */
    fun seriesVolumesOf(context: Context, seriesId: String): List<LocalBook> {
        ensureSeriesMigration(context)
        val map = seriesIdMap(context)
        return books(context).filter { map[it.id] == seriesId }
            .sortedWith(compareBy({ volumeNumber(it.title) }, { it.addedAt }))
    }

    /** bookId → seriesId in one index read (migration guarantees one). */
    fun seriesIdMap(context: Context): Map<String, String> {
        val arr = readIndex(context).optJSONArray("books") ?: return emptyMap()
        val m = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sid = o.optString("seriesId").takeIf { it.isNotBlank() } ?: continue
            m[o.optString("id")] = sid
        }
        return m
    }

    fun renameSeries(context: Context, id: String, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        mutateSeries(context, id) { it.put("name", clean) }
    }

    fun setSeriesDescription(context: Context, id: String, description: String) {
        mutateSeries(context, id) { it.put("description", description.trim()) }
    }

    private fun mutateSeries(context: Context, id: String, fn: (JSONObject) -> Unit) {
        val root = readIndex(context)
        val arr = root.optJSONArray("series") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) { fn(o); break }
        }
        writeIndex(context, root)
    }

    /**
     * Merge [fromId] INTO [toId]: every volume moves, then the emptied
     * series entry goes away (no files touched). The manual fix for
     * auto-grouping misses (Wayne, 2026-10-21: "Vol. 6 Under By the Grace
     * of the Gods" landed as its own series).
     */
    fun mergeSeries(context: Context, fromId: String, toId: String) {
        if (fromId == toId) return
        seriesVolumesOf(context, fromId).forEach { moveBookToSeries(context, it.id, toId) }
        deleteSeries(context, fromId)
    }

    /** Deletes the series AND every volume in it (epub files only when copied). */
    fun deleteSeries(context: Context, id: String) {
        seriesVolumesOf(context, id).forEach { deleteBook(context, it.id) }
        val root = readIndex(context)
        val arr = root.optJSONArray("series") ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) keep.put(o)
        }
        root.put("series", keep)
        writeIndex(context, root)
    }

    /**
     * Move a volume to another series; null spawns a fresh solo series —
     * this replaces the old override/solo machinery with direct membership.
     */
    fun moveBookToSeries(context: Context, bookId: String, seriesId: String?) {
        val root = readIndex(context)
        val books = root.optJSONArray("books")
        var book: JSONObject? = null
        for (i in 0 until (books?.length() ?: 0)) {
            val o = books!!.optJSONObject(i) ?: continue
            if (o.optString("id") == bookId) { book = o; break }
        }
        if (book == null) return
        val target = seriesId ?: run {
            val sid = "s" + UUID.randomUUID().toString().take(8)
            val seriesArr = root.optJSONArray("series") ?: JSONArray().also { root.put("series", it) }
            seriesArr.put(
                JSONObject().put("id", sid).put("libraryId", book.optString("libraryId"))
                    .put("name", strippedTitle(book.optString("title")).ifBlank { book.optString("title") })
                    .put("key", seriesKey(book.optString("title")))
            )
            book.put("seriesOverride", "")  // solo pins are obsolete under v3
            sid
        }
        book.put("seriesId", target)
        book.put("seriesOverride", "")
        writeIndex(context, root)
    }

    /**
     * Auto-grouping key: the title with its trailing volume token stripped
     * ("Re:Zero −Starting Life− Vol. 2" → "Re:Zero −Starting Life−").
     * Lowercased for MATCHING only — display name = [strippedTitle].
     */
    fun seriesKey(title: String): String = strippedTitle(title).lowercase()

    fun strippedTitle(title: String): String {
        var t = title.trim()
        // Explicit markers: Vol./Volume/v/Book/第 + digits (+ optional 巻/卷).
        t = Regex(
            """\s*[-–—:：]?\s*(?:Vol(?:ume)?\.?|v\.?|Book|第)\s*\d+\s*[巻卷]?\s*$""",
            RegexOption.IGNORE_CASE
        ).replace(t, "")
        // Trailing "(4)" / "[4]".
        t = Regex("""\s*[\(\[]\s*\d+\s*[\)\]]\s*$""").replace(t, "")
        // Bare trailing number ("Title 12") — only when a real name remains
        // (the series "86" must not collapse to "").
        t = Regex("""\s+\d{1,3}\s*$""").replace(t, "")
        // Edition qualifiers that differ across volumes of one work
        // ("… (Light Novel) Vol. 6" vs plain "…") split the auto key.
        t = Regex("""\s*\((?:light novel|novel|ln)\)\s*$""", RegexOption.IGNORE_CASE).replace(t, "")
        return t.trim()
    }

    /** Volume ordinal for ordering; MAX_VALUE when absent → addedAt order. */
    fun volumeNumber(title: String): Int = Regex(
        """(?:Vol(?:ume)?\.?|v\.?|Book|第)\s*(\d+)|[\(\[]\s*(\d+)\s*[\)\]]|\s(\d{1,3})\s*$""",
        RegexOption.IGNORE_CASE
    ).find(title)?.let { m ->
        (m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] })
            .toIntOrNull()
    } ?: Int.MAX_VALUE

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
            val images = extractImages(context, book, zip, body, hrefs[index], paras.size)
            val finalParas =
                if (paras.isEmpty() && images.isNotEmpty()) listOf("") else paras
            LocalChapter(title, finalParas, images)
        }

    /**
     * Resolve an epub-internal href against the chapter/OPF base into a
     * real zip entry path: chapter files sit in subfolders and reference
     * "../images/x.jpg" / "./img/x.jpg" — a naive join produces paths the
     * zip never contained, so illustrations silently vanished (Wayne,
     * 2026-10-21: "images are still not showing in the reader").
     */
    internal fun zipPath(base: String, src: String): String {
        val joined = if (base.isBlank()) src else "$base/$src"
        val parts = ArrayDeque<String>()
        joined.split("/").forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }
    /**
     * <img> and SVG-wrapped <image> elements in a chapter body → cached
     * local files. The src resolves against the chapter's own directory
     * via [zipPath] (../ and ./ segments normalized); bytes land in
     * filesDir/local_lib_media/<bookId>/ so re-opening a chapter doesn't
     * re-extract. Coil loads the returned file:// URLs directly.
     */
    private fun extractImages(
        context: Context, book: LocalBook, zip: ZipFile, body: String, chapterHref: String,
        paragraphCount: Int
    ): List<Pair<Int, String>> {
        val base = chapterHref.substringBeforeLast("/", "")
        // Paragraph anchor = how many <p> opens precede the image, so art
        // sits between the paragraphs it visually belongs to. Trailing art
        // (after the last <p>) clamps onto the last paragraph — the render
        // loop only walks existing indices and dropped it before.
        val pOpens = Regex("<p\\b").findAll(body).map { it.range.first }.toList()
        val lastIdx = maxOf(paragraphCount - 1, 0)
        val out = mutableListOf<Pair<Int, String>>()
        val imgTag = Regex("""<img[^>]+src="([^"]+)"|<image[^>]+(?:xlink:)?href="([^"]+)"""")
        imgTag.findAll(body).forEach { m ->
            val src = (m.groupValues[1].ifBlank { m.groupValues[2] }).substringBefore("?")
            if (src.isBlank() || src.startsWith("http")) return@forEach
            val full = zipPath(base, src)
            val entry = zip.getEntry(full) ?: return@forEach
            val ext = full.substringAfterLast('.', "jpg").lowercase()
                .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
            val dir = File(context.filesDir, "local_lib_media/${book.id}").apply { mkdirs() }
            val f = File(dir, "${full.hashCode()}.$ext")
            if (!f.isFile || f.length() == 0L) {
                runCatching { zip.getInputStream(entry).use { it.copyTo(f.outputStream()) } }
            }
            if (f.isFile && f.length() > 0) {
                // Canonical three-slash file URI — File.toURI()'s single
                // slash form is legal but some loaders balk.
                out.add(minOf(pOpens.count { it < m.range.first }, lastIdx) to ("file://" + f.absolutePath))
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
                // Metadata can carry escaped HTML — clean before storing.
                val title = cleanMeta(
                    Regex("<dc:title[^>]*>([^<]*)</dc:title>").find(opf)
                        ?.groupValues?.get(1).orEmpty()
                )
                val author = cleanMeta(
                    Regex("<dc:creator[^>]*>([^<]*)</dc:creator>").find(opf)
                        ?.groupValues?.get(1).orEmpty()
                )
                val description = cleanMeta(
                    Regex("<dc:description[^>]*>([\\s\\S]*?)</dc:description>")
                        .find(opf)?.groupValues?.get(1).orEmpty()
                )
                val base = opfPath.substringBeforeLast("/", "")
                val coverHref = Regex("""<item[^>]+id="[^"]*cover[^"]*"[^>]+href="([^"]+)"""")
                    .find(opf)?.groupValues?.get(1)
                val coverBytes: ByteArray? = coverHref?.let { ch ->
                    val full = zipPath(base, ch)
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
