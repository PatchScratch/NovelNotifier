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
     *  parses without any temp copy). */
    suspend fun publicationFromUri(context: Context, uriString: String): Publication? {
        val (retriever, opener) = ensureEngine(context)
        val asset = runCatching {
            (Url(uriString) as? AbsoluteUrl)?.let { retriever.retrieve(it).getOrNull() }
        }.getOrNull() ?: return null
        return runCatching {
            opener.open(asset, allowUserInteraction = false).getOrNull()
        }.getOrNull()
    }

    /** Reading-order hrefs — Readium-resolved, always correct. */
    fun readingOrder(pub: Publication): List<Link> = pub.readingOrder

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

    /** Cover PNG bytes via Readium's cover service (handles every EPUB
     *  convention); null when the book truly has none. */
    suspend fun coverPng(context: Context, pub: Publication): ByteArray? {
        val bmp: Bitmap = runCatching { pub.cover() }.getOrNull() ?: return null
        return runCatching {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

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
