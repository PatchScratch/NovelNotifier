package com.patchscratch.novelnotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
     * paragraphs arrive as indexed JSON objects and must return the same way
     * so the reader can render English under each Japanese paragraph.
     * Indices make line-merging detectable (a skipped index) instead of
     * silently shifting every paragraph after it.
     */
    private const val ALIGNMENT_CONTRACT =
        "\n\nThe input is a JSON array of objects like {\"i\":0,\"t\":\"…\"}, one per " +
            "paragraph. Reply with ONLY a JSON array of objects with the SAME i values, " +
            "each \"e\" (or \"t\") holding that paragraph's translation. Include EVERY " +
            "index — never merge, split, add or drop paragraphs. Keep the original " +
            "Japanese brackets 「」『』（） exactly as given; any quotes you introduce " +
            "must be curly (“ ”) — never straight ASCII double quotes, which break " +
            "JSON. Output the JSON array and nothing else."

    /**
     * The user's JP→EN style rules (their wording, 2026-10-18) — appended
     * under ANY prompt, default or custom, because they govern output the
     * custom wording can't be trusted to repeat: bracket/punctuation
     * preservation, first-person inner voice in （）, romaji onomatopoeia.
     * Deliberately English-only even on JP-locale devices — the rules
     * prescribe the ENGLISH output.
     */
    private const val STYLE_RULES =
        "\n\nJapanese-to-English web novel translator. English only. No refusal, no notes.\n" +
            "Keep 「」『』（）！？…〜ー. Convert only 。 to . and 、 to ,\n" +
            "「」 = spoken. （） = inner voice of the person who just acted or spoke, " +
            "FIRST PERSON (I/me). Never she/he thought.\n" +
            "Onomatopoeia: romaji only (Bassaa, Kyaa, Bassa—). Keep elongation with — " +
            "or extra vowels.\n" +
            "Do not leave バッサー/きゃー in kana. Do not replace with Slash, WHOOSH, BAM.\n" +
            "Example:\n" +
            "JP: 「きゃー、エッチ！」\n" +
            "（見ちゃった……！ 何で毎朝あんなに元気なのかな。）\n" +
            "EN: 「Kyaa, you perv!」\n" +
            "（I saw it…! Why is he that lively every morning?）"

    /**
     * Names/terms that ship in the prompt ahead of the user's glossary.txt
     * (user-supplied 2026-10-18). Harmless for series that never mention
     * them — an entry only fires when its Japanese form appears.
     */
    private const val DEFAULT_GLOSSARY =
        "如月千紗 = Chisa Kisaragi\n" +
            "小泉晴道 = Harumichi Koizumi\n" +
            "幼馴染 = childhood friend"

    /** Full system message: user prompt + style rules + alignment contract + glossary. */
    fun systemPrompt(custom: String, glossary: String): String = systemPrompt(custom, glossary, "")

    /**
     * Full system message with Novel-Mill-style lock sections: glossary and
     * voices ride under their "## <file>.txt" headers exactly like the mill
     * concatenates them, so hand-written mill files paste straight in.
     */
    fun systemPrompt(custom: String, glossary: String, voices: String): String = buildString {
        append(custom.trim().ifBlank { defaultPrompt() })
        append(STYLE_RULES)
        append(ALIGNMENT_CONTRACT)
        val mergedGlossary = listOf(DEFAULT_GLOSSARY, glossary.trim())
            .filter { it.isNotBlank() }.joinToString("\n")
        if (mergedGlossary.isNotBlank()) {
            append("\n\n## glossary.txt\n").append(mergedGlossary)
        }
        if (voices.isNotBlank()) {
            append("\n\n## voices.txt\n").append(voices.trim())
        }
    }

    /** User message = the paragraphs as an indexed JSON array (i is 1:1). */
    fun userMessage(paragraphs: List<String>): String =
        JSONArray(paragraphs.withIndex().map { (i, p) ->
            JSONObject().put("i", i).put("t", p)
        }).toString()

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
        val slice = cleaned.substring(start, end + 1)
        val arr = try {
            JSONArray(slice)
        } catch (e: Exception) {
            // Models emit unescaped dialogue quotes inside string values
            // (“"—Twelve years," I thought.”) which kills strict JSON. Repair:
            // a quote only closes a string when the next non-space char is a
            // structural , or ] — every other inner quote gets escaped.
            val repaired = try {
                JSONArray(escapeInnerQuotes(slice))
            } catch (e2: Exception) {
                DebugLog.log("XLATE", "reply parse failed", e)
                return null
            }
            DebugLog.log("XLATE", "repaired ${repaired.length()}-item reply with inner-quote fix")
            repaired
        }
        // Indexed objects {"i":n,"e":"…"}: place by i. A couple of skipped
        // indices degrade to blank lines; more than that is a mis-aligned
        // reply we refuse (the caller's corrective retry names the count).
        if (arr.length() > 0 && arr.opt(0) is JSONObject) {
            val byIndex = HashMap<Int, String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return null
                val idx = o.optInt("i", -1)
                if (idx < 0 || idx >= expectedCount) return null
                byIndex[idx] = o.optString("e").ifBlank { o.optString("t") }
            }
            val missing = expectedCount - byIndex.size
            if (missing > 2) return null
            if (missing > 0) DebugLog.log("XLATE", "indexed reply missing $missing items — leaving blanks")
            return List(expectedCount) { byIndex[it] ?: "" }
        }
        // Legacy plain-string array.
        if (arr.length() == 0 || arr.length() != expectedCount) return null
        return (0 until arr.length()).map { arr.optString(it) }
    }

    /** Escape string-internal double quotes so the array parses again. */
    internal fun escapeInnerQuotes(slice: String): String {
        val sb = StringBuilder(slice.length + 16)
        var inString = false
        var i = 0
        while (i < slice.length) {
            val c = slice[i]
            when {
                c == '\\' && inString && i + 1 < slice.length -> {
                    sb.append(c).append(slice[i + 1]); i += 2; continue
                }
                c == '"' -> {
                    if (!inString) {
                        inString = true; sb.append(c)
                    } else {
                        // A close quote must be followed by , or ] — and after
                        // a comma the next non-space must open an item or end
                        // the array. "…" dialogue directly before a comma
                        // ("years," I thought.) fails that second test.
                        var j = i + 1
                        while (j < slice.length && slice[j] == ' ') j++
                        val closes = when {
                            j >= slice.length || slice[j] == ']' -> true
                            slice[j] == ',' -> {
                                var k = j + 1
                                while (k < slice.length && slice[k] == ' ') k++
                                k >= slice.length || slice[k] == '"' || slice[k] == ']'
                            }
                            else -> false
                        }
                        if (closes) { inString = false; sb.append(c) }
                        else sb.append("\\\"")
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
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
        temperature: Double = 0.3,
        voices: String = ""
    ): List<String>? = withContext(Dispatchers.IO) {
        // Blank paragraphs never go over the wire — models drop or merge
        // them, which breaks 1:1 alignment for the whole chunk. They come
        // back as blanks in the same positions.
        val sent = paragraphs.withIndex().filter { it.value.isNotBlank() }
        if (sent.isEmpty()) return@withContext paragraphs.map { "" }
        val out = arrayOfNulls<String>(paragraphs.size)
        val failFlag = AtomicBoolean(false)
        // Long chapters split into paragraph chunks: cheaper per request,
        // bounded output tokens, and a failed chunk fails only itself.
        // Two chunks fly concurrently — a 617-paragraph chapter went from
        // ~16 min sequential to ~7 without tripping provider rate limits.
        // (Lock-file auto-refine runs per CHAPTER after translate(), so
        // in-flight chunks share the same glossary — same as before.)
        val starts = (0 until sent.size step chunkSize)
        coroutineScope {
            starts.forEach { i ->
                launch(Dispatchers.IO) {
                    val chunk = sent.subList(i, minOf(i + chunkSize, sent.size)).map { it.value }
                    val system = systemPrompt(customPrompt, glossary, voices)
                    var parsed = runChunk(endpointBase, apiKey, model, system, chunk, temperature)
                    if (parsed == null) {
                        // Count mismatch is the usual rejection (the model
                        // merges short lines despite the contract). One
                        // corrective retry naming the exact item count
                        // recovers most of them.
                        parsed = runChunk(
                            endpointBase, apiKey, model,
                            system + "\n\nCRITICAL: the input has EXACTLY ${chunk.size} indexed items " +
                                "(i = 0…${chunk.size - 1}). Your reply MUST contain one object for EVERY " +
                                "index with its translation in \"e\". Do not merge, split, add or drop items; " +
                                "use curly quotes (“ ”), never straight ASCII double quotes.",
                            chunk, temperature
                        )
                    }
                    if (parsed == null) {
                        failFlag.set(true)
                        return@launch
                    }
                    parsed.forEachIndexed { j, s -> out[sent[i + j].index] = s }
                }
            }
        }
        if (failFlag.get()) return@withContext null
        List(paragraphs.size) { out[it] ?: "" }
    }

    /** One chunk through the endpoint; null = unusable reply or network error. */
    private fun runChunk(
        endpointBase: String,
        apiKey: String,
        model: String,
        system: String,
        chunk: List<String>,
        temperature: Double
    ): List<String>? {
        val content = try {
            post("$endpointBase/chat/completions", apiKey, requestBody(model, system, userMessage(chunk), temperature))
        } catch (e: Exception) {
            DebugLog.log("XLATE", "request failed", e)
            null
        } ?: return null
        val parsed = parseReply(content, chunk.size)
        if (parsed == null) {
            DebugLog.log("XLATE", "unusable reply (expected ${chunk.size} items): ${content.take(160)}")
        }
        return parsed
    }

    /** Raw chat-completions POST for callers building their own body
     * (XlateStack refinement); returns message content, throws on errors. */
    fun postChat(endpointBase: String, apiKey: String, body: JSONObject): String =
        post("$endpointBase/chat/completions", apiKey, body.toString())

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
        // chat-completions envelope: choices[0].message.content. OpenRouter
        // reports upstream provider errors as HTTP 200 with content:null (and
        // sometimes a top-level error object) — treat that as a request
        // failure, not an unusable reply, and surface the finish reason.
        val root = JSONObject(raw)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank() || content == "null") {
            val finish = choice?.optString("finish_reason").orEmpty()
            val err = root.optJSONObject("error")?.optString("message").orEmpty()
            throw java.io.IOException("empty completion (finish_reason=$finish) ${err.take(160)}")
        }
        return content
    }
}
