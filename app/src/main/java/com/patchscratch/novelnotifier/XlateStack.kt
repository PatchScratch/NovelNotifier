package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Novel-Mill-style translation lock files (github.com/PatchScratch/novel-mill):
 * prompt.txt / glossary.txt / voices.txt, concatenated into the system
 * message as "## <name>" sections. The mill keeps one folder per book on a
 * desktop; here the same files live under filesDir/xlate/ with TWO layers —
 * a global set and an optional per-series set:
 *
 *   xlate/prompt.txt, glossary.txt, voices.txt            (global)
 *   xlate/<seriesHash>/prompt.txt, glossary.txt, voices.txt (per series)
 *
 * Layering: a per-series prompt.txt REPLACES the global prompt (one persona
 * per series); glossary and voices CONCAT (global entries first, series
 * refinements after). All blank → the app's language-following default
 * prompt. Files are plain UTF-8 text, editable in-app per series.
 *
 * Auto-refinement (the piece the mill lacks): after each successfully
 * translated chapter, a small follow-up request shows the model the chapter
 * pairs plus the current per-series lock files and asks for NEW/CHANGED
 * glossary + voice lines as strict JSON; parsed lines append (deduped) to
 * the series files. Controlled by the xlate_auto_refine pref.
 */
object XlateStack {

    const val PROMPT = "prompt.txt"
    const val GLOSSARY = "glossary.txt"
    const val VOICES = "voices.txt"

    fun dir(context: Context): File = context.filesDir.resolve("xlate").apply { mkdirs() }

    fun seriesDir(context: Context, seriesUrl: String): File =
        dir(context).resolve(seriesUrl.hashCode().toUInt().toString()).apply { mkdirs() }

    data class LockFiles(val prompt: String, val glossary: String, val voices: String)

    /** Merged view of the lock files for a series (blank seriesUrl = global only). */
    fun load(context: Context, seriesUrl: String): LockFiles {
        val g = dir(context)
        val s = if (seriesUrl.isBlank()) null else seriesDir(context, seriesUrl)
        fun globalThenSeries(name: String): String {
            val gl = read(g.resolve(name))
            val sl = s?.let { read(it.resolve(name)) } ?: ""
            return listOf(gl, sl).filter { it.isNotBlank() }.joinToString("\n\n")
        }
        return LockFiles(
            // persona: series prompt wins outright when present
            prompt = (s?.let { read(it.resolve(PROMPT)) } ?: "").ifBlank { read(g.resolve(PROMPT)) },
            glossary = globalThenSeries(GLOSSARY),
            voices = globalThenSeries(VOICES)
        )
    }

    fun read(f: File): String = try {
        if (f.isFile) f.readText().trim() else ""
    } catch (e: Exception) {
        DebugLog.log("XLATE", "lock file read failed '${f.name}'", e); ""
    }

    fun write(f: File, text: String) {
        try {
            f.parentFile?.mkdirs()
            f.writeText(text)
        } catch (e: Exception) {
            DebugLog.log("XLATE", "lock file write failed '${f.name}'", e)
        }
    }

    /** Append [lines] to [f], skipping lines already present anywhere in it. */
    fun appendDeduped(f: File, lines: List<String>) {
        if (lines.isEmpty()) return
        val existing = read(f).split('\n').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val fresh = lines.map { it.trim() }.filter { it.isNotBlank() && it !in existing }
        if (fresh.isEmpty()) return
        val base = read(f)
        write(f, if (base.isBlank()) fresh.joinToString("\n") else base + "\n" + fresh.joinToString("\n"))
        DebugLog.log("XLATE", "lock ${f.parentFile?.name}/${f.name} +${fresh.size} lines")
    }

    // --- refinement ----------------------------------------------------------------

    /** Refinement request's system message. */
    fun refineSystemPrompt(): String =
        "You maintain translation lock files for a Japanese-to-English light novel " +
            "translator. Given the current files and excerpts of a newly translated " +
            "chapter, reply with ONLY a JSON object: {\"glossary\": [\"…\"], " +
            "\"voices\": [\"…\"]} where each array holds NEW or CHANGED lines only — " +
            "glossary lines like \"アナベル = Anabel, baron's daughter\" (name or term = " +
            "exact English rendering, comma-separated notes) and voice lines like " +
            "\"ヨハン: gruff count's daughter, uses archaic 貴様\". Do not repeat lines " +
            "already in the files. Empty arrays when nothing new. No prose, no fences."

    /** Excerpts keep the request small: first/last pairs around a middle sample. */
    fun refineUserMessage(glossary: String, voices: String, jp: List<String>, en: List<String>, sample: Int = 30): String {
        val pairs = jp.indices.mapNotNull { i ->
            val j = jp.getOrNull(i)?.take(200)?.replace('\n', ' ') ?: return@mapNotNull null
            val e = en.getOrNull(i)?.take(200)?.replace('\n', ' ') ?: return@mapNotNull null
            if (j.isBlank() || e.isBlank()) null else "$j => $e"
        }
        val chosen = if (pairs.size <= sample) pairs else pairs.take(sample / 2) + listOf("…") + pairs.takeLast(sample / 2)
        return buildString {
            append("## glossary.txt\n").append(glossary.ifBlank { "(empty)" })
            append("\n\n## voices.txt\n").append(voices.ifBlank { "(empty)" })
            append("\n\n## chapter excerpts\n").append(chosen.joinToString("\n"))
        }
    }

    /** Strict-JSON reply → (glossaryLines, voiceLines); null = unusable. */
    fun parseRefineReply(content: String): Pair<List<String>, List<String>>? {
        val cleaned = content.trim()
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(cleaned.substring(start, end + 1))
            fun lines(key: String) = obj.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            Pair(lines("glossary"), lines("voices"))
        } catch (e: Exception) {
            DebugLog.log("XLATE", "refine reply parse failed", e)
            null
        }
    }

    /**
     * Post-chapter refinement through the configured endpoint: ask for new
     * glossary/voice lines from the chapter's context, then autosave them to
     * the per-series lock files. Failures are logged and swallowed —
     * refinement must never break the reading flow.
     */
    suspend fun refineAfterChapter(
        context: Context,
        seriesUrl: String,
        jp: List<String>,
        en: List<String>,
        endpointBase: String,
        apiKey: String,
        model: String,
        temperature: Double
    ): Boolean = withContext(Dispatchers.IO) {
        if (seriesUrl.isBlank()) return@withContext false
        val s = seriesDir(context, seriesUrl)
        val glossary = read(s.resolve(GLOSSARY))
        val voices = read(s.resolve(VOICES))
        val content = try {
            TranslationClient.postChat(
                endpointBase, apiKey,
                org.json.JSONObject()
                    .put("model", model)
                    .put("temperature", temperature)
                    .put("messages", org.json.JSONArray()
                        .put(org.json.JSONObject().put("role", "system").put("content", refineSystemPrompt()))
                        .put(org.json.JSONObject().put("role", "user").put("content", refineUserMessage(glossary, voices, jp, en))))
            )
        } catch (e: Exception) {
            DebugLog.log("XLATE", "refine request failed", e); return@withContext false
        }
        val parsed = parseRefineReply(content) ?: return@withContext false
        appendDeduped(s.resolve(GLOSSARY), parsed.first)
        appendDeduped(s.resolve(VOICES), parsed.second)
        true
    }
}
