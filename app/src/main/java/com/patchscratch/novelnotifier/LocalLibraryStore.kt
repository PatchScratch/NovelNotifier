package com.patchscratch.novelnotifier

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * LOCAL LIBRARY provider (Wayne, 2026-10-21): an offline epub reader and
 * library that sits beside the sites with the same design language.
 *
 *   Library ≈ Site     — user-creates any number; a DEFAULT catch-all
 *                         "Library" exists from first run.
 *   Shelf   ≈ Category — the Kakuyomu CategoryStore pattern.
 *   Series  ≈ Series   — the unit the grid shows and shelves hold, with a
 *                         STABLE id (never re-derived from titles).
 *   Book    = Volume   — one .epub; chapters ride the RELEASES tab and open
 *                         in the shared reader via locallib:// URLs.
 *
 * EPUB ENGINE: Readium (see [ReadiumEngine]) is the parser — publications
 * stream from SAF, stay open per session, and drive the ToC, chapters,
 * covers and metadata. The zip+OPf parser below it is the FALLBACK for
 * anything Readium can't open.
 *
 * ── SECTION MAP (for collaborators) ─────────────────────────────────────
 *   index I/O & caches ....... readIndex/writeIndex, readIndexOnce,
 *                              invalidateCaches (every write drops caches)
 *   libraries CRUD ........... libraries/add/rename/setStorage/delete
 *   series model ............. LocalSeries, seriesList, seriesVolumesOf,
 *                              mergeSeries, moveBookToSeries, cover pin
 *   books CRUD & import ...... books/book/import (copy | link-in-place),
 *                              linkFolder, setBookMeta, deleteBook
 *   snapshot & sweep ......... snapshot/buildSnapshotCore (one IO pass the
 *                              grid renders from), ensureBackfill (covers +
 *                              existence GC, store-scope, survives nav)
 *   garbage collection ....... dupScan (three duplicate signatures),
 *                              dedupeBooks, missing-link detection
 *   covers ................... coverCached (stat-only), coverFileR
 *                              (Readium-first), coverFile (zip ladder),
 *                              looksLikeImage, no-cover markers
 *   Readium serving .......... chapterListR (the book's OWN ToC first),
 *                              chapterTextR, chapterTitlesR, coverBytesR
 *   zip fallbacks ............ withZip, spineHrefs, parseEpub,
 *                              chapterText/chapterTitles, extractImages
 *   epub plumbing ............ zipPath (href normalization), cleanMeta
 *                              (HTML/entity cleanup), strippedTitle/
 *                              seriesKey/volumeNumber (title heuristics)
 * ────────────────────────────────────────────────────────────────────────
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
     *  override — epub files are never rewritten. [coverBookId] pins the
     *  series cover to a chosen volume (Wayne, 2026-10-22); blank = vol 1. */
    data class LocalSeries(
        val id: String,
        val libraryId: String,
        val name: String,
        val key: String,
        val description: String = "",
        // Series cover pinned to a chosen volume (Wayne, 2026-10-22); blank = vol 1.
        val coverBookId: String = "",
        // AI-translated name/description sidecar ("name\ndescription"),
        // v3.0.1 — displayed dimmed beneath the originals, toggleable.
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
        // ATOMIC + SERIALIZED (Wayne's index loss, 2026-10-23: a thousand
        // imports ran concurrent read-modify-write cycles — the sweep,
        // dedupe and dialogs each parse their own tree and race the write,
        // so interleaves lost whole arrays and a torn read could cascade
        // into an empty index being flushed back). One writer at a time,
        // temp file + rename so no reader ever sees a partial file.
        synchronized(writeLock) {
            runCatching {
                val f = indexFile(context)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
        // Any write can change what the caches vouch for — drop them all.
        invalidateCaches()
    }

    private val writeLock = Any()

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
        snapshotCore = null
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
     * Cover for a book, stored in CoverStore (keyed by the locallib:// URL)
     * so the shared CoverThumb component finds it via its standard
     * CoverStore.localFile() lookup. Books without a cached cover get it
     * lazily extracted from the epub — HEAVY (full index read + SAF copy +
     * zip parse); use [coverCached] on hot paths.
     */
    // Negative-cache marker (v2): epubs with no findable cover would re-run
    // the heavy extraction on EVERY load. A zero-byte marker answers "no
    // cover" with one stat. The v2 name exists because the v1 detector was
    // too narrow and marked most of a 5K library coverless — the rename
    // invalidates those markers so the full detector retries once.
    private fun noCoverMarker(context: Context, bookId: String): File =
        File(CoverStore.dir(context), "nolocallib3_$bookId")

    /** Cover files must carry a real image magic — the v1 detector saved
     *  cover.XHTML documents as "covers" (id="cover" often points at the
     *  cover PAGE), which render as gray tiles. */
    internal fun looksLikeImage(f: File): Boolean {
        if (!f.isFile || f.length() < 12) return false
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                ByteArray(12).also { raf.readFully(it) }
            }
        }.getOrNull()?.let { h ->
            (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte()) ||                // JPEG
                (h[0] == 0x89.toByte() && h[1] == 0x50.toByte()) ||            // PNG
                (String(h, 0, 4) == "RIFF" && String(h, 8, 4) == "WEBP") ||    // WebP
                String(h, 0, 3) == "GIF" ||                                    // GIF
                (h[0] == 0x42.toByte() && h[1] == 0x4D.toByte())               // BMP
        } ?: false
    }

    /** Cache-hit-only cover lookup — one SHA + stat, never opens an epub. */
    fun coverCached(context: Context, bookId: String): File? {
        if (noCoverMarker(context, bookId).isFile) return null
        return CoverStore.localFile(context, "locallib://$bookId")?.takeIf(::looksLikeImage)
    }

    fun coverFile(context: Context, bookId: String): File? {
        val seriesUrl = "locallib://$bookId"
        // CoverStore first — this is what CoverThumb checks. A cached file
        // that isn't a real image (the xhtml-cover era) counts as missing.
        CoverStore.localFile(context, seriesUrl)?.takeIf(::looksLikeImage)?.let {
            noCoverMarker(context, bookId).delete()
            return it
        }
        CoverStore.localFile(context, seriesUrl)?.delete()   // junk — replace it
        if (noCoverMarker(context, bookId).isFile) return null
        // Fast path first: streamed from the SAF source (no temp copy).
        // Only when the stream can't answer does the heavy ZipFile route
        // (full copy + thorough ladder incl. spine-page/largest-image) run.
        val book = book(context, bookId) ?: return null
        coverBytesStreaming(context, book)?.let { bytes ->
            CoverStore.saveGenerated(context, seriesUrl, bytes, "jpg")
            return CoverStore.localFile(context, seriesUrl)
        }
        // Lazy extraction from the epub. A dead zip (missing/moved file)
        // must NOT write the marker — transient failures stay retryable.
        runCatching {
            withZip(context, book) { zip, opfPath ->
                val opf = zip.getEntry(opfPath)
                    ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                    ?: return@withZip null
                val coverBytes = coverBytesOf(zip, opf, opfPath)
                coverBytes?.let { bytes ->
                    CoverStore.saveGenerated(context, seriesUrl, bytes, "jpg")
                }
                // Remember a truly coverless epub so loads stop re-trying.
                if (coverBytes == null) runCatching {
                    noCoverMarker(context, bookId).createNewFile()
                }
                null
            }
        }
        return CoverStore.localFile(context, seriesUrl)
    }

    /**
     * Manual "pull the cover out of this epub" (volume ⋮ action): clears
     * the no-cover marker and any stale cached entry, then re-runs the
     * Readium-first resolution.
     */
    suspend fun forceCoverExtraction(context: Context, bookId: String): File? {
        noCoverMarker(context, bookId).delete()
        CoverStore.localFile(context, "locallib://$bookId")?.delete()
        return coverFileR(context, bookId)
    }

    /**
     * Readium-first cover resolution: a valid cache hit returns fast;
     * otherwise the streamer's cover service (every EPUB convention);
     * otherwise the zip ladder as fallback.
     */
    suspend fun coverFileR(context: Context, bookId: String): File? {
        val seriesUrl = "locallib://$bookId"
        CoverStore.localFile(context, seriesUrl)?.takeIf(::looksLikeImage)?.let {
            noCoverMarker(context, bookId).delete()
            return it
        }
        val bytes = coverBytesR(context, bookId)
        if (bytes != null) {
            // JPEG (downsampled in ReadiumEngine) — never the multi-MB
            // PNGs that filled the disk (2026-10-23).
            CoverStore.saveGenerated(context, seriesUrl, bytes, "jpg")
            noCoverMarker(context, bookId).delete()
            return CoverStore.localFile(context, seriesUrl)
        }
        return coverFile(context, bookId)
    }

    private fun itemHrefById(opf: String, id: String): String? =
        Regex("""<item\s+[^>]*id="$id"[^>]*href="([^"]+)"""").find(opf)?.groupValues?.get(1)
            ?: Regex("""<item\s+[^>]*href="([^"]+)"[^>]*id="$id"""").find(opf)?.groupValues?.get(1)

    /**
     * Cover bytes streamed straight from the SAF source — no temp copy, no
     * disk write. Born from the gray-tile backlog (Wayne, 2026-10-22): the
     * sweep's per-book full-epub copy made a multi-thousand-book pass take
     * HOURS. Two cheap sequential reads: the OPF (entries sit at the zip's
     * head — the stream closes the moment it's seen), then one scan for a
     * declared cover entry. Books the stream can't satisfy fall back to
     * the thorough ZipFile path.
     */
    private fun coverBytesStreaming(context: Context, book: LocalBook): ByteArray? {
        val entry = bookEntry(context, book.id) ?: return null
        val uriStr = entry.optString("storedUri").takeIf { it.startsWith("content://") }
            ?: return null
        val opfPath = entry.optString("opfPath").takeIf { it.isNotBlank() } ?: return null
        val uri = Uri.parse(uriStr)
        val opf: String = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins, 1 shl 16)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (e.name == opfPath) return@use String(zis.readBytes(), Charsets.UTF_8)
                        e = zis.nextEntry
                    }
                    null
                }
            }
        }.getOrNull() ?: return null
        // Candidate entry names, ladder order, resolved against the OPF dir.
        val base = opfPath.substringBeforeLast("/", "")
        val names = LinkedHashSet<String>()
        fun add(href: String?) {
            if (!href.isNullOrBlank() && !href.startsWith("http")) names.add(zipPath(base, href))
        }
        Regex("""<item[^>]+properties="[^"]*cover-image[^"]*"[^>]+href="([^"]+)"""")
            .find(opf)?.groupValues?.get(1)?.let(::add)
        Regex("""<item[^>]+href="([^"]+)"[^>]+properties="[^"]*cover-image[^"]*"""")
            .find(opf)?.groupValues?.get(1)?.let(::add)
        Regex("""<meta[^>]+name="cover"[^>]+content="([^"]+)"""")
            .find(opf)?.groupValues?.get(1)?.let { idref -> itemHrefById(opf, idref)?.let(::add) }
        Regex("""<item[^>]+id="[^"]*cover[^"]*"[^>]+href="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let(::add)
        Regex("""<item[^>]+href="([^"]+)"[^>]+id="[^"]*cover[^"]*"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let(::add)
        if (names.isEmpty()) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins, 1 shl 16)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (e.name in names) return@use zis.readBytes()
                        e = zis.nextEntry
                    }
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * The cover image bytes from an opened epub, trying every convention
     * real books use (Wayne, 2026-10-22: the old id-only detector marked
     * most of a 5K library coverless): EPUB3 properties="cover-image",
     * EPUB2 <meta name="cover" content=id> pointer, an id CONTAINING
     * "cover" in either attribute order, any zip path that names a cover
     * image, and finally the first image of the first spine page
     * (cover-page epubs). Null only when the epub truly has no art.
     */
    internal fun coverBytesOf(zip: ZipFile, opf: String, opfPath: String): ByteArray? {
        val base = opfPath.substringBeforeLast("/", "")
        fun entryBytes(href: String?): ByteArray? = href?.let { h ->
            runCatching {
                zip.getEntry(zipPath(base, h))?.let { zip.getInputStream(it).readBytes() }
            }.getOrNull()
        }
        // 1) EPUB3 declaration.
        Regex("""<item[^>]+properties="[^"]*cover-image[^"]*"[^>]+href="([^"]+)"""")
            .find(opf)?.groupValues?.get(1)?.let { entryBytes(it)?.let { b -> return b } }
        Regex("""<item[^>]+href="([^"]+)"[^>]+properties="[^"]*cover-image[^"]*"""")
            .find(opf)?.groupValues?.get(1)?.let { entryBytes(it)?.let { b -> return b } }
        // 2) EPUB2 meta pointer.
        Regex("""<meta[^>]+name="cover"[^>]+content="([^"]+)""")
            .find(opf)?.groupValues?.get(1)?.let { idref ->
                itemHrefById(opf, idref)?.let { entryBytes(it)?.let { b -> return b } }
            }
        // 3) Any id containing "cover", either attribute order.
        Regex("""<item[^>]+id="[^"]*cover[^"]*"[^>]+href="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let { entryBytes(it)?.let { b -> return b } }
        Regex("""<item[^>]+href="([^"]+)"[^>]+id="[^"]*cover[^"]*"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let { entryBytes(it)?.let { b -> return b } }
        // 4) Any zip image whose FILE names a cover.
        val imgExt = Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
        zip.entries().asSequence().filter { !it.isDirectory && imgExt.containsMatchIn(it.name) }
            .filter { it.name.substringAfterLast('/').lowercase().startsWith("cover") }
            .firstOrNull()?.let { e ->
                runCatching { zip.getInputStream(e).readBytes() }.getOrNull()?.let { return it }
            }
        // 5) First image on the first spine page (cover-page epubs).
        val hrefs = spineHrefs(opf, opfPath)
        hrefs.firstOrNull()?.let { first ->
            val doc = runCatching {
                zip.getEntry(first)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            doc?.let { d ->
                Regex("""<img[^>]+src="([^"]+)"|<image[^>]+(?:xlink:)?href="([^"]+)"""")
                    .find(d)?.let { m ->
                        val src = m.groupValues[1].ifBlank { m.groupValues[2] }
                        if (src.isNotBlank() && !src.startsWith("http")) {
                            val chBase = first.substringBeforeLast("/", "")
                            entryBytesIn(zip, chBase, src)?.let { return it }
                        }
                    }
            }
        }
        // 6) Last resort: the largest image in the book — covers are
        // virtually always the heaviest art (bounded: ignore <50KB scraps).
        zip.entries().asSequence()
            .filter { !it.isDirectory && imgExt.containsMatchIn(it.name) && it.size > 50_000L }
            .maxByOrNull { it.size }?.let { e ->
                runCatching { zip.getInputStream(e).readBytes() }.getOrNull()?.let { return it }
            }
        return null
    }

    private fun entryBytesIn(zip: ZipFile, base: String, src: String): ByteArray? =
        runCatching {
            zip.getEntry(zipPath(base, src))?.let { zip.getInputStream(it).readBytes() }
        }.getOrNull()

    /**
     * Import an epub. Copy mode stores bytes in the library's storage
     * (SAF tree or app-private dir); [linkInPlace] leaves the file where
     * it is. Parsing runs through READIUM first (correct spine/cover/metadata
     * for every epub shape); the zip parser is the fallback.
     */
    suspend fun import(
        context: Context, libraryId: String, source: Uri, linkInPlace: Boolean = false
    ): LocalBook? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val lib = library(context, libraryId) ?: return null
        val bookId = UUID.randomUUID().toString().take(10)
        // Readium parse: metadata, spine titles, cover — open, extract,
        // CLOSE (unclosed publications OOM'd the bulk link, 2026-10-23).
        var parsed: EpubParsed? = null
        runCatching {
            val scan = ReadiumEngine.scanFromUri(context, source.toString())
            if (scan != null) {
                parsed = EpubParsed(
                    title = scan.meta.title,
                    author = scan.meta.author,
                    description = scan.meta.description,
                    opfPath = "",   // unused on the Readium path
                    chapterTitles = scan.titles,
                    coverBytes = scan.cover
                )
            }
        }
        if (parsed == null) parsed = parseEpub(bytes)
        val parsedFinal = parsed ?: return null
        val title = parsedFinal.title.ifBlank { "book-$bookId" }
        // Content-signature duplicate check: the same epub can arrive under
        // a different SAF document URI (folder scan + individual pick of
        // one file) — URI dedupe alone missed it and every volume doubled
        // (Wayne, 2026-10-22). Returns the existing book instead of a new
        // entry; callers' counts stay cosmetic.
        run {
            val arr = readIndex(context).optJSONArray("books") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("libraryId") != lib.id) continue
                if (o.optString("title") != title) continue
                if (o.optInt("chapters", 0) != parsedFinal.chapterTitles.size) continue
                val knownSize = o.optLong("bytes", -1L)
                if (knownSize != -1L && knownSize != bytes.size.toLong()) continue
                if (volumeNumber(title) == Int.MAX_VALUE && knownSize == -1L) continue
                return LocalBook(
                    id = o.optString("id"), libraryId = lib.id,
                    title = o.optString("title"), author = o.optString("author"),
                    description = o.optString("description"), fileName = o.optString("fileName"),
                    chapterCount = o.optInt("chapters", 0), addedAt = o.optLong("addedAt", 0)
                )
            }
        }
        val safeTitle = parsedFinal.title.trim().replace(Regex("[^\\w\\-. \\u3040-\\u30ff\\u4e00-\\u9fff]"), "_").take(60)
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
        parsedFinal.coverBytes?.let { cov ->
            runCatching {
                CoverStore.saveGenerated(context, "locallib://$bookId", cov, "jpg")
            }
            noCoverMarker(context, bookId).delete()
        } ?: runCatching { noCoverMarker(context, bookId).createNewFile() }
        val book = LocalBook(
            id = bookId, libraryId = lib.id,
            title = parsedFinal.title.ifBlank { fileName.removeSuffix(".epub") },
            author = parsedFinal.author, description = parsedFinal.description,
            fileName = fileName, chapterCount = parsedFinal.chapterTitles.size,
            addedAt = System.currentTimeMillis(), linked = linkInPlace
        )
        ensureSeriesMigration(context)
        // The whole join-series + append-book + write cycle holds the write
        // lock: during the 1,000-book reorg import, concurrent imports each
        // parsed their OWN tree and raced the write — the last writer
        // erased every other import's book (Wayne's index loss, 10-23).
        return synchronized(writeLock) {
            val rootNow = readIndex(context)
            // Join the series whose key matches (auto-grouping); otherwise
            // this import founds a new one. Solo pins are gone — membership
            // is direct.
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
                    .put("opfPath", parsedFinal.opfPath).put("chapters", book.chapterCount)
                    .put("addedAt", book.addedAt).put("ref", linkInPlace)
                    .put("seriesId", seriesId)
                    .put("bytes", bytes.size.toLong())
            )
            writeIndex(context, rootNow)
            book
        }
    }

    /**
     * Link every .epub under [treeUri] (recursive). ONE persistable tree
     * grant covers all descendants — per-file grants from the multi-picker
     * cap at ~128 and broke large libraries (Wayne, 2026-10-21). Files
     * already linked are skipped so the folder can be re-run safely.
     * Returns (linked, skipped). Runs on the caller's IO dispatcher.
     */
    suspend fun linkFolder(
        context: Context, libraryId: String, treeUri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Pair<Int, Int> {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        // Remember the folder (Wayne's Calibre flow, 2026-10-22: fresh
        // uploads to the same folder must be picked up automatically —
        // the session sweep re-scans recorded folders).
        runCatching {
            val root = readIndex(context)
            val arr = root.optJSONArray("folders") ?: JSONArray().also { root.put("folders", it) }
            val entry = JSONObject().put("libraryId", libraryId).put("uri", treeUri.toString())
            val known = (0 until arr.length()).any { i ->
                arr.optJSONObject(i)?.let { it.optString("uri") == treeUri.toString() } == true
            }
            if (!known) arr.put(entry)
            writeIndex(context, root)
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
        // A comma/colon never meaningfully ENDS a title — it's a separator
        // artifact ("Bride, Vol. 2" → "Bride,"), so trim the tail (Wayne,
        // 2026-10-22). Applies to stored fields via the maintenance pass.
        return t.replace(Regex("\\s+"), " ").trim()
            .trimEnd(',', '，', ':', '：').trim()
    }

    /**
     * Idempotent index cleanup: books imported before cleanMeta() carry
     * escaped HTML in title/author/description, and series names/keys
     * inherited it. Keys recompute from volume 1's cleaned title so future
     * imports still auto-join. Also drops duplicate book entries — the same
     * physical epub re-linked under a different SAF document URI (folder
     * scan + individual pick of the same file; URI-string dedupe missed
     * them — Wayne, 2026-10-22: "duplicates, but not in my files").
     * Writes only when something changed.
     */
    /** The source file's display name — SAF document IDs and plain paths
     *  both end in it. Volume files are named distinctly even when dc:title
     *  is bare ("Welcome to Japan, Ms. Elf!" ×4), so filename+chapters is a
     *  duplicate-proof signature the title ladder can't provide. */
    internal fun sourceName(o: JSONObject): String {
        val uri = o.optString("storedUri")
        if (uri.startsWith("content://")) {
            val last = uri.substringAfterLast('/').substringAfter("%3A", "")
            return runCatching { android.net.Uri.decode(last) }.getOrDefault(last)
        }
        val p = o.optString("storedPath")
        return if (p.isNotBlank()) p.substringAfterLast('/') else ""
    }

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
        // AUTOMATIC DUPLICATE DELETION IS REMOVED (Wayne, 2026-10-23): the
        // title-normalizing signature collided across DIFFERENT series at
        // 5.5K scale and the sweep re-ran it every pass — 21,638 deletions
        // on a 5,536-book library. Import-time signature checks and the
        // URI skip handle real duplicates; bulk deletion is never automatic.
    }

    /**
     * Duplicate scan across the whole index. With a known VOLUME identity
     * (parsed from the title or source filename), the signature is
     * normalized title + volume — chapter counts differ between rips of
     * the same book (different chapter splits) and must not block the
     * merge. Unknown-volume entries (bare title AND bare filename) fall
     * back to title+chapters with equal byte sizes required: bare-title
     * series share one title across volumes. Exact source-filename matches
     * merge regardless.
     */
    private fun dupScan(context: Context): List<Pair<String, String>> {
        val root = readIndex(context)
        val books = root.optJSONArray("books") ?: return emptyList()
        val bySignature = HashMap<String, JSONObject>()
        val byFileSig = HashMap<String, JSONObject>()
        val dupPairs = mutableListOf<Pair<String, String>>()
        val marked = HashSet<String>()
        for (i in 0 until books.length()) {
            val o = books.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id in marked) continue
            val title = o.optString("title")
            val chapters = o.optInt("chapters", 0)
            val name = sourceName(o)
            val fileVol = volumeNumber(name.substringAfterLast('/'))
            val vol = volumeNumber(title).takeIf { it != Int.MAX_VALUE }
                ?: fileVol.takeIf { it != Int.MAX_VALUE } ?: -1
            val norm = o.optString("libraryId") + "|||" + seriesKey(title)
            val key = if (vol != -1) "$norm|||v$vol" else "$norm|||$chapters|||v-1"
            val kept = bySignature[key]
            if (kept == null) {
                bySignature[key] = o
            } else if (vol != -1 || sizesAgree(o, kept)) {
                dupPairs.add(id to kept.optString("id"))
                marked.add(id)
            }
            if (name.isNotBlank() && id !in marked) {
                val fileSig = o.optString("libraryId") + "|||" + name + "|||" + chapters
                val keptFile = byFileSig[fileSig]
                if (keptFile == null) {
                    byFileSig[fileSig] = o
                } else if (keptFile.optString("id") != id) {
                    dupPairs.add(id to keptFile.optString("id"))
                    marked.add(id)
                }
            }
        }
        return dupPairs
    }

    private fun sizesAgree(a: JSONObject, b: JSONObject): Boolean {
        val x = a.optLong("bytes", -1L)
        val y = b.optLong("bytes", -1L)
        return x != -1L && x == y
    }

    /**
     * Remove duplicate book entries (keeping each signature's earliest,
     * [dupPairs] maps each dup to its keeper). A duplicate's read position
     * migrates to the keeper when the keeper's is lower — deleting a read
     * dup must never lose progress.
     */
    private fun dedupeBooks(context: Context, dupPairs: List<Pair<String, String>>) {
        val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
        dupPairs.forEach { (dupId, keeperId) ->
            val dupPos = prefs.getInt("local_pos_$dupId", 0)
            val keepPos = prefs.getInt("local_pos_$keeperId", 0)
            if (dupPos > keepPos) {
                prefs.edit().putInt("local_pos_$keeperId", dupPos).apply()
            }
            DebugLog.log("LOCALLIB", "dedupe: removing duplicate book $dupId (kept $keeperId)")
            deleteBook(context, dupId)
        }
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
            noCoverMarker(context, bookId).delete()
            LocalTranslationStore.deleteBook(context, bookId)
            ReadiumEngine.forget(bookId)
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

    private data class SnapshotCore(
        val library: LocalLibrary?, val books: List<LocalBook>,
        val series: List<LocalSeries>, val seriesIdOf: Map<String, String>,
        // Mutable: the background cover backfill fills it in place so the
        // session cache warms up without rebuilding anything.
        val coverPathOf: ConcurrentHashMap<String, String>
    )

    // Session cache (Wayne, 2026-10-22: "load on open then stay cached for
    // the session"): the router disposes the home screen on every
    // navigation, so returning rebuilt the whole snapshot — a full index
    // parse + cover stats each time. The heavy half is cached per library
    // and dropped by ANY index write (invalidateCaches); read positions
    // refresh per call because the reader advances them.
    @Volatile private var snapshotCore: Pair<String, SnapshotCore>? = null

    fun snapshot(context: Context, libraryId: String): LibrarySnapshot {
        val core = snapshotCore?.takeIf { it.first == libraryId }?.second
            ?: buildSnapshotCore(context, libraryId).also {
                snapshotCore = libraryId to it
            }
        ensureBackfill(context, libraryId)
        val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
        val positions = core.books.associate { it.id to prefs.getInt("local_pos_${it.id}", 0) }
        return LibrarySnapshot(
            library = core.library, books = core.books, series = core.series,
            seriesIdOf = core.seriesIdOf, coverPathOf = core.coverPathOf,
            readPosOf = positions
        )
    }

    /**
     * Screen-independent cover backfill. Born from a live failure: the
     * backfill used to ride the library screen's LaunchedEffect and CANCELLED
     * on navigation — in a 5K library, walking sequentially, any series deep
     * in the list only got covers if the user sat still (Wayne, 2026-10-22:
     * "You Were Experienced… has no covers"). Now: a store-level scope runs
     * it once per library per session, survives navigation, bumps
     * [coverTick] per find so any open screen recomposes, and DELETES the
     * temporary zip cache copy per book (a 5K sweep would otherwise flood
     * cacheDir with full epub copies — interactive opens re-warm on demand).
     */
    private val backfillScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val backfillStarted = Any()
    private val sweepAt = HashMap<String, Long>()

    /** Manual "Re-scan folders" (drawer): run the folder rescan now,
     *  bypassing the cooldown; the library tick reloads the grid. */
    fun rescanFoldersNow(context: Context, libraryId: String): kotlinx.coroutines.Job =
        backfillScope.launch {
            rescanFolders(context, libraryId)
        }

    var coverTick by androidx.compose.runtime.mutableStateOf(0)
        private set

    /** Bumped when a background pass changes the library's CONTENT (a
     *  folder rescan imported new books) — the grid reloads its snapshot. */
    var libraryTick by androidx.compose.runtime.mutableStateOf(0)
        private set

    /** Tree URIs of folders linked into [libraryId]. */
    private fun foldersOf(context: Context, libraryId: String): List<Uri> {
        val arr = readIndex(context).optJSONArray("folders")
        val out = mutableListOf<Uri>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.takeIf { it.optString("libraryId") == libraryId }
                    ?.optString("uri")?.takeIf { it.isNotBlank() }?.let { out.add(Uri.parse(it)) }
            }
        }
        if (out.isEmpty()) healFolderRecords(context, libraryId)?.let { out.addAll(it) }
        return out
    }

    /**
     * SELF-HEAL for folders linked before the watcher existed (the record
     * was added to linkFolder later — Wayne's original Light Novels link,
     * 2026-10-23: rescan reported "0 folders"). Books' stored document URIs
     * carry their tree: strip the /document/… tail and the tree URI falls
     * out — record every distinct tree as a watched folder. Returns the
     * healed set (already written to the index), or null when the library
     * holds no tree-derived books.
     */
    private fun healFolderRecords(context: Context, libraryId: String): List<Uri>? {
        val books = readIndex(context).optJSONArray("books") ?: return null
        val trees = linkedSetOf<String>()
        for (i in 0 until books.length()) {
            val o = books.optJSONObject(i) ?: continue
            if (o.optString("libraryId") != libraryId) continue
            val uri = o.optString("storedUri")
            val idx = uri.indexOf("/document/")
            if (uri.startsWith("content://") && idx > 0) trees.add(uri.substring(0, idx))
        }
        if (trees.isEmpty()) return null
        runCatching {
            val root = readIndex(context)
            val arr = root.optJSONArray("folders") ?: JSONArray().also { root.put("folders", it) }
            trees.forEach { tree ->
                val known = (0 until arr.length()).any { i ->
                    arr.optJSONObject(i)?.let { it.optString("uri") == tree } == true
                }
                if (!known) arr.put(
                    JSONObject().put("libraryId", libraryId).put("uri", tree)
                )
            }
            writeIndex(context, root)
        }
        DebugLog.log("LOCALLIB", "rescan: healed ${trees.size} folder record(s) for lib=$libraryId")
        return trees.map(Uri::parse)
    }

    /**
     * Session folder rescan (once per library): re-enumerate every linked
     * folder and import files the index has never seen — Wayne's Calibre
     * flow (fresh uploads land in the same folder; the library must pick
     * them up without a manual re-link). Runs inside the background sweep.
     */
    private suspend fun rescanFolders(context: Context, libraryId: String) {
        val folders = foldersOf(context, libraryId)
        DebugLog.log("LOCALLIB", "rescan: ${folders.size} folder(s) for lib=$libraryId")
        if (folders.isEmpty()) return
        var added = 0
        folders.forEach { treeUri ->
            kotlinx.coroutines.yield()
            val tree = runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            }.getOrNull() ?: run {
                DebugLog.log("LOCALLIB", "rescan: tree unreachable $treeUri"); null
            } ?: return@forEach
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
            val known = run {
                val arr = readIndex(context).optJSONArray("books") ?: JSONArray()
                val set = HashSet<String>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("storedUri")?.takeIf { it.isNotBlank() }
                        ?.let { set.add(it) }
                }
                set
            }
            DebugLog.log("LOCALLIB", "rescan: ${files.size} epubs on disk, ${known.size} known URIs")
            files.take(3).forEach { DebugLog.log("LOCALLIB", "rescan sample: ${it.name?.take(40)} -> ${it.uri}") }
            files.forEach { f ->
                kotlinx.coroutines.yield()
                if (f.uri.toString() !in known) {
                    if (import(context, libraryId, f.uri, linkInPlace = true) != null) added++
                }
            }
        }
        if (added > 0) {
            DebugLog.log("LOCALLIB", "folder rescan: +$added books")
            libraryTick++
        } else {
            DebugLog.log("LOCALLIB", "folder rescan: nothing new")
        }
    }

    // --- garbage collection: dead source links ---------------------------
    /** Book ids whose epub cannot be opened (file moved/deleted on the
     *  device). Surfaced in the drawer; removal is always a user action. */
    var missingIds by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Re-encode any stored cover over 1MB to a ~800px JPEG (quality 85).
     * The Readium switch initially stored PNG covers at 2-5MB; a few
     * thousand of those filled internal storage and the full disk broke
     * index writes (2026-10-23). Idempotent — compacted files fall under
     * the threshold and are never touched again.
     */
    private fun compactCovers(context: Context) {
        val dir = CoverStore.dir(context)
        var compacted = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() <= 1_000_000) return@forEach
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return@runCatching
                val scale = if (bmp.width > 800) 800f / bmp.width else 1f
                val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                ) else bmp
                val tmp = File(f.parentFile, f.name + ".tmp")
                java.io.FileOutputStream(tmp).use { out ->
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (tmp.length() in 1..f.length()) {
                    f.delete()
                    tmp.renameTo(File(f.parentFile, f.name.substringBeforeLast('.') + ".jpg"))
                    compacted++
                } else tmp.delete()
            }
        }
        if (compacted > 0) DebugLog.log("LOCALLIB", "compacted $compacted oversized cover(s)")
    }

    private fun sourceAlive(context: Context, o: JSONObject): Boolean {
        val uri = o.optString("storedUri")
        return when {
            uri.startsWith("content://") -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.close()
                true
            }.getOrDefault(false)
            o.optString("storedPath").isNotBlank() -> File(o.optString("storedPath")).isFile
            else -> false
        }
    }

    /** Source file's byte size via the provider (no read); -1 = unknown.
     *  Sizes let the title+chapters signature catch content duplicates
     *  saved under near-identical names ("Ms. Elf! Vol. 1" vs "Ms. Elf!,"
     *  — 16 real files, 9 books, Wayne 2026-10-22). */
    private fun sourceSize(context: Context, o: JSONObject): Long {
        val uri = o.optString("storedUri")
        if (uri.startsWith("content://")) {
            return runCatching {
                context.contentResolver.query(
                    Uri.parse(uri),
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
            }.getOrDefault(-1L)
        }
        val p = o.optString("storedPath")
        return if (p.isNotBlank()) File(p).length() else -1L
    }

    /** Remove every dead link from the index; returns how many went. */
    fun removeMissing(context: Context): Int {
        val root = readIndex(context)
        val arr = root.optJSONArray("books") ?: return 0
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.takeIf { it.optBoolean("missing", false) }
                ?.let { ids.add(it.optString("id")) }
        }
        ids.forEach { deleteBook(context, it) }
        missingIds = emptySet()
        return ids.size
    }

    private fun ensureBackfill(context: Context, libraryId: String) {
        // Sweep + folder rescan run per library OPEN (not once per process —
        // Wayne's Calibre uploads land after the app opened, 2026-10-22),
        // cooldown-limited so re-entering the library doesn't re-walk.
        synchronized(backfillStarted) {
            val last = sweepAt[libraryId] ?: 0L
            if (System.currentTimeMillis() - last < 5 * 60 * 1000L) return
            sweepAt[libraryId] = System.currentTimeMillis()
        }
        backfillScope.launch {
            // Folder rescan FIRST: fresh Calibre uploads join the library
            // here, then the snapshot rebuilds with them included and the
            // same sweep covers their existence + covers.
            runCatching { rescanFolders(context, libraryId) }
            val core = snapshotCore?.takeIf { it.first == libraryId }?.second
                ?: buildSnapshotCore(context, libraryId).also { snapshotCore = libraryId to it }
            // Existence pass first (cheap IPCs): mark dead links with a
            // double-strike so a provider blip can't orphan a live book,
            // and stamp byte sizes from the provider — with sizes, the
            // title signature can catch content duplicates saved under
            // near-identical names, so re-run the dup scan afterwards.
            var sized = false
            runCatching {
                val root = readIndex(context)
                val arr = root.optJSONArray("books") ?: JSONArray()
                val byId = HashMap<String, JSONObject>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { byId[it.optString("id")] = it }
                }
                val missing = HashSet<String>()
                var changed = false
                core.books.forEach { b ->
                    kotlinx.coroutines.yield()
                    val o = byId[b.id] ?: return@forEach
                    val alive = sourceAlive(context, o) || sourceAlive(context, o)
                    val was = o.optBoolean("missing", false)
                    when {
                        !alive && !was -> { o.put("missing", true); changed = true; missing.add(b.id) }
                        !alive -> missing.add(b.id)
                        alive && was -> { o.put("missing", false); changed = true }
                    }
                    if (alive) {
                        val sz = sourceSize(context, o)
                        if (sz > 0 && o.optLong("bytes", -1L) != sz) {
                            o.put("bytes", sz); changed = true; sized = true
                        }
                    }
                }
                if (changed) writeIndex(context, root)
                missingIds = missing
            }
            // Compact oversized stored covers (the PNG era): anything over
            // 1MB re-encodes to a ~800px JPEG in place — a full disk is
            // what broke the index in the first place (2026-10-23).
            runCatching { compactCovers(context) }
            var lastCoverBump = 0L
            suspend fun handle(b: LocalBook) {
                kotlinx.coroutines.yield()
                if (core.coverPathOf[b.id].orEmpty().isNotBlank()) return
                val f = coverFileR(context, b.id)
                File(context.cacheDir, "ll_${b.id}.epub").delete()
                if (f != null) {
                    core.coverPathOf[b.id] = f.absolutePath
                    // Throttled (v3.2a): each bump recomposes the visible
                    // tiles — per-cover bumps during a 5K backfill machine-
                    // gunned the grid. 500ms cadence streams covers in
                    // without a stutter.
                    val now = System.currentTimeMillis()
                    if (now - lastCoverBump >= 500) {
                        lastCoverBump = now
                        coverTick++
                    }
                }
            }
            // Two workers: SAF reads are IO-bound; a second lane roughly
            // halves the wall clock of a multi-thousand-book pass.
            kotlinx.coroutines.coroutineScope {
                launch { core.books.filterIndexed { i, _ -> i % 2 == 0 }.forEach { handle(it) } }
                launch { core.books.filterIndexed { i, _ -> i % 2 == 1 }.forEach { handle(it) } }
            }
        }
    }

    private fun buildSnapshotCore(context: Context, libraryId: String): SnapshotCore {
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
        // Cache-hit cover lookups ONLY — the heavy lazy extraction used to
        // run here per coverless book (full index re-parse + epub zip open),
        // which is what made a 5K library take minutes. The backfill
        // coroutine owns extraction after the grid renders.
        val coverPaths = ConcurrentHashMap<String, String>(books.size)
        books.forEach { b ->
            coverPaths[b.id] = coverCached(context, b.id)?.absolutePath ?: ""
        }
        return SnapshotCore(
            library = parseLibraries(root).firstOrNull { it.id == libraryId },
            books = books, series = series, seriesIdOf = sidMap, coverPathOf = coverPaths
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
                    o.optString("name"), o.optString("key"), o.optString("description"),
                    o.optString("coverBookId"), o.optString("translatedMeta")
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

    /** Pin the series cover to a volume; blank [bookId] = volume 1. */
    fun setSeriesCover(context: Context, id: String, bookId: String) {
        mutateSeries(context, id) { it.put("coverBookId", bookId) }
    }

    /** AI-translated series name/description ("name\ndescription"). */
    fun setSeriesTranslatedMeta(context: Context, id: String, meta: String) {
        mutateSeries(context, id) { it.put("translatedMeta", meta) }
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
        // Punctuation drift between rips ("Ms. Elf!," vs "Ms. Elf!") must
        // not split keys either.
        return t.trim().trimEnd(',', ':', '，', '：').trim()
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

    // --- Readium-backed serving (primary) + zip fallback -------------------
    // The streamer resolves spine hrefs correctly for every epub shape the
    // hand parser missed, keeps the Publication OPEN for the session (no
    // per-chapter temp copies), and its cover service handles all
    // conventions (Wayne, 2026-10-22: "missing covers, won't read some
    // pages, loads slow").

    private suspend fun publicationOf(context: Context, book: LocalBook) =
        bookEntry(context, book.id)?.let {
            ReadiumEngine.publication(context, book.id, it.optString("storedUri"), it.optString("storedPath"))
        }

    /** The open publication for paged-mode rendering (Readium navigator). */
    suspend fun publicationFor(context: Context, book: LocalBook): org.readium.r2.shared.publication.Publication? =
        publicationOf(context, book)

    /**
     * THE chapter list: the epub's own ToC (nav/NCX, flattened in book
     * order) when it declares one; the reading order otherwise. Titles AND
     * serving both index into this list — one source of truth.
     */
    suspend fun chapterListR(context: Context, book: LocalBook): List<Pair<String, String>> {
        val pub = publicationOf(context, book) ?: return emptyList()
        val toc = ReadiumEngine.toc(pub)
        if (toc.isNotEmpty()) return toc
        return pub.readingOrder.mapNotNull { link ->
            val href = link.href.toString()
            if (href.isBlank()) null
            else (link.title?.takeIf { it.isNotBlank() } ?: href.substringAfterLast('/')) to href
        }
    }

    suspend fun chapterTitlesR(context: Context, book: LocalBook): List<String> =
        chapterListR(context, book).map { it.first }

    suspend fun chapterTextR(context: Context, book: LocalBook, index: Int): LocalChapter? {
        val pub = publicationOf(context, book) ?: return null
        val list = chapterListR(context, book)
        if (index !in list.indices) return null
        val (chapterTitle, href) = list[index]
        // Resolve the href to a spine resource when it's one (fragments
        // ignored — a ToC entry anchors inside its document); read the
        // document directly otherwise.
        val bare = href.substringBefore("#")
        val spineIndex = pub.readingOrder.indexOfFirst { it.href.toString() == bare }
        val docHref: String
        val doc: String = (
            if (spineIndex >= 0) {
                docHref = pub.readingOrder[spineIndex].href.toString()
                ReadiumEngine.chapterBytes(pub, spineIndex)?.toString(Charsets.UTF_8)
            } else {
                docHref = bare
                ReadiumEngine.bytesByHref(pub, bare)?.toString(Charsets.UTF_8)
            }
            ) ?: return null
        val title = Regex("<title[^>]*>([^<]*)</title>").find(doc)
            ?.groupValues?.get(1)?.trim().orEmpty()
        val body = doc.substringAfter("<body", doc).substringAfter(">", doc)
        val paras = extractParagraphs(doc)
        val images = extractImagesR(pub, context, book, body, docHref, paras.size)
        val finalParas = if (paras.isEmpty() && images.isNotEmpty()) listOf("") else paras
        return LocalChapter(title.ifBlank { chapterTitle }, finalParas, images)
    }

    /** Readium cover bytes; null → caller falls back to the zip ladder. */
    suspend fun coverBytesR(context: Context, bookId: String): ByteArray? {
        val entry = bookEntry(context, bookId) ?: return null
        val pub = ReadiumEngine.publication(
            context, bookId, entry.optString("storedUri"), entry.optString("storedPath")
        ) ?: return null
        return ReadiumEngine.coverJpeg(context, pub)
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

    /**
     * Readium variant of [extractImages]: identical anchoring and caching,
     * but bytes load through the OPEN Publication (in-memory after first
     * read) instead of a zip handle.
     */
    private suspend fun extractImagesR(
        pub: org.readium.r2.shared.publication.Publication,
        context: Context,
        book: LocalBook, body: String, chapterHref: String, paragraphCount: Int
    ): List<Pair<Int, String>> {
        val base = chapterHref.substringBeforeLast("/", "")
        val pOpens = Regex("<p\\b").findAll(body).map { it.range.first }.toList()
        val lastIdx = maxOf(paragraphCount - 1, 0)
        val out = mutableListOf<Pair<Int, String>>()
        val imgTag = Regex("""<img[^>]+src="([^"]+)"|<image[^>]+(?:xlink:)?href="([^"]+)"""")
        imgTag.findAll(body).forEach { m ->
            val src = (m.groupValues[1].ifBlank { m.groupValues[2] }).substringBefore("?")
            if (src.isBlank() || src.startsWith("http")) return@forEach
            val full = zipPath(base, src)
            val bytes = ReadiumEngine.bytesByHref(pub, full) ?: return@forEach
            val ext = full.substringAfterLast('.', "jpg").lowercase()
                .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
            val dir = File(context.filesDir, "local_lib_media/${book.id}").apply { mkdirs() }
            val f = File(dir, "${full.hashCode()}.$ext")
            if (!f.isFile || f.length() == 0L) {
                runCatching { f.writeBytes(bytes) }
            }
            if (f.isFile && f.length() > 0) {
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
                val coverBytes = coverBytesOf(zip, opf, opfPath)
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
