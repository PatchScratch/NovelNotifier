package com.patchscratch.novelnotifier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AlphaPolis workspace (B4): the first site built end-to-end on the
 * framework's adding-a-site contract — home on BookmarkLibraryBody, series
 * detail as a SeriesDetailState on the shared template, episodes read
 * headlessly by the shared reader (no WebView in the reading path).
 *
 * Login (later batch) is the visible in-app WebView feeding the shared
 * cookie jar; everything else is plain HttpURLConnection. Bookmarks are
 * local-only until the bookshelf recon lands — follows/bookshelf sync is
 * the last piece.
 */

/** Local bookmark row (per-site JSON pref `alphapolis_bookmarks`). */
private fun alpBookmarksKey() = "alphapolis_bookmarks"

@Composable
fun AlphaPolisHome(
    activeSite: String,
    onSwitchSite: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWork: (AlpWork) -> Unit,
    onOpenDiscover: () -> Unit = {},
    nsfwUnlocked: Boolean = false,
    onToggleNsfw: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    // Local bookmarks + reading position (client-side until bookshelf sync).
    bookmarks: List<AlpWork> = emptyList(),
    onToggleBookmark: (AlpWork) -> Unit = {},
    readPosById: (String) -> Int = { 0 },
    // Client-side categories (Kakuyomu pattern — AlphaPolis has no folders).
    categories: CategoryState = CategoryState(),
    onOpenCategories: () -> Unit = {}
) {
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    // In-place category selection (NU list-switcher parity).
    var selectedCategoryId by remember { mutableStateOf("") }
    val prefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    var sortMode by remember { mutableStateOf(loadSortMode(prefs)) }

    fun unread(w: AlpWork): Int {
        val pos = readPosById(w.id)
        return if (pos > 0) (w.episodeCount - pos).coerceAtLeast(0) else w.episodeCount
    }

    fun sortWorks(list: List<AlpWork>): List<AlpWork> = when (sortMode) {
        SORT_ALPHA -> list.sortedBy { it.title.lowercase() }
        SORT_UNREAD_COUNT -> list.sortedByDescending { unread(it) }
        SORT_UPDATE_TIME -> list.sortedByDescending { it.lastUpdate }
        else -> list.sortedByDescending { unread(it) > 0 }
    }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet {
                Text(
                    text = "アルファポリス",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                SiteSwitcherChips(activeSite, onSwitchSite)
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(t("Discover  ★", "アルファポリス Discover  ★")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenDiscover()
                    }
                )
                if (bookmarks.isNotEmpty()) {
                    androidx.compose.material3.HorizontalDivider()
                    Text(
                        t("Bookmarks", "ブックマーク"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    // NU-style switcher: All + one row per USED category
                    // (hidden ones too — their rows say what's hidden, not
                    // what's IN them); empty categories are managed in the
                    // Categories screen.
                    val hiddenAll = NsfwFilter.hiddenAlpWorkIds(categories)
                    val visibleAll = if (nsfwUnlocked) bookmarks
                        else NsfwFilter.filterAlphapolis(bookmarks, hiddenAll, unlocked = false)
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(t("All", "すべて") + " (${visibleAll.size})") },
                        selected = selectedCategoryId.isBlank(),
                        onClick = {
                            selectedCategoryId = ""
                            scope.launch { drawerState.close() }
                        }
                    )
                    categories.cats
                        .filter { cat -> !cat.hidden || nsfwUnlocked }
                        .filter { cat -> bookmarks.any { categories.assign[it.id] == cat.id } }
                        .forEach { cat ->
                            androidx.compose.material3.NavigationDrawerItem(
                                label = {
                                    Text(
                                        cat.name + " (" +
                                            visibleAll.count { categories.assign[it.id] == cat.id } + ")"
                                    )
                                },
                                selected = selectedCategoryId == cat.id,
                                onClick = {
                                    selectedCategoryId = cat.id
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(t("Categories  ⚙", "カテゴリ  ⚙")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenCategories()
                        }
                    )
                } else {
                    Text(
                        t("Open a work and tap ⚑ to keep it here — stored on this device.", "作品を開いて⚑ブックマークをタップするとここに保存されます。"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(t("Settings  ⚙", "設定  ⚙")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    }
                )
                androidx.compose.material3.NavigationDrawerItem(
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
        // SFW: hidden categories' works vanish; the selected category then
        // filters on top.
        val base = NsfwFilter.filterAlphapolis(
            bookmarks, NsfwFilter.hiddenAlpWorkIds(categories), nsfwUnlocked
        )
        val visible = base
            .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
            .filter { selectedCategoryId.isBlank() || categories.assign[it.id] == selectedCategoryId }
        val selName = categories.cats.firstOrNull { it.id == selectedCategoryId }?.name
        BookmarkLibraryBody(
            heading = if (selName == null) t("All", "すべて") + " (${visible.size})"
                else "$selName (${visible.size})",
            scrollKey = "alphapolis/" + selectedCategoryId.ifBlank { "all" },
            rows = sortWorks(visible).map { w ->
                BookmarkRow(
                    title = w.title,
                    subtitle = w.author,
                    coverUrl = w.coverUrl,
                    seriesUrl = w.url,
                    open = { onOpenWork(w) },
                    unread = unread(w),
                    latestLabel = if (readPosById(w.id) > 0) t("read ep ", "読了 ") + "${readPosById(w.id)}" else ""
                )
            },
            emptyMessage = t(
                "No bookmarks yet — open a work and tap ⚑ Bookmark to keep it here.",
                "ブックマークはまだありません — 作品を開いて⚑ブックマークをタップしてください。"
            ),
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            sortControl = {
                SortMenu(sortMode = sortMode, onSort = {
                    sortMode = it
                    saveSortMode(prefs, it)
                })
            },
            sortLabel = sortModeLabel(sortMode),
            isRefreshing = isRefreshing,
            onRefresh = onRefresh
        )
    }
}

/**
 * AlphaPolis Discover: ranking tabs (hot / annual / completed) + word
 * search — the Kakuyomu Discover shape on the same open endpoints.
 */
@Composable
fun AlphaPolisDiscoverBody(
    onOpenWork: (AlpWork) -> Unit,
    onOpenDrawer: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AlpWork>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searchRun by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab, searchRun) {
        loading = true
        error = ""
        results = try {
            if (query.isBlank()) fetchAlpRanking(ALP_RANKING_TABS[tab].first)
            else fetchAlpSearch(query)
        } catch (e: Exception) {
            error = e.message ?: "load failed"
            emptyList()
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("\u2039 " + t("Discover", "Discover")) }
            }
            Text(
                t("AlphaPolis Discover", "\u30a2\u30eb\u30d5\u30a1\u30dd\u30ea\u30b9 Discover"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab) {
            ALP_RANKING_TABS.forEachIndexed { i, (_, label) ->
                androidx.compose.material3.Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label) }
                )
            }
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(t("Search", "\u691c\u7d22")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { searchRun++ }) { Text("\u2315") }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results.size) { i ->
                val w = results[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenWork(w) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    CoverThumb(
                        url = w.coverUrl,
                        seriesUrl = w.url,
                        modifier = Modifier.padding(end = 12.dp).size(48.dp)
                    )
                    Column {
                        Text(w.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        if (w.author.isNotBlank()) {
                            Text(w.author, style = MaterialTheme.typography.bodySmall)
                        }
                        TranslatedHint(text = w.title)
                    }
                }
            }
        }
    }
}
