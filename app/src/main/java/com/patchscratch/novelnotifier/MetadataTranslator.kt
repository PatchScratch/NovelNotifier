package com.patchscratch.novelnotifier

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Free (ML Kit, on-device) vs AI-key translation of Japanese SITE INFO —
 * synopses, catchphrases, tags. Engine choice per the user's design:
 * ML Kit is the FREE DEFAULT (no key needed; ~30MB JP→EN pack downloads on
 * first use, then offline); with an API key configured, the user may switch
 * the engine to their OpenAI-compatible endpoint for higher quality —
 * a synopsis is well under $0.001 (negligible).
 *
 * Boundary rule: display layer ONLY. Site-info translations live in a
 * small sidecar cache and never enter ePubs, backups, or anything sent to
 * a site — books keep native text, exactly like the genre-name rule.
 */
object MetadataTranslator {

    const val PREF_ENGINE = "xlate_meta_engine"        // off | device | api
    const val ENGINE_OFF = "off"
    const val ENGINE_DEVICE = "device"
    const val ENGINE_API = "api"

    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private var modelReady = false

    /** The effective engine: 'api' requires a configured key; without one it
     *  degrades to the free on-device engine (the user's ML-Kit-default). */
    fun effectiveEngine(prefs: android.content.SharedPreferences): String {
        val chosen = prefs.getString(PREF_ENGINE, ENGINE_DEVICE) ?: ENGINE_DEVICE
        val key = prefs.getString("xlate_key", "").orEmpty()
        return when {
            chosen == ENGINE_API && key.isBlank() -> ENGINE_DEVICE
            else -> chosen
        }
    }

    private fun deviceTranslator(): com.google.mlkit.nl.translate.Translator {
        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            translator = Translation.getClient(options)
        }
        return translator!!
    }

    /** Download the JP→EN pack if absent (first use needs network; ~30MB). */
    suspend fun ensureModel() {
        if (modelReady) return
        val t = deviceTranslator()
        val done = suspendCancellableCoroutine<Boolean> { cont ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { e ->
                    DebugLog.log("XLATE", "model download failed", e)
                    if (cont.isActive) cont.resume(false)
                }
        }
        modelReady = done
    }

    /** On-device ML Kit translation; null when the model is unavailable. */
    suspend fun translateOnDevice(text: String): String? {
        if (text.isBlank()) return null
        ensureModel()
        if (!modelReady) return null
        val t = deviceTranslator()
        return suspendCancellableCoroutine { cont ->
            t.translate(text)
                .addOnSuccessListener { out -> if (cont.isActive) cont.resume(out) }
                .addOnFailureListener { e ->
                    DebugLog.log("XLATE", "on-device translate failed", e)
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    /**
     * Site info through the configured OpenAI-compatible endpoint — reuses
     * the chapter client (user's custom prompt + temperature + alignment
     * contract) with the text as a single-paragraph payload.
     */
    suspend fun translateViaApi(
        prefs: android.content.SharedPreferences,
        text: String
    ): String? {
        val key = prefs.getString("xlate_key", "").orEmpty()
        if (key.isBlank() || text.isBlank()) return null
        return TranslationClient.translate(
            endpointBase = prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
            apiKey = key,
            model = prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty(),
            paragraphs = listOf(text),
            customPrompt = prefs.getString("xlate_prompt", "").orEmpty(),
            temperature = prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
        )?.firstOrNull()
    }

    // --- tiny hash-keyed cache (re-translate never, API cost never twice) -----

    private fun cacheFile(context: Context): File =
        context.filesDir.resolve("xlate_meta_cache.json")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    internal fun cacheLookup(file: File, text: String): String? = try {
        if (file.exists()) JSONObject(file.readText()).optString(hash(text)).takeIf { it.isNotBlank() } else null
    } catch (e: Exception) { null }

    internal fun cacheStore(file: File, text: String, translation: String) {
        try {
            val obj = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            obj.put(hash(text), translation)
            // Cap at ~400 entries: drop oldest keys beyond the cap.
            val keys = obj.keys().asSequence().toList()
            if (keys.size > 400) keys.take(keys.size - 400).forEach { obj.remove(it) }
            file.writeText(obj.toString())
        } catch (e: Exception) {
            DebugLog.log("XLATE", "meta cache write failed", e)
        }
    }

    /**
     * Translate one piece of site info per the effective engine, through the
     * cache. null = not translatable right now (off / no model / failure) —
     * callers simply keep showing the original.
     */
    suspend fun translateInfo(context: Context, text: String): String? {
        if (text.isBlank()) return null
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        val engine = effectiveEngine(prefs)
        if (engine == ENGINE_OFF) return null
        val file = cacheFile(context)
        cacheLookup(file, text)?.let { return it }
        val out = when (engine) {
            ENGINE_API -> translateViaApi(prefs, text) ?: translateOnDevice(text)
            else -> translateOnDevice(text)
        } ?: return null
        if (out.isNotBlank() && out != text) {
            withContext(Dispatchers.IO) { cacheStore(file, text, out) }
        }
        return out
    }
}
