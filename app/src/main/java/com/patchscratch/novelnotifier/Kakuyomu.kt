package com.patchscratch.novelnotifier

import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kakuyomu (カクヨム) provider — the second second-site provider.
 *
 * Kakuyomu has no public REST API, but its own web app talks to an OPEN
 * GraphQL endpoint at kakuyomu.jp/graphql (no auth, no Cloudflare — the
 * same endpoint the browser uses, verified live 2026-10). Search and
 * rankings POST there; work detail is one GET of the work page, whose
 * __NEXT_DATA__ embeds the full Apollo cache including tableOfContentsV2
 * (the definitive episode order); episode text flows through the existing
 * WebView reader like Syosetu, with a .js-episode-body extractor fast path.
 *
 * Enum vocabularies below were ALL verified by trial against the endpoint
 * (GraphQL rejects unknown enum values — that's the ground truth; labels
 * follow the site's own wording). Pagination is offset-based.
 */

/** One work from search/rankings/detail. */
data class KakuyomuWork(
    val id: String,             // "1177354054891139802"
    val title: String,
    val catchphrase: String,    // the one-line PR copy under the title
    val genre: String,          // Work_Genre enum token ("FANTASY")
    val author: String,         // author.activityName (pen name)
    val isCompleted: Boolean,   // serialStatus == COMPLETED
    val episodeCount: Int,      // publicEpisodeCount
    val points: Int,            // totalReviewPoint (site's "points")
    val charCount: Long,        // totalCharacterCount
    val publishedAt: String,    // ISO "2019-10-12T11:36:32Z"
    val lastUpdate: String,     // lastEpisodePublishedAt, ISO
    val coverUrl: String,       // adminCoverImageUrl ("" when absent)
    val isSexual: Boolean = false,  // the site's R18 flag (no separate genre)
    val introduction: String = "",  // synopsis — detail pages only
    val tags: String = ""           // tagLabels joined — detail pages only
) {
    val url: String get() = "https://kakuyomu.jp/works/$id"
}

/** Search result envelope: rows + server-side total for paging UI. */
data class KakuyomuSearchResult(val works: List<KakuyomuWork>, val totalCount: Int)

/** One episode row of a work's ToC. */
data class KakuyomuEpisode(
    val id: String,
    val title: String,
    val publishedAt: String,
    val chapterTitle: String = ""   // 章 heading the author grouped it under
) {
    fun url(workId: String) = "https://kakuyomu.jp/works/$workId/episodes/$id"
}

/** Detail = work fields + ordered ToC (from one work-page GET). */
data class KakuyomuWorkDetail(val work: KakuyomuWork, val episodes: List<KakuyomuEpisode>)

/**
 * The 12 all-ages Work_Genre values, in the site's own order (enum tokens
 * probed live; every accepted token is listed, rejected candidates like
 * LITERATURE/POEM/CHILDREN are NOT in the enum).
 */
val KAKUYOMU_GENRES: List<Pair<String, Pair<String, String>>> = listOf(
    "DRAMA" to ("Drama" to "現代ドラマ"),
    "FANTASY" to ("Fantasy" to "ファンタジー"),
    "SF" to ("SF" to "SF"),
    "HORROR" to ("Horror" to "ホラー"),
    "ACTION" to ("Action" to "アクション"),
    "LOVE_STORY" to ("Love Story" to "ラブストーリー"),
    "MYSTERY" to ("Mystery" to "ミステリー"),
    "HISTORY" to ("Historical" to "歴史・時代・伝奇"),
    "ROMANCE" to ("Romance" to "恋愛"),
    "NONFICTION" to ("Nonfiction" to "ノンフィクション"),
    "CRITICISM" to ("Criticism & Essays" to "評論・創作論"),
    "OTHERS" to ("Others" to "その他")
)

/** SearchWork_Order values (probed: NEW/RECENT/POPULARITY etc. rejected). */
val KAKUYOMU_ORDERS = listOf(
    "POPULAR" to ("Popular" to "人気順"),
    "WEEKLY_RANKING" to ("Weekly" to "週間ランキング"),
    "PUBLISHED_AT" to ("Newest" to "新着順")
)

/** WorkRankingScore_Key_Period — the rankedWorks tabs. */
val KAKUYOMU_PERIODS = listOf(
    "DAILY" to ("Today" to "今日"),
    "WEEKLY" to ("Weekly" to "週間"),
    "MONTHLY" to ("Monthly" to "月間"),
    "ENTIRE" to ("All-time" to "総合")
)

/** WorkRankingScore_WorkVariation — serial / short story / both. */
val KAKUYOMU_VARIATIONS = listOf(
    "LONG" to ("Serials" to "連載"),
    "SHORT" to ("Short stories" to "短編"),
    "ALL" to ("All" to "すべて")
)

/** Genre token → bilingual display name (UI only). */
fun kakuyomuGenreName(token: String): String =
    KAKUYOMU_GENRES.firstOrNull { it.first == token }
        ?.let { t(it.second.first, it.second.second) } ?: token

/** Genre token → the site's own label. Used for ePub metadata (native
 *  regardless of the language toggle — books record what the SITE says). */
fun kakuyomuGenreNameNative(token: String): String =
    KAKUYOMU_GENRES.firstOrNull { it.first == token }?.second?.second ?: token

fun kakuyomuStatusLabel(isCompleted: Boolean): String =
    if (isCompleted) t("Completed", "完結済") else t("Ongoing", "連載中")

fun kakuyomuEpisodeLabel(count: Int): String = t("$count episodes", "${count}話")

/** Map a Kakuyomu work to the offline-ePub title-page metadata. */
fun KakuyomuWork.toBookMeta(): EpubStore.BookMeta = EpubStore.BookMeta(
    title = title,
    description = introduction.ifBlank { catchphrase },
    type = "Web Novel (JP)・${if (episodeCount <= 1) "短編" else "連載"}",
    genres = kakuyomuGenreNameNative(genre),
    tags = tags,
    author = author,
    year = publishedAt.take(4),
    completelyTranslated = if (isCompleted) "完結済" else "連載中",
    originalPublisher = "カクヨム"
)

// --- GraphQL plumbing --------------------------------------------------------

/**
 * POST one query (with optional variables) to the open GraphQL endpoint.
 * Returns the raw response body; throws on transport/HTTP errors.
 * GraphQL-level errors surface as missing keys downstream (parsers are
 * tolerant), matching the Syosetu provider's degrade-gracefully behavior.
 */
private suspend fun kakuyomuGqlRaw(query: String, variables: JSONObject? = null): String =
    withContext(Dispatchers.IO) {
        val conn = URL("https://kakuyomu.jp/graphql").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
        val body = JSONObject().apply {
            put("query", query)
            if (variables != null) put("variables", variables)
        }.toString().toByteArray()
        conn.setFixedLengthStreamingMode(body.size)
        val out: OutputStream = conn.outputStream
        out.use { it.write(body) }
        // 4xx/5xx: read the error body — Apollo puts the reason there, and
        // "FileNotFoundException" alone says nothing on-device.
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (conn.responseCode !in 200..299) {
            throw java.io.IOException("graphql HTTP ${conn.responseCode}: ${raw.take(300)}")
        }
        conn.disconnect()
        raw
    }

/** GraphQL Work node → row (works for search, rankings and detail alike). */
private fun parseWorkNode(node: JSONObject): KakuyomuWork? {
    val id = node.optString("id")
    if (id.isBlank()) return null
    val author = node.optJSONObject("author")?.optString("activityName").orEmpty()
    return KakuyomuWork(
        id = id,
        title = node.optString("title"),
        catchphrase = node.optString("catchphrase"),
        genre = node.optString("genre"),
        author = author,
        isCompleted = node.optString("serialStatus") == "COMPLETED",
        episodeCount = node.optInt("publicEpisodeCount", 0).coerceAtLeast(0),
        points = node.optInt("totalReviewPoint", 0),
        charCount = node.optLong("totalCharacterCount", 0L),
        publishedAt = node.optString("publishedAt"),
        lastUpdate = node.optString("lastEpisodePublishedAt"),
        // takeIf: GraphQL null and absent both coerce to non-URL strings.
        coverUrl = node.optString("adminCoverImageUrl").takeIf { it.startsWith("http") } ?: "",
        isSexual = node.optBoolean("isSexual"),
        introduction = node.optString("introduction"),
        tags = node.optJSONArray("tagLabels")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it) }
        } ?: ""
    )
}

/** Fields every list/detail query asks for (single source of truth). */
private const val KAKUYOMU_WORK_FIELDS =
    "id title catchphrase genre serialStatus publicEpisodeCount " +
        "totalReviewPoint totalCharacterCount publishedAt lastEpisodePublishedAt " +
        "adminCoverImageUrl isSexual author { activityName }"

/** GraphQL response (the whole {data:…} envelope) → search result. */
internal fun parseKakuyomuSearchJson(json: String): KakuyomuSearchResult = try {
    val conn = JSONObject(json).getJSONObject("data").optJSONObject("searchWorks")
    val works = conn?.optJSONArray("nodes")?.let { arr ->
        (0 until arr.length()).mapNotNull { parseWorkNode(arr.optJSONObject(it) ?: return@mapNotNull null) }
    } ?: emptyList()
    KakuyomuSearchResult(works, conn?.optInt("totalCount", works.size) ?: works.size)
} catch (e: Exception) {
    DebugLog.log("KAKUYOMU", "search parse failed", e)
    KakuyomuSearchResult(emptyList(), 0)
}

/** Free-text search with paging. Empty query returns the empty set on the
 *  site too — callers use rankings for browse-style discovery. */
suspend fun fetchKakuyomuSearch(
    q: String,
    order: String = "POPULAR",
    genre: String? = null,
    offset: Int = 0,
    first: Int = 20
): KakuyomuSearchResult {
    val raw = kakuyomuGqlRaw(
        "query(\$q: String! \$order: SearchWork_Order! \$genres: [Work_Genre!]! " +
            "\$first: Int! \$offset: Int!) { searchWorks(query: \$q order: \$order " +
            "genres: \$genres first: \$first offset: \$offset) { totalCount " +
            "nodes { $KAKUYOMU_WORK_FIELDS } } }",
        JSONObject().apply {
            put("q", q)
            put("order", order)
            // Android's org.json does NOT auto-wrap Collections (the desktop
            // jar in unit tests does — that divergence shipped genres as the
            // STRING "[]" and the server answered "Value \"[]\" does not
            // exist in Work_Genre enum"). Build the array explicitly.
            put("genres", JSONArray(genre?.let { listOf(it) } ?: emptyList<String>()))
            put("first", first)
            put("offset", offset)
        }
    )
    return parseKakuyomuSearchJson(raw)
}

/** GraphQL response (the whole {data:…} envelope) → ranked rows. */
internal fun parseKakuyomuRankingsJson(json: String): List<KakuyomuWork> = try {
    JSONObject(json).getJSONObject("data").optJSONObject("rankedWorks")
        ?.optJSONArray("nodes")?.let { arr ->
            (0 until arr.length()).mapNotNull { parseWorkNode(arr.optJSONObject(it) ?: return@mapNotNull null) }
        } ?: emptyList()
} catch (e: Exception) {
    DebugLog.log("KAKUYOMU", "rankings parse failed", e)
    emptyList()
}

/** Rankings — the Discover tabs (period × genre × serial/short). */
suspend fun fetchKakuyomuRankings(
    period: String = "ENTIRE",
    variation: String = "LONG",
    genre: String? = null,
    first: Int = 30
): List<KakuyomuWork> {
    val raw = kakuyomuGqlRaw(
        // period/workVariation are NON-NULL args (the 400 that taught us:
        // a nullable variable can't feed a non-null position even when the
        // value is present). genre is genuinely nullable — the site sends null.
        "query(\$genre: Work_Genre \$period: WorkRankingScore_Key_Period! " +
            "\$variation: WorkRankingScore_WorkVariation! \$first: Int!) { " +
            "rankedWorks(genre: \$genre period: \$period workVariation: \$variation " +
            "first: \$first) { nodes { $KAKUYOMU_WORK_FIELDS } } }",
        JSONObject().apply {
            put("genre", genre ?: JSONObject.NULL)
            put("period", period)
            put("variation", variation)
            put("first", first)
        }
    )
    return parseKakuyomuRankingsJson(raw)
}

// --- reading history (しおり equivalent) --------------------------------------

/**
 * Reading position, verified live 2026-10-13 against the account:
 * the site records {episodeId, position: String} when a logged-in visitor
 * opens an episode, and the work page then serves
 * visitorReadingHistory { readAt, episodeUnion { id title } } from the
 * Apollo cache (the 続きから読む affordance). position is a free string
 * ("p1" accepted; the site's own client sends scroll anchors) — we send
 * the episode ordinal so a manual set is meaningful even without pixels.
 */
fun kakuyomuEpisodeIdFromUrl(url: String): String? {
    val m = Regex("""/episodes/(\d+)""").find(url) ?: return null
    return m.groupValues[1]
}

suspend fun kakuyomuRecordReadingHistory(episodeId: String, position: String): Boolean {
    val body = JSONObject()
        .put("query", "mutation RecordReadingHistory(\$input: RecordReadingHistoryInput!) { recordReadingHistory(input: \$input) { clientMutationId __typename } }")
        .put("variables", JSONObject().put("input", JSONObject().put("episodeId", episodeId).put("position", position)))
        .toString()
    return try {
        val raw = fetchKakuyomuWithSession("https://kakuyomu.jp/graphql", body)
        val ok = JSONObject(raw).optJSONObject("data")?.optJSONObject("recordReadingHistory") != null
        DebugLog.log("KAKUYOMU", "history record ep=" + episodeId + " ok=" + ok + " " + raw.take(140))
        ok
    } catch (e: Exception) {
        DebugLog.log("KAKUYOMU", "history record failed", e)
        false
    }
}

/**
 * Work page → the logged-in visitor's reading position for that work:
 * ReadingHistory:<userId>/<workId> ref resolving to readAt + episodeUnion.
 * Null when not logged in or the work was never read.
 */
fun parseKakuyomuReadingHistory(html: String): Pair<String, String>? {
    // Apollo cache: "ReadingHistory:<userId>/<workId>": { readAt, episodeUnion: {__ref: "Episode:<id>"} }
    val m = Regex(""""ReadingHistory:[^"]*":\{[^}]*"readAt":"([^"]*)"[^}]*"episodeUnion":\{"__ref":"Episode:([^"]*)"""").find(html)
        ?: return null
    return m.groupValues[2] to m.groupValues[1]   // episodeId to readAt
}

// --- Detail + ToC (one GET of the work page; Apollo cache embedded) ----------

/** Plain GET with a browser-ish UA (also used by tests via local fixtures). */
internal suspend fun fetchKakuyomuHtml(url: String): String = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
    val raw = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    raw
}

/**
 * Work page → detail + ordered ToC. The page embeds the site's own Apollo
 * cache in __NEXT_DATA__; ROOT_QUERY names the work entity, whose
 * tableOfContentsV2 refs chain through TableOfContentsChapter.episodeUnions
 * to Episode nodes (id/title/publishedAt). Falls back to publish-order
 * sorting of the cache's Episode nodes if the ToC chain is ever absent.
 */
fun parseKakuyomuWorkPage(html: String): KakuyomuWorkDetail? {
    val m = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        .find(html) ?: return null
    val apollo = JSONObject(m.groupValues[1])
        .optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("__APOLLO_STATE__")
        ?: return null

    // ROOT_QUERY carries work({"id":"…"}) → __ref; matching the prefix keeps
    // us independent of JSON key escaping differences.
    val root = apollo.optJSONObject("ROOT_QUERY") ?: return null
    val workKey = root.keys().asSequence().firstOrNull { it.startsWith("work({") } ?: return null
    val workRef = root.optJSONObject(workKey)?.optString("__ref") ?: return null
    val workNode = apollo.optJSONObject(workRef) ?: return null
    val authorName = workNode.optJSONObject("author")?.optString("__ref")
        ?.let { apollo.optJSONObject(it)?.optString("activityName") }.orEmpty()

    val work = KakuyomuWork(
        id = workNode.optString("id"),
        title = workNode.optString("title"),
        catchphrase = workNode.optString("catchphrase"),
        genre = workNode.optString("genre"),
        author = authorName,
        isCompleted = workNode.optString("serialStatus") == "COMPLETED",
        episodeCount = workNode.optInt("publicEpisodeCount", 0),
        points = workNode.optInt("totalReviewPoint", 0),
        charCount = workNode.optLong("totalCharacterCount", 0L),
        publishedAt = workNode.optString("publishedAt"),
        lastUpdate = workNode.optString("lastEpisodePublishedAt"),
        coverUrl = workNode.optString("adminCoverImageUrl").takeIf { it.startsWith("http") } ?: "",
        isSexual = workNode.optBoolean("isSexual"),
        introduction = workNode.optString("introduction"),
        tags = workNode.optJSONArray("tagLabels")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it) }
        } ?: ""
    )
    if (work.id.isBlank()) return null

    // Ordered ToC: tableOfContentsV2 → [chapterRef] → episodeUnions → refs.
    val episodes = mutableListOf<KakuyomuEpisode>()
    val tocArray = workNode.optJSONArray("tableOfContentsV2")
    if (tocArray != null) {
        for (i in 0 until tocArray.length()) {
            val chapterRef = tocArray.optJSONObject(i)?.optString("__ref") ?: continue
            val chapterNode = apollo.optJSONObject(chapterRef) ?: continue
            val heading = chapterNode.optJSONObject("chapter")?.optString("__ref")
                ?.let { apollo.optJSONObject(it)?.optString("title") }.orEmpty()
            val unions = chapterNode.optJSONArray("episodeUnions") ?: continue
            for (j in 0 until unions.length()) {
                val epRef = unions.optJSONObject(j)?.optString("__ref") ?: continue
                val ep = apollo.optJSONObject(epRef) ?: continue
                episodes += KakuyomuEpisode(
                    id = ep.optString("id"),
                    title = ep.optString("title"),
                    publishedAt = ep.optString("publishedAt"),
                    chapterTitle = heading
                )
            }
        }
    }
    // Fallback (and sanity net): every Episode node in the cache, publish order.
    if (episodes.none { it.id.isNotBlank() }) {
        apollo.keys().asSequence().filter { it.startsWith("Episode:") }.mapNotNull { k ->
            val ep = apollo.optJSONObject(k) ?: return@mapNotNull null
            KakuyomuEpisode(
                id = ep.optString("id").ifBlank { k.removePrefix("Episode:") },
                title = ep.optString("title"),
                publishedAt = ep.optString("publishedAt")
            )
        }.sortedWith(compareBy({ it.publishedAt }, { it.id })).toList().let { episodes += it }
    }
    return KakuyomuWorkDetail(work, episodes.filter { it.id.isNotBlank() }.distinctBy { it.id })
}

/** Detail in one call: fetch the work page and parse it. */
suspend fun fetchKakuyomuWork(id: String): KakuyomuWorkDetail? =
    parseKakuyomuWorkPage(fetchKakuyomuHtml("https://kakuyomu.jp/works/$id"))

// --- Episode text (used by tests; the reader reads episodes in its WebView) --

/**
 * Episode page → (title, paragraphs). The body is plain server-rendered
 * HTML: p.widget-episodeTitle + div.js-episodeBody > p (id="pN"). Pure
 * function so tests can pin the shape the reader/extractor relies on.
 */
fun parseKakuyomuEpisode(html: String): Pair<String, List<String>> {
    val title = Regex("""class="widget-episodeTitle[^"]*"[^>]*>([^<]+)<""")
        .find(html)?.groupValues?.get(1)?.trim().orEmpty()
    val bodyM = Regex("""<div class="widget-episodeBody[^"]*"[^>]*>(.*?)</div>\s*(?:<div|</div>)""", RegexOption.DOT_MATCHES_ALL)
        .find(html)
    val paragraphs = bodyM?.let { m ->
        Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1]).map { p ->
            p.groupValues[1].replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .trim()
        }.filter { it.isNotEmpty() }.toList()
    } ?: emptyList()
    return title to paragraphs
}

// --- Local bookmarks (device reading list) -----------------------------------

/**
 * Kakuyomu bookmarks, stored as one JSON-array pref (`kakuyomu_bookmarks`)
 * so they ride device backups. Kakuyomu's own site offers follows
 * (フォロー) to logged-in users — no in-app login yet, so these stay
 * on-device; the drawer suggests the site account when relevant.
 */
private fun KakuyomuWork.toBookmarkJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("catchphrase", catchphrase)
    put("genre", genre); put("author", author); put("end", isCompleted)
    put("eps", episodeCount); put("pts", points); put("chars", charCount)
    put("pub", publishedAt); put("up", lastUpdate); put("cover", coverUrl)
    put("r18", isSexual)
}

internal fun kakuyomuBookmarksJson(works: List<KakuyomuWork>): String =
    JSONArray().apply { works.forEach { put(it.toBookmarkJson()) } }.toString()

internal fun parseKakuyomuBookmarks(json: String): List<KakuyomuWork> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val row = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = row.optString("id")
        if (id.isBlank()) return@mapNotNull null
        KakuyomuWork(
            id = id, title = row.optString("title"), catchphrase = row.optString("catchphrase"),
            genre = row.optString("genre"), author = row.optString("author"),
            isCompleted = row.optBoolean("end"), episodeCount = row.optInt("eps"),
            points = row.optInt("pts"), charCount = row.optLong("chars"),
            publishedAt = row.optString("pub"), lastUpdate = row.optString("up"),
            // Strips the retired ogimage fallback URLs (c90d34f) that one
            // sync's worth of stored rows carry — they read as page
            // screenshots, not covers; the default no-cover image replaces
            // them until the next sync rewrites the rows.
            coverUrl = row.optString("cover")
                .takeIf { it.startsWith("http") && !it.contains("/ogimage.png") } ?: "",
            isSexual = row.optBoolean("r18")
        )
    }
} catch (e: Exception) {
    DebugLog.log("KAKUYOMU", "bookmarks parse failed", e)
    emptyList()
}

fun loadKakuyomuBookmarks(prefs: android.content.SharedPreferences): List<KakuyomuWork> =
    prefs.getString("kakuyomu_bookmarks", null)?.let { parseKakuyomuBookmarks(it) } ?: emptyList()

fun saveKakuyomuBookmarks(prefs: android.content.SharedPreferences, works: List<KakuyomuWork>) {
    prefs.edit { putString("kakuyomu_bookmarks", kakuyomuBookmarksJson(works)) }
}

// --- Login + official bookmarks (フォロー) ------------------------------------

/**
 * Kakuyomu login features run on the same open GraphQL endpoint — no CSRF
 * token, just the WebView session's cookies (verified in the site's own JS
 * bundle: mutations POST with same-origin credentials only). All mutation
 * and viewer query shapes below were extracted from that bundle, not
 * invented.
 */
private const val KAKUYOMU_GQL_URL = "https://kakuyomu.jp/graphql"

/** POST/GET to kakuyomu.jp with the WebView session's cookies attached,
 *  following redirects MANUALLY (auto-follow drops custom Cookie headers —
 *  the Syosetu lesson). Body != null → POST as GraphQL JSON. */
internal suspend fun fetchKakuyomuWithSession(url: String, body: String? = null): String =
    withContext(Dispatchers.IO) {
        var current = url
        for (hop in 0 until 5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://kakuyomu.jp/").orEmpty()
            if (cookies.isNotBlank()) conn.setRequestProperty("Cookie", cookies)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location") ?: break
                current = URL(URL(current), location).toString()
                conn.disconnect()
                continue
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (status !in 200..299) {
                throw java.io.IOException("kakuyomu HTTP $status: ${raw.take(200)}")
            }
            return@withContext raw
        }
        ""
    }

/** The signed-in user, or null when the session is absent/expired. */
data class KakuyomuVisitor(val screenName: String, val name: String)

suspend fun fetchKakuyomuVisitor(): KakuyomuVisitor? {
    val raw = fetchKakuyomuWithSession(
        KAKUYOMU_GQL_URL,
        """{"query":"{ visitor { screenName name } }"}"""
    )
    // Kakuyomu's visitor payload started returning "@name" (2026-10-15
    // live catch: follows then queried a user literally named '@PatchScratch'
    // → NOT_FOUND → sync silently kept stale local rows). Strip the prefix.
    val screenName = JSONObject(raw).optJSONObject("data")
        ?.optJSONObject("visitor")?.optString("screenName").orEmpty()
        .removePrefix("@")
    if (screenName.isBlank() || screenName == "null") return null
    val name = JSONObject(raw).optJSONObject("data")?.optJSONObject("visitor")?.optString("name").orEmpty()
    return KakuyomuVisitor(screenName, name)
}

/** The user's followed works (フォローしている小説), offset-paginated. The
 *  shape is the site's own /users/<name>/following_works page query. */
internal fun parseKakuyomuFollowsJson(json: String): List<KakuyomuWork> = try {
    JSONObject(json).getJSONObject("data").optJSONObject("userByScreenName")
        ?.optJSONObject("followingWorks")?.optJSONArray("nodes")?.let { arr ->
            (0 until arr.length()).mapNotNull { parseWorkNode(arr.optJSONObject(it) ?: return@mapNotNull null) }
        } ?: emptyList()
} catch (e: Exception) {
    DebugLog.log("KAKUYOMU", "follows parse failed", e)
    emptyList()
}

suspend fun fetchKakuyomuFollows(screenName: String): List<KakuyomuWork> {
    val all = mutableListOf<KakuyomuWork>()
    var offset = 0
    DebugLog.log("KAKUYOMU", "follows query for screenName=[$screenName]")
    while (true) {
        val raw = fetchKakuyomuWithSession(
            KAKUYOMU_GQL_URL,
            JSONObject().apply {
                put("query",
                    "query(\$s: String! \$first: Int! \$offset: Int!) { userByScreenName(screenName: \$s) { " +
                        "followingWorks(first: \$first offset: \$offset) { nodes { $KAKUYOMU_WORK_FIELDS } } } }")
                put("variables", JSONObject().put("s", screenName).put("first", 50).put("offset", offset))
            }.toString()
        )
        val page = parseKakuyomuFollowsJson(raw)
        if (page.isEmpty() && offset == 0) {
            DebugLog.log("KAKUYOMU", "follows page empty — raw: " + raw.take(300))
        }
        all += page
        if (page.size < 50) break
        offset += 50
    }
    return all.distinctBy { it.id }
}

/** Follow / unfollow a work with the session (site mutations, bundle-exact). */
suspend fun kakuyomuFollow(workId: String): Boolean {
    val raw = fetchKakuyomuWithSession(
        KAKUYOMU_GQL_URL,
        JSONObject().apply {
            put("query",
                "mutation(\$input: FollowWorkInput!) { followWork(input: \$input) { work { id " +
                    "visitorWorkFollowing { id } } } }")
            put("variables", JSONObject().put("input", JSONObject().put("workId", workId)))
        }.toString()
    )
    return !raw.contains("\"errors\"")
}

suspend fun kakuyomuUnfollow(workId: String): Boolean {
    val raw = fetchKakuyomuWithSession(
        KAKUYOMU_GQL_URL,
        JSONObject().apply {
            put("query",
                "mutation(\$input: UnfollowWorksInput!) { unfollowWorks(input: \$input) { works { id } } }")
            put("variables", JSONObject().put("input", JSONObject().put("workIds", JSONArray(listOf(workId)))))
        }.toString()
    )
    return !raw.contains("\"errors\"")
}

/**
 * Merge local bookmarks with the site's follows, NU-style per the user's
 * model: bookmarks MADE IN THE APP are added to the site (push local-only),
 * and THE SITE IS MASTER on conflict — after pushing, the site list (plus
 * just-pushed locals) replaces local state. Unfollowing in-app also
 * unfollows on the site (toggle does both), so divergence only ever comes
 * from site-side changes, which win. Pure except the follow-push callback,
 * so the merge itself is unit-testable.
 */
internal fun mergeKakuyomuBookmarks(
    local: List<KakuyomuWork>,
    site: List<KakuyomuWork>,
    pushedIds: Set<String> = emptySet()
): List<KakuyomuWork> {
    // site rows win for shared ids; local-only rows survive only if they
    // were just pushed (otherwise the site's removal of them is the truth)
    val ids = site.map { it.id }.toSet()
    val survivors = local.filter { it.id !in ids && it.id in pushedIds }
    return site + survivors
}
