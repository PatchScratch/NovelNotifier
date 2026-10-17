package com.patchscratch.novelnotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon

/**
 * Kakuyomu workspace (provider slice 1): its own drawer (site switcher,
 * Discover, Settings) with a rankings-driven Discover body — period tabs,
 * genre/serial filter chips, word search — served by the site's open
 * GraphQL endpoint. Tapping a work opens KakuyomuDetailScreen.
 */
@Composable
fun KakuyomuHome(
    activeSite: String,
    onSwitchSite: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWork: (KakuyomuWork) -> Unit,
    onOpenDiscover: () -> Unit = {},
    r18Enabled: Boolean = false,
    bookmarks: List<KakuyomuWork> = emptyList(),
    categories: CategoryState = CategoryState(),
    onOpenBookmarks: (String) -> Unit = {},
    onOpenCategories: () -> Unit = {},
    loggedIn: Boolean = false,
    nsfwUnlocked: Boolean = false,
    onToggleNsfw: () -> Unit = {},
    onMove: (workId: String, catId: String?) -> Unit = { _, _ -> },
    onCreateAndMove: (workId: String, name: String) -> Unit = { _, _ -> },
    // Pull-to-refresh: re-sync follows (the site is master) — also the way
    // stale rows pick up fresh cover URLs.
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
    var selectedCategoryId by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "カクヨム",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                SiteSwitcherChips(activeSite, onSwitchSite)
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(t("Discover  ★", "カクヨム Discover  ★")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenDiscover()
                    }
                )
                HorizontalDivider()
                Text(
                    t("Bookmarks", "ブックマーク"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        if (loggedIn) t(
                            "No bookmarks yet — ⚑ on a work follows it on Kakuyomu and keeps it here.",
                            "ブックマークはまだありません — 作品の⚑でカクヨムでフォローし、ここに表示されます。"
                        ) else t(
                            "Open a work and tap ⚑ Bookmark to keep it here — saved on this device and carried in backups.",
                            "作品を開いて⚑ブックマークをタップするとここに保存されます — 端末内保存で、バックアップにも含まれます。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                } else {
                    // SFW: hidden categories' rows vanish; the All count
                    // excludes their works.
                    val visible =
                        if (nsfwUnlocked) bookmarks
                        else NsfwFilter.filterKakuyomu(
                            bookmarks, NsfwFilter.hiddenKakuyomuWorkIds(categories), unlocked = false
                        )
                    // NU-style switcher: All + one row per used category.
                    // The library screen (grid/list with covers) holds the
                    // rows; the drawer navigates to a filtered view.
                    if (visible.isNotEmpty()) {
                        NavigationDrawerItem(
                            label = { Text(t("All", "すべて") + " (${visible.size})") },
                            selected = selectedCategoryId.isBlank(),
                            onClick = {
                                selectedCategoryId = ""
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    // Only categories that actually hold works show here;
                    // empty ones are managed in Categories.
                    categories.cats
                        .filter { cat -> !cat.hidden || nsfwUnlocked }
                        .filter { cat -> visible.count { categories.assign[it.id] == cat.id } > 0 }
                        .forEach { cat ->
                            NavigationDrawerItem(
                                label = {
                                    Text("${cat.name} (${visible.count { categories.assign[it.id] == cat.id }})")
                                },
                                selected = selectedCategoryId == cat.id,
                                onClick = {
                                    selectedCategoryId = cat.id
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                }
                Text(
                    if (loggedIn) t(
                        "Bookmarks sync with your Kakuyomu follows — the site's list is the master.",
                        "ブックマークはカクヨムのフォローと同期されます — サイトのリストがマスターです。"
                    ) else t(
                        "Log in from Settings to sync bookmarks with your Kakuyomu follows. Browsing and reading need no account.",
                        "設定からログインするとブックマークがカクヨムのフォローと同期されます。閲覧にアカウントは不要です。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
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
        // NU-parity home: the library panel with in-place category filter.
        val all = NsfwFilter.filterKakuyomu(bookmarks, NsfwFilter.hiddenKakuyomuWorkIds(categories), nsfwUnlocked)
        val visible = all
            .filter { selectedCategoryId.isBlank() || categories.assign[it.id] == selectedCategoryId }
            .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
        var moveTo by remember { mutableStateOf<KakuyomuWork?>(null) }
        BookmarkLibraryBody(
            heading = if (selectedCategoryId.isBlank()) t("All", "すべて") + " (${visible.size})"
            else (categories.cats.firstOrNull { it.id == selectedCategoryId }?.name ?: "") + " (${visible.size})",
            rows = visible.map { work ->
                BookmarkRow(
                    title = work.title,
                    subtitle = work.author,
                    coverUrl = work.coverUrl,
                    seriesUrl = work.url,
                    open = { onOpenWork(work) }
                )
            },
            emptyMessage = if (bookmarks.isEmpty() && !loggedIn) t(
                "Open a work and tap ⚑ Bookmark to keep it here.", "作品を開いて⚑ブックマークをタップするとここに保存されます。"
            ) else t("Nothing here — pick another category or check Discover ★.", "何もありません — 別のカテゴリを選ぶかDiscover ★を確認してください。"),
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onManageRow = { row ->
                moveTo = visible.firstOrNull { it.url == row.seriesUrl }
            },
            isRefreshing = isRefreshing,
            onRefresh = onRefresh
        )
        moveTo?.let { work ->
            CategoryMoveDialog(
                workId = work.id,
                current = categories.cats.firstOrNull { it.id == categories.assign[work.id] },
                cats = categories.cats,
                onMove = { catId -> onMove(work.id, catId) },
                onCreateAndMove = { name -> onCreateAndMove(work.id, name) },
                onDismiss = { moveTo = null }
            )
        }
    }
}

/**
 * The three-site switcher, shared by every drawer (NU, Syosetu, Kakuyomu)
 * so switching stays one tap from any workspace. Short labels — the chip
 * row is the switcher, not a directory.
 */
@Composable
fun SiteSwitcherChips(activeSite: String, onSwitchSite: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        FilterChip(
            selected = activeSite == "nu",
            onClick = { onSwitchSite("nu") },
            label = { Text("NU") },
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        FilterChip(
            selected = activeSite == "syosetu",
            onClick = { onSwitchSite("syosetu") },
            label = { Text(t("Syosetu", "なろう")) },
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        FilterChip(
            selected = activeSite == "kakuyomu",
            onClick = { onSwitchSite("kakuyomu") },
            label = { Text(t("Kakuyomu", "カクヨム")) },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Discover: ranking period tabs (Today/Weekly/Monthly/All-time), filter
 * chips for genre + serial/short/all, and a search field. All calls hit
 * the open GraphQL endpoint directly — no WebView.
 */
@Composable
fun KakuyomuDiscoverBody(
    onOpenWork: (KakuyomuWork) -> Unit,
    openDrawer: () -> Unit = {},
    r18Enabled: Boolean = false,
    // Non-null in the Discover ROUTE: show a back arrow instead of the
    // hamburger (there is no drawer there).
    onBack: (() -> Unit)? = null
) {
    // Default to All-time rankings so the screen fills on entry; the Search
    // tab (index KAKUYOMU_PERIODS.size) stays a deliberate tap away.
    var tab by remember { mutableIntStateOf(KAKUYOMU_PERIODS.size - 1) }
    // Kakuyomu mixes R18 into the same index (no sister site, no server
    // gate — the API serves isSexual rows to anyone). The app gates: all-ages
    // hides them; the chip (only with the Settings 18+ gate passed) flips to
    // R18-only browsing, mirroring the Syosetu sister chip's mental model.
    var r18Mode by remember { mutableStateOf(false) }
    var genre by remember { mutableStateOf<String?>(null) }
    var variation by remember { mutableStateOf("LONG") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<KakuyomuWork>>(emptyList()) }
    val prefetchContext = androidx.compose.ui.platform.LocalContext.current
    // Warm every result title through the (serialized) translator — see the
    // Syosetu twin for why (fling-cancellation blanks otherwise).
    androidx.compose.runtime.LaunchedEffect(results) {
        results.take(30).forEach { MetadataTranslator.translateInfo(prefetchContext, it.title) }
    }
    var total by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // One shared loader: rankings for period tabs, search for the Search tab.
    // Same race guard as the Syosetu twin: rapid tab/genre/variation
    // switching must let only the LATEST load apply state (load-more
    // calls participate too — an older page appending after a tab change
    // would splice foreign rows into the list).
    var loadGen by remember { mutableIntStateOf(0) }

    fun load(reset: Boolean) {
        val gen = ++loadGen
        DebugLog.log("KAKUYOMU", "discover load start gen=" + gen + " tab=" + tab)
        scope.launch {
            loading = true
            error = ""
            if (reset) offset = 0
            try {
                if (tab < KAKUYOMU_PERIODS.size) {
                    val rows = fetchKakuyomuRankings(
                        period = KAKUYOMU_PERIODS[tab].first,
                        variation = variation,
                        genre = genre
                    )
                    if (gen != loadGen) return@launch
                    results = rows
                    total = rows.size
                } else {
                    val res = fetchKakuyomuSearch(
                        q = query.trim(),
                        order = "POPULAR",
                        genre = genre,
                        offset = offset
                    )
                    if (gen != loadGen) return@launch
                    results = if (reset) res.works else (results + res.works).distinctBy { it.id }
                    total = res.totalCount
                }
            } catch (e: Exception) {
                DebugLog.log("KAKUYOMU", "discover load failed", e)
                if (gen == loadGen) error = "読み込めませんでした: ${e.message}"
            }
            DebugLog.log("KAKUYOMU", "discover load done gen=" + gen + " latest=" + loadGen + (if (gen == loadGen) " APPLY" else " DROP"))
            if (gen == loadGen) loading = false
        }
    }

    LaunchedEffect(tab, variation, genre) { if (tab < KAKUYOMU_PERIODS.size) load(true) }

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
                t("Kakuyomu Discover", "カクヨム Discover"),
                style = MaterialTheme.typography.headlineSmall
            )
            if (r18Enabled) {
                Spacer(modifier = Modifier.weight(1f))
                FilterChip(
                    selected = r18Mode,
                    onClick = { r18Mode = !r18Mode },
                    label = { Text(if (r18Mode) "R18" else "R18 ✕") }
                )
            }
        }
        ScrollableTabRow(selectedTabIndex = tab.coerceAtMost(KAKUYOMU_PERIODS.size)) {
            KAKUYOMU_PERIODS.forEachIndexed { i, (_, labels) ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t(labels.first, labels.second)) })
            }
            Tab(
                selected = tab == KAKUYOMU_PERIODS.size,
                onClick = { tab = KAKUYOMU_PERIODS.size },
                text = { Text(t("Search", "検索")) }
            )
        }
        // Filter rail: genre (nullable) + serial/short/all. Rankings honor
        // all of these; search honors genre.
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            FilterChip(
                selected = genre == null,
                onClick = { genre = null },
                label = { Text(t("All genres", "全ジャンル")) },
                modifier = Modifier.padding(end = 6.dp)
            )
            KAKUYOMU_GENRES.forEach { (token, labels) ->
                FilterChip(
                    selected = genre == token,
                    onClick = { genre = if (genre == token) null else token },
                    label = { Text(t(labels.first, labels.second)) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            KAKUYOMU_VARIATIONS.forEach { (token, labels) ->
                FilterChip(
                    selected = variation == token,
                    onClick = { variation = token },
                    label = { Text(t(labels.first, labels.second)) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        if (tab == KAKUYOMU_PERIODS.size) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t("Search Kakuyomu", "カクヨムを検索")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { load(true) },
                    enabled = query.isNotBlank() && !loading
                ) { Text(t("Go", "検索")) }
            }
            if (total > 0 && results.size < total) {
                TextButton(
                    onClick = { offset += 20; load(false) },
                    enabled = !loading,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text(t("Load more (${results.size}/${total})", "もっと読み込む (${results.size}/${total})")) }
            }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        // R18 gate: all-ages hides isSexual rows entirely; R18 mode shows
        // ONLY them. Applied at render so toggling is instant (no refetch).
        val shown = when {
            !r18Enabled -> results.filter { !it.isSexual }
            r18Mode -> results.filter { it.isSexual }
            else -> results
        }
        if (r18Enabled && r18Mode && shown.isEmpty()) {
            Text(
                t("No R18 works in these results — try the Search tab or another genre.",
                    "この条件ではR18作品がありません — 検索タブや別ジャンルをお試しください。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
        TranslatingCaption()
        LazyColumn {
            items(shown, key = { it.id }) { work ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { onOpenWork(work) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            (if (work.isSexual) "R18 · " else "") + work.title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        TranslatedHint(text = work.title, style = MaterialTheme.typography.bodySmall)
                        if (work.catchphrase.isNotBlank()) {
                            Text(
                                work.catchphrase,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                        Text(
                            "${work.author} ・ ${kakuyomuGenreName(work.genre)} ・ " +
                                kakuyomuEpisodeLabel(work.episodeCount) + " ・ " +
                                kakuyomuStatusLabel(work.isCompleted) + " ・ " +
                                t("${work.points} pts", "${work.points}pt"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Work detail: metadata, synopsis, and the full ordered ToC from the work
 * page's embedded Apollo cache. Read opens the shared WebView reader at
 * the tapped episode; Download all builds an offline ePub through the same
 * EpubBulkService pipeline as NU and Syosetu.
 */
@Composable
fun KakuyomuDetailScreen(
    work: KakuyomuWork,
    onOpenReader: (chapters: List<Release>, startIndex: Int) -> Unit,
    onBack: () -> Unit = {},
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    categories: CategoryState = CategoryState(),
    onMove: (workId: String, catId: String?) -> Unit = { _, _ -> },
    onCreateAndMove: (workId: String, name: String) -> Unit = { _, _ -> },
    // MARK AS READ / checkbox: record the site reading-history at this
    // episode (manual). Framework B1's NU-parity write-back.
    onSetHistory: (episodeId: String, ordinal: Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<KakuyomuWorkDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var showMove by remember { mutableStateOf(false) }
    // Offline ToC of last resort: chapters already stored in the series'
    // offline books ("the chapters you've read").
    var storedList by remember(work.id) { mutableStateOf<List<Release>>(emptyList()) }

    LaunchedEffect(work.id) {
        loading = true
        error = ""
        try {
            detail = fetchKakuyomuWork(work.id)
            if (detail != null) {
                // Offline reading: every successful fetch refreshes the ToC
                // cache the offline fallback below reads back.
                saveKakuyomuDetail(context, detail!!)
            }
            if (detail == null) error = "ToC を読み込めませんでした"
        } catch (e: Exception) {
            // Fetch failed (offline / site down): the cached ToC keeps the
            // episode list, "Read from start" and the epub-reader path alive.
            detail = loadKakuyomuDetail(context, work.id)
            error = if (detail == null) "ToC を読み込めませんでした: ${e.message}"
            else t("Offline — showing the cached table of contents", "オフライン — キャッシュの目次を表示")
        }
        if (detail == null) {
            storedList = withContext(Dispatchers.IO) {
                EpubStore.storedChapters(context, work.url).map { it.toRelease() }
            }
        }
        loading = false
    }

    // Reader chapters: label cN keeps ordering/bucket logic identical to NU
    // and Syosetu (100-episode part files for volume-less works).
    fun toChapters(): List<Release> = detail!!.episodes.mapIndexed { i, ep ->
        Release(
            chapter = "c${i + 1}",
            group = "",
            date = ep.publishedAt.take(10),
            url = ep.url(work.id)
        )
    }

    // ---- NU-model detail (framework B1): shared template, exact NU
    // anatomy; Kakuyomu supplies state + site extras only.
    val episodes = detail?.episodes ?: emptyList()
    // Reading position: site history isn't mirrored locally yet (B2) —
    // the reader writes it on open; MARK AS READ records it on demand.
    val position = 0
    val nextOrdinal = if (episodes.isNotEmpty()) (position + 1).coerceAtMost(episodes.size) else 0
    val nextEp = episodes.getOrNull(nextOrdinal - 1)

    fun toChaptersOrNull(): List<Release>? = if (episodes.isNotEmpty()) toChapters() else null
    fun openAt(index: Int) {
        val chapters = toChaptersOrNull()
        if (chapters != null && index < chapters.size) onOpenReader(chapters, index)
        else if (index < storedList.size) onOpenReader(storedList, index)
    }

    SeriesDetailScreen(
        state = SeriesDetailState(
            seriesUrl = work.url,
            onBack = onBack,
            coverUrl = work.coverUrl,
            coverSeriesUrl = work.url,
            title = work.title,
            author = work.author,
            groupBadge = categories.cats.firstOrNull { it.id == categories.assign[work.id] }?.name.orEmpty(),
            changeGroupLabel = if (bookmarked) t("CHANGE LIST", "リスト変更") else t("⚑ BOOKMARK", "⚑ ブックマーク"),
            onChangeGroup = {
                if (bookmarked) showMove = true
                else onToggleBookmark()
            },
            statusLine = error,
            nextChapterLabel = when {
                nextEp != null -> "第${nextOrdinal}話 ${nextEp.title}"
                storedList.isNotEmpty() -> t("Read from start", "最初から読む") + " (${storedList.size} " + t("stored", "保存済み") + ")"
                loading -> t("Loading…", "読み込み中…")
                else -> "No chapter link found"
            },
            nextChapterEnabled = nextEp != null || storedList.isNotEmpty(),
            onReadNext = { if (nextEp != null) openAt(nextOrdinal - 1) else openAt(0) },
            onMarkRead = {
                nextEp?.let { onSetHistory(it.id, nextOrdinal) }
            },
            synopsis = (detail?.work?.introduction ?: "").ifBlank { work.catchphrase },
            typeLine = "Genre: ${kakuyomuGenreName(work.genre)} ・ " +
                (if (work.isCompleted) t("Status: Completed", "状態: 完結") else t("Status: Ongoing", "状態: 連載中")) +
                " ・ ${kakuyomuEpisodeLabel(if (episodes.isNotEmpty()) episodes.size else work.episodeCount)}",
            genres = kakuyomuGenreName(work.genre),
            tags = detail?.work?.tags ?: "",
            episodes = if (episodes.isNotEmpty()) episodes.mapIndexed { i, ep ->
                SeriesEpisodeRow(
                    ordinal = i + 1,
                    label = "第${i + 1}話",
                    title = ep.title,
                    date = ep.publishedAt.take(10),
                    isRead = false,
                    onOpen = { openAt(i) },
                    onCheck = { onSetHistory(ep.id, i + 1) }
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
            openOnSiteLabel = t("Open on Kakuyomu", "カクヨムで開く"),
            loading = loading && episodes.isEmpty() && storedList.isEmpty(),
            infoExtra = {
                Column {
                    if (work.catchphrase.isNotBlank() && (detail?.work?.introduction ?: "").isNotBlank()) {
                        Text(work.catchphrase, style = MaterialTheme.typography.bodySmall)
                    }
                    // Translation coverage (Wayne's 2026-10-17 ask).
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
                                val chapters = toChaptersOrNull() ?: return@TextButton
                                if (chapters.isNotEmpty()) {
                                    EpubBulkService.start(
                                        context,
                                        EpubBulkService.BulkJob(
                                            seriesTitle = work.title,
                                            seriesUrl = work.url,
                                            chapters = chapters.map { EpubBulkService.BulkChapter(it.url, it.chapter) },
                                            meta = (detail?.work ?: work).toBookMeta()
                                        )
                                    )
                                }
                            },
                            enabled = episodes.isNotEmpty()
                        ) { Text(t("Download all", "全部ダウンロード") + " (" + kakuyomuEpisodeLabel(detail?.episodes?.size ?: work.episodeCount) + ")") }
                    }
                    GenerateCoverButton(
                        context = context,
                        seriesUrl = work.url,
                        title = work.title,
                        author = work.author,
                        genre = kakuyomuGenreName(work.genre),
                        synopsis = (detail?.work?.introduction ?: "").ifBlank { work.catchphrase },
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

    if (showMove) {
        CategoryMoveDialog(
            workId = work.id,
            current = categories.cats.firstOrNull { it.id == categories.assign[work.id] },
            cats = categories.cats,
            onMove = { catId -> onMove(work.id, catId) },
            onCreateAndMove = { name -> onCreateAndMove(work.id, name) },
            onDismiss = { showMove = false }
        )
    }
}
