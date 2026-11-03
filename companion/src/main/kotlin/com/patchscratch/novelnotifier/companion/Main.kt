package com.patchscratch.novelnotifier.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.text.SimpleDateFormat
import java.util.Date

/**
 * NovelNotifier desktop companion — pre-release.
 *
 * MANAGEMENT-FIRST (Wayne's desktop vision, 2026-10-31): the phone is the
 * source of truth; this app is a big-screen remote control for shelves,
 * series grouping and merging. Every mutation goes through the phone's
 * /api/v1 LAN API, which itself only calls the app's locked store paths —
 * the companion has no library logic of its own and no local index.
 *
 * Run:   companion/gradlew run       (Linux first; same on Windows)
 * Test:  companion/gradlew test
 */

// ---------------------------------------------------------------- state

/** Left-rail filter. */
sealed interface Filter {
    data object All : Filter
    data object Unshelved : Filter
    data class Shelf(val id: String) : Filter
}

enum class SeriesSort(val label: String) {
    NAME("Name"), RECENT("Recently added"), VOLUMES("Volume count")
}

class AppState {
    var config by mutableStateOf(CompanionConfig.load())
    var api by mutableStateOf<CompanionApi?>(null)
    var status by mutableStateOf<DeviceStatus?>(null)
    var data by mutableStateOf<LibraryData?>(null)
    var filter by mutableStateOf<Filter>(Filter.All)
    var query by mutableStateOf("")
    var sort by mutableStateOf(SeriesSort.NAME)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var detailId by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    var showSuggestions by mutableStateOf(false)
    var showMergePicker by mutableStateOf(false)
}

/** Series currently visible under filter+query+sort (pure in-memory). */
fun visibleSeries(state: AppState): List<SeriesRow> {
    val d = state.data ?: return emptyList()
    val q = state.query.trim().lowercase()
    return d.series.asSequence()
        .filter { s ->
            when (val f = state.filter) {
                Filter.All -> true
                Filter.Unshelved -> d.assignments[s.id] == null
                is Filter.Shelf -> d.assignments[s.id] == f.id
            }
        }
        .filter { s ->
            q.isEmpty() || s.name.lowercase().contains(q) ||
                s.volumes.any { it.title.lowercase().contains(q) || it.author.lowercase().contains(q) }
        }
        .sortedWith(
            when (state.sort) {
                SeriesSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                SeriesSort.RECENT -> compareByDescending { it.lastAdded }
                SeriesSort.VOLUMES -> compareByDescending { it.volumeCount }
            }
        )
        .toList()
}

// ---------------------------------------------------------------- actions

/**
 * Every mutation: run on IO, refresh the library afterwards so the screen
 * reflects the phone's post-write state, surface errors in the status line.
 */
fun mutate(state: AppState, label: String, block: CompanionApi.() -> Unit) {
    val api = state.api ?: return
    val data = state.data
    state.busy = true
    state.message = "$label…"
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block(api)
            val fresh = api.library(data?.library?.id ?: state.config.libraryId)
            withContext(Dispatchers.Main) {
                state.data = fresh
                // Prune selection/detail that no longer exist.
                val ids = fresh.series.map { it.id }.toSet()
                state.selection = state.selection.intersect(ids)
                if (state.detailId != null && state.detailId !in ids) state.detailId = null
                state.busy = false
                state.message = "$label — done"
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                state.busy = false
                state.message = "$label failed: ${e.message}"
            }
        }
    }
}

// ---------------------------------------------------------------- covers

/** In-memory cover cache; the phone serves cache-only bytes (fast + safe). */
object CoverCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > 400
    }

    suspend fun fetch(api: CompanionApi?, bookId: String): ImageBitmap? {
        if (bookId.isBlank() || api == null) return null
        synchronized(map) { map[bookId] }?.let { return it }
        val bytes = withContext(Dispatchers.IO) { runCatching { api.cover(bookId) }.getOrNull() }
            ?: return null
        return runCatching {
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()?.also {
            synchronized(map) { map[bookId] = it }
        }
    }
}

@Composable
fun CoverImage(api: CompanionApi?, bookId: String, modifier: Modifier = Modifier) {
    var bitmap by remember(bookId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(api, bookId) { bitmap = CoverCache.fetch(api, bookId) }
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!,
                contentDescription = "cover",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                Text("…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Confirm", color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Picker for merge targets / volume moves. Library-scale list ⇒ LazyColumn
 * + search field (same rule as the phone's SeriesPickerDialog).
 */
@Composable
fun SeriesPickerDialog(
    title: String,
    candidates: List<SeriesRow>,
    excludeId: String,
    onPick: (SeriesRow) -> Unit,
    onDismiss: () -> Unit,
    excludeIds: Set<String> = emptySet()
) {
    var q by remember { mutableStateOf("") }
    val excluded = remember(excludeId, excludeIds) { excludeIds + excludeId }
    val filtered = remember(q, candidates, excluded) {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) candidates
        else candidates.filter { it.name.lowercase().contains(needle) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                OutlinedTextField(
                    q, { q = it }, placeholder = { Text("Search series") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                LazyColumn {
                    items(filtered.filter { it.id !in excluded }, key = { it.id }) { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(s) }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                s.name,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${s.volumeCount} vol",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Picker for cross-library moves: the phone's library list. */
@Composable
fun LibraryPickerDialog(
    title: String,
    libraries: List<LibRef>,
    excludeId: String,
    onPick: (LibRef) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                Text(
                    "The series and all its volumes move; epub files stay where they are and it lands Unshelved in the target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn {
                    items(libraries.filter { it.id != excludeId }, key = { it.id }) { lib ->
                        Text(
                            lib.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(lib) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- screens

@Composable
fun ConnectScreen(state: AppState) {
    var host by remember { mutableStateOf(state.config.host) }
    var token by remember { mutableStateOf(state.config.token) }
    var error by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    // The attempt is cancellable and hard-capped: an unreachable address
    // must give up in seconds with something the user can act on.
    val scope = rememberCoroutineScope()
    var connectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        connecting = false
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NovelNotifier Companion", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Big-screen management for the library on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
        )
        OutlinedTextField(
            host, { host = it },
            label = { Text("Phone address (e.g. 192.168.0.217:8790)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(0.6f),
            enabled = !connecting
        )
        OutlinedTextField(
            token, { token = it },
            label = { Text("Pairing token (phone: Settings → Desktop companion)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp),
            enabled = !connecting
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Button(
                onClick = {
                    connecting = true; error = ""
                    connectJob = scope.launch(Dispatchers.IO) {
                        try {
                            val candidate = CompanionApi("http://${host.trim()}", token.trim())
                            // 8s probe + a 10s wall clock cap covering DNS and
                            // anything the socket timeouts don't reach.
                            val st = kotlinx.coroutines.withTimeout(10_000) {
                                candidate.status(timeoutMs = 8_000)
                            }
                            val config = state.config.copy(host = host.trim(), token = token.trim())
                            withContext(Dispatchers.Main) {
                                state.api = candidate
                                state.status = st
                                state.config = config
                                CompanionConfig.save(config)
                                connecting = false
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            // MUST precede CancellationException — it subclasses it.
                            withContext(Dispatchers.Main) {
                                connecting = false
                                error = "Timed out after 10 s — is the address right and the phone's companion server on?"
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                connecting = false
                                error = e.message ?: "Connection failed"
                            }
                        }
                    }
                    // Safety net: an Error (e.g. NoClassDefFoundError from a
                    // trimmed runtime) escapes every catch above and would
                    // strand the UI on "Connecting…" forever — the live
                    // 0.1.1 bug. Anything that kills the job unrecovered
                    // resets the button and shows why. The full cause chain
                    // is included: a bare "NoClassDefFoundError" hid WHICH
                    // class was missing (Wayne's report, 2026-11-02) — the
                    // message usually names it.
                    connectJob?.invokeOnCompletion { cause ->
                        if (cause != null && cause !is kotlinx.coroutines.CancellationException) {
                            scope.launch {
                                connecting = false
                                val chain = generateSequence(cause) { it.cause }
                                    .mapNotNull { it.message ?: it.javaClass.simpleName }
                                    .joinToString(" → ")
                                error = "Connection failed ($APP_VERSION): $chain"
                            }
                        }
                    }
                },
                enabled = host.isNotBlank() && token.isNotBlank() && !connecting
            ) { Text(if (connecting) "Connecting…" else "Connect") }
            if (connecting) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { cancelConnect() }) { Text("Cancel") }
            }
        }
        if (error.isNotEmpty()) {
            Text(
                error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
fun ShelvesPane(state: AppState, width: Modifier) {
    val d = state.data ?: return
    Column(
        width
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp)
    ) {
        Text("Libraries", style = MaterialTheme.typography.labelLarge)
        LibraryMenu(state)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Shelves", style = MaterialTheme.typography.labelLarge)

        var newShelf by remember { mutableStateOf(false) }
        var renameShelf by remember { mutableStateOf<Shelf?>(null) }
        var deleteShelf by remember { mutableStateOf<Shelf?>(null) }

        FilterRow("All series (${d.series.size})", state.filter == Filter.All) {
            state.filter = Filter.All
        }
        FilterRow("Unshelved (${d.unshelvedCount})", state.filter == Filter.Unshelved) {
            state.filter = Filter.Unshelved
        }
        d.shelves.forEach { shelf ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterRow(
                    "${shelf.name} (${d.countIn(shelf.id)})",
                    state.filter == Filter.Shelf(shelf.id),
                    Modifier.weight(1f)
                ) { state.filter = Filter.Shelf(shelf.id) }
                IconButton(
                    onClick = { renameShelf = shelf },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(Icons.Filled.Edit, "Rename shelf", Modifier.size(14.dp))
                }
                IconButton(
                    onClick = { deleteShelf = shelf },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(Icons.Filled.Delete, "Delete shelf", Modifier.size(14.dp))
                }
            }
        }
        TextButton(onClick = { newShelf = true }) {
            Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text(" New shelf", style = MaterialTheme.typography.bodySmall)
        }

        if (newShelf) {
            TextEditDialog("New shelf", "", confirmLabel = "Create", onConfirm = { name ->
                newShelf = false
                mutate(state, "Creating shelf") { shelfCreate(d.library.id, name) }
            }, onDismiss = { newShelf = false })
        }
        renameShelf?.let { shelf ->
            TextEditDialog("Rename shelf", shelf.name, onConfirm = { name ->
                renameShelf = null
                mutate(state, "Renaming shelf") { shelfRename(d.library.id, shelf.id, name) }
            }, onDismiss = { renameShelf = null })
        }
        deleteShelf?.let { shelf ->
            ConfirmDialog(
                "Delete shelf \"${shelf.name}\"?",
                "Series on it fall back to Unshelved. No books are deleted.",
                danger = true,
                onConfirm = {
                    val s = shelf; deleteShelf = null
                    mutate(state, "Deleting shelf") { shelfDelete(d.library.id, s.id) }
                },
                onDismiss = { deleteShelf = null }
            )
        }
    }
}

@Composable
private fun FilterRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun LibraryMenu(state: AppState) {
    val st = state.status ?: return
    var open by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val current = st.libraries.firstOrNull { it.id == state.config.libraryId }
        ?: st.libraries.firstOrNull()
    Box {
        TextButton(onClick = { open = true }) {
            Text("${current?.name ?: "Library"} ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            st.libraries.forEach { lib ->
                DropdownMenuItem(
                    text = { Text(lib.name) },
                    onClick = {
                        open = false
                        val config = state.config.copy(libraryId = lib.id)
                        state.config = config
                        CompanionConfig.save(config)
                        loadLibrary(state, lib.id)
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Rename library…") }, onClick = {
                open = false; rename = true
            })
        }
        if (rename) {
            TextEditDialog("Rename library", current?.name ?: "", onConfirm = { name ->
                rename = false
                mutate(state, "Renaming library") { libraryRename(current?.id ?: "", name) }
            }, onDismiss = { rename = false })
        }
    }
}

fun loadLibrary(state: AppState, id: String) {
    val api = state.api ?: return
    state.busy = true
    state.message = "Loading library…"
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val lib = api.library(id)
            withContext(Dispatchers.Main) {
                state.data = lib
                state.selection = emptySet()
                state.detailId = null
                state.filter = Filter.All
                state.busy = false
                state.message = ""
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                state.busy = false
                state.message = "Load failed: ${e.message}"
            }
        }
    }
}

@Composable
fun SeriesListPane(state: AppState, modifier: Modifier) {
    val d = state.data ?: return
    val visible = remember(state.data, state.filter, state.query, state.sort) { visibleSeries(state) }

    Column(modifier.padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                state.query, { state.query = it },
                placeholder = { Text("Search series, volumes, authors") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            var sortOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { sortOpen = true }) { Text("Sort: ${state.sort.label} ▾") }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    SeriesSort.values().forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { state.sort = s; sortOpen = false }
                        )
                    }
                }
            }
        }
        Text(
            "${visible.size} series shown · ${state.selection.size} selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { s ->
                val checked = s.id in state.selection
                Surface(
                    color = if (state.detailId == s.id)
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    else Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.detailId = if (state.detailId == s.id) null else s.id
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                state.selection =
                                    if (on) state.selection + s.id else state.selection - s.id
                            }
                        )
                        Text(
                            s.name,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${s.volumeCount} vol",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        val shelf = d.shelfOf(s.id)
                        Text(
                            shelf?.name ?: "—",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp).width(140.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }

        // Bulk bar
        if (state.selection.isNotEmpty()) {
            var mergeTarget by remember(state.selection) { mutableStateOf<SeriesRow?>(null) }
            var confirmMerge by remember(state.selection) { mutableStateOf<SeriesRow?>(null) }
            Surface(tonalElevation = 4.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("${state.selection.size} selected", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(12.dp))
                    var assignOpen by remember { mutableStateOf(false) }
                    Box {
                        Button(onClick = { assignOpen = true }, enabled = d.shelves.isNotEmpty()) {
                            Text("Move to shelf…")
                        }
                        DropdownMenu(assignOpen, { assignOpen = false }) {
                            d.shelves.forEach { shelf ->
                                DropdownMenuItem(
                                    text = { Text(shelf.name) },
                                    onClick = {
                                        assignOpen = false
                                        val ids = state.selection.toList()
                                        mutate(state, "Shelving ${ids.size} series") {
                                            shelfAssign(d.library.id, ids, shelf.id)
                                        }
                                    }
                                )
                            }
                            if (d.shelves.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Unshelve") },
                                onClick = {
                                    assignOpen = false
                                    val ids = state.selection.toList()
                                    mutate(state, "Unshelving ${ids.size} series") {
                                        shelfAssign(d.library.id, ids, null)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    var moveLibOpen by remember { mutableStateOf(false) }
                    val otherLibraries = (state.status?.libraries ?: emptyList())
                        .filter { it.id != d.library.id }
                    Box {
                        OutlinedButton(
                            onClick = { moveLibOpen = true },
                            enabled = otherLibraries.isNotEmpty()
                        ) { Text("Move to library…") }
                        DropdownMenu(moveLibOpen, { moveLibOpen = false }) {
                            otherLibraries.forEach { lib ->
                                DropdownMenuItem(
                                    text = { Text(lib.name) },
                                    onClick = {
                                        moveLibOpen = false
                                        val ids = state.selection.toList()
                                        mutate(state, "Moving ${ids.size} series to ${lib.name}") {
                                            ids.forEach { seriesMoveToLibrary(it, lib.id) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { state.showMergePicker = true }) {
                        Text("Merge into…")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { state.selection = emptySet() }) { Text("Clear") }
                }
            }
            // Multi-select merge: pick ONE target, every selected series
            // merges into it (same-library only, like all merges).
            if (state.showMergePicker) {
                SeriesPickerDialog(
                    "Merge ${state.selection.size} series into…",
                    d.series, excludeId = "",
                    excludeIds = state.selection,
                    onPick = { target ->
                        state.showMergePicker = false
                        confirmMerge = target
                    },
                    onDismiss = { state.showMergePicker = false }
                )
            }
            confirmMerge?.let { target ->
                val ids = state.selection.filter { it != target.id }
                ConfirmDialog(
                    "Merge ${ids.size} series into \"${target.name}\"?",
                    "Every selected series' volumes move into \"${target.name}\" and the emptied entries go away. No files are touched.",
                    danger = true,
                    onConfirm = {
                        val t = target
                        confirmMerge = null
                        mutate(state, "Merging ${ids.size} series into \"${t.name}\"") {
                            ids.forEach { seriesMerge(it, t.id) }
                        }
                    },
                    onDismiss = { confirmMerge = null }
                )
            }
        }
    }
}

@Composable
fun DetailPane(state: AppState, modifier: Modifier) {
    val d = state.data ?: return
    val id = state.detailId ?: return
    val s = d.series.firstOrNull { it.id == id }
    if (s == null) {
        Box(modifier) { Text("Series removed", Modifier.align(Alignment.Center)) }
        return
    }

    var rename by remember { mutableStateOf(false) }
    var editDesc by remember { mutableStateOf(false) }
    var mergeInto by remember { mutableStateOf(false) }
    var moveToLibrary by remember { mutableStateOf(false) }
    var deleteSeries by remember { mutableStateOf<String?>(null) } // "remove" | "trash"
    var moveVolume by remember { mutableStateOf<Volume?>(null) }
    var deleteVolume by remember { mutableStateOf<Pair<Volume, String>?>(null) } // volume to mode
    var renameSuggested by remember { mutableStateOf<SeriesRow?>(null) }

    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row {
            CoverImage(state.api, s.coverRef, Modifier.size(width = 96.dp, height = 140.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${s.volumeCount} volumes · shelf ${d.shelfOf(s.id)?.name ?: "none"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = { rename = true }) { Text("Rename") }
                    TextButton(onClick = { editDesc = true }) { Text("Description") }
                }
                Row {
                    TextButton(onClick = { mergeInto = true }) { Text("Merge into…") }
                    TextButton(onClick = { moveToLibrary = true }) { Text("Move to library…") }
                }
                Row {
                    TextButton(
                        onClick = { deleteSeries = "trash" },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Move to Trash") }
                    TextButton(
                        onClick = { deleteSeries = "remove" },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Remove") }
                }
            }
        }

        if (s.description.isNotBlank()) {
            Text(
                s.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Volumes", style = MaterialTheme.typography.titleSmall)
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd") }
        s.volumes.forEach { v ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(v.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(v.chapters).append(" ch")
                            if (v.pos > 0) append(" · reading ep ").append(v.pos + 1)
                            if (v.linked) append(" · linked")
                            append(" · ").append(fmt.format(Date(v.addedAt)))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { moveVolume = v }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Edit, "Move to another series", Modifier.size(15.dp))
                }
                IconButton(onClick = { deleteVolume = v to "trash" }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Delete, "Move volume to Trash", Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Suggestions that involve this series.
        val involving = remember(d) {
            MergeSuggestions.forSeries(d.series).filter { it.from.id == s.id || it.into.id == s.id }
        }
        if (involving.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Merge suggestions (✎ renames either side)", style = MaterialTheme.typography.titleSmall)
            involving.take(8).forEach { sug ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        "\"${sug.from.name}\" ↔ \"${sug.into.name}\" (${sug.reason})",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { renameSuggested = sug.from },
                        modifier = Modifier.size(24.dp)
                    ) { Icon(Icons.Filled.Edit, "Rename \"${sug.from.name}\"", Modifier.size(14.dp)) }
                    IconButton(
                        onClick = { renameSuggested = sug.into },
                        modifier = Modifier.size(24.dp)
                    ) { Icon(Icons.Filled.Edit, "Rename \"${sug.into.name}\"", Modifier.size(14.dp)) }
                    TextButton(onClick = {
                        mutate(state, "Merging series") { seriesMerge(sug.from.id, sug.into.id) }
                    }) { Text("Merge") }
                }
            }
        }
    }

    if (rename) {
        TextEditDialog("Rename series", s.name, onConfirm = { name ->
            rename = false
            mutate(state, "Renaming series") { seriesRename(s.id, name) }
        }, onDismiss = { rename = false })
    }
    if (editDesc) {
        TextEditDialog("Series description", s.description, onConfirm = { desc ->
            editDesc = false
            mutate(state, "Saving description") { seriesSetDescription(s.id, desc) }
        }, onDismiss = { editDesc = false })
    }
    if (moveToLibrary) {
        LibraryPickerDialog(
            "Move \"${s.name}\" to library…",
            state.status?.libraries ?: emptyList(),
            excludeId = d.library.id,
            onPick = { lib ->
                moveToLibrary = false
                mutate(state, "Moving \"${s.name}\" to ${lib.name}") {
                    seriesMoveToLibrary(s.id, lib.id)
                }
            },
            onDismiss = { moveToLibrary = false }
        )
    }
    if (mergeInto) {
        SeriesPickerDialog(
            "Merge \"${s.name}\" into…", d.series, excludeId = s.id,
            onPick = { target ->
                mergeInto = false
                mutate(state, "Merging into \"${target.name}\"") { seriesMerge(s.id, target.id) }
            },
            onDismiss = { mergeInto = false }
        )
    }
    deleteSeries?.let { mode ->
        val trash = mode == "trash"
        ConfirmDialog(
            if (trash) "Move series to Trash?" else "Remove series from library?",
            "\"${s.name}\" (${s.volumeCount} volumes). " + if (trash)
                "Volume files move to the phone's .Trash folders; Empty Trash stays on the phone."
            else
                "Entries are removed; linked files stay where they are.",
            danger = true,
            onConfirm = {
                deleteSeries = null
                mutate(state, if (trash) "Trashing series" else "Removing series") {
                    seriesDelete(s.id, trash)
                }
            },
            onDismiss = { deleteSeries = null }
        )
    }
    moveVolume?.let { v ->
        SeriesPickerDialog(
            "Move \"${v.title}\" to series…", d.series, excludeId = "",
            onPick = { target ->
                moveVolume = null
                mutate(state, "Moving volume") { moveBook(v.id, target.id) }
            },
            onDismiss = { moveVolume = null }
        )
    }
    deleteVolume?.let { (v, mode) ->
        val trash = mode == "trash"
        ConfirmDialog(
            if (trash) "Move volume to Trash?" else "Remove volume?",
            "\"${v.title}\"" + if (trash)
                " — the epub moves to the phone's .Trash (Empty Trash stays on the phone)."
            else " — the entry is removed; a linked file stays where it is.",
            danger = true,
            onConfirm = {
                deleteVolume = null
                mutate(state, if (trash) "Trashing volume" else "Removing volume") {
                    bookDelete(v.id, trash)
                }
            },
            onDismiss = { deleteVolume = null }
        )
    }
    renameSuggested?.let { target ->
        TextEditDialog("Rename series", target.name, onConfirm = { name ->
            val t = target
            renameSuggested = null
            mutate(state, "Renaming \"${t.name}\"") { seriesRename(t.id, name) }
        }, onDismiss = { renameSuggested = null })
    }
}

@Composable
fun SuggestionsPanel(state: AppState, onDismiss: () -> Unit) {
    val d = state.data ?: return onDismiss()
    val suggestions = remember(d) { MergeSuggestions.forSeries(d.series) }
    // Rename-in-place (Wayne, 2026-11-03): some suggestions are better fixed
    // by renaming one side than by merging — the pair falls out of the list
    // when the refreshed names no longer match.
    var renaming by remember { mutableStateOf<SeriesRow?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge suggestions (${suggestions.size})") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                Text(
                    "Title-similarity candidates only — every merge needs your confirm. ✎ renames either side instead of merging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn {
                    items(suggestions, key = { it.from.id + it.into.id }) { sug ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${sug.from.name}  →  ${sug.into.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${sug.from.volumeCount} vol + ${sug.into.volumeCount} vol · ${sug.reason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { renaming = sug.from },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Filled.Edit, "Rename \"${sug.from.name}\"", Modifier.size(15.dp)) }
                            IconButton(
                                onClick = { renaming = sug.into },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Filled.Edit, "Rename \"${sug.into.name}\"", Modifier.size(15.dp)) }
                            TextButton(onClick = {
                                mutate(state, "Merging \"${sug.from.name}\"") {
                                    seriesMerge(sug.from.id, sug.into.id)
                                }
                            }) { Text("Merge") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    renaming?.let { target ->
        TextEditDialog("Rename series", target.name, onConfirm = { name ->
            val t = target
            renaming = null
            mutate(state, "Renaming \"${t.name}\"") { seriesRename(t.id, name) }
        }, onDismiss = { renaming = null })
    }
}

// ---------------------------------------------------------------- window

@Composable
fun App(state: AppState) {
    MaterialTheme(colorScheme = lightColorScheme()) {
        if (state.api == null || state.status == null) {
            ConnectScreen(state)
            return@MaterialTheme
        }
        val connected = state.status
        // First library load.
        LaunchedEffect(state.api) {
            val id = state.config.libraryId.ifBlank {
                connected?.libraries?.firstOrNull()?.id ?: "default"
            }
            if (state.data == null) loadLibrary(state, id)
        }

        Scaffold(
            topBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "NovelNotifier Companion — ${connected?.device ?: ""} (v${connected?.appVersion ?: "?"})",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (state.busy) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        // The message takes whatever is left and ellipsizes —
                        // the action buttons stay pinned right and visible at
                        // any window width (Disconnect used to get pushed out
                        // by long status text).
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        OutlinedButton(onClick = { state.showSuggestions = true }) { Text("Merge suggestions") }
                        IconButton(onClick = { state.data?.library?.let { loadLibrary(state, it.id) } }) {
                            Icon(Icons.Filled.Refresh, "Refresh")
                        }
                        TextButton(onClick = {
                            state.api = null; state.status = null; state.data = null
                            state.selection = emptySet(); state.detailId = null
                        }) { Text("Disconnect") }
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                ShelvesPane(state, Modifier.width(260.dp))
                SeriesListPane(state, Modifier.weight(1f))
                if (state.detailId != null) {
                    DetailPane(state, Modifier.width(400.dp))
                }
            }
            if (state.showSuggestions) {
                SuggestionsPanel(state) { state.showSuggestions = false }
            }
        }
    }
}

/** Keep in sync with packageVersion in build.gradle.kts — shown in the
 *  window title and error messages so a screenshot names its build. */
const val APP_VERSION = "0.1.8"

fun main() {
    application {
        Window(
            title = "NovelNotifier Companion $APP_VERSION",
            state = rememberWindowState(width = 1400.dp, height = 880.dp),
            onCloseRequest = { exitApplication() },
            // The TASKBAR shows the WINDOW's icon (_NET_WM_ICON on Linux) —
            // without this the dock falls back to the generic Java mark even
            // though the menu entry carries the right one (live report,
            // Wayne 2026-11-02).
            icon = androidx.compose.ui.res.painterResource("icon.png")
        ) {
            App(remember { AppState() })
        }
    }
}
