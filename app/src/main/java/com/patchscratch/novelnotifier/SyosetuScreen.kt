package com.patchscratch.novelnotifier

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    bookmarks: List<SyosetuBookmark> = emptyList()
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
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = activeSite == "nu",
                        onClick = { onSwitchSite("nu") },
                        label = { Text(t("Novel Updates", "ノベルアップデーツ")) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                    androidx.compose.material3.FilterChip(
                        selected = activeSite == "syosetu",
                        onClick = { onSwitchSite("syosetu") },
                        label = { Text(t("Syosetu", "なろう")) },
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(t("Discover  ★", "なろう Discover  ★")) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                if (loggedIn && bookmarks.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "ブックマーク",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    TextButton(onClick = { scope.launch { drawerState.close() } }) { Text("すべて (${bookmarks.size})") }
                    bookmarks.map { it.category }.filter { it.isNotBlank() }.distinct().forEach { cat ->
                        TextButton(onClick = { scope.launch { drawerState.close() } }) {
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
            openDrawer = { scope.launch { drawerState.open() } }
        )
    }
}

@Composable
fun SyosetuDiscoverBody(
    onOpenNovel: (SyosetuNovel) -> Unit,
    openDrawer: () -> Unit = {}
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SyosetuNovel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            error = ""
            results = try {
                val params = if (tab < SYOSETU_ORDERS.size) {
                    // ranking-equivalent: order by points, all ages only
                    mapOf("order" to SYOSETU_ORDERS[tab].first, "lim" to "30", "ga" to "none")
                } else {
                    mapOf("word" to query, "order" to "yearlypoint", "lim" to "30", "ga" to "none")
                }
                syosetuApiQuery(params)
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
        }
        ScrollableTabRow(selectedTabIndex = tab) {
            SYOSETU_ORDERS.forEachIndexed { i, (_, labels) ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t(labels.first, labels.second)) })
            }
            Tab(selected = tab == SYOSETU_ORDERS.size, onClick = { tab = SYOSETU_ORDERS.size }, text = { Text(t("Search", "検索")) })
        }
        if (tab == SYOSETU_ORDERS.size) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t("Title / keyword", "タイトル・キーワード")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { if (query.isNotBlank()) load() }) { Text(t("Search", "検索")) }
            }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
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
                        Text(
                            "${novel.writer} ・ ${syosetuGenreName(novel.genreCode)} ・ " +
                                (if (novel.isShortStory) "短編" else "${novel.episodeCount}話") +
                                (if (novel.isCompleted) " ・ 完結" else " ・ 連載中"),
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
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var toc by remember { mutableStateOf<SyosetuToc?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Fetch ToC pages until we have every episode (100 per page).
    LaunchedEffect(novel.ncode) {
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

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + t("Discover", "Discover")) }
        }
        Text(
            novel.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "${novel.writer} ・ ${syosetuGenreName(novel.genreCode)} ・ " +
                "${novel.episodeCount}話 ・ ${if (novel.isCompleted) "完結" else "連載中"} ・ " +
                "最終更新 ${novel.lastUpdate.take(10)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (novel.story.isNotBlank()) {
            Text(
                novel.story,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
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
            ) { Text(t("Download all", "全部ダウンロード") + " (${novel.episodeCount}話)") }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        val list = toc?.episodes ?: emptyList()
        LazyColumn {
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
}
