package com.patchscratch.novelnotifier

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local Library home (Wayne, 2026-10-21): the same drawer+grid anatomy the
 * sites use, holding the user's imported epubs. Libraries appear as sites
 * in the switcher; Shelves ride the Kakuyomu CategoryStore pattern
 * (namespaced to the library id); the grid is the shared layout modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Library + books + shelves state, re-read on each mutation.
    var library by remember(libraryId) {
        mutableStateOf(LocalLibraryStore.library(context, libraryId))
    }
    var books by remember(libraryId) { mutableStateOf<List<LocalLibraryStore.LocalBook>>(emptyList()) }
    var shelves by remember(libraryId) { mutableStateOf(CategoryStore.load(context, "local:$libraryId")) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf("") }
    var showNewLibrary by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var layoutMode by remember {
        mutableStateOf(
            loadLayoutMode(context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE))
        )
    }

    fun reload() {
        library = LocalLibraryStore.library(context, libraryId)
        books = LocalLibraryStore.books(context, libraryId)
        shelves = CategoryStore.load(context, "local:$libraryId")
    }
    LaunchedEffect(libraryId) {
        withContext(Dispatchers.IO) {
            // First-touch: ensure the index exists.
            LocalLibraryStore.libraries(context)
        }
        reload()
    }

    // Import: SAF multi-pick .epub files.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch {
                var ok = 0
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        // SAF needs a fresh take for each read.
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
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

    // Storage location picker.
    val storagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            LocalLibraryStore.setLibraryStorage(context, libraryId, uri.toString())
            reload()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    // Library header + settings.
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

                    // Shelves with counts.
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
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰", fontSize = 24.sp)
                    }
                    Text(
                        library?.name ?: "Library",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // Grid/list toggle (shared preference with the sites).
                    TextButton(onClick = {
                        layoutMode = if (layoutMode == LAYOUT_GRID) LAYOUT_LIST else LAYOUT_GRID
                        saveLayoutMode(
                            context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE),
                            layoutMode
                        )
                    }) { Text(if (layoutMode == LAYOUT_GRID) "▦" else "☰") }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (importing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "  " + t("Importing…", "インポート中…"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (importMsg.isNotBlank()) {
                    Text(
                        importMsg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                if (books.isEmpty()) {
                    // Empty state: the same invite the sites use.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            t("No books yet", "本がまだありません"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            t("Tap ☰ → Import epubs… to add your first book", "☰ → EPUBをインポート… で最初の本を追加"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else if (layoutMode == LAYOUT_GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(books, key = { it.id }) { book ->
                            LocalBookCard(book = book, onClick = { onOpenBook(book) })
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(books.count(), key = { books[it].id }) { i ->
                            LocalBookRow(book = books[i], onClick = { onOpenBook(books[i]) })
                        }
                    }
                }
            }
        }
    }

    // New-library dialog (reached from the site switcher's action; the
    // switcher triggers a site switch after creation).
    if (showNewLibrary) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewLibrary = false },
            title = { Text(t("New library", "新しいライブラリ")) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(t("Name", "名前")) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val lib = LocalLibraryStore.addLibrary(context, name)
                        showNewLibrary = false
                        onSwitchSite("local:${lib.id}")
                    }
                }) { Text(t("Create", "作成")) }
            },
            dismissButton = { TextButton(onClick = { showNewLibrary = false }) { Text("Cancel") } }
        )
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

/** Cover card for the grid — the site-card anatomy (cover + title + badge). */
@Composable
fun LocalBookCard(book: LocalLibraryStore.LocalBook, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier
            .aspectRatio(0.68f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val cover = remember(book.id) { LocalLibraryStore.coverFile(context, book.id) }
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        "📖",
                        fontSize = 32.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
            Text(
                book.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/** Row for list mode — title, author, chapter count. */
@Composable
fun LocalBookRow(book: LocalLibraryStore.LocalBook, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Text(
                "${book.chapterCount} ${t("ch", "話")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
