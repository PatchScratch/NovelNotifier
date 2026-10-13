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
    loggedIn: Boolean = false,
    bookmarks: List<SyosetuBookmark> = emptyList(),
    r18Enabled: Boolean = false,
    enabledSubsites: Set<Int> = setOf(1, 2, 3),
    onOpenBookmarks: (String) -> Unit = {}
) {
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
                    onClick = { scope.launch { drawerState.close() } }
                )
                if (loggedIn && bookmarks.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        t("Bookmarks", "ブックマーク"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    // Opens the bookmark library screen (grid/list, covers)
                    // instead of just closing the drawer.
                    TextButton(onClick = {
                        scope.launch { drawerState.close() }
                        onOpenBookmarks("")
                    }) { Text(t("All", "すべて") + " (${bookmarks.size}) ▦") }
                    bookmarks.map { it.category }.filter { it.isNotBlank() }.distinct().forEach { cat ->
                        TextButton(onClick = {
                            scope.launch { drawerState.close() }
                            onOpenBookmarks(cat)
                        }) {
                            Text("$cat (${bookmarks.count { it.category == cat }})")
                        }
                    }
                } else {
                    Text(
                        t("Log in from Settings to sync your bookmark library.", "設定からログインするとブックマークを同期します。"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                NavigationDrawerItem(
                    label = { Text(t("Settings  ⚙", "設定  ⚙")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    }
                )
            }
        }
    ) {
        SyosetuDiscoverBody(
            onOpenNovel = onOpenNovel,
            openDrawer = { scope.launch { drawerState.open() } },
            r18Enabled = r18Enabled,
            enabledSubsites = enabledSubsites
        )
    }
}

@Composable
fun SyosetuDiscoverBody(
    onOpenNovel: (SyosetuNovel) -> Unit,
    openDrawer: () -> Unit = {},
    r18Enabled: Boolean = false,
    enabledSubsites: Set<Int> = setOf(1, 2, 3)
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

    fun load() {
        scope.launch {
            loading = true
            error = ""
            results = try {
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
                error = "読み込めませんでした: ${e.message}"
                emptyList()
            }
            loading = false
        }
    }

    LaunchedEffect(tab) { if (tab < SYOSETU_ORDERS.size) load() }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = openDrawer) {
                Text("☰", style = MaterialTheme.typography.headlineSmall)
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
    onRemoveBookmark: () -> Unit = {}
) {
    val context = LocalContext.current
    var toc by remember { mutableStateOf<SyosetuToc?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Fetch ToC pages until we have every episode (100 per page).
    LaunchedEffect(novel.ncode) {
        DebugLog.log("SYOSETU", "detail story len=${novel.story.length} for ${novel.ncode}")
        loading = true
        error = ""
        try {
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
        } catch (e: Exception) {
            error = "ToC を読み込めませんでした: ${e.message}"
        }
        loading = false
    }

    // Reader chapters: label cN keeps ordering/bucket logic identical to NU.
    fun toChapters(): List<Release> = toc!!.episodes.map { ep ->
        // shorts: the story is the bare URL; serials: ncode/N/
        val url = if (novel.isShortStory) novel.url else "${novel.url}${ep.number}/"
        Release(chapter = "c${ep.number}", group = "", date = "", url = url)
    }

    // One scrollable surface: header, synopsis (JP + translation), buttons
    // and every episode share a single LazyColumn — a tall translated
    // synopsis must not squeeze the chapter list into a small box.
    LazyColumn(modifier = Modifier.fillMaxSize().screenInsets()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ " + t("Discover", "Discover")) }
            }
        }
        item {
            // Cover thumb beside the title, NU-style — shows generated art
            // once CoverStore has one (Syosetu itself serves no covers).
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                CoverThumb(
                    url = "",
                    seriesUrl = novel.url,
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp).width(84.dp).height(112.dp),
                    fullscreen = true
                )
                Column {
                    Text(novel.title, style = MaterialTheme.typography.headlineSmall)
                    TranslatedHint(text = novel.title, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${novel.writer} ・ ${syosetuGenreName(novel.genreCode)} ・ " +
                            syosetuEpisodeLabel(novel.episodeCount) + " ・ " + syosetuStatusLabel(novel.isCompleted),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        t("Updated", "最終更新") + " ${novel.lastUpdate.take(10)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (novel.story.isNotBlank()) {
            item {
                TranslatedInfoText(
                    text = novel.story,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        item {
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(
                    onClick = {
                        val chapters = toChapters()
                        if (chapters.isNotEmpty()) onOpenReader(chapters, 0)
                    },
                    enabled = toc?.episodes?.isNotEmpty() == true
                ) { Text(t("Read from start", "最初から読む")) }
                TextButton(
                    onClick = {
                        val chapters = toChapters()
                        if (chapters.isNotEmpty()) {
                            EpubBulkService.start(
                                context,
                                EpubBulkService.BulkJob(
                                    seriesTitle = novel.title,
                                    seriesUrl = novel.url,
                                    chapters = chapters.map { EpubBulkService.BulkChapter(it.url, it.chapter) },
                                    meta = novel.toBookMeta()
                                )
                            )
                        }
                    },
                    enabled = toc?.episodes?.isNotEmpty() == true
                ) { Text(t("Download all", "全部ダウンロード") + " (" + syosetuEpisodeLabel(novel.episodeCount) + ")") }
            }
        }
        if (bookmark != null) {
            item {
                var showCats by remember { mutableStateOf(false) }
                var confirmDelete by remember { mutableStateOf(false) }
                Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { showCats = true }) {
                        Text(t("Category", "カテゴリ") + ": " + bookmark.category + " ▾")
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(t("Remove bookmark", "ブックマーク解除"), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (showCats) {
                    AlertDialog(
                        onDismissRequest = { showCats = false },
                        title = { Text(t("Change category", "カテゴリ変更")) },
                        text = {
                            Column {
                                categories.forEach { c ->
                                    TextButton(onClick = { showCats = false; onChangeCategory(c.id) }) {
                                        Text(c.name + " (" + c.count + ")")
                                    }
                                }
                                if (categories.isEmpty()) {
                                    Text(t("Sync bookmarks once first (open the drawer).", "先にドロワーからブックマークを同期してください。"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCats = false }) { Text(t("Cancel", "キャンセル")) }
                        }
                    )
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(t("Remove bookmark?", "ブックマークを解除しますか？")) },
                        text = { Text(novel.title) },
                        confirmButton = {
                            TextButton(onClick = { confirmDelete = false; onRemoveBookmark() }) {
                                Text(t("Remove", "解除"), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel", "キャンセル")) }
                        }
                    )
                }
            }
        }
        item {
            GenerateCoverButton(
                context = context,
                seriesUrl = novel.url,
                title = novel.title,
                author = novel.writer,
                genre = syosetuGenreName(novel.genreCode),
                synopsis = novel.story,
                hasCover = CoverStore.hasLocal(context, novel.url),
                onGenerated = {}
            )
        }
        if (loading) {
            item {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
        }
        if (error.isNotBlank()) {
            item {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
        }
        val list = toc?.episodes ?: emptyList()
        items(list, key = { it.number }) { ep ->
            Text(
                "${ep.number}. ${ep.title}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val chapters = toChapters()
                        onOpenReader(chapters, ep.number - 1)
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
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
