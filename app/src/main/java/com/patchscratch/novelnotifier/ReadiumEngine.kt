package com.patchscratch.novelnotifier

import android.content.Context
import android.graphics.Bitmap
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * READIUM ENGINE (Wayne, 2026-10-22: "the current parser is unusable —
 * missing covers, won't read some pages, loads slow, having to extract the
 * epub"): the Readium streamer becomes THE epub parser for the local
 * library. Opened Publications live in a session cache, so chapter flips
 * are in-memory reads — no per-open temp copies, no zip surgery.
 *
 * The hand-rolled zip parser stays below this as a FALLBACK for files
 * Readium can't open; it no longer carries the library.
 */
object ReadiumEngine {

    private val httpClient by lazy { DefaultHttpClient() }
    @Volatile private var retriever: AssetRetriever? = null
    @Volatile private var opener: PublicationOpener? = null

    private fun ensureEngine(context: Context): Pair<AssetRetriever, PublicationOpener> {
        if (retriever == null || opener == null) {
            synchronized(this) {
                if (retriever == null || opener == null) {
                    val r = AssetRetriever(context.contentResolver, httpClient)
                    val p = PublicationOpener(
                        DefaultPublicationParser(context, httpClient, r, null, emptyList())
                    )
                    retriever = r
                    opener = p
                }
            }
        }
        return retriever!! to opener!!
    }

    // Session cache of opened publications per book id.
    private val opened = ConcurrentHashMap<String, Publication>()

    /** Open (or reuse) a book's Publication; null when Readium can't. */
    suspend fun publication(
        context: Context, bookId: String, storedUri: String, storedPath: String
    ): Publication? {
        opened[bookId]?.let { return it }
        val (retriever, opener) = ensureEngine(context)
        val asset = runCatching {
            when {
                storedUri.startsWith("content://") ->
                    (Url(storedUri) as? AbsoluteUrl)?.let { retriever.retrieve(it).getOrNull() }
                storedPath.isNotBlank() ->
                    retriever.retrieve(File(storedPath)).getOrNull()
                else -> null
            }
        }.getOrNull() ?: return null
        val pub = runCatching {
            opener.open(asset, allowUserInteraction = false).getOrNull()
        }.getOrNull() ?: return null
        opened[bookId] = pub
        return pub
    }

    fun forget(bookId: String) {
        runCatching { opened.remove(bookId)?.close() }
    }

    /** Open a publication directly from a source URI (the import path —
     *  parses without any temp copy). CLOSED by the caller when done —
     *  the bulk link OOM'd at ~60 books because 5,800 unclosed
     *  publications accumulated (Wayne, 2026-10-23 rebuild). */
    suspend fun publicationFromUri(context: Context, uriString: String): Publication? {
        val (retriever, opener) = ensureEngine(context)
        val asset = runCatching {
            (Url(uriString) as? AbsoluteUrl)?.let { retriever.retrieve(it).getOrNull() }
        }.getOrNull() ?: return null
        return runCatching {
            opener.open(asset, allowUserInteraction = false).getOrNull()
        }.getOrNull()
    }

    /** Open-parse-close in one: metadata, ToC titles, cover bytes. */
    suspend fun scanFromUri(
        context: Context,
        uriString: String,
        withCover: Boolean = true
    ): ScanResult? {
        val pub = publicationFromUri(context, uriString) ?: return null
        return try {
            val meta = Meta(
                title = pub.metadata.title.orEmpty(),
                author = pub.metadata.authors.mapNotNull { it.name }.joinToString(", "),
                description = pub.metadata.description.orEmpty()
            )
            // Titles: the book's own ToC first; spine titles as fallback.
            val toc = toc(pub)
            val titles = if (toc.isNotEmpty()) toc.map { it.first } else {
                pub.readingOrder.mapIndexed { i, link ->
                    link.title?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}"
                }
            }
            val cover = if (withCover) coverJpeg(context, pub) else null
            ScanResult(meta, titles, cover)
        } finally {
            runCatching { pub.close() }
        }
    }

    data class ScanResult(val meta: Meta, val titles: List<String>, val cover: ByteArray?)

    /** Reading-order hrefs — Readium-resolved, always correct. */
    fun readingOrder(pub: Publication): List<Link> = pub.readingOrder

    /**
     * The book's OWN table of contents (nav/NCX), flattened with nesting
     * order preserved — (title, href) pairs. Empty when the epub declares
     * none; callers fall back to the reading order. This is what "the
     * internal ePub ToC working" means (Wayne, 2026-10-22): the chapter
     * list shows the titles the BOOK declares, in the book's order.
     */
    fun toc(pub: Publication): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun walk(links: List<Link>) {
            links.forEach { link ->
                val title = link.title?.takeIf { it.isNotBlank() }
                val href = link.href.toString()
                if (title != null && href.isNotBlank()) out.add(title to href)
                walk(link.children)
            }
        }
        walk(pub.tableOfContents)
        return out
    }

    /** Chapter resource bytes by spine index; null on any failure. */
    suspend fun chapterBytes(pub: Publication, index: Int): ByteArray? {
        val link = pub.readingOrder.getOrNull(index) ?: return null
        val res = runCatching { pub.get(link) }.getOrNull() ?: return null
        return runCatching { res.read().getOrNull() }.getOrNull()
    }

    /** Bytes by in-book href (images, styles — used by illustration
     *  extraction without any zip handling). */
    suspend fun bytesByHref(pub: Publication, href: String): ByteArray? {
        val url = runCatching { Url(href) }.getOrNull() ?: return null
        val res = runCatching { pub.get(url) }.getOrNull() ?: return null
        return runCatching { res.read().getOrNull() }.getOrNull()
    }

    /**
     * Cover bytes via Readium's cover service, stored as a DOWNSAMPLED
     * JPEG — the first cut wrote 2-5MB PNGs, and ~5,000 of them filled the
     * tablet's disk and broke the index mid-write (Wayne, 2026-10-23:
     * the 9-series loss). ~800px wide, quality 85 ≈ 200-400KB a cover.
     */
    suspend fun coverJpeg(context: Context, pub: Publication, maxW: Int = 800): ByteArray? {
        val src: Bitmap = runCatching { pub.cover() }.getOrNull() ?: return null
        return runCatching {
            val scale = if (src.width > maxW) maxW.toFloat() / src.width else 1f
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
            } else src
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    /** Legacy PNG path kept only for the compile surface; prefer [coverJpeg]. */
    suspend fun coverPng(context: Context, pub: Publication): ByteArray? = coverJpeg(context, pub)

    // --- metadata shape used at import ------------------------------------

    data class Meta(
        val title: String,
        val author: String,
        val description: String
    )

    fun meta(pub: Publication): Meta = Meta(
        title = pub.metadata.title.orEmpty(),
        author = pub.metadata.authors.mapNotNull { it.name }.joinToString(", "),
        description = pub.metadata.description.orEmpty()
    )
}
