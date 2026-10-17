package com.patchscratch.novelnotifier

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon

/**
 * Syosetu discover (provider slice 1): ranking orders + word search, both
 * served by the official JSON API — no WebView involved. Tapping a novel
 * opens SyosetuDetailScreen.
 */
/**
 * Syosetu workspace home: its own drawer (site switcher, なろう Discover,
 * Syosetu settings — bookmark categories arrive with the login slice) with
 * Discover as the body. The NU home has its equivalent drawer; switching
 * sites swaps the whole workspace.
 */
@Composable
fun SyosetuHome(
    activeSite: String,
    onSwitchSite: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNovel: (SyosetuNovel) -> Unit,
    onOpenDiscover: () -> Unit = {},
    loggedIn: Boolean = false,
    bookmarks: List<SyosetuBookmark> = emptyList(),
    r18Enabled: Boolean = false,
    enabledSubsites: Set<Int> = setOf(1, 2, 3),
    onOpenBookmarks: (String) -> Unit = {},
    onOpenCategories: () -> Unit = {},
    nsfwUnlocked: Boolean = false,
    onToggleNsfw: () -> Unit = {},
    hiddenCategories: Set<String> = emptySet(),
    categories: List<SyosetuCategory> = emptyList(),
    onChangeCategory: (SyosetuBookmark, Int) -> Unit = { _, _ -> },
    onRemoveBookmark: (SyosetuBookmark) -> Unit = {},
    // Pull-to-refresh: re-sync the bookmark page from syosetu.com.
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    // Drawer's always-reachable update check (manual — bypasses the
    // once-a-day throttle).
    onCheckUpdate: () -> Unit = {}
) {
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // In-place category selection (NU list-switcher parity).
    var selectedCategory by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    // B2 NU-parity: the four sort modes over しおり-position unread.
    val prefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    var sortMode by remember { mutableStateOf(loadSortMode(prefs)) }
    fun shioriUnread(bm: SyosetuBookmark): Int =
        if (bm.shioriPos > 0) bm.episodeCount.minus(bm.shioriPos).coerceAtLeast(0)
        else bm.episodeCount   // never opened → everything is unread
    fun sortBookmarks(list: List<SyosetuBookmark>): List<SyosetuBookmark> = when (sortMode) {
        SORT_ALPHA -> list.sortedBy { it.title.lowercase() }
        SORT_UNREAD_COUNT -> list.sortedByDescending { shioriUnread(it) }
        SORT_UPDATE_TIME -> list.sortedByDescending { it.updated }
        else -> list.sortedByDescending { shioriUnread(it) > 0 }
    }
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "なろう",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                SiteSwitcherChips(activeSite, onSwitchSite)
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(t("Discover  ★", "なろう Discover  ★")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenDiscover()
                    }
                )
                if (loggedIn && bookmarks.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        t("Bookmarks", "ブックマーク"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    // SFW: hidden 分類 and their rows vanish; counts shrink.
                    val visibleBookmarks =
                        if (nsfwUnlocked) bookmarks
                        else NsfwFilter.filterSyosetu(bookmarks, hiddenCategories, unlocked = false)
                    // NU-style switcher: All + one row per 分類. Opens the
                    // bookmark library screen (grid/list, covers) filtered.
                    if (visibleBookmarks.isNotEmpty()) {
                        NavigationDrawerItem(
                            label = { Text(t("All", "すべて") + " (${visibleBookmarks.size})") },
                            selected = selectedCategory.isBlank(),
                            onClick = {
                                selectedCategory = ""
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    visibleBookmarks.map { it.category }.filter { it.isNotBlank() }.distinct().forEach { cat ->
                        NavigationDrawerItem(
                            label = { Text("$cat (${visibleBookmarks.count { it.category == cat }})") },
                            selected = selectedCategory == cat,
                            onClick = {
                                selectedCategory = cat
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                } else {
                    Text(
                        t("Log in from Settings to sync your bookmark library.", "設定からログインするとブックマークを同期します。"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                NavigationDrawerItem(
                    label = { Text(t("Categories  ⚙", "カテゴリ  ⚙")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenCategories()
                    }
                )
                NavigationDrawerItem(
                    label = { Text(t("Settings  ⚙", "設定  ⚙")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    }
                )
                NavigationDrawerItem(
                    label = { Text(t("Check for app updates", "アプリのアップデートを確認")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onCheckUpdate()
                    }
                )
                NsfwToggleRow(unlocked = nsfwUnlocked, onToggle = onToggleNsfw)
            }
        }
    ) {
        // NU-parity home (Wayne's rule, 2026-10-15): the main panel IS the
        // library — the selected 分類's series in a searchable grid/list;
        // rankings/search moved to the Discover route.
        val visible = NsfwFilter.filterSyosetu(bookmarks, hiddenCategories, nsfwUnlocked)
            .filter { selectedCategory.isBlank() || it.category == selectedCategory }
            .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
        var managed by remember { mutableStateOf<SyosetuBookmark?>(null) }
        BookmarkLibraryBody(
            heading = if (selectedCategory.isBlank()) t("All", "すべて") + " (${visible.size})"
            else "$selectedCategory (${visible.size})",
            rows = sortBookmarks(visible).map { bm ->
                BookmarkRow(
                    title = bm.title,
                    subtitle = bm.category,
                    coverUrl = "",
                    seriesUrl = bm.url,
                    open = { onOpenNovel(hydratedStub(bm)) },
                    unread = shioriUnread(bm),
                    latestLabel = if (bm.shioriPos > 0) t("read ep ", "読了 ") + "${bm.shioriPos}" else ""
                )
            },
            emptyMessage = if (!loggedIn) t(
                "Log in from Settings to sync your bookmark library.", "設定からログインするとブックマークを同期します。"
            ) else t("Nothing here — pick another 分類 or check Discover ★.", "何もありません — 別の分類を選ぶかDiscover ★を確認してください。"),
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onManageRow = { row ->
                managed = visible.firstOrNull { it.url == row.seriesUrl }
            },
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            sortControl = {
                SortMenu(sortMode = sortMode, onSort = {
                    sortMode = it
                    saveSortMode(prefs, it)
                })
            },
            sortLabel = sortModeLabel(sortMode)
        )
        // Long-press management (same dialogs as the library screen).
        managed?.let { bm ->
            AlertDialog(
                onDismissRequest = { managed = null },
                title = { Text(t("Change category", "カテゴリ変更")) },
                text = {
                    Column {
                        categories.forEach { cat ->
                            TextButton(onClick = {
                                onChangeCategory(bm, cat.id)
                                managed = null
                            }) {
                                Text((if (bm.category == cat.name) "✓ " else "") + cat.name + " (${cat.count})")
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    Column {
                        TextButton(onClick = {
                            onRemoveBookmark(bm)
                            managed = null
                        }) { Text(t("Remove bookmark", "ブックマーク解除"), color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { managed = null }) { Text(t("Close", "閉じる")) }
                    }
                }
            )
        }
    }
}

/** Cheap placeholder for a tapped bookmark row; the detail screen
 *  re-hydrates full metadata from cache/API on open. */
private fun hydratedStub(bm: SyosetuBookmark): SyosetuNovel = SyosetuNovel(
    ncode = bm.ncode, title = bm.title, writer = "", story = "",
    genreCode = 9801, keywords = "", isCompleted = false, isShortStory = false,
    episodeCount = 0, lastUpdate = bm.updated, points = 0
)

@Composable
fun SyosetuDiscoverBody(
    onOpenNovel: (SyosetuNovel) -> Unit,
    openDrawer: () -> Unit = {},
    r18Enabled: Boolean = false,
    enabledSubsites: Set<Int> = setOf(1, 2, 3),
    // Non-null in the Discover ROUTE: show a back arrow instead of the
    // hamburger (there is no drawer there).
    onBack: (() -> Unit)? = null
) {
    var tab by remember { mutableIntStateOf(0) }
    // 0 = Narou (all-ages); 1/2/3 = the enabled sister in the cycle
    var r18Mode by remember { mutableStateOf(false) }
    var r18Subsite by remember { mutableIntStateOf(1) }
    // Cycle order uses only ENABLED sisters; skip disabled ones.
    fun nextSubsite(current: Int): Int {
        var n = current
        repeat(3) {
            n = if (n >= 3) 1 else n + 1
            if (n in enabledSubsites) return n
        }
        return current
    }
    val activeSubsite: Int =
        if (r18Mode && r18Subsite in enabledSubsites) r18Subsite
        else enabledSubsites.firstOrNull() ?: 1
    var searchParams by remember { mutableStateOf(SyosetuSearchParams()) }
    var results by remember { mutableStateOf<List<SyosetuNovel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Rapid tab/genre switching races fetches; only the LATEST load may
    // apply its rows (otherwise a slow older tab lands last and its rows
    // show under the newer tab's heading).
    var loadGen by remember { mutableIntStateOf(0) }

    fun load() {
        val gen = ++loadGen
        scope.launch {
            loading = true
            error = ""
            val rows = try {
                // R18 mode queries the sister endpoint filtered to the
                // CYCLED subsite (nocgenre) and drops the all-ages pin.
                val extra = if (r18Mode) mapOf("nocgenre" to activeSubsite.toString()) else mapOf("ga" to "none")
                val params = if (tab < SYOSETU_ORDERS.size) {
                    mapOf("order" to SYOSETU_ORDERS[tab].first, "lim" to "30") + extra
                } else {
                    searchParams.copy(ga = if (r18Mode) "" else "none").toApiParams() + extra
                }
                syosetuApiQuery(params, r18 = r18Mode && r18Enabled)
            } catch (e: Exception) {
                if (gen == loadGen) error = "読み込めませんでした: ${e.message}"
                emptyList()
            }
            DebugLog.log("SYOSETU", "discover load gen=" + gen + " latest=" + loadGen + " tab=" + tab + " rows=" + rows.size + (if (gen == loadGen) " APPLY" else " DROP"))
            if (gen == loadGen) {
                results = rows
                loading = false
            }
        }
    }

    LaunchedEffect(tab) { if (tab < SYOSETU_ORDERS.size) load() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Warm every result title through the (serialized) translator so rows
    // render EN immediately even when flung past before their own
    // LaunchedEffect could finish — successes persist in the sidecar cache.
    LaunchedEffect(results) {
        results.take(30).forEach { MetadataTranslator.translateInfo(context, it.title) }
    }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("‹ " + t("Back", "戻る")) }
            } else {
                TextButton(onClick = openDrawer) {
                    Icon(Icons.Rounded.Menu, contentDescription = "Menu")
                }
            }
            Text(
                t("Syosetu Discover", "なろう Discover"),
                style = MaterialTheme.typography.headlineSmall
            )
            if (r18Enabled && enabledSubsites.isNotEmpty()) {
                Spacer(modifier = Modifier.weight(1f))
                FilterChip(
                    selected = r18Mode,
                    onClick = {
                        if (!r18Mode) r18Mode = true
                        else if (enabledSubsites.size == 1) r18Mode = false
                        else r18Subsite = nextSubsite(r18Subsite)
                        load()
                    },
                    label = {
                        val sister = when (activeSubsite) {
                            1 -> t("Nocturne", "ノクターン")
                            2 -> t("Moonlight", "ムーンライト")
                            else -> t("Ladies", "レディース")
                        }
                        Text(if (r18Mode) "R18 · $sister" else "R18")
                    }
                )
            }
        }
        ScrollableTabRow(selectedTabIndex = tab) {
            SYOSETU_ORDERS.forEachIndexed { i, (_, labels) ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t(labels.first, labels.second)) })
            }
            Tab(selected = tab == SYOSETU_ORDERS.size, onClick = { tab = SYOSETU_ORDERS.size }, text = { Text(t("Search", "検索")) })
        }
        if (tab == SYOSETU_ORDERS.size) {
            SyosetuAdvancedSearch(
                params = searchParams,
                onUpdate = { searchParams = it },
                onSearch = { load() }
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        if (r18Mode && results.isNotEmpty()) {
            Text(
                t("18+ (novel18). Tap the chip again to cycle sister sites; first chapter open shows Syosetu's age check — confirm once.",
                   "18禁（novel18）。チップを再度タップするとシスターサイトを切り替えます。最初に開くと年齢確認が表示されます — 一度だけ承認してください。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        TranslatingCaption()
        LazyColumn {
            items(results, key = { it.ncode }) { novel ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { onOpenNovel(novel) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(novel.title, style = MaterialTheme.typography.titleMedium)
                        TranslatedHint(text = novel.title, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${novel.writer} ・ ${syosetuGenreName(novel.genreCode)} ・ " +
                                (if (novel.isShortStory) syosetuTypeLabel(true)
                                else syosetuEpisodeLabel(novel.episodeCount)) +
                                " ・ " + syosetuStatusLabel(novel.isCompleted),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (novel.story.isNotBlank()) {
                            Text(
                                novel.story.take(100) + if (novel.story.length > 100) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Syosetu novel page: metadata (from the API row passed in) + episode ToC
 * (scraped from the novel page — episode titles live only there). Tapping an
 * episode opens the shared reader; bulk download feeds the shared ePub
 * service with deterministic episode URLs (ncode/N/ — no NU, no redirects).
 */
@Composable
fun SyosetuDetailScreen(
    novel: SyosetuNovel,
    onOpenReader: (chapters: List<Release>, startIndex: Int) -> Unit,
    onBack: () -> Unit = {},
    // Bookmark management (site write-back, NU-style CHANGE LIST parity):
    // non-null category = this series is bookmarked with that 分類 name.
    bookmark: SyosetuBookmark? = null,
    categories: List<SyosetuCategory> = emptyList(),
    onChangeCategory: (Int) -> Unit = {},
    onRemoveBookmark: () -> Unit = {},
    // ★ add-form (detail-screen bookmarking; the picker needs the site's ids)
    addForm: SyosetuAddBookmarkForm? = null,
    addFormLoading: Boolean = false,
    onLoadAddForm: () -> Unit = {},
    onAddBookmark: (SyosetuAddBookmarkForm, Int) -> Unit = { _, _ -> },
    // MARK AS READ / checkbox: set the site しおり to this episode (manual
    // may move back). Framework B1's NU-parity write-back.
    onSetShiori: (episodeNo: Int) -> Unit = {},
    // B2: the API row carried the episode total — persist it on the
    // bookmark for the home badge.
    onEpisodeCountKnown: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var toc by remember { mutableStateOf<SyosetuToc?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Cache may carry a richer row than the list passed in (full synopsis
    // after a bookmark-tile hydration) — render from it when present.
    var meta by remember(novel.ncode) { mutableStateOf(novel) }
    var showBookmarkCard by remember { mutableStateOf(false) }
    // Offline ToC of last resort: chapters already stored in the series'
    // offline books ("the chapters you've read").
    var storedList by remember(novel.ncode) { mutableStateOf<List<Release>>(emptyList()) }

    // Instant render from the series cache when fresh; fetch only when
    // stale (>12h) or the row's lastUpdate moved (new episodes published).
    LaunchedEffect(novel.ncode) {
        val cached = withContext(Dispatchers.IO) {
            loadSyosetuSeries(context, novel.ncode, novel.lastUpdate)
        }
        if (cached != null) {
            DebugLog.log("SYOSETU", "detail served from cache: ${novel.ncode}")
            if (cached.novel.story.length >= novel.story.length) meta = cached.novel
            toc = cached.toc
        }
        // Bookmark rows (and caches written from them) carry a bare title —
        // no synopsis/genre/keywords for the INFO cards. One cheap
        // official-API call fills the cards and re-seeds the cache.
        if (meta.story.isBlank() || meta.keywords.isBlank()) {
            runCatching {
                syosetuApiQuery(mapOf("ncode" to novel.ncode, "lim" to "1")).firstOrNull()
            }.getOrNull()?.let { full ->
                if (full.story.isNotBlank()) {
                    meta = full
                    if (toc?.episodes?.isNotEmpty() == true) {
                        withContext(Dispatchers.IO) { saveSyosetuSeries(context, full, toc!!) }
                    }
                }
            }
        }
        if (cached != null) {
            loading = false
            return@LaunchedEffect
        }
        DebugLog.log("SYOSETU", "detail story len=${novel.story.length} for ${novel.ncode}")
        loading = true
        error = ""
        try {
            // Bookmark rows arrive with a bare title (no synopsis/genre/
            // keywords) and the ToC scrape below doesn't carry them either —
            // one cheap official-API call fills the INFO tab's cards.
            if (meta.story.isBlank() || meta.keywords.isBlank()) {
                runCatching {
                    syosetuApiQuery(mapOf("ncode" to novel.ncode, "lim" to "1")).firstOrNull()
                }.getOrNull()?.let { full ->
                    if (full.story.isNotBlank()) meta = full
                }
            }
            val all = mutableListOf<SyosetuEpisode>()
            var page = 1
            while (true) {
                val url = if (page == 1) novel.url else "${novel.url}?p=$page"
                val parsed = parseSyosetuToc(fetchSyosetuHtml(url))
                all += parsed.episodes
                if (!parsed.hasMorePages || parsed.episodes.isEmpty()) break
                if (all.size >= novel.episodeCount) break
                page++
            }
            // Short stories (短編) have no numbered episodes — the whole
            // story IS the bare novel page. Synthesize one episode for them.
            val episodes = all.distinctBy { it.number }.sortedBy { it.number }.ifEmpty {
                if (novel.isShortStory) listOf(SyosetuEpisode(1, novel.title)) else emptyList()
            }
            toc = SyosetuToc(
                title = novel.title,
                author = novel.writer,
                episodes = episodes,
                hasMorePages = false
            )
            if (episodes.isNotEmpty()) {
                withContext(Dispatchers.IO) { saveSyosetuSeries(context, meta, toc!!) }
            }
        } catch (e: Exception) {
            // Live fetch failed (offline / site down). A stale cached ToC
            // still opens every stored chapter; without one, the stored
            // chapter list below becomes the visible ToC.
            val stale = withContext(Dispatchers.IO) {
                loadSyosetuSeries(context, novel.ncode, "", allowStale = true)
            }
            if (stale != null) {
                toc = stale.toc
                error = t("Offline — showing the cached table of contents", "オフライン — キャッシュの目次を表示")
            } else {
                error = "ToC を読み込めませんでした: ${e.message}"
            }
        }
        if (toc == null) {
            storedList = withContext(Dispatchers.IO) {
                EpubStore.storedChapters(context, novel.url).map { it.toRelease() }
            }
        }
        loading = false
    }

    // Reader chapters: label cN keeps ordering/bucket logic identical to NU.
    fun toChapters(): List<Release> = toc!!.episodes.map { ep ->
        // shorts: the story is the bare URL; serials: ncode/N/
        val url = if (novel.isShortStory) novel.url else "${novel.url}${ep.number}/"
        Release(chapter = "c${ep.number}", group = "", date = "", url = url)
    }

    // ---- NU-model detail (framework B1): the shared template renders the
    // exact NU anatomy — header + tabs + INFO cards + RELEASES — and this
    // screen only assembles the state. Site extras (translation coverage,
    // offline/download rows, AI cover) ride the infoExtra slot; the
    // bookmark 分類 card rides belowHeader like NU's Change-list card.
    val episodes = toc?.episodes ?: emptyList()
    val position = bookmark?.shioriPos ?: 0
    val nextOrdinal = if (episodes.isNotEmpty()) (position + 1).coerceAtMost(episodes.size) else 0
    val nextEp = episodes.getOrNull(nextOrdinal - 1)

    fun openAt(index: Int) {
        if (episodes.isNotEmpty()) onOpenReader(toChapters(), index)
        else if (index < storedList.size) onOpenReader(storedList, index)
    }

    SeriesDetailScreen(
        state = SeriesDetailState(
            seriesUrl = novel.url,
            onBack = onBack,
            coverUrl = "",
            coverSeriesUrl = novel.url,
            title = meta.title,
            author = meta.writer,
            groupBadge = bookmark?.category.orEmpty(),
            changeGroupLabel = if (bookmark != null) t("CHANGE LIST", "リスト変更")
            else t("★ BOOKMARK", "★ ブックマーク"),
            onChangeGroup = {
                showBookmarkCard = !showBookmarkCard
                if (showBookmarkCard) onLoadAddForm()
            },
            statusLine = error,
            nextChapterLabel = when {
                nextEp != null -> "第${nextEp.number}話 ${nextEp.title}"
                storedList.isNotEmpty() -> t("Read from start", "最初から読む") + " (${storedList.size} " + t("stored", "保存済み") + ")"
                loading -> t("Loading…", "読み込み中…")
                else -> "No chapter link found"
            },
            nextChapterEnabled = nextEp != null || storedList.isNotEmpty(),
            onReadNext = { if (nextEp != null) openAt(nextOrdinal - 1) else openAt(0) },
            onMarkRead = { if (nextEp != null) onSetShiori(nextEp.number) },
            synopsis = meta.story,
            typeLine = "Genre: ${syosetuGenreName(meta.genreCode)} ・ Status: ${syosetuStatusLabel(meta.isCompleted)} ・ ${syosetuEpisodeLabel(meta.episodeCount)} ・ " + t("Updated", "最終更新") + " ${meta.lastUpdate.take(10)}",
            genres = syosetuGenreName(meta.genreCode),
            tags = meta.keywords,
            episodes = if (episodes.isNotEmpty()) episodes.map { ep ->
                SeriesEpisodeRow(
                    ordinal = ep.number,
                    label = "第${ep.number}話",
                    title = ep.title,
                    date = "",
                    isRead = ep.number == position,
                    onOpen = { openAt(ep.number - 1) },
                    onCheck = { onSetShiori(ep.number) }
                )
            } else storedList.mapIndexed { i, rel ->
                // Offline ToC of last resort: no live/stale ToC, but the
                // offline books hold chapters — list exactly what can open.
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
            openOnSiteLabel = t("Open on syosetu.com", "小説家になろうで開く"),
            loading = loading && episodes.isEmpty() && storedList.isEmpty(),
            belowHeader = {
                if (showBookmarkCard) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(t("Bookmark 分類", "ブックマーク分類"), style = MaterialTheme.typography.titleMedium)
                            when {
                                addFormLoading -> Text(
                                    t("Loading categories…", "分類を読み込み中…"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                addForm == null -> Text(
                                    t("Could not load the form — tap ★ again to retry.", "フォームを読み込めませんでした — ★をもう一度タップして再試行。"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                addForm!!.categories.isEmpty() -> Text(
                                    t("Log in from Settings to bookmark on Syosetu.", "設定からログインするとSyosetuでブックマークできます。"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                else -> addForm!!.categories.forEach { (id, name) ->
                                    TextButton(onClick = {
                                        onAddBookmark(addForm!!, id)
                                        showBookmarkCard = false
                                    }) {
                                        Text((if (bookmark?.category == name) "✓ " else "") + name)
                                    }
                                }
                            }
                            if (bookmark != null) {
                                TextButton(onClick = {
                                    onRemoveBookmark()
                                    showBookmarkCard = false
                                }) {
                                    Text(t("Remove bookmark", "ブックマーク解除"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                            TextButton(onClick = { showBookmarkCard = false }) {
                                Text(t("Close", "閉じる"))
                            }
                        }
                    }
                }
            },
            infoExtra = {
                Column {
                    // Translation coverage (Wayne's 2026-10-17 ask).
                    var xlated by remember(novel.ncode) { mutableStateOf(-1 to -1) }
                    LaunchedEffect(novel.ncode) {
                        xlated = withContext(Dispatchers.IO) {
                            EpubStore.translatedCount(context, novel.url) to EpubStore.storedCount(context, novel.url)
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
                                            seriesTitle = novel.title,
                                            seriesUrl = novel.url,
                                            chapters = toChapters().map { EpubBulkService.BulkChapter(it.url, it.chapter) },
                                            meta = novel.toBookMeta()
                                        )
                                    )
                                }
                            },
                            enabled = episodes.isNotEmpty()
                        ) { Text(t("Download all", "全部ダウンロード") + " (" + syosetuEpisodeLabel(novel.episodeCount) + ")") }
                    }
                    GenerateCoverButton(
                        context = context,
                        seriesUrl = novel.url,
                        title = novel.title,
                        author = novel.writer,
                        genre = syosetuGenreName(novel.genreCode),
                        synopsis = novel.story,
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
}

/**
 * The advanced search form (yomou search.php parity, expressed through the
 * official API): keywords, excluded keywords, genre multi-select, serial
 * type, completion, reading time / length / points minimums, content
 * elements (require/exclude), sort order, and search scope. "More filters"
 * collapses the rarely-touched half; the search button always runs.
 */
@Composable
fun SyosetuAdvancedSearch(
    params: SyosetuSearchParams,
    onUpdate: (SyosetuSearchParams) -> Unit,
    onSearch: () -> Unit
) {
    var showFilters by remember { mutableStateOf(false) }
    var showGenres by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = params.word,
                onValueChange = { onUpdate(params.copy(word = it, page = 1)) },
                label = { Text(t("Keywords", "キーワード")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSearch) { Text(t("Search", "検索")) }
        }
        androidx.compose.material3.OutlinedTextField(
            value = params.notword,
            onValueChange = { onUpdate(params.copy(notword = it, page = 1)) },
            label = { Text(t("Exclude keywords", "除外キーワード")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // genre chips row
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = params.genres.isNotEmpty(),
                onClick = { showGenres = true },
                label = {
                    Text(t("Genre", "ジャンル") + if (params.genres.isEmpty()) "" else " (${params.genres.size})")
                }
            )
            // sort order cycling chip
            val orderLabel = SYOSETU_SEARCH_ORDERS.firstOrNull { it.first == params.order }
            FilterChip(
                selected = true,
                onClick = {
                    val idx = SYOSETU_SEARCH_ORDERS.indexOfFirst { it.first == params.order }
                    val next = SYOSETU_SEARCH_ORDERS[(idx + 1) % SYOSETU_SEARCH_ORDERS.size]
                    onUpdate(params.copy(order = next.first, page = 1))
                },
                label = {
                    val (en, jp) = orderLabel?.second ?: ("" to "")
                    Text("↕ " + t(en, jp))
                }
            )
        }
        TextButton(onClick = { showFilters = !showFilters }) {
            Text((if (showFilters) "▾ " else "▸ ") + t("More filters", "詳細フィルタ"))
        }
        if (showFilters) {
            // type + completion cycling chips
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = params.serial != null,
                    onClick = {
                        val next = when (params.serial) {
                            null -> true; true -> false; false -> null
                        }
                        onUpdate(params.copy(serial = next, page = 1))
                    },
                    label = {
                        Text(t("Type", "形式") + ": " + when (params.serial) {
                            null -> t("All", "すべて")
                            true -> t("Serial", "連載")
                            false -> t("Short", "短編")
                        })
                    }
                )
                FilterChip(
                    selected = params.completed != null,
                    onClick = {
                        val next = when (params.completed) {
                            null -> true; true -> false; false -> null
                        }
                        onUpdate(params.copy(completed = next, page = 1))
                    },
                    label = {
                        Text(t("Status", "状況") + ": " + when (params.completed) {
                            null -> t("All", "すべて")
                            true -> t("Completed", "完結")
                            false -> t("Ongoing", "連載中")
                        })
                    }
                )
                // minimums: tap cycles through the site's own ladder values
                val timeSteps = listOf(0, 5, 10, 30, 60, 120, 180, 240, 300)
                FilterChip(
                    selected = params.minMinutes > 0,
                    onClick = {
                        val idx = timeSteps.indexOf(params.minMinutes)
                        onUpdate(params.copy(minMinutes = timeSteps[(idx + 1) % timeSteps.size], page = 1))
                    },
                    label = {
                        Text(if (params.minMinutes == 0) t("Reading time", "読了時間")
                        else t("≥", "以上") + if (params.minMinutes >= 60) " ${params.minMinutes / 60}h" else " ${params.minMinutes}m")
                    }
                )
                val lenSteps = listOf(0, 10000, 50000, 100000, 300000, 500000, 1000000)
                FilterChip(
                    selected = params.minLength > 0,
                    onClick = {
                        val idx = lenSteps.indexOf(params.minLength)
                        onUpdate(params.copy(minLength = lenSteps[(idx + 1) % lenSteps.size], page = 1))
                    },
                    label = {
                        Text(if (params.minLength == 0) t("Length", "文字数")
                        else " ≥ " + if (params.minLength >= 10000) "${params.minLength / 10000}" + t("0k chars", "万字") else "${params.minLength}")
                    }
                )
                val ptSteps = listOf(0, 100, 1000, 3000, 10000, 50000, 100000, 200000)
                FilterChip(
                    selected = params.minPoints > 0,
                    onClick = {
                        val idx = ptSteps.indexOf(params.minPoints)
                        onUpdate(params.copy(minPoints = ptSteps[(idx + 1) % ptSteps.size], page = 1))
                    },
                    label = {
                        Text(if (params.minPoints == 0) t("Points", "ポイント")
                        else " ≥ ${if (params.minPoints >= 10000) (params.minPoints / 10000).toString() + t("0k", "万") else params.minPoints.toString()}pt")
                    }
                )
            }
            // content elements: tap cycles don't-care → require → exclude
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                val elems: List<Triple<String, ElementFilter, (ElementFilter) -> SyosetuSearchParams>> = listOf(
                    Triple("R15", params.r15) { f -> params.copy(r15 = f) },
                    Triple(t("Gore", "残酷な描写"), params.zankoku) { f -> params.copy(zankoku = f) },
                    Triple("BL", params.bl) { f -> params.copy(bl = f) },
                    Triple("GL", params.gl) { f -> params.copy(gl = f) },
                    Triple(t("Reincarnation", "転生"), params.tensei) { f -> params.copy(tensei = f) },
                    Triple(t("Transmigration", "転移"), params.tenni) { f -> params.copy(tenni = f) }
                )
                elems.forEach { (label, value, setter) ->
                    FilterChip(
                        selected = value != ElementFilter.ANY,
                        onClick = {
                            onUpdate(setter(
                                when (value) {
                                    ElementFilter.ANY -> ElementFilter.REQUIRE
                                    ElementFilter.REQUIRE -> ElementFilter.EXCLUDE
                                    ElementFilter.EXCLUDE -> ElementFilter.ANY
                                }
                            ).copy(page = 1))
                        },
                        label = {
                            val mark = when (value) {
                                ElementFilter.ANY -> ""
                                ElementFilter.REQUIRE -> " ✓"
                                ElementFilter.EXCLUDE -> " ✗"
                            }
                            Text(label + mark)
                        }
                    )
                }
            }
            // search scope
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                val scopes: List<Triple<String, Boolean, (Boolean) -> SyosetuSearchParams>> = listOf(
                    Triple(t("Title", "タイトル"), params.inTitle) { v -> params.copy(inTitle = v) },
                    Triple(t("Synopsis", "あらすじ"), params.inSynopsis) { v -> params.copy(inSynopsis = v) },
                    Triple(t("Keywords", "キーワード"), params.inKeywords) { v -> params.copy(inKeywords = v) },
                    Triple(t("Author", "作者名"), params.inAuthor) { v -> params.copy(inAuthor = v) }
                )
                scopes.forEach { (label, checked, setter) ->
                    FilterChip(
                        selected = checked,
                        onClick = { onUpdate(setter(!checked).copy(page = 1)) },
                        label = { Text(label) }
                    )
                }
            }
        }
        if (showGenres) {
            AlertDialog(
                onDismissRequest = { showGenres = false },
                title = { Text(t("Genre", "ジャンル")) },
                text = {
                    Column {
                        SYOSETU_GENRES.forEach { (code, name) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val next = if (code in params.genres) params.genres - code else params.genres + code
                                        onUpdate(params.copy(genres = next, page = 1))
                                    }
                            ) {
                                Checkbox(
                                    checked = code in params.genres,
                                    onCheckedChange = {
                                        val next = if (code in params.genres) params.genres - code else params.genres + code
                                        onUpdate(params.copy(genres = next, page = 1))
                                    }
                                )
                                Text(t(syosetuGenreName(code), name))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showGenres = false; onSearch() }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { showGenres = false }) { Text(t("Cancel", "キャンセル")) } }
            )
        }
    }
}
