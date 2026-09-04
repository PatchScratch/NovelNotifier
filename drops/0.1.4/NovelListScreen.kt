package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * Home screen: hamburger drawer of reading lists, cover tiles, pull-to-refresh.
 *
 * How Compose state works here:
 *   `remember { mutableStateOf(...) }` keeps a value across redraws.
 *   `by` is a Kotlin *property delegate*. That is why we import getValue and
 *   setValue even though they never appear as function calls — do not delete them.
 *   Bumping [listReloadKey] or [siteSync] is how we tell the hidden WebView
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
 * [pauseLocalCovers] is true while CoverPrefetchService is running so two
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
    var coverQueue by remember { mutableStateOf(emptyList<Novel>()) }
    var showListOptions by remember { mutableStateOf(false) }
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    var layoutMode by remember { mutableStateOf(loadLayoutMode(prefs)) }
    var sortMode by remember { mutableStateOf(loadSortMode(prefs)) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    /** Write the current lists + selected list to filesDir/list_cache.json. */
    fun persistLists() {
        saveListSnapshot(
            context,
            ListSnapshot(
                lists = lists,
                selectedId = selectedListId,
                selectedName = selectedList,
                novelsByList = novelsByList
            )
        )
    }

    // First composition only (Unit never changes): paint from disk, then scrape if stale.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        val snap = loadListSnapshot(context)
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
    val shownNovels = (if (selectedListId.isBlank()) rssNovels else listNovels).map { novel ->
        val cached = coverCache[novel.title].orEmpty()
        if (cached.isNotBlank() && novel.coverUrl.isBlank()) novel.copy(coverUrl = cached) else novel
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Reading List",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
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
                                persistLists()
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
                        val widthDp = LocalConfiguration.current.screenWidthDp
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

    // Hidden 1dp WebView, parked off-screen so it never flashes over the grid.
    // siteSync > 0 is the "please scrape" flag. JavaScript returns:
    //   "id===List Name|||id===Other:::NOVELS:::Title===c12===url===cover|||..."
    if (siteSync > 0) AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .offset(x = 4000.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val script = """
                            (function() {
                              if (document.body && document.body.innerText.indexOf('Just a moment') !== -1) {
                                return 'CF';
                              }
                              var found = {};
                              var order = [];
                              function isJunk(name) {
                                name = (name || '').replace(/\s+/g, ' ').trim();
                                if (!name || name.length > 50) return true;
                                if (/^\d+$/.test(name)) return true;
                                if (/^(OR|AND|YES|NO|---|--|–|Default|Dark|Dark Crisp|Alphabetical|Unread|Latest|Reading Lists|Reading List|Edit Lists)$/i.test(name)) return true;
                                return false;
                              }
                              function addList(id, name) {
                                name = (name || '').replace(/\s+/g, ' ').trim();
                                id = (id || '').trim();
                                if (isJunk(name)) return;
                                if (found[name]) return;
                                found[name] = id || name;
                                order.push(name);
                              }
                              var moveSelect = null;
                              document.querySelectorAll('select').forEach(function(sel) {
                                var texts = [];
                                for (var i = 0; i < sel.options.length; i++) {
                                  texts.push((sel.options[i].textContent || '').trim());
                                }
                                if (texts.indexOf('---') !== -1 || texts.indexOf('--') !== -1) {
                                  moveSelect = sel;
                                }
                              });
                              if (moveSelect) {
                                for (var j = 0; j < moveSelect.options.length; j++) {
                                  addList(moveSelect.options[j].value, moveSelect.options[j].textContent);
                                }
                              }
                              document.querySelectorAll('a[href*="/reading-list"]').forEach(function(a) {
                                var href = a.href || '';
                                var m = href.match(/[?&]list=([^&]+)/);
                                addList(m ? decodeURIComponent(m[1]) : '', a.textContent);
                              });
                              var lists = [];
                              for (var n = 0; n < order.length; n++) {
                                lists.push(found[order[n]] + '===' + order[n]);
                              }
                              var novels = [];
                              function isJunkTitle(title) {
                                return /^(series information|description|latest release|status|notes|latest series)$/i.test(title);
                              }
                              document.querySelectorAll('a[href*="/series/"]').forEach(function(a) {
                                var title = (a.textContent || '').replace(/\s+/g, ' ').trim();
                                var href = a.href || '';
                                if (!title || isJunkTitle(title)) return;
                                if (!/\/series\/[a-z0-9-]+/i.test(href)) return;
                                var chapter = '';
                                var lastReadCh = '';
                                var lastReadUrl = '';
                                var img = '';
                                var hasListMark = false;
                                var row = a.closest ? a.closest('tr') : a.parentElement;
                                if (row) {
                                  var statusA = row.querySelector('[id^="mystatus"], a[id*="mystatus"], #mystatus');
                                  var latestA = row.querySelector('[id^="mylatest"], a[id*="mylatest"]');
                                  if (latestA) chapter = (latestA.textContent || '').replace(/\s+/g, ' ').trim();
                                  if (statusA) {
                                    lastReadCh = (statusA.textContent || '').replace(/\s+/g, ' ').trim();
                                    lastReadUrl = statusA.getAttribute('href') || '';
                                  }
                                }
                                var node = a.parentElement;
                                for (var k = 0; k < 8 && node; k++) {
                                  var text = node.textContent || '';
                                  if (!chapter) {
                                    var found = text.match(/\[\s*([^\]]+)\]/g) || [];
                                    var nums = [];
                                    for (var f = 0; f < found.length; f++) {
                                      var inner = found[f].replace(/[\[\]]/g, '').trim();
                                      if (/c\d+|ch\s*\d+|chapter\s*\d+|ep\s*\d+|v\d+/i.test(inner)) nums.push(inner);
                                    }
                                    if (nums.length >= 2) {
                                      if (!lastReadCh) lastReadCh = nums[0];
                                      chapter = nums[nums.length - 1];
                                    } else if (nums.length === 1) {
                                      chapter = nums[0];
                                    }
                                  }
                                  if (node.querySelector && (
                                    node.querySelector('input[id^="chklist"]') ||
                                    node.querySelector('input[type="checkbox"]')
                                  )) hasListMark = true;
                                  var image = node.querySelector ? node.querySelector('img') : null;
                                  if (image && image.src && image.src.indexOf('data:') !== 0 && image.src.indexOf('icon') === -1) {
                                    if (!img) img = image.src;
                                  }
                                  node = node.parentElement;
                                }
                                if (!chapter && !hasListMark) return;
                                novels.push(title + '===' + (chapter || 'No chapter info') + '===' + href + '===' + img + '===' + lastReadCh + '===' + lastReadUrl);
                              });
                              return lists.join('|||') + ':::NOVELS:::' + novels.join('|||');
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { result ->
                            val raw = unwrapJsResult(result)
                            if (raw == "CF") {
                                listStatus = "Site check on reading list — pull to retry after it clears"
                                return@evaluateJavascript
                            }
                            val parts = raw.split(":::NOVELS:::")
                            val listPart = parts.getOrNull(0).orEmpty()
                            val novelPart = parts.getOrNull(1).orEmpty()
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
                                val lastReads = mutableMapOf<String, Pair<String, String>>()
                                val parsedNovels = novelPart.split("|||").mapNotNull { item ->
                                    val bits = item.split("===", limit = 6)
                                    if (bits.size >= 2) {
                                        val title = bits[0]
                                        val seriesUrl = bits.getOrNull(2).orEmpty()
                                        val siteLast = bits.getOrNull(4).orEmpty().trim()
                                        val skipLast = siteLast.isBlank() ||
                                            siteLast == "—" ||
                                            siteLast == "-" ||
                                            siteLast.equals("none", true) ||
                                            siteLast.equals("n/a", true)
                                        if (!skipLast && chapterNum(siteLast) > 0) {
                                            lastReads[title] = siteLast to bits.getOrNull(5).orEmpty()
                                        }
                                        Novel(
                                            title = title,
                                            latestChapter = bits[1],
                                            seriesUrl = seriesUrl,
                                            coverUrl = coverCache[title].orEmpty().ifBlank {
                                                bits.getOrNull(3).orEmpty()
                                            }
                                        )
                                    } else null
                                }.distinctBy { it.title }
                                    .filter { novel ->
                                        val title = novel.title.trim()
                                        title.isNotBlank() &&
                                            !title.equals("Series Information", ignoreCase = true) &&
                                            novel.seriesUrl.contains("/series/")
                                    }
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
                                saveListSnapshot(
                                    context,
                                    ListSnapshot(
                                        lists = lists,
                                        selectedId = selectedListId,
                                        selectedName = selectedList,
                                        novelsByList = nextMap
                                    )
                                )
                                coverQueue = if (selectedListId in updateOff) {
                                    emptyList()
                                } else {
                                    parsedNovels.filter {
                                        it.coverUrl.isBlank() &&
                                            it.seriesUrl.isNotBlank() &&
                                            coverCache[it.title].isNullOrBlank()
                                    }
                                }
                                if (parsedNovels.isNotEmpty()) {
                                    markListSynced(
                                        context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
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
            val target = if (selectedListId.isBlank()) {
                "https://www.novelupdates.com/reading-list/"
            } else {
                "https://www.novelupdates.com/reading-list/?list=$selectedListId"
            }
            val current = webView.url ?: ""
            if (siteSync > 0 && (listReloadKey > 0 || selectedListId.isNotBlank())) {
                if (!current.startsWith(target)) webView.loadUrl(target)
            }
        }
    )

    // In-list cover grabber. CoverPrefetchService (Settings) is the bulk path;
    // this one only fills gaps for the list currently on screen.
    if (!pauseLocalCovers) CoverPrefetcher(
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
private fun NovelGridTile(novel: Novel, unread: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box {
            CoverThumb(
                url = novel.coverUrl,
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
                CoverThumb(url = novel.coverUrl, modifier = Modifier.size(56.dp))
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
