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
/**
 * Storage label for an AlphaPolis episode (Wayne's rule 2026-10-18: the
 * title "1-1" IS Volume 1 Chapter 1, "2-1" Volume 2 Chapter 1). Volume-
 * labeled episodes get "vNcM" so EpubStore sorts by (volume, chapter) and
 * SPLITS one book per volume — the standing bucket rule; anything else
 * (書籍化記念, extras) falls back to the ToC ordinal "cN" (bare chapter keys
 * sort before all volumes, which suits front-matter).
 */
/** RELEASES-row display label: the episode's own number form ("8-16",
 * "第29話"); extras fall back to the ToC ordinal as 第N話. */
fun alpRowLabel(title: String, tocOrdinal: Int): String {
    val t = title.trim()
    Regex("^([0-9]+-[0-9]+)").find(t)?.let { return it.groupValues[1] }
    Regex("^(第[0-9]+話)").find(t)?.let { return it.groupValues[1] }
    return "第" + tocOrdinal + "話"
}

/** RELEASES-row subtitle: the episode title minus its leading number. */
fun alpRowSubtitle(title: String): String =
    title.trim().replaceFirst(Regex("^([0-9]+-[0-9]+|第[0-9]+話)[\\s　]*"), "")

fun alpEpisodeLabel(title: String, tocOrdinal: Int): String {
    val t = title.trim()
    // Volume-chapter series ("1-1" = Vol 1 Ch 1 — Wayne, 2026-10-18)
    Regex("^([0-9]+)-([0-9]+)$").find(t)?.let {
        return "v" + it.groupValues[1] + "c" + it.groupValues[2]
    }
    // Flat 第N話 series: the 話 number IS the site's own chapter key —
    // prefer it over the ToC ordinal (front-matter extras can shift the
    // ordinal). Bare chapter keys sort before any volume labels.
    Regex("^第([0-9]+)話").find(t)?.let {
        return "c" + it.groupValues[1]
    }
    return "c" + tocOrdinal
}


// --- HTTP core ------------------------------------------------------------------

/**
 * CookieJar-fed GET: pulls alphapolis.co.jp cookies from the shared
 * WebView CookieManager so a login done in the visible WebView authorizes
 * these headless requests (premium chapters included).
 */
/**
 * CookieManager must be read on a thread with a Looper (Main) — a plain IO
 * thread can block indefinitely once WebView's cookie provider is busy
 * (observed live: the bookshelf fetch hung forever after the login WebView
 * ran; the fix hoists the read to Main).
 */
internal suspend fun alpCookies(): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        android.webkit.CookieManager.getInstance().getCookie(ALP_BASE).orEmpty()
    }

internal suspend fun alpFetch(
    url: String,
    referer: String? = null,
    // false = anonymous GET. The LOGGED-IN shape of public long chapters
    // ships the empty paginated viewer with no body loader; the ANONYMOUS
    // page carries the .load(episode_body) loader that works headless
    // (verified live 2026-10-18: episode 1832313 "1-1" quiet-nulled with the
    // session, extracted anonymously). Paywalled chapters serve the paywall
    // either way.
    withCookies: Boolean = true
): String =
    withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ALP_UA)
        conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
        if (withCookies) {
            alpCookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        }
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
 * Anonymous GET that also captures the page's Set-Cookie jar: the anonymous
 * episode page issues its own session + XSRF cookies, and its body-POST
 * token only validates when that exact jar rides along.
 */
internal suspend fun alpFetchAnonWithJar(url: String): Pair<String, String> =
    withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ALP_UA)
        conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val jar = (conn.headerFields?.get("Set-Cookie") ?: emptyList())
            .map { it.substringBefore(";").trim() }
            .filter { it.contains("=") && !it.startsWith("=") }
            .distinct()
            .joinToString("; ")
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            throw java.io.IOException("alphapolis HTTP $code at $url")
        }
        raw to jar
    }

/**
 * The episode-body AJAX: POST /novel/episode_body with the page-issued
 * CSRF + per-episode token. Returns body HTML. [anonCookie] pairs with an
 * anonymously-fetched page: the anonymous page's tokens were issued under
 * ITS OWN session (Set-Cookie), so the POST must present exactly that jar
 * — neither cookie-less (419, verified live) nor the login session (also
 * mismatch).
 */
internal suspend fun alpPostEpisodeBody(
    episodeId: String,
    episodeToken: String,
    csrfToken: String,
    referer: String,
    withCookies: Boolean = true,
    anonCookie: String? = null
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
    when {
        anonCookie != null ->
            if (anonCookie.isNotBlank()) conn.setRequestProperty("Cookie", anonCookie)
        withCookies ->
            alpCookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
    }
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
suspend fun alpSetFavorite(contentId: String, add: Boolean, workId: String? = null): Boolean =
    withContext(Dispatchers.IO) {
        try {
            // 419 without it: the site's $.ajaxSetup sets X-CSRF-TOKEN from a
            // per-page token (session-bound). Only WORK pages carry the
            // ajaxSetup script (the /novel landing has none, verified), so
            // pull the token from the work page itself — the episode-body
            // lesson applied.
            val page = alpFetch(
                workId?.let { "$ALP_BASE/novel/$it" } ?: "$ALP_BASE/novel"
            )
            // Exact site bytes: {'X-CSRF-TOKEN': "token"}
            val csrf = Regex("""'X-CSRF-TOKEN':\s*"([A-Za-z0-9]+)""")
                .find(page)?.groupValues?.get(1)
            if (csrf.isNullOrBlank()) {
                DebugLog.log("ALP", "favorite: no CSRF token on page")
                return@withContext false
            }
            val conn = URL("$ALP_BASE/mypage/favorite/" + if (add) "add" else "delete")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", ALP_UA)
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("X-CSRF-TOKEN", csrf)
            alpCookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            val payload = "citi_cont_id=" + URLEncoder.encode(contentId, "UTF-8")
            conn.setFixedLengthStreamingMode(payload.toByteArray().size)
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
            conn.disconnect()
            if (code !in 200..299) DebugLog.log("ALP", "favorite POST $code: $respBody")
            code in 200..299
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
    // The shelf page nests the works inside a paginator object deep in an
    // embedded JSON blob: …"paginator":{"total":3,…,"items":[{"content":
    // {"citi_cont_id","title","url":"/novel/<a>/<c>","updated","is_rental"}…
    // — find the items array by brace-walking from its first occurrence.
    var idx = html.indexOf(""":""items":[{"content":""")
    if (idx < 0) idx = html.indexOf("""items":[{"content":""")
    if (idx >= 0) {
        var depth = 0
        var start = -1
        var end = -1
        for (i in idx until html.length) {
            when (html[i]) {
                '[' -> { if (depth == 0 && start < 0) start = i; depth++ }
                ']' -> { depth--; if (depth == 0 && start >= 0) { end = i + 1; break } }
            }
        }
        if (start >= 0 && end > start) {
            runCatching {
                val items = org.json.JSONArray(html.substring(start, end))
                for (i in 0 until items.length()) {
                    val content = items.optJSONObject(i)?.optJSONObject("content") ?: continue
                    val title = content.optString("title").trim()
                    val id = content.optString("url").removePrefix("/novel/")
                    if (title.isNotBlank() && id.contains("/") && out.none { it.id == id }) {
                        out += AlpWork(
                            id = id, title = title, author = "",
                            lastUpdate = content.optString("updated").replace(".", "-")
                        )
                    }
                }
            }.onFailure { DebugLog.log("ALP", "shelf items parse: " + it.message) }
        }
    }
    if (out.isNotEmpty()) return out
    // The shelf page uses ABSOLUTE hrefs (https://…/novel/<a>/<c>) — the
    // live log taught this; the ranking/search pages use relative ones.
    for (m in Regex("""href="(?:https://www\.alphapolis\.co\.jp)?(/novel/\d+/\d+)""").findAll(html)) {
        val id = m.groupValues[1].removePrefix("/novel/")
        if (out.any { it.id == id }) continue
        // Card context follows the link; the title anchor re-appears inside it.
        val ctx = html.substring(m.range.last, minOf(m.range.last + 6000, html.length))
        val cover = Regex("""<img src="(https://cdn-image[^"]+)"""").find(ctx)?.groupValues?.get(1) ?: ""
        val title = Regex("""<a href="/novel/$id"[^>]*>\s*([^<]{4,120})""")
            .find(ctx)?.groupValues?.get(1)?.trim()
            ?: ctx.substringAfter("alt=\"", "").substringBefore("\"").take(80).ifBlank { null }
            ?: ctx.substringAfter("title=\"", "").substringBefore("\"").take(80).ifBlank { null }
            ?: continue
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
 * Episode page HTML → (title, paragraphs, images). Two server shapes:
 * (A) body embedded in div#novelBody — the LOGGED-IN page serves this
 * (premium included: the session owns the rental, so the server just
 * renders the text; no AJAX loader, no tokens — the live premium test
 * taught this) and (B) the anonymous page's .load(episode_body) AJAX
 * with id+token. Images: <img> tags inside the body harvest to absolute
 * URLs with their paragraph anchors.
 */
fun parseAlpEpisodePage(html: String): Pair<String, AlpEpisodeTokens?> {
    alpLastTitle = alpEpisodeTitle(html)
    alpLastBodyHtml = alpEmbeddedBody(html)
    val title = alpLastTitle

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

// Side-channel from parseAlpEpisodePage for the embedded-body path (A):
// the title + raw body HTML the server rendered inline.
@Volatile internal var alpLastTitle: String = ""
@Volatile internal var alpLastBodyHtml: String = ""

/** Episode title (both layouts). */
internal fun alpEpisodeTitle(html: String): String =
    Regex("""<h2[^>]*class="[^"]*p-novel-episode__episode-title[^"]*"[^>]*>([^<]+)</h2>""")
        .find(html)?.groupValues?.get(1)?.trim()
        .orEmpty()
        .ifBlank {
            Regex("""<h2[^>]*class="[^"]*episode-title[^"]*"[^>]*>([^<]+)</h2>""")
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        }

/**
 * Path (A): div#novelBody's inner HTML when the server embedded the body
 * (anonymous/logged-out pages; the LoadingEpisode spinner div is stripped).
 * Balanced-close aware: the body NESTS divs (each illustration ships as
 * <div class="story-image">), so the capture cuts at the markers that
 * FOLLOW novelBody's own closing tag and strips only that final </div> —
 * a lazy (.*?)</div> would truncate the chapter at the first illustration
 * (live-caught via Wayne's saved 1-1 page). Blank when the page uses the
 * AJAX loader instead.
 */
internal fun alpEmbeddedBody(html: String): String {
    val open = Regex("""<div[^>]*id="novelBody"[^>]*>""").find(html) ?: return ""
    var body = html.substring(open.range.last + 1)
    // everything after novelBody's close starts here
    body = body.substringBefore("""<div class="c-like""")
    body = body.substringBefore("""<div class="p-novel-episode__wrap-bookmark""")
    body = body.trimEnd()
    if (body.endsWith("</div>")) body = body.removeSuffix("</div>").trimEnd()
    body = body.replace(Regex("""<div[^>]*id="LoadingEpisode"[^>]*>.*?</div>""", RegexOption.DOT_MATCHES_ALL), "")
    return body.trim()
}

/**
 * One chapter, fully headless (no WebView). ANONYMOUS FIRST — the logged-out
 * page's .load(episode_body) loader is URL-bound and its POST returns
 * exactly the URL's chapter (verified live across 1-1..1-3). The login
 * session is the LAST resort and its embedded #novelBody is NEVER trusted:
 * a logged-in episode page renders the VIEWER AT THE ACCOUNT'S LAST READ
 * POSITION, so the server-side body can be a completely different
 * episode's text (live-caught 2026-10-18: 異世界 1-2's sessioned page
 * served a mid-story scene from Wayne's site reading position, which the
 * library re-scrape then stored under 1-2). The session still helps for
 * entitled premium: if its page carries a loader, the sessioned POST is
 * token-bound and safe. Null = paywalled or unfetchable → the reader's
 * visible-WebView hop takes over. Shared by the reader ladder, the
 * per-chapter download, and EpubBulkService.
 */
suspend fun fetchAlpChapterHeadless(chapterUrl: String): ChapterExtract? {
    fun build(paras: List<String>, imgs: List<Pair<Int, String>>, title: String): ChapterExtract? =
        if (paras.isEmpty()) null
        else ChapterExtract(title = title, paragraphs = paras, images = imgs)

    return try {
        // (1) ANONYMOUS first: loader POST is URL-bound → correct chapter
        // for every public episode; the anon page's own Set-Cookie jar must
        // ride with the POST (its token binds to that anon session).
        val (anonPage, anonJar) = alpFetchAnonWithJar(chapterUrl)
        val (anonTitle, anonTokens) = parseAlpEpisodePage(anonPage)
        if (anonTokens != null) {
            val body = alpPostEpisodeBody(
                anonTokens.episodeId, anonTokens.episodeToken, anonTokens.csrfToken,
                chapterUrl, anonCookie = anonJar
            )
            val (paras, imgs) = parseAlpBodyWithImages(body)
            if (paras.isNotEmpty()) {
                DebugLog.log("ALP", "headless chapter (B-anon): ${paras.size} paragraphs, ${imgs.size} images")
                return build(paras, imgs, anonTitle)
            }
        } else {
            // anonymous server-rendered shape (no loader): #novelBody is
            // URL-bound here — no session, no viewer position. Trusted.
            val (paras, imgs) = parseAlpBodyWithImages(alpLastBodyHtml)
            if (paras.isNotEmpty()) {
                DebugLog.log("ALP", "headless chapter (A-anon): ${paras.size} paragraphs, ${imgs.size} images")
                return build(paras, imgs, anonTitle.ifBlank { alpLastTitle })
            }
        }

        // (2) SESSIONED fallback — this is where PAID chapters live (the
        // anonymous page never carries them; Wayne: "anonymous first won't
        // help when I pull paid chapters"). Two sub-paths:
        //   (2a) a sessioned loader — its POST is token/URL-bound, safe;
        //   (2b) the sessioned EMBEDDED body — trustworthy ONLY for owned
        // premium (the server renders the chapter the account owns). A
        // NOT-owned episode page instead shows the purchase UI, and a
        // logged-in FREE page renders the viewer at the account's
        // last-read position — both poison. Accept (2b) only when the page
        // carries no purchase call-to-action, and log the path so paid
        // pulls stay auditable.
        val page = alpFetch(chapterUrl)
        val (title, tokens) = parseAlpEpisodePage(page)
        if (tokens != null) {
            val body = alpPostEpisodeBody(
                tokens.episodeId, tokens.episodeToken, tokens.csrfToken, chapterUrl
            )
            val (paras, imgs) = parseAlpBodyWithImages(body)
            build(paras, imgs, title).also {
                DebugLog.log("ALP", "headless chapter (B-session): ${paras.size} paragraphs, ${imgs.size} images")
            }
        } else if (!page.contains("このエピソードを購入") && !page.contains("エピソードをレンタル")) {
            val (paras, imgs) = parseAlpBodyWithImages(alpLastBodyHtml)
            if (paras.isNotEmpty()) {
                DebugLog.log("ALP", "headless chapter (A-session): ${paras.size} paragraphs, ${imgs.size} images")
            }
            build(paras, imgs, title.ifBlank { alpLastTitle })
        } else {
            DebugLog.log("ALP", "headless chapter: paywalled (purchase UI on session page)")
            null
        }
    } catch (e: Exception) {
        DebugLog.log("ALP", "headless chapter fetch failed", e)
        null
    }
}

/**
 * Body HTML → paragraphs + images (document order, paragraph-anchored).
 * Relative image URLs absolutize against the CDN/site base.
 */
fun parseAlpBodyWithImages(html: String): Pair<List<String>, List<Pair<Int, String>>> {
    val paragraphs = mutableListOf<String>()
    val images = mutableListOf<Pair<Int, String>>()

    /**
     * Illustration URL choice (live-caught 2026-10-18 via Wayne's saved
     * 1-1 page): each illustration ships as <a href="…/story_image/<id>/
     * <uuid>.jpeg"><img src="…/story_images/<id>/pc/<uuid>.jpeg">. The
     * ANCHOR href is the original and serves openly (200); the img src's
     * /pc/ variant sits behind a CloudFront block (403 — it starved epub
     * media downloads and Coil). Prefer the anchor, src as fallback.
     */
    fun bestImgUrl(anchorHref: String, imgSrc: String): String {
        val candidates = listOf(anchorHref, imgSrc)
        return candidates.firstOrNull { it.startsWith("http") && it.contains("story_image") }
            ?: candidates.firstOrNull { it.startsWith("http") }
            ?: imgSrc
    }

    // walk <p>…</p> and illustrations (anchor-wrapped or bare <img>) in order
    val token = Regex(
        """<a[^>]+href="(https?://[^"]+)"[^>]*>\s*<img[^>]+src="([^"]+)"|<p[^>]*>(.*?)</p>|<img[^>]+src="([^"]+)"""",
        RegexOption.DOT_MATCHES_ALL
    )
    for (m in token.findAll(html)) {
        val anchorImg = m.groupValues[2].isNotBlank()
        val bareImg = m.groupValues[4].isNotBlank()
        if (anchorImg || bareImg) {
            val anchor = m.groupValues[1]
            val src = if (anchorImg) m.groupValues[2] else m.groupValues[4]
            var u = bestImgUrl(anchor, src)
            if (u.startsWith("//")) u = "https:" + u
            else if (u.startsWith("/")) u = ALP_BASE + u
            images += paragraphs.size to u   // anchor: before the NEXT paragraph
        } else {
            val text = m.groupValues[3]
                .replace(Regex("""<br\s*/?>"""), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&ldquo;", "\u201c").replace("&rdquo;", "\u201d")
                .trim()
            if (text.isNotBlank()) paragraphs += text
        }
    }
    if (paragraphs.isEmpty()) {
        // no <p> structure — fall back to the plain <br> body
        return parseAlpBodyHtml(html) to images
    }
    return paragraphs to images
}

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
