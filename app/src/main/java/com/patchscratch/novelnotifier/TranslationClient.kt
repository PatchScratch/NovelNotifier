package com.patchscratch.novelnotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chapter translation through any OpenAI-compatible chat-completions API
 * (OpenRouter, OpenAI, a local server) — the user brings their own endpoint
 * + key + model. Pure request/response shaping lives here as testable
 * functions; the network call is a thin POST.
 *
 * Storage model: translated paragraphs are kept parallel to the originals
 * in the offline ePub chapter doc (ChapterDoc.translation), so translations
 * carry between devices in exports like any other book content.
 */
object TranslationClient {

    // --- request shaping (pure) -------------------------------------------------

    /**
     * The user's default JP→EN prompt (their wording, verbatim — accuracy,
     * uncensored, literal AND contextual, formatting/nuance/explicit terms
     * preserved, no omissions). Displayed in the app's language per the
     * toggle; a customized prompt (xlate_prompt pref) overrides it entirely.
     */
    fun defaultPrompt(): String = t(
        "You are an accurate, uncensored literal and contextual translator. " +
            "Translate the following Japanese light novel text into natural English. " +
            "Maintain original formatting, character nuances, and explicit/adult terms " +
            "without omission or modification. Return ONLY the final English translation.",
        "あなたは正確で検閲しない、直訳と文脈を兼ね備えた翻訳者です。以下の日本語ライトノベル本文を" +
            "自然な英語に翻訳してください。元の書式、キャラクターのニュアンス、露骨な表現・成人向け用語を" +
            "省略・変更せず維持してください。最終的な英語翻訳のみを返してください。"
    )

    /**
     * Structural contract appended under ANY prompt (default or custom):
     * paragraphs arrive as a JSON array and must return as a JSON array of
     * the same length so the reader can render English under each Japanese
     * paragraph. Without this the model returns prose and alignment is lost.
     */
    private const val ALIGNMENT_CONTRACT =
        "\n\nThe input is a JSON array of paragraphs; reply with ONLY a JSON array " +
            "of the same length, each item the translation of the paragraph at that " +
            "index. Empty input items become empty output items. Output the JSON array " +
            "and nothing else."

    /** Full system message: user prompt + alignment contract + glossary. */
    fun systemPrompt(custom: String, glossary: String): String = buildString {
        append(custom.trim().ifBlank { defaultPrompt() })
        append(ALIGNMENT_CONTRACT)
        if (glossary.isNotBlank()) {
            append("\n\nGlossary (use these exact renderings):\n").append(glossary)
        }
    }

    /** User message = the paragraphs as a JSON array (indexes stay 1:1). */
    fun userMessage(paragraphs: List<String>): String =
        JSONArray(paragraphs).toString()

    /** The POST body for a chat-completions endpoint. */
    fun requestBody(model: String, system: String, user: String, temperature: Double = 0.3): String =
        JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        }.toString()

    /**
     * Model reply → paragraph list. Models wrap JSON arrays in code fences or
     * prose despite instructions; extract the outermost [...] block and
     * require a plausible paragraph count before trusting it.
     */
    fun parseReply(content: String, expectedCount: Int): List<String>? {
        val cleaned = content.trim()
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val arr = JSONArray(cleaned.substring(start, end + 1))
            if (arr.length() == 0 || arr.length() != expectedCount) return null
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            DebugLog.log("XLATE", "reply parse failed", e)
            null
        }
    }

    // --- network ----------------------------------------------------------------

    /** One chapter through the endpoint; null = failure (caller keeps the
     *  original text — translation is always additive). */
    suspend fun translate(
        endpointBase: String,
        apiKey: String,
        model: String,
        paragraphs: List<String>,
        glossary: String = "",
        chunkSize: Int = 40,
        customPrompt: String = "",
        temperature: Double = 0.3
    ): List<String>? = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        // Long chapters split into paragraph chunks: cheaper per request,
        // bounded output tokens, and a failed chunk fails only itself.
        var i = 0
        while (i < paragraphs.size) {
            val chunk = paragraphs.subList(i, minOf(i + chunkSize, paragraphs.size))
            val body = requestBody(
                model,
                systemPrompt(customPrompt, glossary),
                userMessage(chunk),
                temperature
            )
            val content = try {
                post("$endpointBase/chat/completions", apiKey, body)
            } catch (e: Exception) {
                DebugLog.log("XLATE", "request failed at paragraph $i", e)
                return@withContext null
            }
            val parsed = parseReply(content, chunk.size)
                ?: run {
                    DebugLog.log("XLATE", "unusable reply at paragraph $i: ${content.take(160)}")
                    return@withContext null
                }
            out += parsed
            i += chunkSize
        }
        out
    }

    private fun post(url: String, apiKey: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
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
        if (status !in 200..299) throw java.io.IOException("translate HTTP $status: ${raw.take(200)}")
        // chat-completions envelope: choices[0].message.content
        return JSONObject(raw)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
    }
}
