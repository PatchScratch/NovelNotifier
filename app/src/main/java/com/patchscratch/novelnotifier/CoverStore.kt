package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cover images as local files (filesDir/covers/<seriesUrlHash>.<ext>), not just
 * URLs. Two reasons: offline-first grid, and backups/transfers that carry the
 * actual artwork so a new device does not re-download 1,400 images.
 *
 * Files come straight from NU's CDN, which serves plain HTTP clients (verified
 * 2026-10-09) — no WebView pacing needed, but downloads stay sequential and
 * size-capped to be polite.
 */
object CoverStore {

    private const val MAX_BYTES = 8 * 1024 * 1024
    private val COVER_UA = "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"

    fun dir(context: Context): File = context.filesDir.resolve("covers").apply { mkdirs() }

    /** Filename prefix for a series — matches the series_cache hashing scheme. */
    private fun prefix(seriesUrl: String) = seriesUrl.hashCode().toUInt().toString()

    fun localFile(context: Context, seriesUrl: String): File? {
        if (seriesUrl.isBlank()) return null
        val p = prefix(seriesUrl)
        return dir(context).listFiles()?.firstOrNull { it.name.startsWith("$p.") }
    }

    fun hasLocal(context: Context, seriesUrl: String): Boolean =
        localFile(context, seriesUrl) != null

    fun count(context: Context): Int = dir(context).listFiles()?.size ?: 0

    /**
     * Download [coverUrl] into the store for [seriesUrl]. Returns the file, or
     * null for blank/placeholder URLs, HTTP errors, or junk responses.
     */
    suspend fun download(context: Context, seriesUrl: String, coverUrl: String): File? {
        if (seriesUrl.isBlank() || usableCoverUrl(coverUrl).isBlank()) return null
        if (hasLocal(context, seriesUrl)) return localFile(context, seriesUrl)
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(coverUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", COVER_UA)
                if (connection.responseCode !in 200..299) return@withContext null
                val ext = extension(connection.contentType, coverUrl)
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
                val file = dir(context).resolve("${prefix(seriesUrl)}.$ext")
                file.writeBytes(bytes)
                DebugLog.log("COVER", "stored ${file.name} (${bytes.size}B) '$seriesUrl'")
                file
            } catch (e: Exception) {
                DebugLog.log("COVER", "download failed '$coverUrl'", e)
                null
            }
        }
    }

    private fun extension(contentType: String?, url: String): String {
        val fromUrl = url.substringAfterLast('.', "").lowercase()
        if (fromUrl.length in 2..4 && fromUrl.all { it.isLetterOrDigit() }) return fromUrl
        return when {
            contentType?.contains("png") == true -> "png"
            contentType?.contains("webp") == true -> "webp"
            contentType?.contains("gif") == true -> "gif"
            else -> "jpg"
        }
    }
}
