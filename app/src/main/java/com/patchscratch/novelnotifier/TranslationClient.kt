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
     * System prompt: web-novel JP→EN with strict JSON-array in/out so
     * paragraphs stay indexed 1:1 with the source (order, count, and blank
     * lines preserved). The glossary (per-series name/term sheet) rides the
     * system message for consistency across chapters.
     */
    fun systemPrompt(glossary: String): String = buildString {
        append(
            "You are a professional Japanese-to-English translator for web novels. " +
                "Translate the user's text into natural, readable English prose, keeping " +
                "the web-novel register (light tone, dialogue-forward). Keep personal " +
                "names consistent; romanize Japanese names in Hepburn. Never summarize, " +
                "never omit, never add commentary. The input is a JSON array of " +
                "paragraphs; reply with ONLY a JSON array of the same length, each item " +
                "the translation of the paragraph at that index. Empty input items " +
                "become empty output items. Reply with the JSON array and nothing else."
        )
        if (glossary.isNotBlank()) {
            append("\n\nGlossary (use these exact renderings):\n").append(glossary)
        }
    }

    /** User message = the paragraphs as a JSON array (indexes stay 1:1). */
    fun userMessage(paragraphs: List<String>): String =
        JSONArray(paragraphs).toString()

    /** The POST body for a chat-completions endpoint. */
    fun requestBody(model: String, system: String, user: String): String =
        JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
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
        chunkSize: Int = 40
    ): List<String>? = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        // Long chapters split into paragraph chunks: cheaper per request,
        // bounded output tokens, and a failed chunk fails only itself.
        var i = 0
        while (i < paragraphs.size) {
            val chunk = paragraphs.subList(i, minOf(i + chunkSize, paragraphs.size))
            val body = requestBody(
                model,
                systemPrompt(glossary),
                userMessage(chunk)
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
