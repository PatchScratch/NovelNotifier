package com.patchscratch.novelnotifier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
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
    val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    var series by remember(seriesId) { mutableStateOf<LocalLibraryStore.LocalSeries?>(null) }
    var volumes by remember(seriesId) { mutableStateOf<List<LocalLibraryStore.LocalBook>>(emptyList()) }
    var shelves by remember(seriesId) { mutableStateOf(CategoryState()) }
    var readPos by remember(seriesId) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var nextTitles by remember(seriesId) { mutableStateOf<List<String>>(emptyList()) }
    var showShelfPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showEditMeta by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showTranslateAll by remember { mutableStateOf(false) }
    var showTranslatedMeta by remember(seriesId) { mutableStateOf(false) }
    // v3.0.1 translation job state: live progress in the status line.
    var xlateStatus by remember(seriesId) { mutableStateOf("") }
    var otherSeries by remember(seriesId) {
        mutableStateOf<List<LocalLibraryStore.LocalSeries>>(emptyList())
    }
    var coverPaths by remember(seriesId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(seriesId) {
        withContext(Dispatchers.IO) {
            series = LocalLibraryStore.seriesById(context, seriesId)
            val vols = LocalLibraryStore.seriesVolumesOf(context, seriesId)
            volumes = vols
            shelves = CategoryStore.load(context, "local:$libraryId")
            // Cover per volume (chosen one resolves the header); the cache
            // answer first, the heavy extraction fills misses.
            coverPaths = vols.associate {
                it.id to (LocalLibraryStore.coverCached(context, it.id)?.absolutePath ?: "")
            }
            val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
            readPos = vols.associate { it.id to prefs.getInt("local_pos_${it.id}", 0) }
            otherSeries = LocalLibraryStore.seriesList(context, libraryId)
                .filter { it.id != seriesId }
            // Reader-target titles for the Next Chapter volume only —
            // opening rides the preloaded list, no zip IO on the tap.
            firstUnfinished(vols, readPos)?.let { v ->
                nextTitles = LocalLibraryStore.chapterTitlesR(context, v)
                    .ifEmpty { LocalLibraryStore.chapterTitles(context, v) }
            }
        }
    }

    val s = series ?: return
    val vol1 = volumes.firstOrNull()
    // Series cover: the pinned volume when set, else volume 1.
    val coverVol =
        if (s.coverBookId.isNotBlank()) volumes.firstOrNull { it.id == s.coverBookId } ?: vol1
        else vol1
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
            // Breadcrumb: the library this series lives in.
            crumbLabel = LocalLibraryStore.library(context, libraryId)?.name.orEmpty(),
            coverUrl = coverPaths[coverVol?.id].orEmpty().takeIf { it.isNotBlank() }
                ?.let { "file://$it" } ?: "",
            coverSeriesUrl = coverVol?.let { "locallib://${it.id}" } ?: "",
            title = s.name,
            author = vol1?.author.orEmpty(),
            groupBadge = currentShelf?.name ?: "",
            changeGroupLabel = currentShelf?.let { "⚑ ${it.name}" } ?: t("⚑ SHELF", "⚑ 棚"),
            onChangeGroup = { showShelfPicker = true },
            statusLine = xlateStatus,
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
            // v3.0.1: the AI sidecar renders dimmed under the originals and
            // the ⋮ toggle shows/hides it — same pattern as the sites.
            translateMeta = showTranslatedMeta,
            onToggleTranslateMeta = if (s.translatedMeta.isNotBlank()) {
                { showTranslatedMeta = !showTranslatedMeta }
            } else null,
            infoExtra = {
                Column {
                    if (showTranslatedMeta && s.translatedMeta.isNotBlank()) {
                        s.translatedMeta.split("\n").filter { it.isNotBlank() }.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            },
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
                // v3.0.1 series-menu translation (Wayne's original v3.0
                // ask): AI-translate the name/description sidecar, and
                // bulk-translate every stored-less chapter of every volume.
                DropdownMenuItem(
                    text = { Text(t("Translate title & description…", "タイトル・説明を翻訳…")) },
                    onClick = {
                        closeMenu()
                        xlateStatus = t("Translating…", "翻訳中…")
                        scope.launch {
                            val meta = withContext(Dispatchers.IO) {
                                translateSeriesMeta(
                                    context, prefs, s,
                                    s.description.ifBlank { vol1?.description.orEmpty() }
                                )
                            }
                            if (meta != null) {
                                withContext(Dispatchers.IO) {
                                    LocalLibraryStore.setSeriesTranslatedMeta(context, s.id, meta)
                                    series = LocalLibraryStore.seriesById(context, s.id)
                                }
                                showTranslatedMeta = true
                                xlateStatus = t("Title & description translated", "翻訳しました")
                            } else {
                                xlateStatus = t("Translation failed — check your key in Settings", "翻訳失敗 — 設定のキーを確認")
                            }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(t("Translate all chapters…", "全話を翻訳…")) },
                    onClick = { closeMenu(); showTranslateAll = true }
                )
                // Series cover: pin any volume's cover (or back to vol 1).
                if (volumes.size > 1) {
                    DropdownMenuItem(
                        text = { Text(t("Choose cover…", "カバーを選択…")) },
                        onClick = { closeMenu(); showCoverPicker = true }
                    )
                }
                // The manual fix for auto-grouping misses: pull a stray
                // series (with all its volumes) into the right one.
                if (otherSeries.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(t("Merge into another series…", "別のシリーズに統合…")) },
                        onClick = { closeMenu(); showMergePicker = true }
                    )
                }
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

    // Series cover picker: any volume's cover, or the vol-1 default.
    // Volumes with no cached cover can pull theirs from the epub inline.
    if (showCoverPicker) {
        AlertDialog(
            onDismissRequest = { showCoverPicker = false },
            title = { Text(t("Series cover", "シリーズのカバー")) },
            text = {
                Column {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        items(volumes, key = { it.id }) { v ->
                            val vol = LocalLibraryStore.volumeNumber(v.title)
                            val path = coverPaths[v.id].orEmpty()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            LocalLibraryStore.setSeriesCover(context, s.id, v.id)
                                            series = LocalLibraryStore.seriesById(context, s.id)
                                        }
                                        showCoverPicker = false
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    (if (s.coverBookId == v.id) "✓ " else "　") +
                                        (if (vol != Int.MAX_VALUE) "Vol $vol" else "ePub"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (path.isBlank()) {
                                    TextButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val f = LocalLibraryStore.forceCoverExtraction(context, v.id)
                                            if (f != null) {
                                                coverPaths = coverPaths + (v.id to f.absolutePath)
                                            }
                                        }
                                    }) { Text(t("From epub", "EPUBから")) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        LocalLibraryStore.setSeriesCover(context, s.id, "")
                        series = LocalLibraryStore.seriesById(context, s.id)
                    }
                    showCoverPicker = false
                }) { Text(t("Use volume 1", "1巻を使用")) }
            }
        )
    }

    // Bulk chapter translation (v3.0.1): every volume, every chapter,
    // skipped-when-already-translated so it resumes after interruption.
    // Scope-bound (leave the page = pause); a foreground service can take
    // over later if unattended runs matter.
    if (showTranslateAll) {
        AlertDialog(
            onDismissRequest = { showTranslateAll = false },
            title = { Text(t("Translate all chapters", "全話を翻訳")) },
            text = { Text(t(
                "Translates every chapter of every volume through your AI key (${volumes.sumOf { it.chapterCount }} chapters; already-translated chapters are skipped and re-running resumes). Keep this page open — leaving pauses the job at the next chapter.",
                "AIキーで全巻の全話を翻訳します（${volumes.sumOf { it.chapterCount }}話、翻訳済みはスキップ・再実行で再開）。実行中はこの画面を開いたままにしてください。"
            )) },
            confirmButton = {
                TextButton(onClick = {
                    showTranslateAll = false
                    val key = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
                        .getString("xlate_key", "").orEmpty()
                    if (key.isBlank()) {
                        xlateStatus = t("Set your translation key in Settings first", "先に設定で翻訳キーを設定してください")
                        return@TextButton
                    }
                    xlateStatus = t("Starting…", "開始中…")
                    scope.launch {
                        val done = withContext(Dispatchers.IO) {
                            bulkTranslateSeries(context, s.id, key) { done, total ->
                                xlateStatus = "${t("Translating", "翻訳中")} $done/$total"
                            }
                        }
                        xlateStatus = when (done) {
                            null -> t("Translation stopped — key or endpoint error", "翻訳中断 — キー/エンドポイントを確認")
                            else -> t("Translated $done chapters", "$done 話を翻訳しました")
                        }
                    }
                }) { Text(t("Translate", "翻訳")) }
            },
            dismissButton = {
                TextButton(onClick = { showTranslateAll = false }) { Text("Cancel") }
            }
        )
    }

    // Merge picker: this series' volumes fold into the chosen target.
    if (showMergePicker) {
        SeriesPickerDialog(
            title = t("Merge into series", "シリーズに統合"),
            note = t(
                "All volumes of \"${s.name}\" move into the series you pick; the emptied one disappears.",
                "「${s.name}」の全巻が選んだシリーズに移動し、空になったシリーズは削除されます。"
            ),
            series = otherSeries,
            onPick = { target ->
                showMergePicker = false
                scope.launch(Dispatchers.IO) {
                    LocalLibraryStore.mergeSeries(context, s.id, target.id)
                }
                onBack()
            },
            onDismiss = { showMergePicker = false }
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

/** One series name+description through the translation endpoint; the
 *  sidecar comes back "name\ndescription". Null = failure. */
private suspend fun translateSeriesMeta(
    context: android.content.Context,
    prefs: android.content.SharedPreferences,
    s: LocalLibraryStore.LocalSeries,
    description: String
): String? {
    val key = prefs.getString("xlate_key", "").orEmpty()
    if (key.isBlank()) return null
    val locks = XlateStack.load(context, "locallib://series/${s.id}")
    val result = runCatching {
        TranslationClient.translate(
            endpointBase = prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
            apiKey = key,
            model = prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty(),
            paragraphs = listOf(s.name, description.ifBlank { " " }),
            customPrompt = locks.prompt.ifBlank { prefs.getString("xlate_prompt", "").orEmpty() },
            glossary = locks.glossary,
            voices = locks.voices,
            temperature = prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
        )
    }.getOrNull() ?: return null
    return listOfNotNull(
        result.getOrNull(0)?.takeIf { it.isNotBlank() && it != " " },
        result.getOrNull(1)?.takeIf { it.isNotBlank() && it != " " }
    ).joinToString("\n").ifBlank { null }
}

/**
 * Every chapter of every volume through the endpoint, sequential chapters
 * (the client parallelizes chunks internally). Already-translated chapters
 * skip — safe to interrupt and re-run. Returns translated count, or null
 * on a key/endpoint failure.
 */
private suspend fun bulkTranslateSeries(
    context: android.content.Context,
    seriesId: String,
    key: String,
    onProgress: (done: Int, total: Int) -> Unit
): Int? {
    val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    val endpointBase = prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty()
    val model = prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty()
    val temp = prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
    val locks = XlateStack.load(context, "locallib://series/$seriesId")
    val volumes = LocalLibraryStore.seriesVolumesOf(context, seriesId)
    val total = volumes.sumOf { it.chapterCount }
    var done = 0
    var translated = 0
    volumes.forEach v@{ v ->
        for (idx in 0 until v.chapterCount) {
            kotlinx.coroutines.yield()
            done++
            if (LocalTranslationStore.has(context, v.id, idx)) {
                onProgress(done, total)
                continue
            }
            val chapter = LocalLibraryStore.chapterTextR(context, v, idx) ?: continue
            if (chapter.paragraphs.none { it.isNotBlank() }) {
                onProgress(done, total)
                continue
            }
            val result = runCatching {
                TranslationClient.translate(
                    endpointBase = endpointBase,
                    apiKey = key,
                    model = model,
                    paragraphs = chapter.paragraphs,
                    customPrompt = locks.prompt.ifBlank { prefs.getString("xlate_prompt", "").orEmpty() },
                    glossary = locks.glossary,
                    voices = locks.voices,
                    temperature = temp
                )
            }.getOrNull()
            if (result == null) return null
            LocalTranslationStore.put(context, v.id, idx, result)
            translated++
            onProgress(done, total)
        }
    }
    return translated
}

/**
 * Scrollable, searchable series picker for dialogs — a plain Column of
 * thousands of series overflows the dialog and can't scroll (Wayne,
 * 2026-10-21). LazyColumn with a bounded height + filter box.
 */
@Composable
fun SeriesPickerDialog(
    title: String,
    series: List<LocalLibraryStore.LocalSeries>,
    onPick: (LocalLibraryStore.LocalSeries) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t("Search", "検索")) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp, top = 4.dp)
                )
                val filtered = if (query.isBlank()) series
                else series.filter { it.name.contains(query, ignoreCase = true) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(filtered, key = { it.id }) { s ->
                        TextButton(onClick = { onPick(s) }) {
                            Text(s.name, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "閉じる")) }
        }
    )
}
