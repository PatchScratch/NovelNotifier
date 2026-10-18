package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AlphaPolis series detail (B4): a pure SeriesDetailState assembly on the
 * shared template — the framework contract from SharedDetail.kt. The work
 * page (one headless GET) supplies everything: metadata, ToC (embedded
 * JSON), and the reading-position fallback comes from local bookmarks.
 *
 * MARK AS READ / checkboxes set the LOCAL position (no site position API
 * known — the bookshelf recon decides whether one exists; if it does, the
 * write-back slots in exactly like しおり).
 */
@Composable
fun AlphaPolisDetailScreen(
    work: AlpWork,
    bookmarked: Boolean,
    onToggleBookmark: (AlpWork) -> Unit,
    readPos: Int,
    onSetReadPos: (Int) -> Unit,
    onOpenReader: (chapters: List<Release>, startIndex: Int) -> Unit,
    onBack: () -> Unit = {},
    // Client-side categories (Kakuyomu pattern) — the ⚑ button becomes the
    // category picker; membership is single and move-only.
    categories: CategoryState = CategoryState(),
    onMove: (workId: String, catId: String?) -> Unit = { _, _ -> },
    onCreateAndMove: (workId: String, name: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var detail by remember { mutableStateOf<AlpWorkDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    // Offline ToC of last resort (stored ePub chapters).
    var storedList by remember { mutableStateOf<List<Release>>(emptyList()) }
    // Per-chapter offline (user decision 2026-10-18: premium chapters are
    // bought/rented one at a time, so each episode row downloads itself).
    var storedUrls by remember(work.id) { mutableStateOf<Set<String>>(emptySet()) }
    var downloadingUrl by remember(work.id) { mutableStateOf<String?>(null) }
    var downloadMsg by remember(work.id) { mutableStateOf("") }
    var showCategoryPicker by remember(work.id) { mutableStateOf(false) }
    val currentCategory = categories.assign[work.id]
        ?.let { id -> categories.cats.firstOrNull { it.id == id } }

    LaunchedEffect(work.id) {
        loading = true
        error = ""
        try {
            val html = alpFetch(work.url)
            detail = parseAlpWorkPage(html)
            if (detail == null) error = "作品情報を読み込めませんでした"
            // Cover carry-over: the shared tile prefers CoverStore, so the
            // work-page art needs persisting once — bare bookshelf-derived
            // bookmarks carry no coverUrl, and ePub saves embed the store's
            // file. Without this the cover showed only on this page.
            detail?.work?.coverUrl?.takeIf { it.startsWith("http") }?.let { cu ->
                runCatching {
                    if (!CoverStore.hasLocal(context, work.url)) {
                        CoverStore.download(context, work.url, cu)
                    }
                }.onFailure { DebugLog.log("ALP", "cover download failed", it as? Exception) }
            }
        } catch (e: Exception) {
            error = "読み込み失敗: ${e.message}"
        }
        if (detail == null) {
            storedList = withContext(Dispatchers.IO) {
                EpubStore.storedChapters(context, work.url).map { it.toRelease() }
            }
        }
        storedUrls = withContext(Dispatchers.IO) {
            EpubStore.storedChapters(context, work.url).map { it.url }.toSet()
        }
        loading = false
    }

    /** Headless single-chapter save. Paywalled (unentitled) chapters come
     *  back null — those still capture in the reader's web-hop on open. */
    fun downloadEpisode(ep: AlpEpisode, ordinal: Int) {
        val url = ep.url
        if (url in storedUrls || downloadingUrl != null) return
        downloadingUrl = url
        downloadMsg = ""
        scope.launch {
            val extract = fetchAlpChapterHeadless(url)
            if (extract != null && extract.paragraphs.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    EpubStore.appendChapter(
                        context, work.title, work.url,
                        url, alpEpisodeLabel(ep.title, ordinal), extract, null
                    )
                }
                storedUrls = storedUrls + url
            } else if (!ep.isPublic) {
                downloadMsg = t(
                    "Premium chapter — buy or rent it on AlphaPolis first (unlocked: your entitled chapters download instantly)",
                    "プレミアム章節 — 先にアルファポリスで購入・レンタルしてください（購入済みはすぐ保存されます）"
                )
            } else {
                downloadMsg = t(
                    "Couldn't fetch — open the chapter in the reader to save it",
                    "取得できません — リーダーで開くと保存できます"
                )
            }
            downloadingUrl = null
        }
    }

    val episodes = detail?.episodes ?: emptyList()
    val position = readPos
    val nextOrdinal = if (episodes.isNotEmpty()) (position + 1).coerceAtMost(episodes.size) else 0
    val nextEp = episodes.getOrNull(nextOrdinal - 1)

    fun toChapters(): List<Release> = episodes.mapIndexed { i, ep ->
        Release(chapter = alpEpisodeLabel(ep.title, i + 1), group = "", date = "", url = ep.url)
    }
    fun openAt(index: Int) {
        if (episodes.isNotEmpty()) onOpenReader(toChapters(), index)
        else if (index < storedList.size) onOpenReader(storedList, index)
    }

    SeriesDetailScreen(
        state = SeriesDetailState(
            seriesUrl = work.url,
            onBack = onBack,
            coverUrl = detail?.work?.coverUrl ?: work.coverUrl,
            coverSeriesUrl = work.url,
            title = detail?.work?.title ?: work.title,
            author = detail?.work?.author ?: work.author,
            rating = "",
            groupBadge = when {
                currentCategory != null -> currentCategory.name
                bookmarked -> t("Bookmarked", "ブックマーク済み")
                else -> ""
            },
            changeGroupLabel = when {
                currentCategory != null -> "⚑ " + currentCategory.name
                bookmarked -> t("⚑ BOOKMARKED", "⚑ ブックマーク済み")
                else -> t("⚑ BOOKMARK", "⚑ ブックマーク")
            },
            onChangeGroup = {
                // Categorised works re-pick their category (move-only);
                // uncategorised ones get the picker too — it doubles as the
                // create-and-assign surface, and All leaves the bookmark as-is.
                if (currentCategory == null && !bookmarked) onToggleBookmark(work)
                else showCategoryPicker = true
            },
            statusLine = if (downloadMsg.isNotBlank()) downloadMsg else error,
            nextChapterLabel = when {
                nextEp != null -> nextEp.title.ifBlank { "第${nextOrdinal}話" }
                storedList.isNotEmpty() -> t("Read from start", "最初から読む") + " (${storedList.size} " + t("stored", "保存済み") + ")"
                loading -> t("Loading…", "読み込み中…")
                else -> "No chapter link found"
            },
            nextChapterEnabled = nextEp != null || storedList.isNotEmpty(),
            onReadNext = { if (nextEp != null) openAt(nextOrdinal - 1) else openAt(0) },
            onMarkRead = { if (nextEp != null) onSetReadPos(nextOrdinal) },
            synopsis = detail?.work?.introduction ?: "",
            typeLine = detail?.work?.let {
                listOfNotNull(
                    it.genre.takeIf { g -> g.isNotBlank() }?.let { g -> "Genre: $g" },
                    if (it.isCompleted) "Status: Completed" else if (it.episodeCount > 0 || it.lastUpdate.isNotBlank()) "Status: Ongoing" else null,
                    it.charCount.takeIf { c -> c > 0 }?.let { c -> "%,d chars".format(c) }
                ).joinToString(" ・ ")
            } ?: "",
            genres = detail?.work?.genre ?: "",
            tags = detail?.work?.tags ?: "",
            episodes = if (episodes.isNotEmpty()) episodes.mapIndexed { i, ep ->
                SeriesEpisodeRow(
                    ordinal = i + 1,
                    // Site-faithful numbering (Wayne 2026-10-18: the list
                    // must read like AlphaPolis's own): volume-chapter
                    // titles lead ("8-16"), 第N話 titles keep their 話,
                    // unnumbered extras fall back to the ToC ordinal.
                    label = alpRowLabel(ep.title, i + 1),
                    title = alpRowSubtitle(ep.title),
                    date = "",
                    isRead = i + 1 == position,
                    onOpen = { openAt(i) },
                    onCheck = { onSetReadPos(i + 1) },
                    downloaded = ep.url in storedUrls,
                    downloading = downloadingUrl == ep.url,
                    onDownload = { downloadEpisode(ep, i + 1) },
                    premium = !ep.isPublic
                )
            } else storedList.mapIndexed { i, rel ->
                SeriesEpisodeRow(
                    ordinal = i + 1,
                    label = rel.chapter,
                    title = "",
                    date = "",
                    isRead = false,
                    onOpen = { openAt(i) }
                )
            },
            readOrdinal = position,
            openOnSiteLabel = t("Open on AlphaPolis", "アルファポリスで開く"),
            loading = loading && episodes.isEmpty() && storedList.isEmpty(),
            infoExtra = {
                Column {
                    // Translation coverage line (same as the JP sites).
                    var xlated by remember(work.id) { mutableStateOf(-1 to -1) }
                    LaunchedEffect(work.id) {
                        xlated = withContext(Dispatchers.IO) {
                            EpubStore.translatedCount(context, work.url) to EpubStore.storedCount(context, work.url)
                        }
                    }
                    val (xn, xm) = xlated
                    if (xm > 0) {
                        Text(
                            if (xn >= xm) t("✓ All ", "✓ 全") + "$xm" + t(" chapters translated", "話 翻訳済み")
                            else t("Translated: ", "翻訳済み：") + "$xn/$xm" + t(" chapters", "話"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(
                            onClick = { openAt(0) },
                            enabled = episodes.isNotEmpty() || storedList.isNotEmpty()
                        ) { Text(t("Read from start", "最初から読む")) }
                        TextButton(
                            onClick = {
                                if (episodes.isNotEmpty()) {
                                    EpubBulkService.start(
                                        context,
                                        EpubBulkService.BulkJob(
                                            seriesTitle = work.title,
                                            seriesUrl = work.url,
                                            chapters = toChapters().map { EpubBulkService.BulkChapter(it.url, it.chapter) },
                                            meta = null
                                        )
                                    )
                                }
                            },
                            enabled = episodes.isNotEmpty()
                        ) { Text(t("Download all", "全部ダウンロード") + " (${episodes.size})") }
                    }
                    GenerateCoverButton(
                        context = context,
                        seriesUrl = work.url,
                        title = work.title,
                        author = work.author,
                        genre = detail?.work?.genre ?: "",
                        synopsis = detail?.work?.introduction ?: "",
                        onGenerated = {}
                    )
                }
            },
            releasesFooter = {
                if (episodes.isEmpty() && storedList.isEmpty()) {
                    Text(
                        if (loading) t("Loading episodes…", "各話を読み込み中…") else t("No chapters cached yet.", "各話はまだありません。"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        )
    )

    if (showCategoryPicker) {
        CategoryMoveDialog(
            workId = work.id,
            current = currentCategory,
            cats = categories.cats,
            onMove = { catId -> onMove(work.id, catId) },
            onCreateAndMove = { name -> onCreateAndMove(work.id, name) },
            onDismiss = { showCategoryPicker = false }
        )
    }
}
