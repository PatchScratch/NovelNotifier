package com.patchscratch.novelnotifier

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Byte cache for a chapter's inline illustrations (user report 2026-10-18:
 * "images don't get exported as rendered in reader"). EpubStore rewrites
 * whole books on every append/translate/layout-sync, so image bytes must
 * live OUTSIDE the zip and re-embed on each write — downloading per write
 * would multiply traffic and break offline rewrites.
 *
 * Files: filesDir/epub_media/<sha1(url)>.<ext>, size-capped like CoverStore.
 * Deliberately NOT in backups (epubs aren't either); after a restore the
 * next online write re-fetches. Missing bytes degrade to a skipped image,
 * never a failed book write.
 */
object EpubMediaStore {

    private const val MAX_BYTES = 12 * 1024 * 1024
    private val UA = "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"

    fun dir(context: Context): File = context.filesDir.resolve("epub_media").apply { mkdirs() }

    internal fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    internal fun ext(url: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (fromUrl.length in 2..4 && fromUrl.all { it.isLetterOrDigit() }) fromUrl else "jpg"
    }

    fun localFor(context: Context, url: String): File? {
        if (!url.startsWith("http")) return null
        val f = dir(context).resolve("${key(url)}.${ext(url)}")
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Return the cached bytes for [url], downloading once when absent.
     * Null = unavailable (offline, HTTP error, junk) — callers skip the
     * image, they never fail the chapter. Runs on the caller's IO dispatcher.
     */
    fun bytes(context: Context, url: String): ByteArray? {
        localFor(context, url)?.let { return runCatching { it.readBytes() }.getOrNull() }
        if (!url.startsWith("http")) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode !in 200..299) {
                DebugLog.log("EPUB", "media HTTP ${conn.responseCode} ${url.take(80)}")
                return null
            }
            val raw = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (raw.isEmpty() || raw.size > MAX_BYTES) return null
            val f = dir(context).resolve("${key(url)}.${ext(url)}")
            f.writeBytes(raw)
            raw
        } catch (e: Exception) {
            DebugLog.log("EPUB", "media download failed ${url.take(80)}", e)
            null
        }
    }
}
