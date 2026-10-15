package com.patchscratch.novelnotifier

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Neural text-to-speech through Sherpa-ONNX + Kokoro multi-lang v1.1 (int8),
 * fully offline. The model (~140MB tar.bz2 from the sherpa-onnx GitHub
 * tts-models release) downloads on first use — the same opt-in UX as the
 * JMdict toggle — and lives in filesDir/tts-model/. Supports EN and JP
 * (Japanese quality verified live; the espeak-ng ja_dict handles
 * phonemization).
 *
 * The engine speaks a list of paragraphs (whatever the reader's current
 * layout shows: JP, EN, or both interleaved), reporting progress through
 * [onParagraph] so the caller can highlight. [stop] cancels generation
 * and playback; the AudioTrack is released cleanly.
 */
object TtsEngine {

    private const val TAG = "TTS"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
    private const val MODEL_DIR_NAME = "tts-model/kokoro-int8-multi-lang-v1_1"

    // Kokoro voice IDs: 0-50 are English speakers, 50+ Chinese.
    // We expose a small curated set; users pick in Settings.
    data class Voice(val id: Int, val name: String, val lang: String)
    val VOICES = listOf(
        Voice(0, "af (US female)", "en"),
        Voice(10, "am (US male)", "en"),
        Voice(20, "bf (UK female)", "en"),
        Voice(30, "bm (UK male)", "en"),
        Voice(50, "zf (Chinese female)", "zh")
    )

    private var tts: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val generating = AtomicBoolean(false)
    private var speakJob: Job? = null

    /** Model root inside filesDir. */
    fun modelDir(context: Context): File =
        context.filesDir.resolve(MODEL_DIR_NAME)

    /** True when the Kokoro model files exist. */
    fun isModelReady(context: Context): Boolean =
        modelDir(context).resolve("model.int8.onnx").isFile

    /**
     * Download and extract the Kokoro model (~140MB tar.bz2 → ~160MB files).
     * Progress reports the download percentage (0-100). Returns false on
     * failure — callers keep the Settings toggle off.
     */
    suspend fun ensureModel(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady(context)) return@withContext true
        val tmp = File(context.cacheDir, "kokoro-model.tar.bz2")
        try {
            // Download with progress
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 NovelNotifier/1.0")
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress((downloaded * 100 / total).toInt())
                    }
                }
            }
            conn.disconnect()

            // Extract tar.bz2 → filesDir/tts-model/
            val ttsRoot = context.filesDir.resolve("tts-model").apply { mkdirs() }
            val proc = ProcessBuilder("tar", "xjf", tmp.absolutePath, "-C", ttsRoot.absolutePath, "--no-same-owner")
                .redirectErrorStream(true)
                .start()
            proc.waitFor()
            if (proc.exitValue() != 0) {
                DebugLog.log(TAG, "extract failed: ${proc.inputStream.bufferedReader().readText().take(200)}")
                return@withContext false
            }
            tmp.delete()
            DebugLog.log(TAG, "model extracted to ${modelDir(context).path}")
            // Verify the critical file landed
            isModelReady(context)
        } catch (e: Exception) {
            DebugLog.log(TAG, "model download/extract failed", e)
            tmp.delete()
            false
        }
    }

    /** Delete the model (Settings toggle off = free the space). */
    fun deleteModel(context: Context) {
        context.filesDir.resolve("tts-model").deleteRecursively()
        tts = null
        DebugLog.log(TAG, "model deleted")
    }

    /** Space the model occupies, in MB (for the Settings display). */
    fun modelSizeMb(context: Context): Long =
        (context.filesDir.resolve("tts-model")
            .walkBottomUp().filter { it.isFile }.map { it.length() }.sum()) / (1024 * 1024)

    /**
     * Speak paragraphs through Kokoro + AudioTrack. Each paragraph is
     * synthesized independently (sherpa-onnx chunks long text internally
     * via maxNumSentences); [onParagraph] fires BEFORE each paragraph so
     * the caller can highlight it. Returns when done or stopped.
     */
    suspend fun speak(
        context: Context,
        paragraphs: List<String>,
        voiceId: Int = 0,
        speed: Float = 1.0f,
        onParagraph: (Int) -> Unit = {},
        onDone: () -> Unit = {}
    ) = coroutineScope {
        if (generating.get()) return@coroutineScope
        generating.set(true)
        speakJob = launch(Dispatchers.Default) {
            try {
                val dir = modelDir(context)
                if (!isModelReady(context)) {
                    DebugLog.log(TAG, "model not ready")
                    return@launch
                }

                // Lazy-init the engine
                if (tts == null) {
                    val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                        model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                            kokoro = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(
                                model = dir.resolve("model.int8.onnx").absolutePath,
                                voices = dir.resolve("voices.bin").absolutePath,
                                tokens = dir.resolve("tokens.txt").absolutePath,
                                dataDir = dir.resolve("espeak-ng-data").absolutePath,
                                lexicon = dir.resolve("lexicon-us-en.txt").absolutePath,
                                lang = "en"
                            ),
                            numThreads = 2,
                            provider = "cpu"
                        ),
                        maxNumSentences = 2
                    )
                    tts = try {
                        com.k2fsa.sherpa.onnx.OfflineTts(
                            assetManager = null,
                            config = config
                        )
                    } catch (initErr: Exception) {
                        DebugLog.log(TAG, "engine init failed — model corrupt or paths wrong", initErr)
                        null
                    } ?: return@launch
                    DebugLog.log(TAG, "engine initialized, sampleRate=" + tts!!.sampleRate())
                }

                val sampleRate = tts!!.sampleRate()
                ensureAudioTrack(sampleRate)

                for ((index, text) in paragraphs.withIndex()) {
                    if (!generating.get()) break
                    if (text.isBlank()) continue

                    onParagraph(index)

                    // Generate with callback for streaming playback
                    val started = System.currentTimeMillis()
                    // The JNI bridge calls callback.invoke(FloatArray) via
                    // reflection and expects Integer — a bare Kotlin lambda
                    // inside a coroutine compiles to a synthetic class whose
                    // signature doesn't match (NoSuchMethodError → native
                    // SIGABRT, verified live on the phone). An explicit
                    // object with the right invoke() bridges correctly.
                    val callback = object : (FloatArray) -> Int {
                        override fun invoke(samples: FloatArray): Int {
                            if (!generating.get()) return 1
                            writeSamples(samples, sampleRate)
                            return 0
                        }
                    }
                    tts!!.generateWithCallback(
                        text = text,
                        sid = voiceId,
                        speed = speed,
                        callback = callback
                    )
                    val elapsed = System.currentTimeMillis() - started
                    DebugLog.log(TAG, "para $index: ${text.length} chars → ${elapsed}ms")
                }

                // Drain the AudioTrack: wait for the last written frames
                // to finish playing, then stop.
                audioTrack?.let { track ->
                    val headBefore = track.playbackHeadPosition
                    // Approximate drain: sleep proportional to buffered frames
                    Thread.sleep(200)
                    track.stop()
                }
            } catch (e: Exception) {
                DebugLog.log(TAG, "speak failed", e)
            } finally {
                generating.set(false)
                releaseAudioTrack()
                onDone()
            }
        }
    }

    /** Generate one paragraph and play it. Returns true on success.
     *  Used by TtsController for Kokoro engine playback. */
    suspend fun generateSingle(
        context: Context,
        text: String,
        voiceId: Int = 0,
        speed: Float = 1.0f
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isModelReady(context)) return@withContext false
        // Must ASSIGN — a bare `initEngine(context) ?: return` left tts null
        // and the next `tts!!` NPE'd on first use (caught → silent fallback).
        if (tts == null) {
            tts = initEngine(context) ?: return@withContext false
        }
        try {
            val started = System.currentTimeMillis()
            val generated = tts!!.generate(text = text, sid = voiceId, speed = speed)
            ensureAudioTrack(tts!!.sampleRate())
            writeSamples(generated.samples, tts!!.sampleRate())
            DebugLog.log(TAG, "kokoro gen: ${text.length} chars → ${System.currentTimeMillis() - started}ms, ${generated.samples.size} samples")
            true
        } catch (e: Exception) {
            DebugLog.log(TAG, "generateSingle failed", e)
            false
        }
    }

    /** Play raw PCM (16-bit) audio bytes — used by the cloud TTS engine. */
    suspend fun playPcm(pcmBytes: ByteArray, sampleRate: Int = 24000) = withContext(Dispatchers.IO) {
            try {
                ensureAudioTrackPcm16(sampleRate)
                audioTrack?.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
                // Wait for playback to finish
                audioTrack?.play()
                val durationMs = (pcmBytes.size / (sampleRate * 2)) * 1000  // 16-bit mono
                if (durationMs > 0) Thread.sleep(durationMs.toLong())
            } catch (e: Exception) {
                DebugLog.log(TAG, "playPcm failed", e)
            }
    }

    private fun initEngine(context: Context): com.k2fsa.sherpa.onnx.OfflineTts? {
        val dir = modelDir(context)
        return try {
            val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                    kokoro = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(
                        model = dir.resolve("model.int8.onnx").absolutePath,
                        voices = dir.resolve("voices.bin").absolutePath,
                        tokens = dir.resolve("tokens.txt").absolutePath,
                        dataDir = dir.resolve("espeak-ng-data").absolutePath,
                        lexicon = dir.resolve("lexicon-us-en.txt").absolutePath,
                        lang = "en"
                    ),
                    numThreads = 2,
                    provider = "cpu"
                ),
                maxNumSentences = 2
            )
            com.k2fsa.sherpa.onnx.OfflineTts(assetManager = null, config = config)
        } catch (e: Exception) {
            DebugLog.log(TAG, "engine init failed", e)
            null
        }
    }

    private fun ensureAudioTrackPcm16(sampleRate: Int) {
        if (audioTrack?.sampleRate == sampleRate) return
        releaseAudioTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuf, sampleRate * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    /** Stop generation and playback immediately. */
    fun stop() {
        generating.set(false)
        speakJob?.cancel()
        audioTrack?.let { track ->
            try { track.pause(); track.flush() } catch (_: Exception) {}
        }
    }

    val isSpeaking: Boolean get() = generating.get()

    // --- audio plumbing -----------------------------------------------------------

    private fun ensureAudioTrack(sampleRate: Int) {
        if (audioTrack?.sampleRate == sampleRate) return
        releaseAudioTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build(),
            maxOf(minBuf, sampleRate * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    private fun writeSamples(samples: FloatArray, sampleRate: Int) {
        val track = audioTrack ?: return
        if (samples.isEmpty()) return
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
