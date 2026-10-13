package com.patchscratch.novelnotifier

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI-generated cover art for cover-less series (all sites), through the
 * user's OpenAI-compatible provider. Uses OpenRouter's dedicated
 * /images endpoint (POST {model, prompt, resolution, aspect_ratio} →
 * data[0].b64_json) — verified against the live docs 2026-10-13.
 *
 * Images land in CoverStore like downloaded covers, so grids, backups and
 * transfers treat them identically. A marker file (filesDir/covers_ai/)
 * records provenance so the UI can offer "regenerate" only for art we
 * made. The prompt always forbids text in the image — model typography
 * typos on a book cover cannot be un-seen.
 */
object CoverGenClient {

    const val DEFAULT_MODEL = "bytedance-seed/seedream-5-0-flash"

    // --- request shaping (pure) -----------------------------------------------

    /** Prompt from whatever metadata the site gave us. */
    fun promptFor(title: String, genre: String, synopsis: String): String = buildString {
        append("Book cover illustration for a light novel web serial")
        if (title.isNotBlank()) append(" titled \"").append(title.replace("\"", "'")).append("\"")
        if (genre.isNotBlank()) append(". Genre: ").append(genre)
        if (synopsis.isNotBlank()) {
            val s = synopsis.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            append(". Premise: ").append(s.take(500))
            if (s.length > 500) append("…")
        }
        append(
            ". Japanese light novel cover art style: expressive character or scene " +
                "illustration, dramatic composition, rich color, vertical book-cover framing. " +
                "Absolutely NO text, letters, words, logos or watermarks in the image."
        )
    }

    /** POST body for the /images endpoint. Covers want a modest portrait. */
    fun requestBody(model: String, prompt: String, resolution: String = "1K", aspectRatio: String = "2:3"): String =
        JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("resolution", resolution)
            put("aspect_ratio", aspectRatio)
        }.toString()

    /** Reply → decoded image bytes + file extension; null = no usable image. */
    fun parseReply(raw: String): Pair<ByteArray, String>? {
        val root = JSONObject(raw)
        val item = root.optJSONArray("data")?.optJSONObject(0) ?: return null
        val b64 = item.optString("b64_json")
        if (b64.isBlank()) return null
        val media = item.optString("media_type", "image/png")
        val ext = when {
            media.contains("jpeg") -> "jpg"
            media.contains("webp") -> "webp"
            else -> "png"
        }
        return try {
            // MIME decoder: tolerates the line-wrapped base64 some providers emit
            Pair(java.util.Base64.getMimeDecoder().decode(b64), ext)
        } catch (e: Exception) {
            DebugLog.log("COVERGEN", "b64 decode failed", e)
            null
        }
    }

    /** One cover through the provider; null = failure. */
    suspend fun generate(endpointBase: String, apiKey: String, model: String, prompt: String): Pair<ByteArray, String>? =
        withContext(Dispatchers.IO) {
            val raw = try {
                post("$endpointBase/images", apiKey, requestBody(model, prompt))
            } catch (e: Exception) {
                DebugLog.log("COVERGEN", "request failed", e)
                null
            } ?: return@withContext null
            val parsed = parseReply(raw)
            if (parsed == null) DebugLog.log("COVERGEN", "no image in reply: ${raw.take(160)}")
            else {
                val cost = JSONObject(raw).optJSONObject("usage")?.optDouble("cost") ?: -1.0
                DebugLog.log("COVERGEN", "image ok (${parsed.first.size}B, cost=$cost)")
            }
            parsed
        }

    private fun post(url: String, apiKey: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        val bytes = body.toByteArray()
        conn.setFixedLengthStreamingMode(bytes.size)
        val out: OutputStream = conn.outputStream
        out.use { it.write(bytes) }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (status !in 200..299) throw java.io.IOException("covergen HTTP $status: ${raw.take(200)}")
        return raw
    }
}

/**
 * Shared "Generate cover (AI)" button for series detail screens. Shows for
 * cover-less series (and as "Regenerate" when the current cover is one we
 * generated); spins while working; on success the cover lands in CoverStore
 * and [onGenerated] fires so callers can refresh their own state.
 */
@Composable
fun GenerateCoverButton(
    context: Context,
    seriesUrl: String,
    title: String,
    genre: String,
    synopsis: String,
    hasCover: Boolean,
    onGenerated: () -> Unit
) {
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("xlate_key", "").orEmpty()
    if (apiKey.isBlank()) return  // feature appears once the AI key exists
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !busy,
            onClick = {
                busy = true; failed = false
                scope.launch {
                    val model = prefs.getString("xlate_cover_model", CoverGenClient.DEFAULT_MODEL).orEmpty()
                        .ifBlank { CoverGenClient.DEFAULT_MODEL }
                    val result = CoverGenClient.generate(
                        prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                        apiKey, model,
                        CoverGenClient.promptFor(title, genre, synopsis)
                    )
                    busy = false
                    if (result == null) {
                        failed = true
                    } else {
                        CoverStore.saveGenerated(context, seriesUrl, result.first, result.second)
                        onGenerated()
                    }
                }
            }
        ) {
            Text(
                when {
                    busy -> t("Generating cover…", "カバー生成中…")
                    failed -> t("Cover failed — retry (AI)", "カバー生成失敗 — 再試行（AI）")
                    CoverStore.isGenerated(context, seriesUrl) -> t("Regenerate cover (AI)", "カバーを再生成（AI）")
                    else -> t("Generate cover (AI)", "カバーを生成（AI）")
                }
            )
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}
