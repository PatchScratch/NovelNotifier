package com.patchscratch.novelnotifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-series manual chapter-list state (Wayne, 2026-10-20): the reader's
 * "Chapter list ▸" submenu can pin a site ToC harvested from the WebView
 * and toggle between it and the NU releases. Stored in
 * filesDir/reader_toc.json as {seriesUrl: {url, src, ch: [[label, url],…]}}
 * so the choice survives reader sessions.
 */
object ReaderTocStore {
    private const val FILE = "reader_toc.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): JSONObject = runCatching {
        val f = file(context)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun write(context: Context, root: JSONObject) {
        runCatching { file(context).writeText(root.toString()) }
    }

    fun setToc(context: Context, seriesUrl: String, tocUrl: String, chapters: List<Release>) {
        val root = read(context)
        root.put(
            seriesUrl,
            JSONObject()
                .put("url", tocUrl)
                .put("src", "toc")
                .put(
                    "ch",
                    JSONArray().apply {
                        chapters.forEach { r -> put(JSONArray().put(r.chapter).put(r.url)) }
                    }
                )
        )
        write(context, root)
    }

    fun setSource(context: Context, seriesUrl: String, source: String) {
        val root = read(context)
        val o = root.optJSONObject(seriesUrl) ?: JSONObject().also { root.put(seriesUrl, it) }
        o.put("src", source)
        write(context, root)
    }

    fun clear(context: Context, seriesUrl: String) {
        val root = read(context)
        root.remove(seriesUrl)
        write(context, root)
    }

    /** "toc" when a stored ToC exists AND is the active source; else "nu". */
    fun sourceOf(seriesUrl: String, context: Context): String {
        val o = read(context).optJSONObject(seriesUrl) ?: return "nu"
        return if (o.optString("src") == "toc" && o.has("ch")) "toc" else "nu"
    }

    fun tocUrlOf(seriesUrl: String, context: Context): String =
        read(context).optJSONObject(seriesUrl)?.optString("url").orEmpty()

    fun chaptersOf(seriesUrl: String, context: Context): List<Release>? = runCatching {
        val arr = read(context).optJSONObject(seriesUrl)?.optJSONArray("ch") ?: return null
        (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            val t = pair.optString(0); val u = pair.optString(1)
            if (t.isNotBlank() && u.startsWith("http")) Release(chapter = t, group = "", date = "", url = u) else null
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
