package com.patchscratch.novelnotifier

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny rotating file log, pulled with
 *   adb shell run-as com.patchscratch.novelnotifier cat files/debug.log
 *
 * Records the things we cannot see any other way: which Cloudflare detector
 * fired and on what URL, scrape outcomes (rows parsed, covers found), Coil
 * image failures, prefetch progress, RSS errors. Keep entries one line so
 * the file greps well. No-op until DebugLog.init(context) runs.
 */
object DebugLog {
    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()
    private var logFile: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) logFile = context.filesDir.resolve("debug.log")
        }
    }

    fun log(tag: String, message: String, error: Throwable? = null) {
        val file = logFile ?: return
        val line = buildString {
            append(stamp.format(Date()))
            append(" [").append(tag).append("] ")
            append(message.replace('\n', ' '))
            error?.let {
                append(" — ")
                append(it.javaClass.simpleName)
                it.message?.let { m -> append(": ").append(m.replace('\n', ' ')) }
            }
        }
        try {
            synchronized(lock) {
                if (file.length() > MAX_BYTES) rotate(file)
                file.appendText(line + "\n")
            }
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    /** Keep the newest quarter of the file; drop the rest. */
    private fun rotate(file: File) {
        val text = file.readText()
        val keep = text.length / 4
        val cut = text.indexOf('\n', text.length - keep).let { if (it < 0) text.length else it }
        file.writeText(text.substring(cut).trimStart() + "\n")
    }
}
