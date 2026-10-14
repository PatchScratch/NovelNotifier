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
 * made. Only the title/author typography the prompt asks for is allowed in
 * the image — extraneous text a custom prompt may add is banned.
 */
object CoverGenClient {

    // Cover model ladder. Seedream is the cheap default ($0.018/cover) but
    // its moderation hard-rejects suggestive (ecchi) synopses with a 400;
    // when it does, generation retries once on Nano Banana, which passes
    // them (~$0.04) and renders the in-image typography better. Only
    // definitive refusals escalate — see shouldFallback.
    const val DEFAULT_MODEL = "bytedance-seed/seedream-5-0-flash"
    const val FALLBACK_MODEL = "google/gemini-nano-banana-2.1"

    // --- request shaping (pure) -----------------------------------------------

    /**
     * Default cover prompt (displayed in the app's language). The
     * {title}/{author}/{genre}/{synopsis} placeholders are substituted at
     * request time. The title and author are rendered INTO the artwork as
     * cover typography matched to the art style (user preference) — the
     * no-text rule only bans extraneous text a custom prompt may re-scope.
     */
    fun defaultPrompt(): String = t(
        "Book cover illustration for a light novel web serial titled \"{title}\" " +
            "by {author}. Genre: {genre}. Premise: {synopsis}. Japanese light novel cover art " +
            "style: expressive character or scene illustration, dramatic composition, rich " +
            "color, vertical book-cover framing. Render the title \"{title}\" and the author " +
            "name \"{author}\" as integrated cover typography whose lettering style, color and " +
            "placement suit the artwork (skip the author line when empty). Absolutely NO other " +
            "text, letters, logos or watermarks.",
        "日本のライトノベル『{title}』（作者：{author}）の表紙イラスト。ジャンル：{genre}。" +
            "あらすじ：{synopsis}。ライトノベル表紙の作風：表情豊かなキャラクターまたは情景のイラスト、" +
            "ドラマチックな構図、豊かな色彩、縦長の書籍表紙構成。タイトル『{title}』と作者名「{author}」を、" +
            "アートの作風に合う書体・色・配置の表紙タイポグラフィとして画像内に描き込むこと" +
            "（作者名が空の場合は省略）。それ以外の文字・活字・ロゴ・透かしは絶対に入れないこと。"
    )

    /**
     * Final prompt from metadata + the user's template ([custom]; blank =
     * language-following default). Placeholders {title}/{author}/{genre}/
     * {synopsis} substitute the metadata (synopsis capped at 500 chars).
     * A template that uses NO placeholder still receives the metadata
     * block appended — a style-only prompt must not lose the story it is
     * illustrating.
     */
    fun promptFor(
        title: String,
        author: String,
        genre: String,
        synopsis: String,
        custom: String = ""
    ): String {
        val template = custom.trim().ifBlank { defaultPrompt() }
        val cleanTitle = title.replace("\"", "'").trim()
        val cleanAuthor = author.replace("\"", "'").trim()
        val cleanGenre = genre.trim()
        val s = synopsis.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val cleanSynopsis = if (s.length > 500) s.take(500) + "…" else s

        val substituted = template
            .replace("{title}", cleanTitle)
            .replace("{author}", cleanAuthor)
            .replace("{genre}", cleanGenre)
            .replace("{synopsis}", cleanSynopsis)
        val hasPlaceholder = "{title}" in template || "{author}" in template ||
            "{genre}" in template || "{synopsis}" in template
        if (hasPlaceholder) return substituted

        // No placeholders — append the metadata so context survives.
        return buildString {
            append(substituted)
            append(" Subject: a light novel web serial")
            if (cleanTitle.isNotBlank()) append(" titled \"").append(cleanTitle).append("\"")
            if (cleanAuthor.isNotBlank()) append(" by \"").append(cleanAuthor).append("\"")
            if (cleanGenre.isNotBlank()) append(". Genre: ").append(cleanGenre)
            if (cleanSynopsis.isNotBlank()) append(". Premise: ").append(cleanSynopsis)
        }
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

    /** One cover through the provider, with the failure reason on loss. */
    sealed class CoverResult {
        data class Success(val image: ByteArray, val ext: String) : CoverResult()

        /** [canFallback] marks failures worth retrying on [FALLBACK_MODEL]. */
        data class Failure(val reason: String, val canFallback: Boolean = false) : CoverResult()
    }

    /**
     * Whether a failed attempt should be retried on [FALLBACK_MODEL]: true
     * only for definitive provider-side trouble — a content-moderation
     * refusal (Seedream rejects ecchi synopses) or a 5xx outage. Account
     * problems (key/credits/rate limit) hit both models equally, and
     * network errors are ambiguous, so those surface to the user instead
     * of double-billing a second attempt.
     */
    fun shouldFallback(status: Int, body: String): Boolean {
        if (status in 500..599) return true
        if (status != 400) return false
        val b = body.lowercase()
        return listOf(
            "content moderation", "content policy", "safety system", "prohibited",
            "flagged", "inappropriate", "sensitive content"
        ).any { it in b }
    }

    /**
     * Human-readable failure reason from a provider error. Providers put a
     * JSON body with error.message in HTTP failures (e.g. Seedream's
     * content-moderation rejection) — surface that text, classified when
     * the status code says something the user can act on.
     */
    fun friendlyError(raw: String?): String {
        val msg = raw.orEmpty()
        val providerMsg = try {
            val start = msg.indexOf('{')
            if (start >= 0) JSONObject(msg.substring(start)).optJSONObject("error")?.optString("message") else null
        } catch (_: Exception) {
            null
        }
        val detail = (providerMsg ?: msg).replace(Regex("\\s+"), " ").trim().take(160)
        val code = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        return when (code) {
            401, 403 -> "API key rejected (check Settings) — $detail"
            402 -> "Provider account out of credits — $detail"
            429 -> "Rate limited — try again shortly. $detail"
            in 500..599 -> "Provider error — try again later. $detail"
            else -> detail.ifBlank { "Network request failed" }
        }
    }

    /**
     * One cover through the model ladder: the requested model, then
     * [FALLBACK_MODEL] once if it refused (see [shouldFallback]). When both
     * fail the reasons are combined so the dialog tells the whole story.
     */
    suspend fun generate(endpointBase: String, apiKey: String, model: String, prompt: String): CoverResult =
        withContext(Dispatchers.IO) {
            val first = attempt(endpointBase, apiKey, model, prompt)
            if (first !is CoverResult.Failure || !first.canFallback || model == FALLBACK_MODEL) first
            else {
                DebugLog.log("COVERGEN", "$model refused (${first.reason.take(120)}); retrying on $FALLBACK_MODEL")
                when (val second = attempt(endpointBase, apiKey, FALLBACK_MODEL, prompt)) {
                    is CoverResult.Success -> second
                    is CoverResult.Failure -> CoverResult.Failure(
                        ("${first.reason} — fallback $FALLBACK_MODEL also failed: ${second.reason}").take(320),
                        canFallback = false
                    )
                }
            }
        }

    private fun attempt(endpointBase: String, apiKey: String, model: String, prompt: String): CoverResult {
        val raw = try {
            post("$endpointBase/images", apiKey, requestBody(model, prompt))
        } catch (e: HttpError) {
            DebugLog.log("COVERGEN", "HTTP ${e.status} via $model: ${e.body.take(160)}")
            return CoverResult.Failure(friendlyError(e.message), shouldFallback(e.status, e.body))
        } catch (e: Exception) {
            DebugLog.log("COVERGEN", "request failed via $model", e)
            return CoverResult.Failure(friendlyError(e.message))
        }
        val parsed = parseReply(raw)
        if (parsed == null) {
            DebugLog.log("COVERGEN", "no image in reply via $model: ${raw.take(160)}")
            return CoverResult.Failure(
                friendlyError(raw.ifBlank { "Provider sent no image" })
            )
        }
        val cost = JSONObject(raw).optJSONObject("usage")?.optDouble("cost") ?: -1.0
        DebugLog.log("COVERGEN", "image ok via $model (${parsed.first.size}B, cost=$cost)")
        return CoverResult.Success(parsed.first, parsed.second)
    }

    /** HTTP failure carrying status + body so callers can classify it. */
    private class HttpError(val status: Int, val body: String) :
        java.io.IOException("covergen HTTP $status: ${body.take(200)}")

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
        if (status !in 200..299) throw HttpError(status, raw)
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
    author: String,
    genre: String,
    synopsis: String,
    onGenerated: () -> Unit
) {
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("xlate_key", "").orEmpty()
    if (apiKey.isBlank()) return  // feature appears once the AI key exists
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failReason by remember { mutableStateOf<String?>(null) }

    fun run() {
        busy = true; failReason = null
        scope.launch {
            val model = prefs.getString("xlate_cover_model", CoverGenClient.DEFAULT_MODEL).orEmpty()
                .ifBlank { CoverGenClient.DEFAULT_MODEL }
            when (val result = CoverGenClient.generate(
                prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                apiKey, model,
                CoverGenClient.promptFor(
                    title, author, genre, synopsis,
                    prefs.getString("xlate_cover_prompt", "").orEmpty()
                )
            )) {
                is CoverGenClient.CoverResult.Success -> {
                    CoverStore.saveGenerated(context, seriesUrl, result.image, result.ext)
                    onGenerated()
                }
                is CoverGenClient.CoverResult.Failure ->
                    failReason = result.reason
            }
            busy = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !busy,
            onClick = { run() }
        ) {
            Text(
                when {
                    busy -> t("Generating cover…", "カバー生成中…")
                    failReason != null -> t("Cover failed — retry (AI)", "カバー生成失敗 — 再試行（AI）")
                    CoverStore.isGenerated(context, seriesUrl) -> t("Regenerate cover (AI)", "カバーを再生成（AI）")
                    else -> t("Generate cover (AI)", "カバーを生成（AI）")
                }
            )
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
    failReason?.let { reason ->
        ErrorDialog(
            title = t("Cover generation failed", "カバー生成に失敗しました"),
            message = reason,
            onRetry = { run() },
            onDismiss = { failReason = null }
        )
    }
}
