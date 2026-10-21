package com.patchscratch.novelnotifier

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local Library home (Wayne, 2026-10-21): rendered through the SHARED
 * BookmarkLibraryBody — the same scaffold every site's home uses (search,
 * sort, grid/list, pull-to-refresh). Libraries appear as sites in the
 * switcher; Shelves ride the Kakuyomu CategoryStore pattern.
 */
@Composable
fun LocalLibraryHome(
    libraryId: String,
    activeSite: String,
    onSwitchSite: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenBook: (LocalLibraryStore.LocalBook) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenCategories: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // NSFW gate: hidden shelves (and their books) stay out of sight until
    // the biometric unlock — same rule as every site's hidden categories.
    val app = androidx.lifecycle.viewmodel.compose.viewModel<AppViewModel>()
    val nsfwUnlocked = app.nsfwUnlocked

    var library by remember(libraryId) {
        mutableStateOf(LocalLibraryStore.library(context, libraryId))
    }
    var books by remember(libraryId) { mutableStateOf<List<LocalLibraryStore.LocalBook>>(emptyList()) }
    var shelves by remember(libraryId) { mutableStateOf(CategoryState()) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    // B3: drawer-shelf filter; null = all books.
    var shelfFilter by remember { mutableStateOf<String?>(null) }
    val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    var sortMode by remember { mutableStateOf(loadSortMode(prefs)) }

    fun reload() {
        library = LocalLibraryStore.library(context, libraryId)
        books = LocalLibraryStore.books(context, libraryId)
        shelves = CategoryStore.load(context, "local:$libraryId")
    }
    LaunchedEffect(libraryId) {
        withContext(Dispatchers.IO) { LocalLibraryStore.libraries(context) }
        reload()
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch {
                var ok = 0
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        if (LocalLibraryStore.import(context, libraryId, uri) != null) ok++
                    }
                }
                reload()
                importing = false
                importMsg = "[${
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                }] Imported $ok of ${uris.size}"
            }
        }
    }

    // Read-in-place import (Wayne, 2026-10-21): epubs already on the device
    // join the Library through a persistable URI grant — no copy, no move.
    val linkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch {
                var ok = 0
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        if (LocalLibraryStore.import(context, libraryId, uri, linkInPlace = true) != null) ok++
                    }
                }
                reload()
                importing = false
                importMsg = "[${
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                }] Linked $ok of ${uris.size}"
            }
        }
    }

    // Folder linking: one tree grant, every epub beneath it — the scale path
    // (Wayne, 2026-10-21: linking a large library file-by-file didn't work).
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importing = true
            importMsg = t("Scanning folder…", "フォルダをスキャン中…")
            scope.launch {
                val (linked, skipped) = withContext(Dispatchers.IO) {
                    LocalLibraryStore.linkFolder(context, libraryId, uri) { done, total ->
                        importMsg = "${t("Linking", "リンク中")} $done/$total…"
                    }
                }
                reload()
                importing = false
                importMsg = "[${
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                }] ${t("Linked", "リンク")} $linked, ${t("skipped", "スキップ")} $skipped"
            }
        }
    }

    val storagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            LocalLibraryStore.setLibraryStorage(context, libraryId, uri.toString())
            reload()
        }
    }

    // Rows are SERIES (Wayne, 2026-10-21: "Series = Series") — the same unit
    // the sites' grids show. Cover/name from volume 1; unread = chapters
    // remaining across volumes; shelves hold series. Books on a hidden
    // (locked) shelf stay out of the grid entirely.
    val sidMap = LocalLibraryStore.seriesIdMap(context)
    val hiddenShelfIds = shelves.cats.filter { it.hidden && !nsfwUnlocked }.map { it.id }.toSet()
    val visibleBooks = books.filter { b ->
        val sid = sidMap[b.id]
        sid?.let { shelves.assign[it] }?.let { it !in hiddenShelfIds } ?: true
    }
    val bySeries = visibleBooks.groupBy { sidMap[it.id] ?: it.id }
    val prefsForUnread = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    val allSeries = LocalLibraryStore.seriesList(context, libraryId)
    val rows = allSeries
        .filter { s -> (shelves.assign[s.id]?.let { it !in hiddenShelfIds } ?: true) }
        .filter { s -> shelfFilter == null || shelves.assign[s.id] == shelfFilter }
        .filter { s -> bySeries[s.id].orEmpty().isNotEmpty() }
        .filter { s -> s.name.contains(searchQuery, ignoreCase = true) }
        .sortedWith(
            when (sortMode) {
                SORT_ALPHA -> compareBy { it.name.lowercase() }
                else -> compareByDescending { s -> bySeries[s.id].orEmpty().maxOfOrNull { it.addedAt } ?: 0 }
            }
        )
        .map { s ->
            val vols = bySeries[s.id].orEmpty()
                .sortedWith(compareBy({ LocalLibraryStore.volumeNumber(it.title) }, { it.addedAt }))
            val vol1 = vols.firstOrNull()
            val unread = vols.sumOf { v ->
                val pos = prefsForUnread.getInt("local_pos_${v.id}", 0)
                maxOf(0, v.chapterCount - pos)
            }
            val coverPath = vol1?.let { LocalLibraryStore.coverFile(context, it.id)?.absolutePath }.orEmpty()
            BookmarkRow(
                title = s.name,
                subtitle = vol1?.author.orEmpty(),
                coverUrl = if (coverPath.isNotBlank()) "file://$coverPath" else "",
                // Volume 1's CoverStore key — the series borrows its cover.
                seriesUrl = vol1?.let { "locallib://${it.id}" } ?: "",
                open = { onOpenSeries(s.id) },
                unread = unread,
                latestLabel = if (vols.size > 1) "${vols.size} ${t("vols", "巻")}"
                else vol1?.takeIf { it.chapterCount > 0 }?.let { "${it.chapterCount} ${t("ch", "話")}" } ?: ""
            )
        }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        library?.name ?: "Library",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    SiteSwitcherChips(activeSite, onSwitchSite)

                    NavigationDrawerItem(
                        label = { Text(t("Import epubs (copy)…", "EPUBをインポート（コピー）…")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            importPicker.launch(arrayOf("application/epub+zip"))
                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(t("Link epubs on device (no copy)…", "端末内EPUBをリンク（コピーなし）…")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            linkPicker.launch(arrayOf("application/epub+zip"))
                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(t("Link a whole folder…", "フォルダごとリンク…")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            folderPicker.launch(null)
                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(t("Shelves  ⚙", "棚  ⚙")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenCategories()
                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(t("Storage location…", "保存場所…")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            storagePicker.launch(null)
                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(t("Rename library…", "ライブラリ名変更…")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showRename = true
                        }
                    )

                    if (shelves.cats.isNotEmpty()) {
                        Text(
                            t("Shelves", "棚"),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        val assignments = shelves.assign
                        // B3: tapping a shelf filters the grid (the NU
                        // reading-list rows' behavior); tapping it again
                        // clears back to all books. Hidden shelves list
                        // only while the NSFW unlock is live.
                        if (shelfFilter != null) {
                            NavigationDrawerItem(
                                label = { Text(t("All books", "すべての本")) },
                                selected = false,
                                onClick = { shelfFilter = null }
                            )
                        }
                        shelves.cats.filter { !it.hidden || nsfwUnlocked }.forEach { cat ->
                            // Shelves hold SERIES now — counts follow.
                            val count = allSeries.count { assignments[it.id] == cat.id }
                            NavigationDrawerItem(
                                label = { Text("${cat.name}  ($count)" ) },
                                selected = shelfFilter == cat.id,
                                onClick = {
                                    shelfFilter = if (shelfFilter == cat.id) null else cat.id
                                }
                            )
                        }
                    }

                    NavigationDrawerItem(
                        label = { Text(t("Settings", "設定")) },
                        selected = false,
                        onClick = onOpenSettings
                    )
                    NavigationDrawerItem(
                        label = { Text(t("Check for app updates", "アプリ更新を確認")) },
                        selected = false,
                        onClick = onCheckUpdate
                    )
                }
            }
        }
    ) {
        // THE shared scaffold — identical rendering to every site's home.
        Column(modifier = Modifier.fillMaxWidth()) {
            if (importMsg.isNotBlank()) {
                Text(
                    importMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            BookmarkLibraryBody(
                heading = shelfFilter?.let { id ->
                    shelves.cats.firstOrNull { it.id == id }?.name
                } ?: library?.name ?: "Library",
                rows = rows,
                emptyMessage = t(
                    "No books yet — ☰ → Import epubs… to add your first",
                    "本がまだありません — ☰ → EPUBをインポート…"
                ),
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                isRefreshing = importing,
                onRefresh = null,
                sortControl = {
                    SortMenu(sortMode = sortMode, onSort = {
                        sortMode = it
                        saveSortMode(prefs, it)
                    })
                },
                sortLabel = sortModeLabel(sortMode)
            )
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(library?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(t("Rename library", "ライブラリ名変更")) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(t("Name", "名前")) }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        LocalLibraryStore.renameLibrary(context, libraryId, name)
                        showRename = false
                        reload()
                    }
                }) { Text(t("Rename", "変更")) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}
