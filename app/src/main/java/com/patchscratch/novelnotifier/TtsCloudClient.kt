package com.patchscratch.novelnotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud TTS through an OpenAI-compatible /audio/speech endpoint (OpenRouter,
 * OpenAI, local servers). Returns raw audio bytes (MP3/WAV/PCM) from the
 * provider; TtsEngine.playPcm handles playback. The cost per chapter is
 * small (~$0.015 for tts-1, less for tts-1-mini).
 */
object TtsCloudClient {

    private const val TAG = "TTS"

    /**
     * Synthesize [input] text to speech. Returns PCM bytes (the caller
     * plays them), or null on failure.
     */
    suspend fun synthesize(
        endpoint: String,
        apiKey: String,
        model: String,
        voice: String,
        input: String,
        speed: Float = 1.0f
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = endpoint.trimEnd('/') + "/audio/speech"
            val body = JSONObject()
                .put("model", model)
                .put("voice", voice)
                .put("input", input)
                .put("speed", speed)
                .put("response_format", "pcm")   // raw 24kHz 16-bit mono
                .toString()

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            val bytes = body.toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val status = conn.responseCode
            val raw = if (status in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                DebugLog.log(TAG, "cloud TTS HTTP $status")
                null
            }
            conn.disconnect()
            raw
        } catch (e: Exception) {
            DebugLog.log(TAG, "cloud TTS request failed", e)
            null
        }
    }
}
