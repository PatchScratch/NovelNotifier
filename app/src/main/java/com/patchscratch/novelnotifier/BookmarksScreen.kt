package com.patchscratch.novelnotifier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

/**
 * Bookmark library screens, NU-home style: a dynamic-cover grid (columns
 * follow screen width) or a compact list, the visitor's choice via the
 * shared layout_mode preference. Covers come from whatever the site row
 * carries (Kakuyomu ships real cover URLs; Syosetu rows carry none yet, so
 * tiles show the no-cover placeholder until generated covers arrive — the
 * same CoverStore lookup NU uses, so AI covers will slot in unchanged).
 */

/** One row/tile of either flavor, normalized for the shared body. */
data class BookmarkRow(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val seriesUrl: String,
    val open: () -> Unit,
    // B2 unread parity: NU-home style badge + latest-chapter line.
    val unread: Int = 0,
    val latestLabel: String = "",
    // Shelf bubble (Wayne, 2026-10-29): the shelf/category name a series
    // belongs to, shown as a small chip on tiles and rows. Empty = no chip
    // (sites never set it; unshelved series show nothing).
    val shelf: String = ""
)

/**
 * Session-scoped scroll positions for list screens (Wayne, 2026-10-30:
 * "remember list position when navigating back from series page —
 * inconvenient to scroll all the way back in a large library"). Opening a
 * series replaces the list composable, and a `remember`-held state dies
 * with it; states keyed by list identity live here instead and survive the
 * round trip. LRU-capped so wandering through many lists can't grow it
 * unbounded; state objects are plain holders, safe to re-attach to a fresh
 * lazy layout on return.
 */
object ListScrollMemory {
    private const val MAX = 16
    private val grids = LinkedHashMap<String, androidx.compose.foundation.lazy.grid.LazyGridState>()
    private val lists = LinkedHashMap<String, androidx.compose.foundation.lazy.LazyListState>()

    fun grid(key: String): androidx.compose.foundation.lazy.grid.LazyGridState = synchronized(this) {
        grids.remove(key)?.let { grids[key] = it; return it }
        while (grids.size >= MAX) grids.remove(grids.keys.first())
        androidx.compose.foundation.lazy.grid.LazyGridState().also { grids[key] = it }
    }

    fun list(key: String): androidx.compose.foundation.lazy.LazyListState = synchronized(this) {
        lists.remove(key)?.let { lists[key] = it; return it }
        while (lists.size >= MAX) lists.remove(lists.keys.first())
        androidx.compose.foundation.lazy.LazyListState().also { lists[key] = it }
    }
}

@Composable
private fun BookmarksBody(
    heading: String,
    rows: List<BookmarkRow>,
    emptyMessage: String,
    onBack: () -> Unit,
    // Long-press a row/tile to manage it (Syosetu: change category /
    // remove). Null for stores without a site write-back.
    onManageRow: ((BookmarkRow) -> Unit)? = null,
    // Scroll-restore identity for this list (see ListScrollMemory).
    scrollKey: String = heading
) {
    val context = LocalContext.current
    var layoutMode by remember { mutableStateOf(loadLayoutMode(context.getSharedPreferences("novel_prefs", 0))) }
    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + t("Bookmarks", "ブックマーク")) }
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            LayoutToggle(layoutMode = layoutMode, onFlip = {
                layoutMode = if (layoutMode == LAYOUT_GRID) LAYOUT_LIST else LAYOUT_GRID
                saveLayoutMode(context.getSharedPreferences("novel_prefs", 0), layoutMode)
            })
        }
        BookmarkRowsLayout(rows, emptyMessage, onManageRow, layoutMode, scrollKey = scrollKey)
    }
}

/**
 * The NU-shaped HOME panel (2026-10-15 site-consistency pass): hamburger +
 * search line + layout toggle above the shared grid/list. The caller owns
 * filtering (category, search, SFW-hidden) — this renders what it gets.
 * Pass [onRefresh] (with [isRefreshing] from the site VM) to give the panel
 * NU's pull-to-refresh; null keeps the plain layout (route screens).
 */
@Composable
fun BookmarkLibraryBody(
    heading: String,
    rows: List<BookmarkRow>,
    emptyMessage: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onManageRow: ((BookmarkRow) -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    // B2 NU-parity: sort menu anchor next to the layout toggle; the
    // heading suffix labels the active mode ("" hides it).
    sortControl: @Composable () -> Unit = {},
    sortLabel: String = "",
    // Search history scope (Wayne, 2026-10-22): non-blank enables recent-
    // query chips under the field and records the query when a result row
    // opens — that tap is "this search found it".
    searchHistoryScope: String = "",
    // v3.2a: per-tile cover refresh (local libraries) — the backfill's
    // cover tick lands HERE, at tile render time, so covers stream in
    // without invalidating the caller's row build. Sites never pass it.
    coverRefresh: Int = 0,
    // Scroll-restore identity for this list (see ListScrollMemory).
    scrollKey: String = heading
) {
    val context = LocalContext.current
    var layoutMode by remember { mutableStateOf(loadLayoutMode(context.getSharedPreferences("novel_prefs", 0))) }
    var fieldFocused by remember { mutableStateOf(false) }
    var historyTick by remember { mutableIntStateOf(0) }
    val history = if (searchHistoryScope.isBlank()) emptyList()
    else SearchHistory.recent(context, searchHistoryScope).also { historyTick }
    // Row opens record the query (wrapped copies; rows are plain data).
    val histRows = if (searchHistoryScope.isBlank() || searchQuery.isBlank()) rows
    else rows.map { r -> r.copy(open = { SearchHistory.push(context, searchHistoryScope, searchQuery); r.open() }) }
    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Rounded.Menu, contentDescription = "Menu")
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text(t("Search this list", "このリストを検索")) },
                singleLine = true,
                // Clear affordance (Wayne, 2026-10-22): an ✕ while text is
                // present — same glyph style as the selection bar's exit.
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        TextButton(onClick = { onSearchChange("") }) { Text("✕") }
                    }
                } else null,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { fieldFocused = it.isFocused }
            )
            sortControl()
            LayoutToggle(layoutMode = layoutMode, onFlip = {
                layoutMode = if (layoutMode == LAYOUT_GRID) LAYOUT_LIST else LAYOUT_GRID
                saveLayoutMode(context.getSharedPreferences("novel_prefs", 0), layoutMode)
            })
        }
        // Recent queries: tap to reuse, long-press to forget.
        if (fieldFocused && history.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                items(history.size) { i ->
                    val q = history[i]
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 1.dp,
                        modifier = Modifier.combinedClickable(
                            onClick = { onSearchChange(q) },
                            onLongClick = {
                                SearchHistory.remove(context, searchHistoryScope, q)
                                historyTick++
                            }
                        )
                    ) {
                        Text(
                            q,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Text(
            heading + if (sortLabel.isNotBlank()) "  ·  $sortLabel" else "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            maxLines = 1
        )
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                BookmarkRowsLayout(histRows, emptyMessage, onManageRow, layoutMode, coverRefresh, scrollKey)
            }
        } else {
            BookmarkRowsLayout(histRows, emptyMessage, onManageRow, layoutMode, coverRefresh, scrollKey)
        }
    }
}

/**
 * B2 NU-parity: the home sort menu (the four NU modes). State and
 * filtering live with the caller — this only renders the anchor and
 * persists the choice.
 */
@Composable
fun SortMenu(sortMode: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⇅") }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                SORT_ALPHA to t("Alphabetical", "五十音順"),
                SORT_UNREAD_COUNT to t("Unread (Count)", "未読（件数）"),
                SORT_UNREAD_AGE to t("Unread (Update Age)", "未読（更新順）"),
                SORT_UPDATE_TIME to t("Update Time", "更新時間")
            ).forEach { (mode, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (sortMode == mode) "✓ $label" else label) },
                    onClick = { open = false; onSort(mode) }
                )
            }
        }
    }
}

@Composable
private fun LayoutToggle(layoutMode: String, onFlip: () -> Unit) {
    TextButton(onClick = onFlip) {
        if (layoutMode == LAYOUT_GRID) {
            Icon(Icons.Rounded.ViewList, contentDescription = "Switch to list")
        } else {
            Icon(Icons.Rounded.GridView, contentDescription = "Switch to grid")
        }
    }
}

@Composable
private fun BookmarkRowsLayout(
    rows: List<BookmarkRow>,
    emptyMessage: String,
    onManageRow: ((BookmarkRow) -> Unit)?,
    layoutMode: String,
    coverRefresh: Int = 0,
    scrollKey: String = ""
) {
    if (rows.isEmpty()) {
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
        // Session-held state: back from a series lands on the same tile
        // instead of the top (ListScrollMemory). Keys include the layout
        // mode so grid and list keep independent positions.
        val gridState = ListScrollMemory.grid("$scrollKey/grid")
        Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rows, key = { it.seriesUrl }) { row ->
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = { row.open() },
                        onLongClick = { onManageRow?.invoke(row) }
                    )
                ) {
                    Box {
                        CoverThumb(
                            url = row.coverUrl,
                            seriesUrl = row.seriesUrl,
                            refreshKey = coverRefresh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                        )
                        if (row.unread > 0) {
                            UnreadBadge(
                                count = row.unread,
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                        }
                        // Shelf bubble (Wayne, 2026-10-29): which shelf this
                        // series lives in, at a glance while scrolling.
                        if (row.shelf.isNotBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                            ) {
                                Text(
                                    row.shelf,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = 120.dp)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (row.latestLabel.isNotBlank()) {
                        Text(
                            row.latestLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                            maxLines = 1,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    if (row.subtitle.isNotBlank()) {
                        Text(
                            row.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        // Indicative scrollbar (Wayne, 2026-10-23): a thin thumb on the
        // right edge shows where you are in a 5.7K-book grid.
        ScrollbarIndicator(
            progress = {
                val info = gridState.layoutInfo
                val vis = info.visibleItemsInfo
                if (info.totalItemsCount > 1 && vis.isNotEmpty()) {
                    val first = vis.first()
                    val perItem = (vis.last().index - first.index).coerceAtLeast(1)
                    (first.index.toFloat() / (info.totalItemsCount - perItem)).coerceIn(0f, 1f)
                } else 0f
            },
            fraction = {
                val info = gridState.layoutInfo
                if (info.totalItemsCount > 0)
                    info.visibleItemsInfo.size.toFloat() / info.totalItemsCount
                else 1f
            }
        )
        }
    } else {
        val listState = ListScrollMemory.list("$scrollKey/list")
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(rows.size) { index ->
                val row = rows[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { row.open() }, onLongClick = { onManageRow?.invoke(row) })
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            CoverThumb(
                                url = row.coverUrl,
                                seriesUrl = row.seriesUrl,
                                refreshKey = coverRefresh,
                                modifier = Modifier.size(56.dp)
                            )
                            if (row.unread > 0) {
                                UnreadBadge(
                                    count = row.unread,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            if (row.subtitle.isNotBlank()) {
                                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            if (row.latestLabel.isNotBlank()) {
                                Text(
                                    row.latestLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                    maxLines = 1
                                )
                            }
                            if (row.shelf.isNotBlank()) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                                    modifier = Modifier.padding(top = 3.dp)
                                ) {
                                    Text(
                                        row.shelf,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .widthIn(max = 160.dp)
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ScrollbarIndicator(
            progress = {
                val info = listState.layoutInfo
                if (info.totalItemsCount > 1 && info.visibleItemsInfo.isNotEmpty()) {
                    val first = info.visibleItemsInfo.first()
                    (first.index.toFloat() / (info.totalItemsCount - info.visibleItemsInfo.size))
                        .coerceIn(0f, 1f)
                } else 0f
            },
            fraction = {
                val info = listState.layoutInfo
                if (info.totalItemsCount > 0)
                    info.visibleItemsInfo.size.toFloat() / info.totalItemsCount
                else 1f
            }
        )
        }
    }
}

/**
 * Indicative scrollbar: a thin rounded thumb riding the right edge. Not
 * draggable — it answers "where am I in 5,700 books" at a glance (Wayne,
 * 2026-10-23). [progress] 0..1 = top position; [fraction] = visible share.
 */
@Composable
private fun ScrollbarIndicator(
    progress: () -> Float,
    fraction: () -> Float
) {
    val p = progress()
    val f = fraction()
    if (f >= 1f) return   // everything visible — no bar
    BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
        val trackH = maxHeight
        val thumbH = (trackH * f).coerceIn(androidx.compose.ui.unit.Dp(28f), trackH * 0.6f)
        val y = trackH * p * (1f - f)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = y)
                .width(3.dp)
                .height(thumbH)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                )
        )
    }
}

/**
 * Syosetu bookmark library. category "" shows everything (drawer "All");
 * a named category filters. Tapping a novel hydrates full metadata via the
 * official API (one cheap request) before opening the detail screen, so
 * rows built from the bare bookmark page still open with genre/author.
 */
@Composable
fun SyosetuBookmarksScreen(
    bookmarks: List<SyosetuBookmark>,
    category: String = "",
    onOpenNovel: (SyosetuNovel) -> Unit,
    onBack: () -> Unit,
    categories: List<SyosetuCategory> = emptyList(),
    onChangeCategory: (SyosetuBookmark, Int) -> Unit = { _, _ -> },
    onRemove: (SyosetuBookmark) -> Unit = {},
    nsfwUnlocked: Boolean = false,
    hiddenCategories: Set<String> = emptySet()
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // SFW: hidden 分類 rows vanish even in the "All" view.
    val base = if (nsfwUnlocked) bookmarks
    else NsfwFilter.filterSyosetu(bookmarks, hiddenCategories, unlocked = false)
    val shown = if (category.isBlank()) base else base.filter { it.category == category }
    fun open(bm: SyosetuBookmark) {
        // Minimal placeholder opens instantly if the API call fails; the
        // detail screen re-fetches the ToC live either way.
        val minimal = SyosetuNovel(
            ncode = bm.ncode, title = bm.title, writer = "", story = "",
            genreCode = 9801, keywords = "", isCompleted = false, isShortStory = false,
            episodeCount = 0, lastUpdate = bm.updated, points = 0
        )
        scope.launch {
            val hydrated = try {
                syosetuApiQuery(mapOf("ncode" to bm.ncode, "lim" to "1")).firstOrNull()
            } catch (e: Exception) {
                DebugLog.log("SYOSETU", "bookmark hydrate failed for ${bm.ncode}", e)
                null
            }
            onOpenNovel(hydrated ?: minimal)
        }
    }
    // Long-press management: which row's dialog is open, and its mode.
    var managed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<BookmarkRow?>(null) }
    var confirmRemove by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SyosetuBookmark?>(null) }
    val managedBm: SyosetuBookmark? = managed?.let { row ->
        shown.firstOrNull { bm -> row.seriesUrl.endsWith(bm.ncode.lowercase() + "/") }
    }
    BookmarksBody(
        heading = if (category.isBlank()) t("All", "すべて") + " (${shown.size})" else "$category (${shown.size})",
        scrollKey = "syosetu/cat/" + category,
        rows = shown.map { bm ->
            BookmarkRow(
                title = bm.title,
                subtitle = bm.updated,
                coverUrl = "",
                seriesUrl = "https://ncode.syosetu.com/${bm.ncode.lowercase()}/",
                open = { open(bm) }
            )
        },
        onManageRow = { row -> managed = row },
        emptyMessage = t(
            "No bookmarks here yet — bookmark novels on syosetu.com, then Sync in Settings.",
            "ブックマークがありません — syosetu.com でブックマークし、設定で同期してください。"
        ),
        onBack = onBack
    )
    managed?.let { row ->
        val bm = managedBm
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { managed = null },
            title = { androidx.compose.material3.Text(row.title, maxLines = 2) },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(t("Category", "カテゴリ") + ": " + (bm?.category ?: ""), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    categories.forEach { c ->
                        androidx.compose.material3.TextButton(onClick = {
                            bm?.let { onChangeCategory(it, c.id) }
                            managed = null
                        }) { androidx.compose.material3.Text(c.name + " (" + c.count + ")") }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    bm?.let { confirmRemove = it }
                    managed = null
                }) { androidx.compose.material3.Text(t("Remove bookmark", "ブックマーク解除"), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { managed = null }) { androidx.compose.material3.Text(t("Cancel", "キャンセル")) }
            }
        )
    }
    confirmRemove?.let { bm ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { androidx.compose.material3.Text(t("Remove bookmark?", "ブックマークを解除しますか？")) },
            text = { androidx.compose.material3.Text(bm.title) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onRemove(bm)
                    confirmRemove = null
                }) { androidx.compose.material3.Text(t("Remove", "解除"), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRemove = null }) { androidx.compose.material3.Text(t("Cancel", "キャンセル")) }
            }
        )
    }
}

/**
 * Kakuyomu bookmark library — real covers where the work provides one.
 * categoryId "" shows all; otherwise works assigned to that client-side
 * category. Long-press a row to move it between categories.
 */
@Composable
fun KakuyomuBookmarksScreen(
    bookmarks: List<KakuyomuWork>,
    onOpenWork: (KakuyomuWork) -> Unit,
    onBack: () -> Unit,
    categories: CategoryState = CategoryState(),
    categoryId: String = "",
    onMove: (workId: String, catId: String?) -> Unit = { _, _ -> },
    onCreateAndMove: (workId: String, String) -> Unit = { _, _ -> },
    nsfwUnlocked: Boolean = false
) {
    val catName = categories.cats.firstOrNull { it.id == categoryId }?.name ?: ""
    // SFW: works assigned to a hidden category vanish from every view.
    val all = if (nsfwUnlocked) bookmarks
    else NsfwFilter.filterKakuyomu(bookmarks, NsfwFilter.hiddenKakuyomuWorkIds(categories), unlocked = false)
    val shown = if (categoryId.isBlank()) all
    else all.filter { categories.assign[it.id] == categoryId }
    var moveTo by remember { mutableStateOf<KakuyomuWork?>(null) }

    BookmarksBody(
        heading = if (categoryId.isBlank()) t("Bookmarks", "ブックマーク") + " (${shown.size})"
        else "$catName (${shown.size})",
        scrollKey = "kakuyomu/cat/" + categoryId,
        rows = shown.map { work ->
            BookmarkRow(
                title = work.title,
                subtitle = work.author,
                coverUrl = work.coverUrl,
                seriesUrl = work.url,
                open = { onOpenWork(work) }
            )
        },
        emptyMessage = t(
            "No bookmarks yet — open a work and tap ⚑ Bookmark.",
            "ブックマークがありません — 作品を開いて⚑ブックマークをタップしてください。"
        ),
        onBack = onBack,
        onManageRow = { row ->
            moveTo = shown.firstOrNull { it.url == row.seriesUrl }
        }
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
