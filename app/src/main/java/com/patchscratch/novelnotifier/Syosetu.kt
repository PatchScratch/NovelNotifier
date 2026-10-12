package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Syosetu (小説家になろう) provider — the first second-site provider.
 *
 * Discovery and metadata go through the OFFICIAL JSON API
 * (api.syosetu.com/novelapi/api/) — documented, stable, no scraping, no
 * Cloudflare; guideline: max ~1 request/second (we pace 4 s+). Episode text
 * still flows through the existing WebView reader, whose extractor was
 * verified live against Syosetu episodes (works unchanged).
 *
 * Bookmarks/shiori (login features) are NOT in the API — that's the
 * library-sync slice, later. This slice: search, rankings-equivalent
 * ordering, novel detail, read, offline ePubs — all UI in Japanese where the
 * site is Japanese, English labels where it's our chrome.
 */

/** One novel from the Syosetu API. */
data class SyosetuNovel(
    val ncode: String,          // "N9669BK"
    val title: String,
    val writer: String,
    val story: String,          // synopsis
    val genreCode: Int,         // 101/102 異世界, 201/202 ファンタジー…
    val keywords: String,       // space-separated
    val isCompleted: Boolean,   // API "end": 0=連載中 1=完結
    val isShortStory: Boolean,  // API "noveltype": 1=連載 2=短編
    val episodeCount: Int,      // API general_all_no (shorts = 1)
    val lastUpdate: String,     // general_lastup "2015-04-03 23:00:00"
    val points: Int             // yearly_point for ranking context
) {
    val url: String get() = "https://ncode.syosetu.com/${ncode.lowercase()}/"
}

/** One episode row from a novel's ToC page. */
data class SyosetuEpisode(val number: Int, val title: String) {
    val url: String get() = number.toString()
}

/** Parsed ToC page (one page of up to 100 episodes + novel header). */
data class SyosetuToc(
    val title: String,
    val author: String,
    val episodes: List<SyosetuEpisode>,
    val hasMorePages: Boolean   // "エピソード 1～100 を表示中" with more to come
)

// --- API client -------------------------------------------------------------

/** Sort orders we expose in Discover; API has many more. */
val SYOSETU_ORDERS = listOf(
    "dailypoint" to "日別ポイント",
    "weeklypoint" to "週別ポイント",
    "monthlypoint" to "月別ポイント",
    "yearlypoint" to "年間ポイント",
    "favnovelcnt" to "ブックマーク数"
)

suspend fun syosetuApiQuery(params: Map<String, String>): List<SyosetuNovel> =
    withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        } + "&out=json&gzip=0" +
            // field tokens: t/n/w/s/g/k/e + nt=noveltype, ga=general_all_no,
            // gl=general_lastup, p=yearly_point (verified against the API —
            // wrong tokens silently return missing keys, which made every
            // novel look like a 1-episode serial)
            "&of=t-n-w-s-g-k-e-nt-ga-gl-p"
        val connection = URL("https://api.syosetu.com/novelapi/api/?$query").openConnection()
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        // Syosetu asks API consumers for an identifiable User-Agent.
        connection.setRequestProperty("User-Agent", "NovelNotifier/1.0 (github.com/PatchScratch/NovelNotifier)")
        val raw = connection.getInputStream().bufferedReader().use { it.readText() }
        parseSyosetuApi(raw)
    }

suspend fun fetchSyosetuHtml(url: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection()
    connection.connectTimeout = 15_000
    connection.readTimeout = 20_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
    connection.getInputStream().bufferedReader().use { it.readText() }
}

/** API JSON → novels; row 0 is the allcount header. Tolerant of field gaps. */
fun parseSyosetuApi(json: String): List<SyosetuNovel> = try {
    val arr = JSONArray(json)
    (1 until arr.length()).mapNotNull { i ->
        val row = arr.optJSONObject(i) ?: return@mapNotNull null
        val ncode = row.optString("ncode")
        if (ncode.isBlank()) return@mapNotNull null
        SyosetuNovel(
            ncode = ncode,
            title = row.optString("title"),
            writer = row.optString("writer"),
            story = row.optString("story"),
            genreCode = row.optInt("genre"),
            keywords = row.optString("keyword"),
            isCompleted = row.optInt("end") == 1,
            isShortStory = row.optInt("noveltype") == 2,
            episodeCount = row.optInt("general_all_no", 1).coerceAtLeast(1),
            lastUpdate = row.optString("general_lastup"),
            points = row.optInt("yearly_point", row.optInt("daily_point"))
        )
    }
} catch (e: Exception) {
    DebugLog.log("SYOSETU", "api parse failed", e)
    emptyList()
}

// --- ToC parser (pure regex on the novel page HTML) -------------------------

fun parseSyosetuToc(html: String): SyosetuToc {
    val title = Regex("""class="p-novel__title[^"]*"[^>]*>([^<]+)<""").find(html)
        ?.groupValues?.get(1)?.trim()
        ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
    val author = Regex("""作者：\s*<a[^>]*>([^<]+)</a>""").find(html)
        ?.groupValues?.get(1)?.trim().orEmpty()
    // Episode rows: <a href="https://ncode.syosetu.com/n2267be/12/"> title </a>
    // inside the p-eplist; hrefs are also relative (/n2267be/12/).
    val episodes = Regex("""<a href="(?:https://ncode\.syosetu\.com)?/[a-z0-9]+/(\d+)/?"[^>]*>([^<]+)</a>""")
        .findAll(html)
        .mapNotNull { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            SyosetuEpisode(n, m.groupValues[2].trim())
        }
        .distinctBy { it.number }
        .sortedBy { it.number }
        .toList()
    val hasMore = html.contains("p-eplist") && episodes.size >= 100
    return SyosetuToc(title = title, author = author, episodes = episodes, hasMorePages = hasMore)
}

/** Genre code → display name (subset; unmapped codes show the number). */
fun syosetuGenreName(code: Int): String = when (code) {
    101 -> "異世界〔転生〕"
    102 -> "異世界〔転移〕"
    201 -> "ハイファンタジー"
    202 -> "ローファンタジー"
    301 -> "純文学"
    302 -> "ヒューマンドラマ"
    303 -> "歴史"
    304 -> "推理"
    305 -> "ホラー"
    306 -> "アクション"
    307 -> "コメディ"
    else -> "ジャンル $code"
}

/** Map a Syosetu novel to the offline-ePub title-page metadata. */
fun SyosetuNovel.toBookMeta(): EpubStore.BookMeta = EpubStore.BookMeta(
    title = title,
    description = story,
    type = "Web Novel (JP)・${if (isShortStory) "短編" else "連載"}",
    genres = syosetuGenreName(genreCode),
    tags = keywords,
    author = writer,
    year = lastUpdate.take(4),
    completelyTranslated = if (isCompleted) "完結" else "連載中",
    originalPublisher = "小説家になろう"
)
