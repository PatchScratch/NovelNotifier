package com.patchscratch.novelnotifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-series manual chapter-list state (Wayne, 2026-10-20): the reader's
 * "Chapter list ▸" submenu can pin a site ToC harvested from the WebView
 * and toggle between it and the NU releases. v2 carries the VOLUME
 * structure (heading-split ToCs, per-volume covers — heuristics from
 * Wayne's lnpipe analyse_toc) so EpubStore can split books per volume and
 * mark incomplete ones. Stored in filesDir/reader_toc.json as
 * {seriesUrl: {url, src, ch: [[label, url, vol]], vols: {"N": [title, cover]}}}
 * so the choice survives reader sessions.
 */
object ReaderTocStore {

    /**
     * A permalink the bridge may record (2026-10-24 poisoning fix): an
     * absolute URL that is NOT an intermediate page. The foxteller /rss/
     * overview once won the extraction race, got recorded as a chapter's
     * address, and every later open loaded the overview directly.
     */
    internal fun isValidPermalink(url: String): Boolean =
        url.startsWith("http") && !url.contains("/rss/")

    private const val FILE = "reader_toc.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): JSONObject = runCatching {
        val f = file(context)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun write(context: Context, root: JSONObject) {
        runCatching { file(context).writeText(root.toString()) }
    }

    fun setToc(
        context: Context,
        seriesUrl: String,
        tocUrl: String,
        harvest: ReaderTocHarvest
    ) {
        val root = read(context)
        root.put(
            seriesUrl,
            JSONObject()
                .put("url", tocUrl)
                .put("src", "toc")
                .put(
                    "ch",
                    JSONArray().apply {
                        harvest.entries.forEach { e ->
                            put(JSONArray().put(e.label).put(e.url).put(e.vol))
                        }
                    }
                )
                .put(
                    "vols",
                    JSONObject().apply {
                        harvest.volTitles.keys.forEach { n ->
                            put(
                                n.toString(),
                                JSONArray().put(harvest.volTitles[n].orEmpty())
                                    .put(harvest.volCovers[n].orEmpty())
                            )
                        }
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

    /**
     * extnu→permalink map (Wayne, 2026-10-20): NU rows carry NU's /extnu/
     * links while ToC-downloaded chapters store site permalinks — the
     * reader's cover hop learns the pair whenever a chapter resolves, and
     * row detection consults it. Bounded per series.
     */
    fun mapUrl(context: Context, seriesUrl: String, fromExtNu: String, toPermalink: String) {
        if (!fromExtNu.contains("novelupdates.com") || toPermalink.contains("novelupdates.com")) return
        if (!isValidPermalink(toPermalink)) return   // never record overview pages
        val root = read(context)
        val o = root.optJSONObject(seriesUrl) ?: JSONObject().also { root.put(seriesUrl, it) }
        val map = o.optJSONObject("map") ?: JSONObject().also { o.put("map", it) }
        if (!map.has(fromExtNu) && map.length() >= 500) return
        map.put(fromExtNu, toPermalink)
        write(context, root)
    }

    fun mappedUrl(seriesUrl: String, fromExtNu: String, context: Context): String? =
        read(context).optJSONObject(seriesUrl)?.optJSONObject("map")?.optString(fromExtNu)
            ?.takeIf { isValidPermalink(it) }   // polluted entries self-heal to null

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

    private fun entries(context: Context, seriesUrl: String): List<TocEntry> {
        val arr = read(context).optJSONObject(seriesUrl)?.optJSONArray("ch") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val row = arr.optJSONArray(i) ?: return@mapNotNull null
            val t = row.optString(0)
            val u = row.optString(1)
            if (t.isNotBlank() && u.startsWith("http")) TocEntry(t, u, row.optInt(2, 0)) else null
        }
    }

    fun chaptersOf(seriesUrl: String, context: Context): List<Release>? =
        entries(context, seriesUrl).map { Release(chapter = it.label, group = "", date = "", url = it.url) }
            .takeIf { it.isNotEmpty() }

    /**
     * Volume of a chapter being SAVED (Wayne, 2026-10-20: epubs split by the
     * site's own volume structure). Exact URL first (ToC-driven opens); then
     * label key-matching for NU-routed urls — "c29" matches a ToC "v2c29"
     * entry, a full label matches by containment. 0 = no volume known.
     */
    fun volumeFor(context: Context, seriesUrl: String, chapterUrl: String, label: String): Int {
        val es = entries(context, seriesUrl)
        if (es.isEmpty()) return 0
        es.firstOrNull { it.url == chapterUrl && it.vol > 0 }?.let { return it.vol }
        val key = chapterKey(label)
        val norm = label.trim().lowercase()
        es.firstOrNull { it.vol > 0 && it.label.trim().lowercase().contains(norm) && norm.length > 2 }?.let { return it.vol }
        if (key != null) {
            val (wv, wc) = key
            es.firstOrNull { entry ->
                if (entry.vol <= 0) return@firstOrNull false
                val ek = chapterKey(entry.label) ?: return@firstOrNull false
                (wv == 0 || wv == ek.first) && wc == ek.second
            }?.let { return it.vol }
        }
        return 0
    }

    /** How many chapters the ToC assigns to a volume (incomplete marker). */
    fun volCount(context: Context, seriesUrl: String, vol: Int): Int =
        entries(context, seriesUrl).count { it.vol == vol }

    /** Cover URL for a volume, when the ToC page carried one. */
    fun volCover(context: Context, seriesUrl: String, vol: Int): String? {
        val pair = read(context).optJSONObject(seriesUrl)
            ?.optJSONObject("vols")?.optJSONArray(vol.toString()) ?: return null
        return pair.optString(1).takeIf { it.startsWith("http") }
    }

    /** Volume heading text (for book titles), when known. */
    fun volTitle(context: Context, seriesUrl: String, vol: Int): String? {
        val pair = read(context).optJSONObject(seriesUrl)
            ?.optJSONObject("vols")?.optJSONArray(vol.toString()) ?: return null
        return pair.optString(0).takeIf { it.isNotBlank() }
    }
}
