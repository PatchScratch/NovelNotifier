package com.patchscratch.novelnotifier

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-featured TTS controller with three engines and Voice Aloud /
 * Moon+ Reader style controls (pause, skip paragraph, tap-to-start, live
 * speed). State flows drive the reader's control bar; paragraph callbacks
 * drive highlight.
 *
 * Engines:
 * - ANDROID: the system's TextToSpeech (Google TTS reads Japanese natively;
 *   zero download, instant start, decent quality). This is the DEFAULT.
 * - KOKORO: neural Sherpa-ONNX + Kokoro multi-lang (English only for now;
 *   higher quality than system TTS, ~140MB model download).
 * - CLOUD: OpenAI-compatible /audio/speech endpoint (both languages,
 *   best quality, costs per chapter — bring your own key).
 *
 * Pause/resume: Android TTS has no native pause, so we stop and re-speak
 * the current paragraph (it's fast). Kokoro stops the AudioTrack and
 * re-synthesizes. Speed changes restart the current paragraph at the new
 * rate — standard behavior in the reference apps.
 */
object TtsController {

    enum class Engine { ANDROID, KOKORO, CLOUD }
    enum class State { IDLE, PLAYING, PAUSED, DONE }

    data class TtsState(
        val state: State = State.IDLE,
        val paragraph: Int = -1,
        val total: Int = 0,
        val engine: Engine = Engine.ANDROID,
        val speed: Float = 1.0f
    )

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state

    private var paragraphs: List<String> = emptyList()
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var prefs: SharedPreferences? = null

    // --- engine: Android TextToSpeech ------------------------------------------------
    private var androidTts: TextToSpeech? = null
    private var ttsReady = false

    // --- engine: Kokoro (Sherpa-ONNX) --------------------------------------------------
    // Reuses TtsEngine for model management + AudioTrack playback.

    // --- lifecycle -----------------------------------------------------------------------

    fun initialize(context: Context) {
        if (androidTts == null) {
            prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
            val ctx = context.applicationContext
            androidTts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    // Default to Japanese; the reader text drives the actual language
                    androidTts?.language = Locale.JAPANESE
                    DebugLog.log("TTS", "Android TTS ready")
                } else {
                    DebugLog.log("TTS", "Android TTS init FAILED")
                }
            }
            scope = CoroutineScope(Dispatchers.Default)
        }
    }

    private fun engineFromPrefs(): Engine = when (prefs?.getString("tts_engine", "ANDROID")) {
        "KOKORO" -> Engine.KOKORO
        "CLOUD" -> Engine.CLOUD
        else -> Engine.ANDROID
    }

    private fun speedFromPrefs(): Float =
        prefs?.getString("tts_speed", "1.0")?.toFloatOrNull() ?: 1.0f

    // --- playback control -----------------------------------------------------------------

    /**
     * Start speaking from [startIndex]. [paragraphList] is whatever the
     * reader's layout shows (JP, EN, or both — the caller decides).
     */
    fun start(context: Context, paragraphList: List<String>, startIndex: Int = 0) {
        stop()
        if (paragraphList.isEmpty()) return
        initialize(context)

        paragraphs = paragraphList
        val engine = engineFromPrefs()
        val speed = speedFromPrefs()
        _state.value = TtsState(
            state = State.PLAYING,
            paragraph = startIndex.coerceIn(0, paragraphList.lastIndex),
            total = paragraphList.size,
            engine = engine,
            speed = speed
        )
        playCurrent(context)
    }

    /** Pause: stop playback, remember position. */
    fun pause() {
        if (_state.value.state != State.PLAYING) return
        job?.cancel()
        stopEngine()
        _state.value = _state.value.copy(state = State.PAUSED)
    }

    /** Resume: re-speak the current paragraph. */
    fun resume(context: Context) {
        if (_state.value.state != State.PAUSED) return
        _state.value = _state.value.copy(state = State.PLAYING)
        playCurrent(context)
    }

    /** Stop and reset to IDLE. */
    fun stop() {
        job?.cancel()
        job = null
        stopEngine()
        _state.value = TtsState()   // resets to IDLE, paragraph = -1
    }

    /** Skip to the next paragraph (wraps to done at the end). */
    fun next(context: Context) {
        val s = _state.value
        if (s.state !in listOf(State.PLAYING, State.PAUSED)) return
        val next = s.paragraph + 1
        if (next >= s.total) {
            stop()
            _state.value = _state.value.copy(state = State.DONE)
            return
        }
        _state.value = s.copy(paragraph = next)
        if (s.state == State.PLAYING) {
            stopEngine()
            playCurrent(context)
        }
    }

    /** Skip to the previous paragraph (stops at 0). */
    fun prev(context: Context) {
        val s = _state.value
        if (s.state !in listOf(State.PLAYING, State.PAUSED)) return
        val prev = (s.paragraph - 1).coerceAtLeast(0)
        _state.value = s.copy(paragraph = prev)
        if (s.state == State.PLAYING) {
            stopEngine()
            playCurrent(context)
        }
    }

    /** Tap a paragraph to jump there and play. */
    fun seekTo(context: Context, index: Int) {
        if (paragraphs.isEmpty()) return
        _state.value = _state.value.copy(paragraph = index.coerceIn(0, paragraphs.lastIndex), state = State.PLAYING)
        stopEngine()
        playCurrent(context)
    }

    /** Change speed live — restarts the current paragraph at the new rate. */
    fun setSpeed(context: Context, rate: Float) {
        prefs?.edit()?.putString("tts_speed", rate.toString())?.apply()
        _state.value = _state.value.copy(speed = rate)
        if (_state.value.state == State.PLAYING) {
            stopEngine()
            playCurrent(context)
        }
    }

    /** Toggle play/pause. */
    fun togglePlayPause(context: Context) {
        when (_state.value.state) {
            State.PLAYING -> pause()
            State.PAUSED -> resume(context)
            State.IDLE, State.DONE -> { /* caller should call start() */ }
        }
    }

    // --- engine dispatch --------------------------------------------------------------------

    private fun playCurrent(context: Context) {
        val index = _state.value.paragraph
        val text = paragraphs.getOrNull(index)?.trim() ?: return
        if (text.isBlank()) {
            // Skip blank paragraphs automatically
            if (index < _state.value.total - 1) {
                _state.value = _state.value.copy(paragraph = index + 1)
                playCurrent(context)
            } else {
                stop()
                _state.value = _state.value.copy(state = State.DONE)
            }
            return
        }

        job = scope?.launch(Dispatchers.Default) {
            when (_state.value.engine) {
                Engine.ANDROID -> playAndroid(text)
                Engine.KOKORO -> playKokoro(context, text)
                Engine.CLOUD -> playCloud(context, text)
            }
        }
    }

    private fun stopEngine() {
        try { androidTts?.stop() } catch (_: Exception) {}
        try { TtsEngine.stop() } catch (_: Exception) {}
    }

    // --- engine: Android TTS -------------------------------------------------------------------

    private suspend fun playAndroid(text: String) {
        // Wait for TTS init — can take 500-2000ms on first launch.
        // Poll with generous timeout; speech quality is worth the wait.
        var waited = 0
        while (!ttsReady && waited < 5_000) {
            delay(250)
            waited += 250
        }
        if (!ttsReady) {
            DebugLog.log("TTS", "Android TTS not ready after 5s — giving up")
            return
        }
        val tts = androidTts ?: return
        val speed = _state.value.speed

        // Detect language from the text (Japanese chars → ja, else en)
        val hasJp = text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FAF }
        tts.language = if (hasJp) Locale.JAPANESE else Locale.US
        tts.setSpeechRate(speed)

        val utteranceId = "para_${_state.value.paragraph}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && _state.value.state == State.PLAYING) {
                    advanceAfterParagraph()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                DebugLog.log("TTS", "Android TTS error for $id")
                advanceAfterParagraph() // don't stall on errors
            }
        })

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        // Note: speak() is async — the onDone callback drives advancement.
        // We don't need to suspend here; the coroutine completes immediately.
    }

    // --- engine: Kokoro (Sherpa-ONNX) --------------------------------------------------------

    private suspend fun playKokoro(context: Context, text: String) {
        if (!TtsEngine.isModelReady(context)) {
            DebugLog.log("TTS", "Kokoro model not ready — falling back to Android TTS")
            playAndroid(text)
            return
        }
        val index = _state.value.paragraph
        val speed = _state.value.speed
        val voiceId = prefs?.getInt("tts_voice", 0) ?: 0

        val spoken = TtsEngine.generateSingle(text, voiceId, speed)
        if (spoken != null) {
            if (_state.value.state == State.PLAYING) {
                advanceAfterParagraph()
            }
        } else {
            // Generation failed — try Android as fallback
            DebugLog.log("TTS", "Kokoro generation failed — Android fallback")
            playAndroid(text)
        }
    }

    // --- engine: Cloud API ---------------------------------------------------------------------

    private suspend fun playCloud(context: Context, text: String) {
        val p = prefs ?: return
        val key = p.getString("xlate_key", "").orEmpty()
        if (key.isBlank()) {
            playAndroid(text)
            return
        }
        val hasJp = text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FAF }
        val voice = if (hasJp) "onyx" else "alloy"   // OpenAI voice names as defaults
        val model = p.getString("tts_cloud_model", "tts-1").orEmpty()
        val speed = _state.value.speed

        try {
            val audio = TtsCloudClient.synthesize(
                endpoint = p.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                apiKey = key,
                model = model,
                voice = voice,
                input = text,
                speed = speed
            )
            if (audio != null) {
                TtsEngine.playPcm(audio)
                if (_state.value.state == State.PLAYING) {
                    advanceAfterParagraph()
                }
            } else {
                playAndroid(text)
            }
        } catch (e: Exception) {
            DebugLog.log("TTS", "cloud TTS failed", e)
            playAndroid(text)
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun advanceAfterParagraph() {
        val s = _state.value
        if (s.state != State.PLAYING) return
        val next = s.paragraph + 1
        if (next >= s.total) {
            _state.value = s.copy(state = State.DONE)
            return
        }
        _state.value = s.copy(paragraph = next)
        // Post to the scope to call playCurrent with the context we already have
        // (stored from the last start() call)
        val ctx = lastContext ?: return
        scope?.launch(Dispatchers.Default) {
            playCurrent(ctx)
        }
    }

    @Volatile private var lastContext: Context? = null
    fun setContext(context: Context) {
        lastContext = context.applicationContext
    }

    /** Clean shutdown — call from onCleared/onDestroy. */
    fun shutdown() {
        stop()
        try { androidTts?.shutdown() } catch (_: Exception) {}
        androidTts = null
        ttsReady = false
    }
}
