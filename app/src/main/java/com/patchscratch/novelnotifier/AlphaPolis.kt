package com.patchscratch.novelnotifier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AlphaPolis (アルファポリス) provider — the JP webnovel big three's third
 * pillar. Logic ported from Wayne's novel_downloader-personal branch
 * (alp_*.py, 2026-10 recon) and re-verified live against the 2026 site
 * renewal:
 *
 * - FULLY HEADLESS after login: every fetch is plain HttpURLConnection
 *   sharing the app WebView cookie jar (CookieManager) — the same trick
 *   Syosetu uses. Only the login itself needs the visible in-app WebView.
 * - Work page (server-rendered): h1.p-content-info__title,
 *   a.p-content-info__author, JSON-LD Article.genre, the detail block
 *   (初回公開日時/更新日時/初回完結日時/文字数), and the ToC embedded as
 *   script[type="application/json"] → chapterEpisodes (chapters with
 *   headings; episodes with url/mainTitle/subTitle/isPublic).
 * - Episode text: the page carries a CSRF token (X-CSRF-TOKEN in page JS)
 *   plus a per-episode id+token; POST /novel/episode_body with the SAME
 *   cookie jar returns the body HTML (br-separated paragraphs). Premium
 *   chapters the logged-in account has paid for flow through the same
 *   POST (the personal branch's core capability). The token is
 *   session-bound: an anonymous 419 taught us the GET that yields the
 *   token must share the jar with the POST.
 *
 * Pacing: site guideline politeness — 1.5s default gap in the personal
 * branch; the app's reader fetches one chapter at a time on user action,
 * so no bulk pacing is wired here yet (bulk download reuses it later).
 */

private const val ALP_BASE = "https://www.alphapolis.co.jp"
private const val ALP_UA = "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"

/** One work row (rankings / search / library). */
data class AlpWork(
    val id: String,             // "243223524/173169133" (novel path pair)
    val title: String,
    val author: String,
    val genre: String = "",     // site label e.g. ファンタジー
    val isCompleted: Boolean = false,
    val episodeCount: Int = 0,
    val charCount: Long = 0L,
    val publishedAt: String = "",  // ISO date
    val lastUpdate: String = "",
    val coverUrl: String = "",
    val introduction: String = "",
    val tags: String = ""
) {
    val url: String get() = "$ALP_BASE/novel/$id"
}

/** One ToC row (chapter heading optional). */
data class AlpEpisode(
    val id: String,             // trailing /episode/<id>
    val title: String,
    val chapterTitle: String = "",
    val isPublic: Boolean = true,
    // The ToC's own (work-scoped) path: /novel/<workId>/episode/<id> —
    // episodes are NOT addressable without the work segment (the 404 the
    // first live read caught).
    val path: String = ""
) {
    val url: String get() = ALP_BASE + path.ifBlank { "/novel/episode/$id" }
}

/** Work detail + ordered ToC (one GET of the work page). */
data class AlpWorkDetail(val work: AlpWork, val episodes: List<AlpEpisode>)

// --- HTTP core ------------------------------------------------------------------

/**
 * CookieJar-fed GET: pulls alphapolis.co.jp cookies from the shared
 * WebView CookieManager so a login done in the visible WebView authorizes
 * these headless requests (premium chapters included).
 */
internal fun alpCookies(): String =
    android.webkit.CookieManager.getInstance().getCookie(ALP_BASE).orEmpty()

internal suspend fun alpFetch(url: String, referer: String? = null): String =
    withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ALP_UA)
        conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
        alpCookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        referer?.let { conn.setRequestProperty("Referer", it) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (conn.responseCode !in 200..299) {
            throw java.io.IOException("alphapolis HTTP ${conn.responseCode} at $url")
        }
        raw
    }

/**
 * The episode-body AJAX: POST /novel/episode_body with the page-issued
 * CSRF + per-episode token. Returns body HTML.
 */
internal suspend fun alpPostEpisodeBody(
    episodeId: String,
    episodeToken: String,
    csrfToken: String,
    referer: String
): String = withContext(Dispatchers.IO) {
    val conn = URL("$ALP_BASE/novel/episode_body").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
    conn.setRequestProperty("User-Agent", ALP_UA)
    conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
    conn.setRequestProperty("X-CSRF-TOKEN", csrfToken)
    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
    conn.setRequestProperty("Referer", referer)
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    alpCookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
    val body = "episode=" + URLEncoder.encode(episodeId, "UTF-8") +
        "&token=" + URLEncoder.encode(episodeToken, "UTF-8")
    conn.setFixedLengthStreamingMode(body.toByteArray().size)
    val out: OutputStream = conn.outputStream
    out.use { it.write(body.toByteArray()) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
    val code = conn.responseCode
    conn.disconnect()
    if (code !in 200..299) {
        // 419 = CSRF mismatch: the token's issuing GET and this POST must
        // share one cookie jar (verified live — anonymous jars 419).
        throw java.io.IOException("episode_body HTTP $code: ${raw.take(120)}")
    }
    raw
}

// --- work page → detail + ToC ------------------------------------------------------

/**
 * Work page HTML → detail + ordered ToC. Pure function: unit-tested
 * against the 2026-renewal fixture.
 */
fun parseAlpWorkPage(html: String): AlpWorkDetail? {
    val title = Regex("""<h1[^>]*class="[^"]*p-content-info__title[^"]*"[^>]*>([^<]+)</h1>""")
        .find(html)?.groupValues?.get(1)?.trim().orEmpty()
    // Author anchor text sits on its own line inside the link (fixture:
    // <a ... class="p-content-info__author c-link ..."> カムイイムカ（…） )
    val author = Regex(
        """<a[^>]*class="[^"]*p-content-info__author[^"]*"[^>]*>\s*([^<]{2,60})""",
        RegexOption.DOT_MATCHES_ALL
    ).find(html)?.groupValues?.get(1)?.trim().orEmpty()
        .ifBlank {
            Regex("""<div class="author"[^>]*>\s*<a[^>]*>([^<]{2,60})</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        }
    // The canonical id comes from og:url — plain /novel/ scans hit
    // recommendation cards first.
    val id = (Regex("""<meta property="og:url" content="[^"]*/novel/(\d+/\d+)"""")
        .find(html)?.groupValues?.get(1)
        ?: Regex("""/novel/(\d+/\d+)""").find(html)?.groupValues?.get(1)).orEmpty()
    if (title.isBlank() || id.isBlank()) return null

    // JSON-LD genre
    val genre = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(html).mapNotNull { m ->
            runCatching {
                val o = JSONObject(m.groupValues[1])
                if (o.optString("@type") == "Article") o.optString("genre") else null
            }.getOrNull()
        }.firstOrNull().orEmpty()

    // synopsis: the abstract block keeps paragraph breaks (meta collapses them)
    val intro = Regex("""<div[^>]*class="[^"]*p-content-info__abstract[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1)
        ?.let { alpStripTags(it) }
        .orEmpty()
        .ifBlank {
            Regex("""<meta name="description" content="([^"]*)"""")
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        }

    // detail block (sidebar list shape, 2026 renewal):
    // <span class="p-sidebar-content-info__detail-label">ラベル</span> <span> 値 </span>
    fun detailValue(label: String): String =
        Regex("""detail-label">\s*$label\s*</span>\s*<span>\s*([^<]*)</span>""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
    val published = detailValue("初回公開日時").replace(".", "-")
    val updated = detailValue("更新日時").replace(".", "-")
    val completed = detailValue("初回完結日時").isNotBlank()
    val chars = detailValue("文字数").replace(",", "").toLongOrNull() ?: 0L

    val cover = Regex("""<meta property="og:image" content="([^"]*)"""")
        .find(html)?.groupValues?.get(1).orEmpty()

    val tags = Regex("""<a[^>]*href="[^"]*tag_ids=\d+[^"]*"[^>]*>([^<]{1,30})</a>""")
        .findAll(html).map { it.groupValues[1].trim() }.distinct().joinToString(" ")

    // ToC: script[type="application/json"] carrying chapterEpisodes
    val episodes = mutableListOf<AlpEpisode>()
    for (m in Regex("""<script type="application/json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
        val t = m.groupValues[1].trim()
        if ("chapterEpisodes" !in t) continue
        runCatching {
            val arr = JSONObject(t).optJSONArray("chapterEpisodes") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val chapter = arr.optJSONObject(i) ?: continue
                val heading = chapter.optString("title").trim()
                val eps = chapter.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val ep = eps.optJSONObject(j) ?: continue
                    val href = ep.optString("url")
                    val epId = href.substringAfterLast("/").trim()
                    if (epId.toLongOrNull() == null) continue
                    val main = ep.optString("mainTitle").trim()
                    val sub = ep.optString("subTitle").trim()
                    val epTitle = (main + if (sub.isNotBlank()) "　$sub" else "").trim()
                    episodes += AlpEpisode(
                        id = epId,
                        title = epTitle,
                        chapterTitle = heading,
                        isPublic = ep.optBoolean("isPublic", true),
                        path = href
                    )
                }
            }
        }
    }

    return AlpWorkDetail(
        work = AlpWork(
            id = id, title = title, author = author, genre = genre,
            isCompleted = completed,
            episodeCount = episodes.count { it.isPublic },
            charCount = chars,
            publishedAt = published, lastUpdate = updated,
            coverUrl = cover, introduction = intro, tags = tags
        ),
        episodes = episodes
    )
}

/** Small HTML → text for the abstract block (keeps paragraph breaks). */
private fun alpStripTags(html: String): String = html
    .replace(Regex("<br\\s*/?>"), "\n")
    .replace(Regex("</p>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .trim()

// --- favorites (bookshelf) + write-back -------------------------------------------

/**
 * Work page HTML → whether the logged-in account has favorited it. The
 * server renders the Vue button's props as JSON at
 * #app-content-cover-favorite-button; anonymous pages carry isFavorite=false.
 */
fun parseAlpFavoriteState(html: String): Boolean = try {
    val i = html.indexOf("app-content-cover-favorite-button")
    if (i < 0) false else {
        val m = Regex("""<script type="application/json">\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html.substring(i))
        m?.let { org.json.JSONObject(it.groupValues[1])
            .optJSONObject("favoriteButton")?.optBoolean("isFavorite", false) } ?: false
    }
} catch (e: Exception) { false }

/**
 * ⚑ write-back (the site's own button semantics): POST add/delete with
 * {citi_cont_id} + the XHR header, from the shared session jar.
 */
suspend fun alpSetFavorite(contentId: String, add: Boolean): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val conn = URL("$ALP_BASE/mypage/favorite/" + if (add) "add" else "delete")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", ALP_UA)
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            alpCookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            val body = "citi_cont_id=" + URLEncoder.encode(contentId, "UTF-8")
            conn.setFixedLengthStreamingMode(body.toByteArray().size)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            DebugLog.log("ALP", "favorite add/remove failed", e)
            false
        }
    }

/**
 * Bookshelf page HTML → works. Rows reuse the ranking card shape minus the
 * rank span; each card links /novel/<author>/<content>.
 */
fun parseAlpBookshelf(html: String): List<AlpWork> {
    val out = mutableListOf<AlpWork>()
    for (m in Regex("""href="(/novel/\d+/\d+)""").findAll(html)) {
        val id = m.groupValues[1].removePrefix("/novel/")
        if (out.any { it.id == id }) continue
        val cover = Regex("""<img src="(https://cdn-image[^"]+)"""")
            .find(html.substring(m.range.last))?.groupValues?.get(1) ?: ""
        val title = Regex("""<a href="/novel/$id"[^>]*>\s*([^<]{4,80})""")
            .find(html)?.groupValues?.get(1)?.trim() ?: continue
        out += AlpWork(id = id, title = title, author = "", coverUrl = cover)
    }
    return out
}

/** The logged-in bookshelf (site-native follows; the sync doctrine). */
suspend fun fetchAlpBookshelf(): List<AlpWork> = try {
    parseAlpBookshelf(alpFetch("$ALP_BASE/mypage/favorite/index/novel"))
} catch (e: Exception) {
    DebugLog.log("ALP", "bookshelf fetch failed", e)
    emptyList()
}

// --- rankings + search -------------------------------------------------------------

/**
 * Ranking page HTML → ordered rows. The ranking tabs are
 * /novel/ranking/{hot,annual,completed} (probed live: daily/weekly/monthly
 * don't exist). Rows share the c-content card shape: rank span → cover →
 * title anchor → author.
 */
fun parseAlpRanking(html: String): List<AlpWork> {
    val out = mutableListOf<Pair<Int, AlpWork>>()
    for (chunk in Regex("""<span class="c-content__rank""").split(html).drop(1)) {
        val rank = Regex("""^[^>]*>\s*(\d+)""").find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        val url = Regex("""href="(/novel/\d+/\d+)"""").find(chunk)?.groupValues?.get(1) ?: continue
        val cover = Regex("""<img src="(https://cdn-image[^"]+)"""").find(chunk)?.groupValues?.get(1) ?: ""
        val title = Regex("""c-content__title">\s*<a[^>]*>\s*([^<]+)""").find(chunk)?.groupValues?.get(1)?.trim() ?: continue
        val author = Regex("""c-content__author-publink">\s*<a[^>]*>\s*([^<]+)""").find(chunk)?.groupValues?.get(1)?.trim() ?: ""
        val abstract = Regex("""c-content__abstract">([^<]+)""").find(chunk)?.groupValues?.get(1)?.trim() ?: ""
        out += rank to AlpWork(
            id = url.removePrefix("/novel/"), title = title, author = author,
            coverUrl = cover, introduction = abstract
        )
    }
    return out.sortedBy { it.first }.map { it.second }
}

/**
 * Search page HTML → rows (GET /search?category=novel&query=…&page=N).
 * Search rows use the older .content-title/.author shape (no rank spans);
 * paging via &page= (server total is huge — cap by caller).
 */
fun parseAlpSearch(html: String): List<AlpWork> {
    val out = mutableListOf<AlpWork>()
    for (chunk in Regex("""<div class="content-title">""").split(html).drop(1)) {
        val m = Regex("""<a href="(/novel/\d+/\d+)"[^>]*>\s*([^<]+)""").find(chunk) ?: continue
        val author = Regex("""class="author">\s*<a[^>]*>\s*([^<]+)""").find(chunk)?.groupValues?.get(1)?.trim() ?: ""
        out += AlpWork(id = m.groupValues[1].removePrefix("/novel/"), title = m.groupValues[2].trim(), author = author)
    }
    return out
}

/** Ranking tab ids in the site's own order (labels for the Discover chips). */
val ALP_RANKING_TABS = listOf("hot" to "急上昇", "annual" to "年間", "completed" to "完結")

/** One ranking page (100 rows). */
suspend fun fetchAlpRanking(tab: String): List<AlpWork> =
    parseAlpRanking(alpFetch("$ALP_BASE/novel/ranking/$tab"))

/** One search page (20 rows). */
suspend fun fetchAlpSearch(query: String, page: Int = 1): List<AlpWork> =
    parseAlpSearch(
        alpFetch("$ALP_BASE/search?category=novel&query=" + URLEncoder.encode(query, "UTF-8") + "&page=$page")
    )

// --- episode page → title + body ----------------------------------------------------

/**
 * Episode page HTML → (title, paragraphs). Two server shapes (2026 renewal):
 * (A) body embedded in div#novelBody (rare) and (B) the page JS's
 * .load(episode_body) with id+token — (B) is authoritative when present.
 * This parses the page only; the POST itself is [alpPostEpisodeBody] fed
 * with the values from [AlpEpisodeTokens].
 */
fun parseAlpEpisodePage(html: String): Pair<String, AlpEpisodeTokens?> {
    val title = Regex("""<h2[^>]*class="[^"]*p-novel-episode__episode-title[^"]*"[^>]*>([^<]+)</h2>""")
        .find(html)?.groupValues?.get(1)?.trim()
        .orEmpty()
        .ifBlank {
            Regex("""<h2[^>]*class="[^"]*episode-title[^"]*"[^>]*>([^<]+)</h2>""")
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        }

    val csrf = Regex("""X-CSRF-TOKEN['"]?\s*:\s*['"]([^'"]+)['"]""")
        .find(html)?.groupValues?.get(1)
    // .load('…episode_body…', {episode: N, token: '…'}) — either key order
    val idTok = Regex(
        """episode_body['"][^}]*['"]?episode['"]?\s*:\s*(\d+)[^}]*['"]?token['"]?\s*:\s*['"]([^'"]+)['"]"""
    ).find(html)?.groupValues?.let { it[1] to it[2] }
        ?: Regex(
            """episode_body['"][^}]*['"]?token['"]?\s*:\s*['"]([^'"]+)['"][^}]*['"]?episode['"]?\s*:\s*(\d+)"""
        ).find(html)?.groupValues?.let { it[2] to it[1] }

    return if (csrf != null && idTok != null) {
        title to AlpEpisodeTokens(episodeId = idTok.first, episodeToken = idTok.second, csrfToken = csrf)
    } else {
        title to null
    }
}

/** Values scraped from an episode page that authorize the body POST. */
data class AlpEpisodeTokens(val episodeId: String, val episodeToken: String, val csrfToken: String)

/**
 * Episode page → paragraph list, end to end (embedded shape only — for
 * pages that carry no AJAX loader). Body HTML uses <br> separators rather
 * than <p> (verified live); paragraph = blank-line-delimited text run.
 */
fun parseAlpBodyHtml(html: String): List<String> {
    val text = html
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("</p>"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&ldquo;", "“").replace("&rdquo;", "”")
    return text.split(Regex("\n\\s*\n"))
        .map { it.replace(Regex("[ \\t]*\\n[ \\t]*"), "\n").trim() }
        .filter { it.isNotBlank() }
}
