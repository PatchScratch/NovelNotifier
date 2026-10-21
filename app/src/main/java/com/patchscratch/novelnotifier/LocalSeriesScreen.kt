package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local series page (v3.0, restructured 2026-10-21): Series = the unit the
 * sites model — rendered through the SHARED SeriesDetailScreen (INFO /
 * RELEASES tabs, same formatting). Volumes sit on RELEASES like episodes;
 * chapters stay in each epub's ToC and open in the volume's reader. Shelves
 * hold series (CHANGE SHELF here); read tracking stays per-epub.
 */
@Composable
fun LocalSeriesScreen(
    libraryId: String,
    seriesId: String,
    onBack: () -> Unit,
    onOpenBook: (LocalLibraryStore.LocalBook) -> Unit,
    onOpenReader: (ReaderTarget) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var series by remember(seriesId) { mutableStateOf<LocalLibraryStore.LocalSeries?>(null) }
    var volumes by remember(seriesId) { mutableStateOf<List<LocalLibraryStore.LocalBook>>(emptyList()) }
    var shelves by remember(seriesId) { mutableStateOf(CategoryState()) }
    var coverPath by remember(seriesId) { mutableStateOf("") }
    var readPos by remember(seriesId) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var nextTitles by remember(seriesId) { mutableStateOf<List<String>>(emptyList()) }
    var showShelfPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showEditMeta by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(seriesId) {
        withContext(Dispatchers.IO) {
            series = LocalLibraryStore.seriesById(context, seriesId)
            val vols = LocalLibraryStore.seriesVolumesOf(context, seriesId)
            volumes = vols
            shelves = CategoryStore.load(context, "local:$libraryId")
            coverPath = vols.firstOrNull()
                ?.let { LocalLibraryStore.coverFile(context, it.id)?.absolutePath }.orEmpty()
            val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
            readPos = vols.associate { it.id to prefs.getInt("local_pos_${it.id}", 0) }
            // Reader-target titles for the Next Chapter volume only —
            // opening rides the preloaded list, no zip IO on the tap.
            firstUnfinished(vols, readPos)?.let { v ->
                nextTitles = LocalLibraryStore.chapterTitles(context, v)
            }
        }
    }

    val s = series ?: return
    val vol1 = volumes.firstOrNull()
    val currentShelf = shelves.assign[s.id]?.let { id -> shelves.cats.firstOrNull { it.id == id } }

    // Reading position stays per-epub (Wayne: "bookmarking and read tracking
    // stay with ePub"): the series' next chapter = the FIRST volume (volume
    // order) with chapters left.
    fun firstUnfinishedLocal() = firstUnfinished(volumes, readPos)

    fun openNext() {
        val v = firstUnfinishedLocal() ?: return
        val pos = (readPos[v.id] ?: 0).coerceIn(0, nextTitles.lastIndex.coerceAtLeast(0))
        onOpenReader(
            ReaderTarget(
                seriesTitle = s.name,
                seriesUrl = "locallib://series/${s.id}",
                chapters = nextTitles.mapIndexed { i, title ->
                    Release(chapter = title, group = "", date = "", url = "locallib://${v.id}/$i")
                },
                startIndex = pos
            )
        )
    }

    fun openVolumeReader(v: LocalLibraryStore.LocalBook) = onOpenBook(v)

    SeriesDetailScreen(
        state = SeriesDetailState(
            seriesUrl = "locallib://series/${s.id}",
            onBack = onBack,
            coverUrl = if (coverPath.isNotBlank()) "file://$coverPath" else "",
            coverSeriesUrl = vol1?.let { "locallib://${it.id}" } ?: "",
            title = s.name,
            author = vol1?.author.orEmpty(),
            groupBadge = currentShelf?.name ?: "",
            changeGroupLabel = currentShelf?.let { "⚑ ${it.name}" } ?: t("⚑ SHELF", "⚑ 棚"),
            onChangeGroup = { showShelfPicker = true },
            nextChapterLabel = when {
                volumes.isEmpty() -> ""
                firstUnfinishedLocal() == null -> t("All volumes read", "全巻読了")
                else -> {
                    val v = firstUnfinishedLocal()!!
                    val pos = readPos[v.id] ?: 0
                    val vol = LocalLibraryStore.volumeNumber(v.title)
                    (if (vol != Int.MAX_VALUE) "Vol $vol  ·  " else "") +
                        if (pos > 0) t("Chapter ${pos + 1}", "第${pos + 1}話")
                        else t("Start reading", "読み始める")
                }
            },
            nextChapterEnabled = volumes.isNotEmpty() && firstUnfinishedLocal() != null,
            onReadNext = { openNext() },
            onMarkRead = null,
            synopsis = s.description.ifBlank { vol1?.description.orEmpty() },
            typeLine = listOfNotNull(
                "${volumes.size} ${t("volumes", "巻")}".takeIf { volumes.isNotEmpty() },
                "${volumes.sumOf { it.chapterCount }} ${t("chapters", "話")}"
                    .takeIf { volumes.isNotEmpty() },
                t("Linked (read in place)", "リンク（その場で読取）")
                    .takeIf { volumes.isNotEmpty() && volumes.all { it.linked } }
            ).joinToString(" ・ "),
            episodes = volumes.mapIndexed { i, v ->
                val pos = readPos[v.id] ?: 0
                SeriesEpisodeRow(
                    ordinal = i + 1,
                    label = LocalLibraryStore.volumeNumber(v.title).let {
                        if (it != Int.MAX_VALUE) "Vol $it" else "ePub"
                    },
                    title = v.title,
                    date = "",
                    isRead = v.chapterCount > 0 && pos >= v.chapterCount,
                    onOpen = { openVolumeReader(v) },
                    onCheck = null
                )
            },
            readOrdinal = 0,
            menuExtra = { closeMenu ->
                DropdownMenuItem(
                    text = { Text(t("Rename series…", "シリーズ名変更…")) },
                    onClick = { closeMenu(); showRename = true }
                )
                DropdownMenuItem(
                    text = { Text(t("Edit description…", "説明を編集…")) },
                    onClick = { closeMenu(); showEditMeta = true }
                )
                DropdownMenuItem(
                    text = { Text(t("Delete series", "シリーズを削除"), color = MaterialTheme.colorScheme.error) },
                    onClick = { closeMenu(); showDelete = true }
                )
            }
        )
    )

    // Shelf picker — shelves hold SERIES (the Kakuyomu category pattern).
    if (showShelfPicker) {
        AlertDialog(
            onDismissRequest = { showShelfPicker = false },
            title = { Text(t("Move to shelf", "棚に移動")) },
            text = {
                Column {
                    shelves.cats.forEach { cat ->
                        TextButton(onClick = {
                            shelves = CategoryStore.move(shelves, s.id, cat.id)
                            CategoryStore.save(context, "local:$libraryId", shelves)
                            showShelfPicker = false
                        }) { Text((if (currentShelf?.id == cat.id) "✓ " else "　") + cat.name) }
                    }
                    TextButton(onClick = {
                        shelves = CategoryStore.move(shelves, s.id, null)
                        CategoryStore.save(context, "local:$libraryId", shelves)
                        showShelfPicker = false
                    }) { Text(t("No shelf", "棚なし")) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShelfPicker = false }) { Text(t("Close", "閉じる")) }
            }
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(t("Rename series", "シリーズ名変更")) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) LocalLibraryStore.renameSeries(context, s.id, name)
                    showRename = false
                    scope.launch(Dispatchers.IO) {
                        series = LocalLibraryStore.seriesById(context, s.id)
                    }
                }) { Text(t("Save", "保存")) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    if (showEditMeta) {
        var desc by remember { mutableStateOf(s.description) }
        AlertDialog(
            onDismissRequest = { showEditMeta = false },
            title = { Text(t("Series description", "シリーズの説明")) },
            text = {
                OutlinedTextField(value = desc, onValueChange = { desc = it }, minLines = 3, maxLines = 8)
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalLibraryStore.setSeriesDescription(context, s.id, desc)
                    showEditMeta = false
                    scope.launch(Dispatchers.IO) {
                        series = LocalLibraryStore.seriesById(context, s.id)
                    }
                }) { Text(t("Save", "保存")) }
            },
            dismissButton = { TextButton(onClick = { showEditMeta = false }) { Text("Cancel") } }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(t("Delete series?", "シリーズを削除？")) },
            text = { Text(
                t(
                    "Removes the series and its ${volumes.size} volume(s) from this library. Linked files on the device are NOT deleted.",
                    "このライブラリからシリーズと全${volumes.size}巻を削除します。リンク済みの端末内ファイルは削除されません。"
                )
            ) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch(Dispatchers.IO) {
                        LocalLibraryStore.deleteSeries(context, s.id)
                    }
                    onBack()
                }) { Text(t("Delete", "削除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }
}

/** First volume (volume order) with chapters left; null = everything read. */
private fun firstUnfinished(
    volumes: List<LocalLibraryStore.LocalBook>,
    readPos: Map<String, Int>
): LocalLibraryStore.LocalBook? =
    volumes.firstOrNull { v -> v.chapterCount > (readPos[v.id] ?: 0) }
