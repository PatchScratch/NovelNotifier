package com.patchscratch.novelnotifier

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-book chapter translations for the LOCAL LIBRARY (v3.0.1): the epub
 * files themselves are never rewritten — translations live beside them as
 * JSON sidecars, exactly like the site books' EpubStore translations.
 * Shape: filesDir/local_translations/<bookId>.json → { index: [paras] }.
 * NOT in backups (regenerable, same policy as the epubs).
 */
object LocalTranslationStore {

    private fun dir(context: Context) = File(context.filesDir, "local_translations").apply { mkdirs() }
    private fun file(context: Context, bookId: String) = File(dir(context), "$bookId.json")

    fun get(context: Context, bookId: String, chapterIndex: Int): List<String>? = runCatching {
        val f = file(context, bookId)
        if (!f.isFile) return null
        val arr = JSONObject(f.readText()).optJSONArray(chapterIndex.toString()) ?: return null
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrNull()

    fun put(context: Context, bookId: String, chapterIndex: Int, paragraphs: List<String>) {
        runCatching {
            val f = file(context, bookId)
            val root = if (f.isFile) JSONObject(f.readText()) else JSONObject()
            root.put(chapterIndex.toString(), org.json.JSONArray(paragraphs))
            f.writeText(root.toString())
        }
    }

    fun has(context: Context, bookId: String, chapterIndex: Int): Boolean =
        get(context, bookId, chapterIndex) != null

    /** How many chapters of the book carry a translation (menu coverage). */
    fun translatedCount(context: Context, bookId: String, chapterCount: Int): Int {
        val f = file(context, bookId)
        if (!f.isFile) return 0
        return runCatching {
            JSONObject(f.readText()).length().coerceAtMost(chapterCount)
        }.getOrDefault(0)
    }

    fun deleteBook(context: Context, bookId: String) {
        runCatching { file(context, bookId).delete() }
    }
}
