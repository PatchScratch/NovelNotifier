package com.patchscratch.novelnotifier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Icon

/**
 * Bookmark library screens, NU-home style: a dynamic-cover grid (columns
 * follow screen width) or a compact list, the visitor's choice via the
 * shared layout_mode preference. Covers come from whatever the site row
 * carries (Kakuyomu ships real cover URLs; Syosetu rows carry none yet, so
 * tiles show the no-cover placeholder until generated covers arrive — the
 * same CoverStore lookup NU uses, so AI covers will slot in unchanged).
 */

/** One row/tile of either flavor, normalized for the shared body. */
private data class BookmarkRow(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val seriesUrl: String,
    val open: () -> Unit
)

@Composable
private fun BookmarksBody(
    heading: String,
    rows: List<BookmarkRow>,
    emptyMessage: String,
    onBack: () -> Unit,
    // Long-press a row/tile to manage it (Syosetu: change category /
    // remove). Null for stores without a site write-back.
    onManageRow: ((BookmarkRow) -> Unit)? = null
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
            TextButton(onClick = {
                layoutMode = if (layoutMode == LAYOUT_GRID) LAYOUT_LIST else LAYOUT_GRID
                saveLayoutMode(context.getSharedPreferences("novel_prefs", 0), layoutMode)
            }) { if (layoutMode == LAYOUT_GRID) {
                Icon(Icons.Rounded.ViewList, contentDescription = "Switch to list")
            } else {
                Icon(Icons.Rounded.GridView, contentDescription = "Switch to grid")
            } }
        }
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(32.dp))
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
                items(rows, key = { it.seriesUrl }) { row ->
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = { row.open() },
                            onLongClick = { onManageRow?.invoke(row) }
                        )
                    ) {
                        CoverThumb(
                            url = row.coverUrl,
                            seriesUrl = row.seriesUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                        )
                        Text(
                            row.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
        } else {
            LazyColumn(
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
                            CoverThumb(
                                url = row.coverUrl,
                                seriesUrl = row.seriesUrl,
                                modifier = Modifier.size(56.dp)
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                if (row.subtitle.isNotBlank()) {
                                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
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
    onRemove: (SyosetuBookmark) -> Unit = {}
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shown = if (category.isBlank()) bookmarks else bookmarks.filter { it.category == category }
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
    onCreateAndMove: (workId: String, String) -> Unit = { _, _ -> }
) {
    val catName = categories.cats.firstOrNull { it.id == categoryId }?.name ?: ""
    val shown = if (categoryId.isBlank()) bookmarks
    else bookmarks.filter { categories.assign[it.id] == categoryId }
    var moveTo by remember { mutableStateOf<KakuyomuWork?>(null) }

    BookmarksBody(
        heading = if (categoryId.isBlank()) t("Bookmarks", "ブックマーク") + " (${shown.size})"
        else "$catName (${shown.size})",
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
