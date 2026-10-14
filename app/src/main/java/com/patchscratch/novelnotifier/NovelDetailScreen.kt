package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import kotlinx.coroutines.delay

/**
 * One series: INFO + RELEASES, CHANGE LIST, mark as read, ⋮ Refresh.
 *
 * How Compose state works here:
 *   `remember { mutableStateOf(...) }` keeps a value across redraws.
 *   `by` is a Kotlin *property delegate*. That is why we import getValue and
 *   setValue even though they never appear as function calls — do not delete them.
 *
 * Paint order:
 *   1. Load series_cache/<hash>.json if present so INFO/RELEASES appear immediately.
 *   2. First open downloads every RELEASES table page into cache (with a gap
 *      between pages so we do not skip or trip the site check).
 *   3. Later opens paint cache immediately. ⋮ Refresh re-fetches from page 1.
 *   4. RELEASES uses scroll-to-load if any pages are still missing.
 *
 * Hidden WebView is 1dp off-screen unless Cloudflare shows a check.
 * Then it expands so the user can complete the check. There is no bypass.
 *
 * CHANGE LIST: JS finds select#taskOption and fires a jQuery change event
 * so the site actually saves the move (a plain selectedIndex change is not enough).
 *
 * MARK AS READ: JS hits /readinglist_update.php?rid=&sid=&checked=yes
 *   rid = checkbox id without the "chklist" prefix
 *   sid = #mypostid on the series page (cached on SeriesPage.sid)
 *
 * READ CHAPTER (and chapter rows in RELEASES) open the in-app reader
 * (browser mode, Phase 3.1); ⋮ still offers Open on Novel Updates externally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelDetailScreen(
    novel: Novel,
    lastRead: Pair<String, String>?,
    notifyOn: Boolean,
    availableLists: List<ReadingList>,
    onMarkRead: () -> Unit,
    onSetLastRead: (String, String, Boolean) -> Unit,
    onToggleNotify: () -> Unit,
    onCoverFound: (String) -> Unit,
    onOpenReader: (chapters: List<Release>, startIndex: Int, meta: EpubStore.BookMeta?) -> Unit = { _, _, _ -> },
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val vm = viewModel<NovelDetailViewModel>()
    // page = INFO + RELEASES we currently show. Starts empty, then cache, then scrape.
    val page = vm.page
    val listStatus = vm.listStatus
    val loadedPage = vm.loadedPage
    val wantPage = vm.wantPage
    val maxPage = vm.maxPage
    val loadingMore = vm.loadingMore
    val pageLoadFailed = vm.pageLoadFailed
    var tab by remember { mutableIntStateOf(0) } // 0 = INFO, 1 = RELEASES
    var newestFirst by remember { mutableStateOf(true) }
    var hideRead by remember { mutableStateOf(false) }
    var descExpanded by remember { mutableStateOf(false) }
    var showOrder by remember { mutableStateOf(false) }
    // Per-series translation-group filter: excluded groups hide from RELEASES
    // and are skipped by the reader/bulk (reading order is filtered too, so
    // ‹ Prev / Next › and "Download all" only cover the chosen groups).
    var groupOff by remember(novel.seriesUrl) {
        mutableStateOf(loadGroupOff(context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE), novel.seriesUrl))
    }
    var showGroups by remember { mutableStateOf(false) }
    fun toggleGroup(name: String) {
        val next = if (name in groupOff) groupOff - name else groupOff + name
        groupOff = next
        saveGroupOff(context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE), novel.seriesUrl, next)
    }
    var showChangeList by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val changeLists = availableLists.ifEmpty { page.lists }

    // Cache-first policy lives in the VM; cross-VM effects (cover capture,
    // read-marker sync) ride the drain-once event list.
    LaunchedEffect(novel.seriesUrl, novel.latestChapter) { vm.open(novel, lastRead) }
    LaunchedEffect(vm.pendingEvents) {
        if (vm.pendingEvents.isEmpty()) return@LaunchedEffect
        vm.pendingEvents.forEach { ev ->
            when (ev) {
                is NovelDetailViewModel.Event.CoverFound -> onCoverFound(ev.url)
                is NovelDetailViewModel.Event.SetLastRead -> onSetLastRead(ev.chapter, ev.url, true)
            }
        }
        vm.pendingEvents = emptyList()
    }
    // Release the exclusive session when this screen leaves composition.
    DisposableEffect(Unit) {
        onDispose { vm.leave() }
    }

    val cover = usableCoverUrl(page.coverUrl).ifBlank { usableCoverUrl(novel.coverUrl) }
    // Bumped after AI cover generation so CoverThumb re-reads the store.
    var coverState by remember { mutableStateOf(0) }
    val lastReadNum = lastRead?.first?.let(::chapterNum) ?: -1
    val expectedNextNum = if (lastReadNum > 0) lastReadNum + 1 else -1
    // Next unread = last-read c-number + 1 if that row is loaded, else the lowest c we have.
    val nextUnread = if (expectedNextNum > 0) {
        page.releases.firstOrNull { chapterNum(it.chapter) == expectedNextNum }
    } else {
        page.releases.minByOrNull { num ->
            val n = chapterNum(num.chapter)
            if (n < 0) Int.MAX_VALUE else n
        }
    }
    val shownReleases = sortedReleases(page.releases, newestFirst).filter { release ->
        !hideRead || lastRead == null || chapterNum(release.chapter) >= lastReadNum
    }

    // The reader walks the book oldest → newest; hand it the full list in
    // reading order plus the tapped row's index. Groups the user filtered
    // out are excluded — reading order, the reader, and bulk download all
    // cover only the chosen groups.
    val readingOrder = remember(page.releases, groupOff) {
        sortedReleases(page.releases, newestFirst = false).filter { it.group.isBlank() || it.group !in groupOff }
    }
    // Title-page metadata rides along once the series page has been scraped
    // (blank fields simply drop rows); until then the reader keeps book meta.
    val bookMeta = remember(page.title, page.author, page.genres, page.tags, page.associatedNames) {
        EpubStore.BookMeta.from(page, novel.title)
    }
    fun openInReader(release: Release) {
        if (release.url.isBlank()) return
        val at = readingOrder.indexOfFirst { it.url == release.url && it.chapter == release.chapter }
        onOpenReader(readingOrder, at, bookMeta)
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Turn on the session and ask it for [pageNumber] of the RELEASES table. */
    fun requestPage(pageNumber: Int) {
        vm.requestPage(pageNumber)
    }

    fun retryPage() {
        vm.retryPage()
    }

    fun applyOrder(newest: Boolean) {
        newestFirst = newest
        showOrder = false
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 2 && last >= total - 3
        }
    }
    LaunchedEffect(nearEnd, loadingMore, pageLoadFailed, loadedPage, maxPage, tab) {
        if (tab != 1 || !nearEnd || loadingMore || pageLoadFailed) return@LaunchedEffect
        vm.requestNextPage()
    }

    // Hard-timeout safety net for hung loads (a slow page is NOT proof of a CF check).
    LaunchedEffect(loadingMore) {
        if (!loadingMore) return@LaunchedEffect
        delay(8.seconds)
        vm.noteLoadTimedOut()
    }

    // Extra pages: prefetch 1..max with a 2s gap, or scroll near the end.

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(64.dp)
                ) {
                    Text("←", fontSize = 36.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { showPageMenu = true },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Text("⋮", fontSize = 36.sp)
                    }
                    DropdownMenu(
                        expanded = showPageMenu,
                        onDismissRequest = { showPageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(t("Open on Novel Updates", "ノベルアップデーツで開く")) },
                            onClick = {
                                showPageMenu = false
                                openUrl(novel.seriesUrl)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(t("Refresh", "更新")) },
                            onClick = {
                                showPageMenu = false
                                vm.listStatus = "Refreshing..."
                                requestPage(1)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                CoverThumb(
                    url = cover,
                    seriesUrl = novel.seriesUrl,
                    refreshKey = coverState,
                    fullscreen = true,
                    modifier = Modifier
                        .width(84.dp)
                        .height(112.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = page.title.ifBlank { novel.title },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (page.author.isNotBlank()) {
                        Text(page.author, style = MaterialTheme.typography.bodySmall)
                    }
                    if (page.rating.isNotBlank()) {
                        Text(prettyRating(page.rating), style = MaterialTheme.typography.bodySmall)
                    }
                    if (isRealListName(page.currentList)) {
                        Text(
                            text = page.currentList,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Button(onClick = { showChangeList = true }) { Text(t("CHANGE LIST", "リスト変更")) }
                        TextButton(onClick = onToggleNotify) {
                            if (notifyOn) {
                                Icon(Icons.Rounded.Notifications, contentDescription = "Notifications on",
                                    modifier = Modifier.size(32.dp))
                            } else {
                                Icon(Icons.Rounded.NotificationsOff, contentDescription = "Notifications off",
                                    modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    if (listStatus.isNotBlank()) {
                        Text(listStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    // Cover-less NU series can get AI art like the JP sites.
                    if (cover.isBlank()) {
                        GenerateCoverButton(
                            context = context,
                            seriesUrl = novel.seriesUrl,
                            title = page.title.ifBlank { novel.title },
                            author = page.author,
                            genre = page.genres,
                            synopsis = page.description,
                            onGenerated = { coverState += 1 }
                        )
                    }
                }
            }
            if (showChangeList) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(t("Change list", "リスト変更"), style = MaterialTheme.typography.titleMedium)
                        if (changeLists.isEmpty()) {
                            Text(
                                "Open the hamburger menu once so lists can load, then try again.",
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            changeLists.forEach { list ->
                                TextButton(
                                    onClick = {
                                        vm.queueMoveList(list.id, list.name)
                                        showChangeList = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (list.name == page.currentList) "✓  ${list.name}" else list.name)
                                }
                            }
                        }
                        TextButton(
                            onClick = { showChangeList = false },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Cancel") }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(t("INFO", "情報")) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(t("RELEASES", "各話")) })
            }
            if (tab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(t("Next Chapter", "次の各話"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    nextUnread != null ->
                                        "${nextUnread.chapter}  by ${nextUnread.group}"
                                    expectedNextNum > 0 ->
                                        "c$expectedNextNum is not in the loaded pages yet. Wait for cache or scroll RELEASES."
                                    else -> "No chapter link found"
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                TextButton(
                                    onClick = { nextUnread?.let(::openInReader) },
                                    enabled = nextUnread?.url?.isNotBlank() == true
                                ) { Text(t("READ CHAPTER", "読む")) }
                                TextButton(
                                    onClick = {
                                        nextUnread?.let {
                                            onSetLastRead(it.chapter, it.url, true)
                                            vm.queueReadMarker(
                                                rid = it.rid.ifBlank { null },
                                                chapter = it.chapter
                                            )
                                        }
                                        onMarkRead()
                                    },
                                    enabled = nextUnread != null
                                ) { Text(t("MARK AS READ", "既読にする")) }
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .clickable { descExpanded = !descExpanded }
                        ) {
                            Text(t("Description", "あらすじ"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = page.description.ifBlank { "No description yet." },
                                maxLines = if (descExpanded) Int.MAX_VALUE else 6,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(if (descExpanded) "▲" else "▼", modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (page.type.isNotBlank() || page.status.isNotBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (page.type.isNotBlank()) Text("Type: ${page.type}")
                                if (page.status.isNotBlank()) Text("Status: ${page.status}")
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(t("Genres", "ジャンル"), style = MaterialTheme.typography.titleMedium)
                            Text(page.genres.ifBlank { "None listed" }, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(t("Tags", "タグ"), style = MaterialTheme.typography.titleMedium)
                            Text(page.tags.ifBlank { "None listed" }, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.align(Alignment.End)) {
                        val allGroups = page.releases.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                        if (groupOff.isNotEmpty()) {
                            TextButton(onClick = { showGroups = true }) {
                                Text("Groups: ${allGroups.size - allGroups.count { it in groupOff }}/${allGroups.size} ⛭")
                            }
                        }
                        TextButton(
                            onClick = { showGroups = true },
                            enabled = allGroups.isNotEmpty()
                        ) {
                            if (groupOff.isEmpty()) Text("All groups ⛭") else Text("Filter ⛭")
                        }
                        TextButton(onClick = { showOrder = true }) {
                            Text(if (newestFirst) "Newest First ▼" else "Oldest First ▼")
                        }
                    }
                    if (showGroups) {
                        AlertDialog(
                            onDismissRequest = { showGroups = false },
                            title = { Text("Translation groups") },
                            text = {
                                Column {
                                    Text(
                                        "Hidden groups disappear from RELEASES and are skipped by the reader and bulk download.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    val allGroups = page.releases.map { it.group.trim() }
                                        .filter { it.isNotEmpty() }
                                        .groupBy { it }
                                        .entries
                                        .sortedBy { it.key.lowercase() }
                                    allGroups.forEach { (name, rows) ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { toggleGroup(name) }
                                        ) {
                                            Switch(
                                                checked = name !in groupOff,
                                                onCheckedChange = { toggleGroup(name) }
                                            )
                                            Text(
                                                "$name (${rows.size})",
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    groupOff = emptySet()
                                    saveGroupOff(
                                        context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE),
                                        novel.seriesUrl, emptySet()
                                    )
                                    showGroups = false
                                }) { Text("Show all") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showGroups = false }) { Text("Done") }
                            }
                        )
                    }
                    if (showOrder) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(t("Release Ordering", "各話の並び順"), style = MaterialTheme.typography.titleLarge)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { newestFirst = true }
                                        .padding(top = 12.dp)
                                ) {
                                    RadioButton(selected = newestFirst, onClick = { newestFirst = true })
                                    Column {
                                        Text(t("Newest First", "新しい順"))
                                        Text(
                                            "Show releases in a descending order",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { newestFirst = false }
                                        .padding(top = 8.dp)
                                ) {
                                    RadioButton(selected = !newestFirst, onClick = { newestFirst = false })
                                    Column {
                                        Text(t("Oldest First", "古い順"))
                                        Text(
                                            t("Show releases in an ascending order", "各話を昇順で表示"),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text(t("Hide read releases", "既読の各話を隠す"), modifier = Modifier.weight(1f))
                                    Switch(checked = hideRead, onCheckedChange = { hideRead = it })
                                }
                                TextButton(
                                    onClick = { applyOrder(newestFirst) },
                                    modifier = Modifier.align(Alignment.End)
                                ) { Text(t("SAVE", "保存")) }
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            count = shownReleases.size,
                            key = { index -> shownReleases[index].chapter + shownReleases[index].url + index }
                        ) { index ->
                            val release = shownReleases[index]
                            val thisNum = chapterNum(release.chapter)
                            val isRead = lastReadNum > 0 && thisNum > 0 && thisNum == lastReadNum
                            key("${release.chapter}|${release.url}|$index") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = release.chapter,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier
                                            .width(88.dp)
                                            .clickable { openInReader(release) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(release.group)
                                        Text(release.date, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Checkbox(
                                        checked = isRead,
                                        onCheckedChange = { checked ->
                                            val thisNum = chapterNum(release.chapter)
                                            if (checked) {
                                                onSetLastRead(release.chapter, release.url, true)
                                                // NU keeps ONE tick per series — the read position —
                                                // and checking any row MOVES the marker there; the
                                                // site clears the old one itself. One call is enough.
                                                // A back-track also records a floor so the read
                                                // feed's stale, higher marks cannot undo it.
                                                if (thisNum > 0 && lastReadNum > 0 && thisNum < lastReadNum) {
                                                    recordReadFloor(
                                                        context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE),
                                                        novel.title, thisNum
                                                    )
                                                    vm.listStatus = "Moving read position back to ${release.chapter}…"
                                                }
                                                vm.queueReadMarker(
                                                    rid = release.rid.ifBlank { null },
                                                    chapter = release.chapter
                                                )
                                                onMarkRead()
                                            } else if (isRead) {
                                                // Un-checked the marker: move it down to the highest
                                                // read chapter below — or clear it entirely (rid=0).
                                                val below = page.releases
                                                    .filter {
                                                        it.read && chapterNum(it.chapter) in 1 until lastReadNum &&
                                                            it.rid.isNotBlank()
                                                    }
                                                    .maxByOrNull { chapterNum(it.chapter) }
                                                if (below != null) {
                                                    onSetLastRead(below.chapter, below.url, true)
                                                    recordReadFloor(
                                                        context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE),
                                                        novel.title, chapterNum(below.chapter)
                                                    )
                                                    vm.queueReadMarker(rid = below.rid, chapter = null)
                                                    vm.listStatus = "Read position moved to ${below.chapter}"
                                                } else {
                                                    onSetLastRead(release.chapter, release.url, false)
                                                    recordReadFloor(
                                                        context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE),
                                                        novel.title, 0
                                                    )
                                                    vm.queueReadMarker(rid = "0", chapter = null)
                                                    vm.listStatus = "Read position cleared"
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        val moreLeft = loadedPage < maxPage
                        item {
                            Column(modifier = Modifier.padding(16.dp)) {
                                when {
                                    pageLoadFailed -> {
                                        Text(
                                            text = "Could not load page $wantPage. The site may be rate-limiting.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(onClick = {
                                            retryPage()
                                        }) { Text("RETRY") }
                                    }
                                    loadingMore -> {
                                        Text(
                                            text = "Caching page ${wantPage.coerceAtLeast(1)} of ${maxPage.coerceAtLeast(wantPage)}…",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    moreLeft -> {
                                        Text(
                                            text = "Scroll for older chapters",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    page.releases.isNotEmpty() -> {
                                        Text(
                                            text = "${page.releases.size} chapters cached",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
