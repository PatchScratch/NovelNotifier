@file:Suppress("SpellCheckingInspection")

package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.core.content.edit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Home screen: hamburger drawer of reading lists, cover tiles, pull-to-refresh.
 *
 * How Compose state works here:
 *   `remember { mutableStateOf(...) }` keeps a value across redraws.
 *   `by` is a Kotlin *property delegate*. That is why we import getValue and
 *   setValue even though they never appear as function calls — do not delete them.
 *   Bumping listReloadKey or siteSync is how we tell the hidden WebView
 *   "load this list again".
 *
 * Paint order:
 *   1. Load list_cache.json so the grid appears immediately.
 *   2. If last_list_sync is older than checkHours, start a hidden WebView scrape.
 *   3. Merge site rows into cache. An empty scrape with real cached c-numbers
 *      is kept, not wiped (Cloudflare / bot pages return no series).
 *
 * Hidden WebView (1dp, off-screen) reads
 *   https://www.novelupdates.com/reading-list/?list=…
 * JavaScript returns list names + series rows. Kotlin parses that string.
 *
 * [pauseLocalCovers] is true while SyncEngineService is running so two
 * WebViews do not hit the site at once.
 */
@SuppressLint("SetJavaScriptEnabled")
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
    pauseLocalCovers: Boolean = false
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // --- screen state (survives recomposition, resets if you leave the screen) ---
    var lists by remember { mutableStateOf(emptyList<ReadingList>()) }
    var selectedList by remember { mutableStateOf("My Reading List") }
    var selectedListId by remember { mutableStateOf("") }
    var listNovels by remember { mutableStateOf(emptyList<Novel>()) }
    var novelsByList by remember { mutableStateOf<Map<String, List<Novel>>>(emptyMap()) }
    var listStatus by remember { mutableStateOf("") }
    var listReloadKey by remember { mutableIntStateOf(0) }
    var siteSync by remember { mutableIntStateOf(0) }
    var showChallenge by remember { mutableStateOf(false) }
    // A Cloudflare signal was seen, but the WebView may clear it alone —
    // the grace timer below expands the pane only if it stays stuck.
    var pendingChallenge by remember { mutableStateOf(false) }
    // The pane's WebView lays its first page out at 1dp (hidden); when the
    // pane expands we reload ONCE at real size, or the challenge renders
    // zoomed-in and half transparent.
    var challengeRescaled by remember { mutableStateOf(false) }
    var coverQueue by remember { mutableStateOf(emptyList<Novel>()) }
    var showListOptions by remember { mutableStateOf(false) }
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    var layoutMode by remember { mutableStateOf(loadLayoutMode(prefs)) }
    var sortMode by remember { mutableStateOf(loadSortMode(prefs)) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    /**
     * Write the current lists + selected list to filesDir/list_cache.json.
     * Snapshot the state on the caller's thread, write the JSON on Dispatchers.IO.
     */
    fun persistListsOffMainThread() {
        val snapshot = ListSnapshot(
            lists = lists,
            selectedId = selectedListId,
            selectedName = selectedList,
            novelsByList = novelsByList
        )
        scope.launch(Dispatchers.IO) { saveListSnapshot(context, snapshot) }
    }

    // System back closes the List options overlay instead of leaving the app.
    BackHandler(enabled = showListOptions) { showListOptions = false }

    // First composition only (Unit never changes): paint from disk, then scrape if stale.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        val snap = withContext(Dispatchers.IO) { loadListSnapshot(context) }
        if (snap.lists.isNotEmpty()) {
            lists = snap.lists
            onListsLoaded(snap.lists)
            novelsByList = snap.novelsByList
            val pick = snap.lists.find { it.id == snap.selectedId } ?: snap.lists.first()
            selectedListId = pick.id
            selectedList = if (snap.selectedId == pick.id && snap.selectedName.isNotBlank()) {
                snap.selectedName
            } else {
                pick.name
            }
            listNovels = snap.novelsByList[pick.id].orEmpty()
            listStatus = if (listNovels.isEmpty()) {
                "Pull down to refresh $selectedList"
            } else {
                "Cached ${listNovels.size} series · ${lastListSyncLabel(prefs)}"
            }
            val canUpdate = selectedListId.isBlank() || selectedListId !in updateOff
            if (canUpdate && listSyncIsStale(prefs, checkHours)) {
                listStatus = "List older than $checkHours hours — checking site..."
                siteSync += 1
                listReloadKey += 1
            }
        } else {
            siteSync += 1
            listStatus = "Loading lists..."
        }
        if (siteCheckNeeded(prefs)) {
            pendingChallenge = true
            siteSync += 1
            listStatus = "Checking Novel Updates..."
        }
    }

    // Cloudflare grace: modern managed challenges usually clear themselves in
    // a real WebView. Expanding the pane for every CF signal made the page
    // flash on screen during ordinary refreshes. Surface the pane only when a
    // challenge is STILL unresolved after 12 seconds; a successful parse or a
    // page that finishes healthy cancels it silently.
    LaunchedEffect(pendingChallenge) {
        if (!pendingChallenge) return@LaunchedEffect
        delay(12.seconds)
        if (pendingChallenge && !showChallenge) {
            DebugLog.log("CF", "challenge unresolved after 12s — expanding pane")
            showChallenge = true
            markSiteCheckNeeded(prefs)
            listStatus = "Site check needed — complete it below"
        }
    }

    // A slow page is NOT proof of a Cloudflare check. Expanding the challenge
    // pane on a timeout made the real Novel Updates page flash on-screen during
    // ordinary refreshes — only genuine CF signals (URL / title / content probe
    // in the WebViewClient below) may expand it. The timer is a status hint only.
    LaunchedEffect(siteSync, listReloadKey) {
        if (siteSync == 0 || listReloadKey == 0) return@LaunchedEffect
        delay(20.seconds)
        if (siteSync > 0 && !showChallenge) {
            listStatus = "Still waiting on Novel Updates — the site is slow right now"
        }
    }

    // List options is its own full screen. return so the drawer is not drawn under it.
    if (showListOptions) {
        ListOptionsScreen(
            lists = lists,
            notifyOff = notifyOff,
            updateOff = updateOff,
            onSave = { n, u ->
                onSaveListOptions(n, u)
                showListOptions = false
            },
            onCancel = { showListOptions = false }
        )
        return
    }

    // Overlay saved cover URLs onto rows that only have a title + chapter.
    // Placeholder URLs count as "no cover" so they can be repaired.
    val shownNovels = (if (selectedListId.isBlank()) rssNovels else listNovels).map { novel ->
        val cached = usableCoverUrl(coverCache[novel.title])
        if (cached.isNotBlank() && usableCoverUrl(novel.coverUrl).isBlank()) {
            novel.copy(coverUrl = cached)
        } else {
            novel
        }
    }.filter { novel ->
        searchQuery.isBlank() || novel.title.contains(searchQuery, ignoreCase = true)
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
    val shownStatus = if (selectedListId.isBlank()) {
        rssStatus
    } else {
        val missing = shownNovels.count { it.coverUrl.isBlank() && it.seriesUrl.isNotBlank() }
        val archived = selectedListId in updateOff
        val base = listStatus.ifBlank { "Loading $selectedList..." }
        when {
            archived -> "$base\nUpdates off for this list"
            missing > 0 -> "$base\nFetching covers ($missing left)"
            else -> base
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                NavigationDrawerItem(
                    label = { Text("Edit Lists  ⚙") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onEditLists()
                    }
                )
                NavigationDrawerItem(
                    label = { Text("List options  ⚙") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showListOptions = true
                    }
                )
                // One row per NU list. Cached lists paint immediately; empty lists scrape.
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(lists.size) { index ->
                        val list = lists[index]
                        NavigationDrawerItem(
                            label = { Text(list.name) },
                            selected = selectedListId == list.id,
                            onClick = {
                                selectedList = list.name
                                selectedListId = list.id
                                coverQueue = emptyList()
                                val cached = novelsByList[list.id].orEmpty()
                                listNovels = cached
                                if (cached.isNotEmpty()) {
                                    listStatus = "Cached ${cached.size} series"
                                } else if (list.id in updateOff) {
                                    listStatus = "Updates off for ${list.name}"
                                } else {
                                    listStatus = "Loading ${list.name}..."
                                    siteSync += 1
                                    listReloadKey += 1
                                }
                                persistListsOffMainThread()
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
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
                            Text(selectedList)
                            Text(
                                text = sortModeLabel(sortMode),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.headlineSmall)
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
                        ) { Text(if (layoutMode == LAYOUT_GRID) "☰" else "▦") }
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
                        if (selectedListId.isBlank()) {
                            onRefresh()
                        } else if (selectedListId !in updateOff) {
                            listStatus = "Checking $selectedList..."
                            siteSync += 1
                            listReloadKey += 1
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    val emptyMessage = when {
                        loginState.startsWith("Not") && lists.isEmpty() -> "Log in from Settings, then pull to refresh."
                        shownNovels.isEmpty() && searchQuery.isNotBlank() -> "No titles match \"$searchQuery\"."
                        listNovels.isEmpty() && selectedListId.isNotBlank() -> "This list is empty. Pull down to refresh."
                        shownNovels.isEmpty() -> "Pull down to refresh."
                        else -> ""
                    }
                    if (emptyMessage.isNotBlank()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = emptyMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(32.dp)
                            )
                        }
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

    // Hidden 1dp WebView unless Cloudflare needs a real window.
    if (siteSync > 0 || showChallenge) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            if (showChallenge) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Complete the site check if you see one",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = {
                        showChallenge = false
                        pendingChallenge = false
                        listStatus = "Site check closed — pull to retry"
                    }) { Text("Close") }
                }
            }
            AndroidView(
        modifier = if (showChallenge) {
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(horizontal = 8.dp)
        } else {
            Modifier
                .size(1.dp)
                .alpha(0f)
                .offset(x = 4000.dp)
        },
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (isCloudflareTitle(title)) {
                            DebugLog.log("CF", "detector=title '$title'")
                            pendingChallenge = true
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (isCloudflareUrl(url)) {
                            DebugLog.log("CF", "detector=start-url $url")
                            pendingChallenge = true
                        }
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isLoginRedirect(url)) {
                            listStatus = "Session expired — log in from Settings"
                            DebugLog.log("SYNC", "list scrape hit login redirect")
                            return@onPageFinished
                        }
                        if (isCloudflareUrl(url) || isCloudflareTitle(view?.title)) {
                            DebugLog.log("CF", "detector=finish url=$url title='${view?.title}'")
                            pendingChallenge = true
                        }
                        val script = listPageJs()
                        view?.evaluateJavascript(script) { result ->
                            val raw = unwrapJsResult(result)
                            if (raw == "CF" || isCloudflareUrl(url)) {
                                DebugLog.log("CF", "detector=script-probe url=$url")
                                pendingChallenge = true
                                return@evaluateJavascript
                            }
                            val parts = raw.split(":::NOVELS:::")
                            val listPart = parts.getOrNull(0).orEmpty()
                            if (listPart.isNotBlank()) {
                                val parsedLists = listPart.split("|||").mapNotNull { item ->
                                    val bits = item.split("===", limit = 2)
                                    if (bits.size == 2) ReadingList(bits[0], bits[1]) else null
                                }.distinctBy { it.name }
                                if (parsedLists.isNotEmpty()) {
                                    lists = parsedLists
                                    onListsLoaded(parsedLists)
                                    if (selectedListId.isBlank() || parsedLists.none { it.id == selectedListId }) {
                                        val first = parsedLists.first()
                                        selectedListId = first.id
                                        selectedList = first.name
                                    }
                                }
                            }
                            val pageUrl = url.orEmpty()
                            if (selectedListId.isNotBlank() &&
                                !pageUrl.contains("list=$selectedListId") &&
                                pageUrl.contains("/reading-list")
                            ) {
                                listStatus = "Opening $selectedList..."
                                listReloadKey += 1
                                siteSync += 1
                                return@evaluateJavascript
                            }
                            if (selectedListId.isNotBlank()) {
                                // Never let a blank/placeholder site cover wipe one we had.
                                val oldCoverByUrl = novelsByList[selectedListId].orEmpty()
                                    .mapNotNull { n ->
                                        n.seriesUrl.takeIf { it.isNotBlank() }
                                            ?.let { it to usableCoverUrl(n.coverUrl) }
                                    }
                                    .toMap()
                                val result = parseListPageResult(raw, coverCache, oldCoverByUrl)
                                // NU shows the personal feed links on this page — capture them once.
                                if (result.rssUrl.isNotBlank() && prefs.getString("rss_url", "").isNullOrBlank()) {
                                    prefs.edit { putString("rss_url", result.rssUrl) }
                                    onRssUrlFound(result.rssUrl)
                                    DebugLog.log("SYNC", "captured releases link: ${result.rssUrl}")
                                }
                                if (result.readRssUrl.isNotBlank() && prefs.getString("rss_read_url", "").isNullOrBlank()) {
                                    prefs.edit { putString("rss_read_url", result.readRssUrl) }
                                    onReadFeedFound(result.readRssUrl)
                                    DebugLog.log("SYNC", "captured read-activity link: ${result.readRssUrl}")
                                }
                                val lastReads = result.lastReads
                                val parsedNovels = result.novels
                                // Row-capture visibility: what the scraper took
                                // from this page (diagnosing suggestion-widget
                                // leaks into the library, 2026-10-13).
                                DebugLog.log(
                                    "SYNC",
                                    "list '$selectedList' parsed ${parsedNovels.size} rows: " +
                                        parsedNovels.take(30).joinToString { it.title.take(28) }
                                )
                                val cachedRows = novelsByList[selectedListId].orEmpty().ifEmpty { listNovels }
                                val cachedLooksReal = cachedRows.any { chapterNum(it.latestChapter) > 0 }
                                // Cloudflare / empty HTML must never wipe a real library.
                                if (parsedNovels.isEmpty() && cachedLooksReal) {
                                    listNovels = cachedRows
                                    listStatus = "Site parse returned nothing — keeping ${cachedRows.size} cached series"
                                    return@evaluateJavascript
                                }
                                val stamped = stampChapterTimes(listNovels, parsedNovels)
                                val changed = chapterChangeCount(listNovels, stamped)
                                val nextMap = putExclusiveList(novelsByList, selectedListId, stamped)
                                listNovels = stamped
                                novelsByList = nextMap
                                if (lastReads.isNotEmpty()) onLastReads(lastReads)
                                val snapshotToWrite = ListSnapshot(
                                    lists = lists,
                                    selectedId = selectedListId,
                                    selectedName = selectedList,
                                    novelsByList = nextMap
                                )
                                scope.launch(Dispatchers.IO) {
                                    saveListSnapshot(context, snapshotToWrite)
                                }
                                coverQueue = if (selectedListId in updateOff) {
                                    emptyList()
                                } else {
                                    parsedNovels.filter {
                                        usableCoverUrl(it.coverUrl).isBlank() &&
                                            it.seriesUrl.isNotBlank() &&
                                            usableCoverUrl(coverCache[it.title]).isBlank()
                                    }
                                }
                                if (parsedNovels.isNotEmpty()) {
                                    markListSynced(
                                        context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
                                    )
                                    clearSiteCheckNeeded(prefs)
                                    showChallenge = false
                                    pendingChallenge = false
                                    DebugLog.log(
                                        "SYNC",
                                        "list '$selectedList' rows=${parsedNovels.size} " +
                                            "coversMissing=${parsedNovels.count { usableCoverUrl(it.coverUrl).isBlank() }}"
                                    )
                                }
                                listStatus = when {
                                    parsedNovels.isEmpty() -> "No series found in $selectedList"
                                    changed > 0 -> "Updated $selectedList · $changed chapter change" + if (changed == 1) "" else "s"
                                    else -> "Updated $selectedList · no chapter changes"
                                }
                            }
                        }
                    }
                }
                loadUrl("https://www.novelupdates.com/reading-list/")
            }
        },
        update = { webView ->
            if (showChallenge) {
                webView.setBackgroundColor(android.graphics.Color.WHITE)
                if (!challengeRescaled) {
                    challengeRescaled = true
                    // The page was laid out at 1dp while hidden, which wrecks
                    // the WebView's computed zoom. Relayout as a phone page
                    // (width = pane, 1:1 scale — see applyChallengeLayout) and
                    // reload AFTER this frame's layout pass (post), when the
                    // pane has its real size; reload() alone keeps the old
                    // broken layout.
                    webView.post {
                        applyChallengeLayout(webView, mobile = true)
                        webView.reload()
                    }
                }
            } else {
                challengeRescaled = false
                applyChallengeLayout(webView, mobile = false)
                webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            val target = if (selectedListId.isBlank()) {
                "https://www.novelupdates.com/reading-list/"
            } else {
                "https://www.novelupdates.com/reading-list/?list=$selectedListId"
            }
            val current = webView.url ?: ""
            if (siteSync > 0 && (listReloadKey > 0 || selectedListId.isNotBlank() || showChallenge)) {
                if (!current.startsWith(target)) webView.loadUrl(target)
            }
        }
    )
        }
    }

    // In-list cover grabber. SyncEngineService (Settings) is the bulk path;
    // this one only fills gaps for the list currently on screen.
    if (!pauseLocalCovers && !showChallenge) CoverPrefetcher(
        queue = coverQueue,
        onCoverFound = { title, url ->
            onCoverFound(title, url)
            coverQueue = coverQueue.filterNot { it.title == title }
            listNovels = listNovels.map {
                if (it.title == title) it.copy(coverUrl = url) else it
            }
        },
        onSkip = { title ->
            val skipped = coverQueue.find { it.title == title }
            coverQueue = coverQueue.filterNot { it.title == title } + listOfNotNull(skipped)
        }
    )
    }
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
