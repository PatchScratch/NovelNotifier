package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.withContext

/**
 * Local book detail (v3.0 B2): the shared SeriesDetail template renders an
 * imported epub like any site's series — cover, title/author (+ dimmed AI
 * translation when the sidecar exists), description, chapters on RELEASES,
 * per-book read position driving Next Chapter, shelf picker as CHANGE LIST,
 * and the ⋮ translation actions (title/description + bulk chapter).
 */
@Composable
fun LocalBookDetail(
    bookId: String,
    onBack: () -> Unit,
    onOpenReader: (ReaderTarget) -> Unit,
    onOpenCategories: () -> Unit = {}
) {
    val context = LocalContext.current
    var book by remember(bookId) { mutableStateOf<LocalLibraryStore.LocalBook?>(null) }
    var chapters by remember(bookId) { mutableStateOf<List<String>>(emptyList()) }
    var shelves by remember(bookId) { mutableStateOf(CategoryState()) }
    var loading by remember(bookId) { mutableStateOf(true) }
    var showShelfPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var translatedTitle by remember(bookId) { mutableStateOf("") }
    var readPos by remember(bookId) { mutableIntStateOf(0) }

    fun currentLibraryId(): String = book?.libraryId ?: "default"

    LaunchedEffect(bookId) {
        withContext(Dispatchers.IO) {
            book = LocalLibraryStore.book(context, bookId)
            book?.let { b ->
                chapters = LocalLibraryStore.chapterTitles(context, b)
                shelves = CategoryStore.load(context, "local:${b.libraryId}")
            }
        }
        val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
        readPos = prefs.getInt("local_pos_$bookId", 0)
        loading = false
    }

    val b = book ?: return
    val currentShelf = shelves.assign[b.id]?.let { id -> shelves.cats.firstOrNull { it.id == id } }

    fun toChapters(): List<Release> = chapters.mapIndexed { i, title ->
        Release(chapter = title, group = "", date = "", url = "locallib://$bookId/$i")
    }

    fun openAt(index: Int) {
        val target = toChapters()
        if (target.isEmpty()) return
        onOpenReader(
            ReaderTarget(
                seriesTitle = b.title,
                seriesUrl = "locallib://$bookId",
                chapters = target,
                startIndex = index.coerceIn(0, target.lastIndex)
            )
        )
    }

    SeriesDetailScreen(
        state = SeriesDetailState(
            seriesUrl = "locallib://$bookId",
            onBack = onBack,
            coverUrl = "",
            coverSeriesUrl = "locallib://$bookId",
            title = b.title,
            author = b.author,
            rating = "",
            groupBadge = currentShelf?.name ?: "",
            changeGroupLabel = if (currentShelf != null) "⚑ ${currentShelf.name}"
            else t("⚑ SHELF", "⚑ 棚"),
            onChangeGroup = { showShelfPicker = true },
            statusLine = "",
            nextChapterLabel = when {
                chapters.isNotEmpty() && readPos < chapters.size ->
                    "Chapter ${readPos + 1} of ${chapters.size}" +
                        (if (readPos > 0) "  ·  " + t("resume", "再開") else "")
                chapters.isNotEmpty() -> t("Read from start", "最初から読む")
                loading -> t("Loading…", "読み込み中…")
                else -> "No chapters"
            },
            nextChapterEnabled = chapters.isNotEmpty(),
            onReadNext = {
                openAt(if (readPos > 0) readPos else 0)
            },
            onMarkRead = null, // local-only: position advances by reading
            synopsis = b.description,
            typeLine = listOfNotNull(
                "ePub".takeIf { b.chapterCount > 0 },
                "${b.chapterCount} ${t("chapters", "話")}".takeIf { b.chapterCount > 0 }
            ).joinToString(" ・ "),
            genres = "",
            tags = "",
            episodes = chapters.mapIndexed { i, title ->
                SeriesEpisodeRow(
                    ordinal = i + 1,
                    label = "Ch ${i + 1}",
                    title = title,
                    date = "",
                    isRead = i + 1 <= readPos,
                    onOpen = { openAt(i) },
                    onCheck = null // no site write-back for local books
                )
            },
            readOrdinal = readPos,
            openOnSiteLabel = "",
            loading = loading && chapters.isEmpty(),
            // Translation (Wayne, 2026-10-21): metadata AI translation with
            // sidecar persistence + the dimmed display gate.
            translateMeta = b.translatedMeta.isNotBlank(),
            onToggleTranslateMeta = if (b.translatedMeta.isNotBlank()) {
                { /* sidecar is present → toggle display */ }
            } else null,
            infoExtra = {
                Column {
                    if (b.translatedMeta.isNotBlank()) {
                        val lines = b.translatedMeta.split("\n", limit = 2)
                        if (lines.isNotEmpty() && lines[0].isNotBlank()) {
                            Text(
                                lines[0],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        if (lines.size > 1 && lines[1].isNotBlank()) {
                            Text(
                                lines[1],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            releasesFooter = {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row {
                        TextButton(onClick = { openAt(0) }) {
                            Text(t("Read from start", "最初から読む"))
                        }
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text(t("Delete book", "この本を削除"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        )
    )

    // Shelf picker (the Kakuyomu category-picker pattern).
    if (showShelfPicker) {
        AlertDialog(
            onDismissRequest = { showShelfPicker = false },
            title = { Text(t("Move to shelf", "棚に移動")) },
            text = {
                Column {
                    shelves.cats.forEach { cat ->
                        TextButton(onClick = {
                            shelves = CategoryStore.move(shelves, b.id, cat.id)
                            CategoryStore.save(context, "local:${currentLibraryId()}", shelves)
                            showShelfPicker = false
                        }) { Text((if (currentShelf?.id == cat.id) "✓ " else "　") + cat.name) }
                    }
                    TextButton(onClick = {
                        showShelfPicker = false
                        onOpenCategories()
                    }) { Text(t("Manage shelves…", "棚を管理…")) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShelfPicker = false }) { Text(t("Close", "閉じる")) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(t("Delete book?", "この本を削除？")) },
            text = { Text(t("Removes the epub and its cover from this library.", "EPUBとカバーをこのライブラリから削除します。")) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    kotlinx.coroutines.runBlocking {
                        withContext(Dispatchers.IO) { LocalLibraryStore.deleteBook(context, bookId) }
                    }
                    onBack()
                }) { Text(t("Delete", "削除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}
