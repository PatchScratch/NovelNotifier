package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.CookieManager
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
    "dailypoint" to ("Daily points" to "日別ポイント"),
    "weeklypoint" to ("Weekly points" to "週別ポイント"),
    "monthlypoint" to ("Monthly points" to "月別ポイント"),
    "yearlypoint" to ("Yearly points" to "年間ポイント"),
    "favnovelcnt" to ("Bookmarks" to "ブックマーク数")
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

/**
 * Fetch a logged-in syosetu.com page with the WebView session's cookies.
 * Follows redirects MANUALLY, re-attaching cookies on every hop — the
 * automatic redirect follower does not reliably re-send custom headers,
 * and an unauthenticated hop 302s to /login/ with an empty body (the
 * "no bookmarks" bug). Cookie NAMES are logged, never values.
 */
suspend fun fetchSyosetuWithSession(url: String): String = withContext(Dispatchers.IO) {
    var current = url
    for (hop in 0 until 5) {
        val conn = java.net.URL(current).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
        val cookies = CookieManager.getInstance().getCookie("https://syosetu.com/").orEmpty()
        if (cookies.isNotBlank()) {
            conn.setRequestProperty("Cookie", cookies)
            if (hop == 0) {
                val names = cookies.split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
                DebugLog.log("SYOSETU", "session cookie names: $names")
            }
        } else if (hop == 0) {
            DebugLog.log("SYOSETU", "NO session cookies for syosetu.com — login first")
        }
        val status = conn.responseCode
        if (status in 300..399) {
            val location = conn.getHeaderField("Location") ?: break
            current = java.net.URL(java.net.URL(current), location).toString()
            DebugLog.log("SYOSETU", "sync redirect $status → $current")
            conn.disconnect()
            continue
        }
        if (status != 200) {
            DebugLog.log("SYOSETU", "sync HTTP $status at $current")
            conn.disconnect()
            return@withContext ""
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        DebugLog.log("SYOSETU", "sync got ${body.length} bytes from $current")
        conn.disconnect()
        return@withContext body
    }
    ""
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

// --- bookmark library (slice 2) ---------------------------------------------

/** One bookmarked novel row from the favorites page. */
data class SyosetuBookmark(
    val ncode: String,
    val title: String,
    val category: String,
    val updated: String
) {
    val url: String get() = "https://ncode.syosetu.com/${ncode.lowercase()}/"
}

/**
 * Scrape the favorites list (favnovelmain) from the CURRENT page of a logged-
 * in WebView. Structure map is defensive: Narou lists novels as links to
 * ncode pages; category context comes from the 分類 selector/tabs. First run
 * on a real account may need tuning — [raw] echoes a snippet for the log.
 */
fun parseSyosetuBookmarks(html: String): List<SyosetuBookmark> {
    val out = mutableListOf<SyosetuBookmark>()
    // current category label if the page shows one (分類 links or selected tab)
    val cat = Regex("""分類[：:]?\s*</[^>]+>\s*<a[^>]*>([^<]+)</a>""").find(html)?.groupValues?.get(1)
        ?: Regex("""class="[^"]*current[^"]*"[^>]*>([^<]{2,20})<""").find(html)?.groupValues?.get(1)
        ?: ""
    // Verified live (2026-10-12): bookmark rows are
    //   <a href="https://ncode.syosetu.com/nXXXX/"><span class="c-up-label
    //   c-up-label--novel-long">連載</span>&nbsp;Title</a>
    // — the anchor content STARTS with a tag (serial/short label span), so
    // capture inner HTML and strip tags; the trailing /?" skips episode
    // links (ncode/1/).
    Regex("""<a [^>]*href="https://ncode\.syosetu\.com/(n[0-9a-z]+)/?"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .forEach { m ->
            val ncode = m.groupValues[1].uppercase()
            if (out.any { it.ncode == ncode }) return@forEach
            var title = m.groupValues[2]
                .replace(Regex("<[^>]*>"), " ")          // label span etc.
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace(Regex("[[:space:]]+"), " ").trim()
            // drop the leading 連載/短編 label the span contributes
            title = title.removePrefix("連載 ").removePrefix("短編 ").trim()
            val idx = m.range.first
            val window = html.substring(idx, minOf(idx + 800, html.length))
            val upd = Regex("""(\d{4}/\d{2}/\d{2} \d{2}:\d{2})""").find(window)?.groupValues?.get(1) ?: ""
            out.add(SyosetuBookmark(ncode, title, cat, upd))
        }
    return out
}

/** Bookmarks cache: filesDir/syosetu/bookmarks.json (URL-hash keyed store stays per-site by construction). */
fun syosetuBookmarksFile(context: Context) =
    context.filesDir.resolve("syosetu").apply { mkdirs() }.resolve("bookmarks.json")

fun saveSyosetuBookmarks(context: Context, bookmarks: List<SyosetuBookmark>) {
    val arr = JSONArray()
    bookmarks.forEach {
        arr.put(JSONObject().put("n", it.ncode).put("t", it.title).put("c", it.category).put("u", it.updated))
    }
    syosetuBookmarksFile(context).writeText(arr.toString())
}

fun loadSyosetuBookmarks(context: Context): List<SyosetuBookmark> = try {
    val arr = JSONArray(syosetuBookmarksFile(context).readText())
    (0 until arr.length()).mapNotNull { i ->
        val row = arr.optJSONObject(i) ?: return@mapNotNull null
        SyosetuBookmark(row.optString("n"), row.optString("t"), row.optString("c"), row.optString("u"))
    }
} catch (e: Exception) { emptyList() }

/** JS payload: structure dump of the favorites page for scrape tuning. */
fun syosetuBookmarksJs(): String = """
(function() {
  try {
    var out = { cats: [], rows: [] };
    document.querySelectorAll('a[href]').forEach(function(a) {
      var h = a.href || '';
      var t = (a.textContent || '').replace(/\s+/g, ' ').trim();
      if (h.indexOf('favnovelmain') !== -1 && t && t.length < 30 && out.cats.length < 20) {
        out.cats.push(t + '|' + h.slice(-40));
      }
      if (h.indexOf('ncode.syosetu.com') !== -1 && t.length > 1 && out.rows.length < 500) {
        out.rows.push({ u: h.slice(-24), t: t.slice(0, 50) });
      }
    });
    var classes = {};
    document.querySelectorAll('[class]').forEach(function(el) {
      var c = String(el.className).split(' ')[0];
      if (c) classes[c] = (classes[c] || 0) + 1;
    });
    var top = Object.entries(classes).sort(function(a,b){ return b[1]-a[1]; }).slice(0, 15)
      .map(function(x){ return x[0] + ':' + x[1]; });
    return JSON.stringify({ cats: out.cats, rowCount: out.rows.length, rows: out.rows.slice(0, 5), classes: top });
  } catch (e) { return 'ERR ' + e; }
})();
""".trimIndent()

// --- advanced search (search.php parity, via the official API) -------------

/** Genre codes from yomou's search form (verified 2026-10-12). */
val SYOSETU_GENRES = listOf(
    101 to "異世界", 102 to "現実世界",
    201 to "ハイファンタジー", 202 to "ローファンタジー",
    301 to "純文学", 302 to "ヒューマンドラマ", 303 to "歴史", 304 to "推理",
    305 to "ホラー", 306 to "アクション", 307 to "コメディー",
    401 to "VRゲーム", 402 to "宇宙", 403 to "空想科学", 404 to "パニック",
    9801 to "ノンジャンル", 9999 to "その他"
)

/** Sort orders (API-verified tokens; labels from search.php). */
val SYOSETU_SEARCH_ORDERS = listOf(
    "hyoka" to ("Total points" to "総合ポイント"),
    "dailypoint" to ("Daily points" to "日間ポイント"),
    "weeklypoint" to ("Weekly points" to "週間ポイント"),
    "monthlypoint" to ("Monthly points" to "月間ポイント"),
    "quarterpoint" to ("Quarterly points" to "四半期ポイント"),
    "yearlypoint" to ("Yearly points" to "年間ポイント"),
    "favnovelcnt" to ("Bookmarks" to "ブックマーク数"),
    "reviewcnt" to ("Reviews" to "レビュー数"),
    "hyokacnt" to ("Raters" to "評価者数"),
    "lengthdesc" to ("Longest" to "文字数"),
    "new" to ("Newest" to "最新掲載順"),
    "old" to ("Oldest updated" to "更新が古い順"),
    "generalfirstup" to ("First posted" to "初回掲載順")
)

/** Three-state element filter: don't care / require / exclude. */
enum class ElementFilter { ANY, REQUIRE, EXCLUDE }

/** Everything the advanced search can express; [toApiParams] maps to the API. */
data class SyosetuSearchParams(
    val word: String = "",
    val notword: String = "",
    val genres: Set<Int> = emptySet(),
    val serial: Boolean? = null,     // null=both, true=連載, false=短編
    val completed: Boolean? = null,  // null=both, true=完結, false=連載中
    val minMinutes: Int = 0,         // reading time (5/10/30/60/…分以上)
    val minLength: Int = 0,          // 文字数以上
    val minPoints: Int = 0,          // 総合ポイント以上
    val r15: ElementFilter = ElementFilter.ANY,
    val zankoku: ElementFilter = ElementFilter.ANY,   // 残酷な描写
    val bl: ElementFilter = ElementFilter.ANY,
    val gl: ElementFilter = ElementFilter.ANY,
    val tensei: ElementFilter = ElementFilter.ANY,    // 異世界転生
    val tenni: ElementFilter = ElementFilter.ANY,     // 異世界転移
    val inTitle: Boolean = true,
    val inSynopsis: Boolean = false,
    val inKeywords: Boolean = true,
    val inAuthor: Boolean = false,
    val order: String = "hyoka",
    val page: Int = 1,
    val ga: String = ""  // "none" pins all-ages results
) {
    fun toApiParams(): Map<String, String> = buildMap {
        if (word.isNotBlank()) put("word", word)
        if (notword.isNotBlank()) put("notword", notword)
        genres.sorted().forEach { put("genre", it.toString()) }  // repeats = OR
        serial?.let { put("nt", if (it) "1" else "2") }
        completed?.let { put("e", if (it) "1" else "0") }
        if (minMinutes > 0) put("mintime", minMinutes.toString())
        if (minLength > 0) put("minlen", minLength.toString())
        if (minPoints > 0) put("min_globalpoint", minPoints.toString())
        fun flag(name: String, f: ElementFilter) {
            when (f) {
                ElementFilter.REQUIRE -> put("is$name", "1")
                ElementFilter.EXCLUDE -> put("not$name", "1")
                ElementFilter.ANY -> {}
            }
        }
        flag("r15", r15); flag("zankoku", zankoku)
        flag("bl", bl); flag("gl", gl)
        flag("tensei", tensei); flag("tenni", tenni)
        // scope flags: API omits a field = search everywhere; setting only
        // chosen ones narrows the word search to those fields
        if (!(inTitle && inSynopsis && inKeywords && inAuthor)) {
            if (inTitle) put("title", "1")
            if (inSynopsis) put("ex", "1")
            if (inKeywords) put("keyword", "1")
            if (inAuthor) put("wname", "1")
        }
        put("order", order)
        put("lim", "20")
        put("st", ((page - 1) * 20).toString())
        if (ga.isNotBlank()) put("ga", ga)
    }
}
