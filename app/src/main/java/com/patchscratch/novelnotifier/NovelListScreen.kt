@file:Suppress("SpellCheckingInspection")

package com.patchscratch.novelnotifier

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import kotlin.time.Duration.Companion.seconds

/**
 * Home screen: hamburger drawer of reading lists, cover tiles, pull-to-refresh.
 *
 * Phase 4b: the scrape lives in [ListViewModel] on a SiteClient Session;
 * this screen is pure UI over VM state. Cloudflare during a list scrape
 * surfaces through the root ChallengeOverlay (SiteClient.cfNeeded), not a
 * pane here.
 *
 * [pauseLocalCovers] is true while SyncEngineService is running so the
 * in-list cover grabber and the engine do not hit the site at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelListScreen(
    rssNovels: List<Novel>,
    rssStatus: String,
    checkHours: Int,
    loginState: String,
    readTitles: Set<String>,
    lastRead: Map<String, Pair<String, String>> = emptyMap(),
    coverCache: Map<String, String>,
    notifyOff: Set<String>,
    updateOff: Set<String>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onNovelClick: (Novel) -> Unit,
    onCoverFound: (String, String) -> Unit,
    onListsLoaded: (List<ReadingList>) -> Unit,
    onRssUrlFound: (String) -> Unit = {},
    onReadFeedFound: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    activeSite: String = "nu",
    onSwitchSite: (String) -> Unit = {},
    onOpenSyosetu: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onEditLists: () -> Unit,
    onSaveListOptions: (Set<String>, Set<String>) -> Unit,
    onLastReads: (Map<String, Pair<String, String>>) -> Unit = {},
    nsfwUnlocked: Boolean = false,
    onToggleNsfw: () -> Unit = {},
    hiddenLists: Set<String> = emptySet(),
    onHiddenLists: (Set<String>) -> Unit = {},
    pauseLocalCovers: Boolean = false,
    // Drawer's always-reachable update check (manual — bypasses the
    // once-a-day throttle).
    onCheckUpdate: () -> Unit = {}
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val vm = viewModel<ListViewModel>()

    // Feed the app-level list names (detail screen's CHANGE LIST picker).
    LaunchedEffect(vm.lists) {
        if (vm.lists.isNotEmpty()) onListsLoaded(vm.lists)
    }
    // Cross-VM side effects of a finished scrape (feed capture + read merge).
    LaunchedEffect(vm.pendingSideEffects) {
        vm.pendingSideEffects?.let { fx ->
            if (fx.rssUrl.isNotBlank()) onRssUrlFound(fx.rssUrl)
            if (fx.readRssUrl.isNotBlank()) onReadFeedFound(fx.readRssUrl)
            if (fx.lastReads.isNotEmpty()) onLastReads(fx.lastReads)
            vm.pendingSideEffects = null
        }
    }
    // First composition: paint from disk, then scrape if stale.
    LaunchedEffect(Unit) { vm.initialize(checkHours) }
    // A slow page is NOT proof of a Cloudflare check — status hint only.
    LaunchedEffect(vm.syncing) {
        if (!vm.syncing) return@LaunchedEffect
        delay(20.seconds)
        vm.noteStillWaiting()
    }

    // --- presentation-only state ---
    var showListOptions by remember { mutableStateOf(false) }
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    var layoutMode by remember { mutableStateOf(loadLayoutMode(prefs)) }
    var sortMode by remember { mutableStateOf(loadSortMode(prefs)) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    // System back closes the List options overlay instead of leaving the app.
    BackHandler(enabled = showListOptions) { showListOptions = false }

    // The Lists window is its own full screen. return so the drawer is not drawn under it.
    if (showListOptions) {
        ListOptionsScreen(
            lists = vm.lists,
            notifyOff = notifyOff,
            updateOff = updateOff,
            hiddenLists = hiddenLists,
            onSave = { n, u, h ->
                onSaveListOptions(n, u)
                onHiddenLists(h)
                showListOptions = false
            },
            onCancel = { showListOptions = false },
            onEditLists = {
                showListOptions = false
                onEditLists()
            },
            shScanLabel = vm.shScan?.let { "${it.done}/${it.total}" } ?: "",
            shScanRunning = vm.shScan?.running == true,
            onToggleShScan = { vm.toggleShScan() }
        )
        return
    }

    // SFW mode: titles living in a hidden reading list vanish. Derived from
    // the snapshot's reverse index; re-derived when the flags change or a
    // scrape updates the list set.
    val hiddenTitles = remember(nsfwUnlocked, hiddenLists, vm.lists) {
        if (nsfwUnlocked) emptySet()
        else NsfwFilter.hiddenNuTitles(loadListSnapshot(context).novelsByList, hiddenLists)
    }

    // Overlay saved cover URLs onto rows that only have a title + chapter.
    // Placeholder URLs count as "no cover" so they can be repaired.
    val shownNovels = (if (vm.selectedListId.isBlank()) rssNovels else vm.listNovels).map { novel ->
        val cached = usableCoverUrl(coverCache[novel.title])
        if (cached.isNotBlank() && usableCoverUrl(novel.coverUrl).isBlank()) {
            novel.copy(coverUrl = cached)
        } else {
            novel
        }
    }.filter { novel ->
        (searchQuery.isBlank() || novel.title.contains(searchQuery, ignoreCase = true)) &&
            novel.title !in hiddenTitles
    }.let { rows ->
        when (sortMode) {
            SORT_ALPHA -> rows.sortedBy { it.title.lowercase() }
            SORT_UNREAD_COUNT -> rows.sortedByDescending { unreadCount(it.latestChapter, lastRead[it.title]?.first) }
            SORT_UPDATE_TIME -> rows.sortedByDescending { it.updatedAt }
            else -> rows.sortedWith(
                compareByDescending<Novel> { unreadCount(it.latestChapter, lastRead[it.title]?.first) > 0 }
                    .thenByDescending { it.updatedAt }
            )
        }
    }
    val shownStatus = if (vm.selectedListId.isBlank()) {
        rssStatus
    } else {
        val missing = shownNovels.count { it.coverUrl.isBlank() && it.seriesUrl.isNotBlank() }
        val archived = vm.selectedListId in updateOff
        val base = vm.listStatus.ifBlank { "Loading ${vm.selectedList}..." }
        when {
            archived -> "$base\nUpdates off for this list"
            missing > 0 -> "$base\nFetching covers ($missing left)"
            else -> base
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = t("Reading List", "リーディングリスト"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                // Site switcher: providers are segregated workspaces — the
                // drawer below shows the ACTIVE site's lists and entries.
                SiteSwitcherChips(activeSite, onSwitchSite)
                HorizontalDivider()
                if (activeSite == "syosetu") {
                    NavigationDrawerItem(
                        label = { Text("なろう Discover  ★") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSyosetu()
                        }
                    )
                    Text(
                        "Bookmark categories sync arrives with the login slice.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    HorizontalDivider()
                } else {
                NavigationDrawerItem(
                    label = { Text(t("Search Novel Updates  ⌕", "ノベルアップデーツを検索  ⌕")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSearch()
                    }
                )
                NavigationDrawerItem(
                    label = { Text(t("Discover  ★", "ディスカバー  ★")) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenDiscover()
                    }
                )
                }
                // One management entry (Wayne's de-clutter rule): the Lists
                // window holds notify/update/hidden switches per list, the
                // site-side list editor, and the SH scan.
                NavigationDrawerItem(
                    label = { Text("Lists  ⚙") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showListOptions = true
                    }
                )
                // One row per visible NU list. Hidden lists only render
                // while NSFW mode is unlocked. Cached lists paint
                // immediately; empty lists scrape.
                val visibleLists = if (nsfwUnlocked) vm.lists
                else vm.lists.filter { it.id !in hiddenLists }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(visibleLists.size) { index ->
                        val list = visibleLists[index]
                        NavigationDrawerItem(
                            label = { Text(list.name) },
                            selected = vm.selectedListId == list.id,
                            onClick = {
                                vm.selectList(list)
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                HorizontalDivider()
                NsfwToggleRow(unlocked = nsfwUnlocked, onToggle = onToggleNsfw)
                NavigationDrawerItem(
                    label = { Text("Settings") },
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
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(vm.selectedList)
                            Text(
                                text = sortModeLabel(sortMode),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) { Text("⌕") }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) { Text("⇅") }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf(SORT_ALPHA, SORT_UNREAD_COUNT, SORT_UNREAD_AGE, SORT_UPDATE_TIME).forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(sortModeLabel(mode)) },
                                        onClick = {
                                            sortMode = mode
                                            saveSortMode(prefs, mode)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                layoutMode = if (layoutMode == LAYOUT_GRID) LAYOUT_LIST else LAYOUT_GRID
                                saveLayoutMode(prefs, layoutMode)
                            }
                        ) { if (layoutMode == LAYOUT_GRID) {
                Icon(Icons.Rounded.ViewList, contentDescription = "Switch to list")
            } else {
                Icon(Icons.Rounded.GridView, contentDescription = "Switch to grid")
            } }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showInfo = true }) { Text("i") }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
            ) {
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search this list") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true
                    )
                }
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        if (vm.selectedListId.isBlank()) {
                            onRefresh()
                        } else {
                            vm.pullToRefreshList()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    val emptyMessage = when {
                        loginState.startsWith("Not") && vm.lists.isEmpty() -> "Log in from Settings, then pull to refresh."
                        shownNovels.isEmpty() && searchQuery.isNotBlank() -> "No titles match \"$searchQuery\"."
                        vm.listNovels.isEmpty() && vm.selectedListId.isNotBlank() -> "This list is empty. Pull down to refresh."
                        shownNovels.isEmpty() -> "Pull down to refresh."
                        else -> ""
                    }
                    if (emptyMessage.isNotBlank()) {
                        EmptyState(message = emptyMessage)
                    } else if (layoutMode == LAYOUT_GRID) {
                        val widthDp = with(LocalDensity.current) {
                            LocalWindowInfo.current.containerSize.width.toDp()
                        }.value.toInt()
                        val columns = when {
                            widthDp >= 840 -> 5
                            widthDp >= 600 -> 4
                            else -> 3
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(shownNovels.size) { index ->
                                NovelGridTile(
                                    novel = shownNovels[index],
                                    seriesUrl = shownNovels[index].seriesUrl,
                                    unread = unreadCount(shownNovels[index].latestChapter, lastRead[shownNovels[index].title]?.first),
                                    onClick = { onNovelClick(shownNovels[index]) }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(shownNovels.size) { index ->
                                NovelListRow(
                                    novel = shownNovels[index],
                                    lastRead = lastRead[shownNovels[index].title],
                                    markedRead = shownNovels[index].title in readTitles,
                                    onClick = { onNovelClick(shownNovels[index]) }
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showInfo) {
            ModalBottomSheet(
                onDismissRequest = { showInfo = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(modifier = Modifier.padding(16.dp).padding(bottom = 24.dp)) {
                    Text("List info", style = MaterialTheme.typography.titleMedium)
                    Text(shownStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Text("Check interval: every $checkHours hours", style = MaterialTheme.typography.bodySmall)
                    Text(loginState, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // In-list cover grabber. SyncEngineService (Settings) is the bulk path;
    // this one only fills gaps for the list currently on screen.
    if (!pauseLocalCovers) CoverPrefetcher(
        queue = vm.coverQueue,
        onCoverFound = { title, url ->
            onCoverFound(title, url)
            vm.coverPrefetched(title, url)
        },
        onSkip = { title -> vm.coverSkipped(title) }
    )
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun NovelGridTile(novel: Novel, seriesUrl: String, unread: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box {
            CoverThumb(
                url = novel.coverUrl,
                seriesUrl = seriesUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
            )
            UnreadBadge(unread, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            text = novel.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = novel.latestChapter,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun NovelListRow(
    novel: Novel,
    lastRead: Pair<String, String>?,
    markedRead: Boolean,
    onClick: () -> Unit
) {
    val unread = unreadCount(novel.latestChapter, lastRead?.first)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                CoverThumb(url = novel.coverUrl, seriesUrl = novel.seriesUrl, modifier = Modifier.size(56.dp))
                UnreadBadge(unread, modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = novel.title, style = MaterialTheme.typography.titleMedium)
                Text(text = novel.latestChapter, style = MaterialTheme.typography.bodyMedium)
                when {
                    unread > 0 && lastRead != null -> Text(
                        text = "$unread unread · last ${lastRead.first}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    lastRead != null -> Text(
                        text = "Last read ${lastRead.first}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    markedRead -> Text(
                        text = "Read",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
