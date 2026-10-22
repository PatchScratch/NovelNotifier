package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    onOpenSeries: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var book by remember(bookId) { mutableStateOf<LocalLibraryStore.LocalBook?>(null) }
    var chapters by remember(bookId) { mutableStateOf<List<String>>(emptyList()) }
    var mySeries by remember(bookId) { mutableStateOf<LocalLibraryStore.LocalSeries?>(null) }
    var otherSeries by remember(bookId) { mutableStateOf<List<LocalLibraryStore.LocalSeries>>(emptyList()) }
    var loading by remember(bookId) { mutableStateOf(true) }
    var showSeriesPicker by remember { mutableStateOf(false) }
    var showEditMeta by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var readPos by remember(bookId) { mutableIntStateOf(0) }

    fun currentLibraryId(): String = book?.libraryId ?: "default"

    LaunchedEffect(bookId) {
        withContext(Dispatchers.IO) {
            book = LocalLibraryStore.book(context, bookId)
            book?.let { b ->
                chapters = LocalLibraryStore.chapterTitles(context, b)
                val sid = LocalLibraryStore.seriesIdMap(context)[b.id]
                mySeries = sid?.let { LocalLibraryStore.seriesById(context, it) }
                otherSeries = LocalLibraryStore.seriesList(context, b.libraryId)
                    .filter { it.id != sid }
            }
        }
        val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
        readPos = prefs.getInt("local_pos_$bookId", 0)
        loading = false
    }

    val b = book ?: return

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
            // Breadcrumb: the series this volume belongs to.
            crumbLabel = mySeries?.name.orEmpty(),
            // Cover from the epub's embedded cover image (filesDir cache).
            coverUrl = LocalLibraryStore.coverFile(context, bookId)
                ?.let { "file://${it.absolutePath}" } ?: "",
            coverSeriesUrl = "locallib://$bookId",
            title = b.title,
            author = b.author,
            rating = "",
            // Shelves hold SERIES now — the badge on a volume opens its
            // series page (the ⚑ action row is the natural door).
            groupBadge = mySeries?.name ?: "",
            changeGroupLabel = mySeries?.let { "📚 ${it.name}" } ?: t("📚 SERIES", "📚 シリーズ"),
            onChangeGroup = {
                val sid = mySeries?.id
                if (sid != null) onOpenSeries(sid) else showSeriesPicker = true
            },
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
            // Series membership (v3.0 restructure): move between series or
            // pull out into a fresh solo one; volume metadata is editable
            // in place (index-level — epub files never change).
            menuExtra = { closeMenu ->
                if (mySeries != null && otherSeries.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(t("Move to another series…", "別のシリーズへ移動…")) },
                        onClick = { closeMenu(); showSeriesPicker = true }
                    )
                }
                if (mySeries != null) {
                    DropdownMenuItem(
                        text = { Text(t("Remove from series", "シリーズから外す")) },
                        onClick = {
                            closeMenu()
                            scope.launch(Dispatchers.IO) {
                                LocalLibraryStore.moveBookToSeries(context, b.id, null)
                                val sid = LocalLibraryStore.seriesIdMap(context)[b.id]
                                mySeries = sid?.let { LocalLibraryStore.seriesById(context, it) }
                                otherSeries = LocalLibraryStore.seriesList(context, b.libraryId)
                                    .filter { it.id != sid }
                            }
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(t("Move to series…", "シリーズに移動…")) },
                        onClick = { closeMenu(); showSeriesPicker = true }
                    )
                }
                DropdownMenuItem(
                    text = { Text(t("Edit volume info…", "巻情報を編集…")) },
                    onClick = { closeMenu(); showEditMeta = true }
                )
                DropdownMenuItem(
                    text = { Text(t("Extract cover from epub", "EPUBからカバー抽出")) },
                    onClick = {
                        closeMenu()
                        scope.launch(Dispatchers.IO) {
                            val f = LocalLibraryStore.forceCoverExtraction(context, b.id)
                            DebugLog.log(
                                "LOCALLIB",
                                "manual cover extract ${b.id}: ${if (f != null) "ok" else "none found"}"
                            )
                        }
                    }
                )
            },
            synopsis = b.description,
            typeLine = listOfNotNull(
                "ePub".takeIf { b.chapterCount > 0 },
                "${b.chapterCount} ${t("chapters", "話")}".takeIf { b.chapterCount > 0 },
                // Linked books read from the user's own file — worth seeing
                // on the page so delete expectations are clear.
                t("Linked (read in place)", "リンク（その場で読取）").takeIf { b.linked }
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

    // Series move picker: series, not books — shelves and identity live there.
    if (showSeriesPicker) {
        SeriesPickerDialog(
            title = t("Move to series", "シリーズに移動"),
            series = otherSeries,
            onPick = { target ->
                showSeriesPicker = false
                scope.launch(Dispatchers.IO) {
                    LocalLibraryStore.moveBookToSeries(context, b.id, target.id)
                    mySeries = LocalLibraryStore.seriesById(context, target.id)
                    otherSeries = LocalLibraryStore.seriesList(context, b.libraryId)
                        .filter { it.id != target.id }
                }
            },
            onDismiss = { showSeriesPicker = false }
        )
    }

    // Volume metadata editor (index-level; epub files stay untouched).
    if (showEditMeta) {
        var mTitle by remember { mutableStateOf(b.title) }
        var mAuthor by remember { mutableStateOf(b.author) }
        var mDesc by remember { mutableStateOf(b.description) }
        AlertDialog(
            onDismissRequest = { showEditMeta = false },
            title = { Text(t("Edit volume info", "巻情報を編集")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = mTitle, onValueChange = { mTitle = it },
                        label = { Text(t("Title", "タイトル")) }, singleLine = true
                    )
                    OutlinedTextField(
                        value = mAuthor, onValueChange = { mAuthor = it },
                        label = { Text(t("Author", "作者")) }, singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = mDesc, onValueChange = { mDesc = it },
                        label = { Text(t("Description", "説明")) }, minLines = 3, maxLines = 8,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showEditMeta = false
                    scope.launch(Dispatchers.IO) {
                        LocalLibraryStore.setBookMeta(
                            context, b.id,
                            title = mTitle, author = mAuthor, description = mDesc
                        )
                        book = LocalLibraryStore.book(context, b.id)
                    }
                }) { Text(t("Save", "保存")) }
            },
            dismissButton = { TextButton(onClick = { showEditMeta = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(t("Delete book?", "この本を削除？")) },
            text = { Text(
                if (b.linked) t(
                    "Removes this book from the library. Your original file on the device is NOT deleted.",
                    "ライブラリからこの本を削除します。端末上の元ファイルは削除されません。"
                ) else t(
                    "Removes the epub and its cover from this library.",
                    "EPUBとカバーをこのライブラリから削除します。"
                )
            ) },
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
