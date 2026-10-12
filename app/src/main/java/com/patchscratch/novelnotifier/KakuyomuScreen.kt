package com.patchscratch.novelnotifier

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
    onOpenWork: (KakuyomuWork) -> Unit
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
                    text = "カクヨム",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                SiteSwitcherChips(activeSite, onSwitchSite)
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(t("Discover  ★", "カクヨム Discover  ★")) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                Text(
                    t("Kakuyomu needs no account — search and read right away. Login features may arrive later.",
                        "カクヨムはアカウント不要です — すぐ検索・閲覧できます。ログイン機能は後日。"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
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
        KakuyomuDiscoverBody(
            onOpenWork = onOpenWork,
            openDrawer = { scope.launch { drawerState.open() } }
        )
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
    openDrawer: () -> Unit = {}
) {
    // Default to All-time rankings so the screen fills on entry; the Search
    // tab (index KAKUYOMU_PERIODS.size) stays a deliberate tap away.
    var tab by remember { mutableIntStateOf(KAKUYOMU_PERIODS.size - 1) }
    var genre by remember { mutableStateOf<String?>(null) }
    var variation by remember { mutableStateOf("LONG") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<KakuyomuWork>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // One shared loader: rankings for period tabs, search for the Search tab.
    fun load(reset: Boolean) {
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
                    results = rows
                    total = rows.size
                } else {
                    val res = fetchKakuyomuSearch(
                        q = query.trim(),
                        order = "POPULAR",
                        genre = genre,
                        offset = offset
                    )
                    results = if (reset) res.works else (results + res.works).distinctBy { it.id }
                    total = res.totalCount
                }
            } catch (e: Exception) {
                DebugLog.log("KAKUYOMU", "discover load failed", e)
                error = "読み込めませんでした: ${e.message}"
            }
            loading = false
        }
    }

    LaunchedEffect(tab, variation, genre) { if (tab < KAKUYOMU_PERIODS.size) load(true) }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = openDrawer) {
                Text("☰", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                t("Kakuyomu Discover", "カクヨム Discover"),
                style = MaterialTheme.typography.headlineSmall
            )
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
        LazyColumn {
            items(results, key = { it.id }) { work ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { onOpenWork(work) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(work.title, style = MaterialTheme.typography.titleMedium)
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
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<KakuyomuWorkDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(work.id) {
        loading = true
        error = ""
        try {
            detail = fetchKakuyomuWork(work.id)
            if (detail == null) error = "ToC を読み込めませんでした"
        } catch (e: Exception) {
            error = "ToC を読み込めませんでした: ${e.message}"
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

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + t("Discover", "Discover")) }
        }
        Text(
            work.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "${work.author} ・ ${kakuyomuGenreName(work.genre)} ・ " +
                kakuyomuEpisodeLabel(work.episodeCount) + " ・ " + kakuyomuStatusLabel(work.isCompleted),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val intro = detail?.work?.introduction ?: ""
        if (intro.isNotBlank()) {
            Text(intro, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(
                onClick = {
                    val chapters = toChapters()
                    if (chapters.isNotEmpty()) onOpenReader(chapters, 0)
                },
                enabled = detail?.episodes?.isNotEmpty() == true
            ) { Text(t("Read from start", "最初から読む")) }
            TextButton(
                onClick = {
                    val chapters = toChapters()
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
                enabled = detail?.episodes?.isNotEmpty() == true
            ) { Text(t("Download all", "全部ダウンロード") + " (" + kakuyomuEpisodeLabel(detail?.episodes?.size ?: work.episodeCount) + ")") }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        val list = detail?.episodes ?: emptyList()
        LazyColumn {
            items(list, key = { it.id }) { ep ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val chapters = toChapters()
                            onOpenReader(chapters, list.indexOf(ep))
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    if (ep.chapterTitle.isNotBlank()) {
                        Text(
                            ep.chapterTitle,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text("${list.indexOf(ep) + 1}. ${ep.title}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
