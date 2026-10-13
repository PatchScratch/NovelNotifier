package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Series-wide translation of STORED chapters — the bulk twin of the bulk
 * downloader. Runs in a process-wide scope (survives navigation, dies with
 * the process — the same lifetime contract as a reading session), one
 * chapter at a time through the chapter client, then auto-refines the
 * series lock files exactly like a single-chapter translate. Chapters that
 * already carry a translation are skipped; unstored chapters are counted
 * as "not downloaded" so the UI can point at Download all first.
 */
object TranslationQueue {

    data class Progress(
        val seriesUrl: String,
        val seriesTitle: String,
        val done: Int,
        val total: Int,        // stored, not-yet-translated chapters
        val storedCount: Int,  // all stored chapters (for the label)
        val running: Boolean
    )

    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state

    @Volatile private var cancelled = false
    private var scope: CoroutineScope? = null

    fun isRunning(seriesUrl: String): Boolean =
        _state.value?.let { it.running && it.seriesUrl == seriesUrl } == true

    fun stop() {
        cancelled = true
    }

    fun start(context: Context, seriesTitle: String, seriesUrl: String) {
        if (isRunning(seriesUrl)) return
        cancelled = false
        val appContext = context.applicationContext
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope?.launch {
            val prefs = appContext.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            val key = prefs.getString("xlate_key", "").orEmpty()
            if (key.isBlank()) {
                _state.value = null
                return@launch
            }
            val docs = EpubStore.readAllStoredDocs(appContext, seriesUrl)
            val pending = docs.filter { it.translation == null }
            val stored = docs.size
            _state.value = Progress(seriesUrl, seriesTitle, 0, pending.size, stored, true)
            DebugLog.log("XLATE", "bulk start: ${pending.size} of ${stored} stored chapters pending")
            var done = 0
            var locks = XlateStack.load(appContext, seriesUrl)
            for (doc in pending) {
                if (cancelled) break
                val result = try {
                    TranslationClient.translate(
                        endpointBase = prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                        apiKey = key,
                        model = prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty(),
                        paragraphs = doc.paragraphs,
                        customPrompt = locks.prompt.ifBlank {
                            prefs.getString("xlate_prompt", "").orEmpty()
                        },
                        glossary = locks.glossary,
                        voices = locks.voices,
                        temperature = prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
                    )
                } catch (e: Exception) {
                    DebugLog.log("XLATE", "bulk chapter failed '${doc.label}'", e)
                    null
                }
                if (result != null) {
                    EpubStore.setTranslation(appContext, seriesTitle, seriesUrl, doc.url, result)
                    if (prefs.getBoolean("xlate_auto_refine", true)) {
                        XlateStack.refineAfterChapter(
                            appContext, seriesUrl, doc.paragraphs, result,
                            prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                            key,
                            prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty(),
                            prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
                        )
                        // the lock files grew — reload for the next chapter
                        locks = XlateStack.load(appContext, seriesUrl)
                    }
                }
                done++
                _state.value = Progress(seriesUrl, seriesTitle, done, pending.size, stored, true)
            }
            _state.value = Progress(seriesUrl, seriesTitle, done, pending.size, stored, false)
            DebugLog.log("XLATE", "bulk finished: $done chapters${if (cancelled) " (stopped)" else ""}")
        }
    }
}
