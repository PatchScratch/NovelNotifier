package com.patchscratch.novelnotifier

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    onOpenCategories: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var library by remember(libraryId) {
        mutableStateOf(LocalLibraryStore.library(context, libraryId))
    }
    var books by remember(libraryId) { mutableStateOf<List<LocalLibraryStore.LocalBook>>(emptyList()) }
    var shelves by remember(libraryId) { mutableStateOf(CategoryState()) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
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

    val storagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            LocalLibraryStore.setLibraryStorage(context, libraryId, uri.toString())
            reload()
        }
    }

    // Build BookmarkRow items — the exact same shape the sites produce.
    val rows = books
        .filter { it.title.contains(searchQuery, ignoreCase = true) }
        .sortedWith(
            when (sortMode) {
                SORT_ALPHA -> compareBy { it.title.lowercase() }
                SORT_UPDATE_TIME -> compareByDescending { it.addedAt }
                else -> compareByDescending { it.addedAt }
            }
        )
        .map { book ->
            val coverPath = LocalLibraryStore.coverFile(context, book.id)?.absolutePath.orEmpty()
            BookmarkRow(
                title = book.title,
                subtitle = book.author,
                coverUrl = if (coverPath.isNotBlank()) "file://$coverPath" else "",
                seriesUrl = "locallib://${book.id}",
                open = { onOpenBook(book) },
                unread = 0,
                latestLabel = if (book.chapterCount > 0) "${book.chapterCount} ${t("ch", "話")}" else ""
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
                        label = { Text(t("Import epubs…", "EPUBをインポート…")) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            importPicker.launch(arrayOf("application/epub+zip"))
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
                        shelves.cats.forEach { cat ->
                            val count = books.count { assignments[it.id] == cat.id }
                            NavigationDrawerItem(
                                label = { Text("${cat.name}  ($count)") },
                                selected = false,
                                onClick = { /* B3: shelf filter */ }
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
                heading = library?.name ?: "Library",
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
