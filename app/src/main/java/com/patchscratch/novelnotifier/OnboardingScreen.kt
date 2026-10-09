package com.patchscratch.novelnotifier

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Fresh-login onboarding for large libraries (user spec, 2026-10-09):
 *
 *  1. Load the user's reading lists from the site (list jobs only).
 *  2. Then — once we know the size — warn how long a FULL download takes,
 *     estimated conservatively (9 s per site hit, 5 pages per series).
 *  3. Let the user mark a few lists as priority. The queue loads ALL covers
 *     first (visual reassurance), then chapter pages with priority lists
 *     ahead of the rest, so read-state arrives sooner where it matters.
 *
 * The heavy work runs in the SyncEngineService with its counter notification;
 * this screen only starts it and shows progress. The user can leave any time.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    var phase by remember { mutableStateOf("lists") } // lists → pick → done
    var lists by remember { mutableStateOf(emptyList<ReadingList>()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var engineStatus by remember { mutableStateOf(SyncEngineService.statusText(context)) }
    // done to total list jobs — drives the live counter on the hydration screen.
    var listProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Phase 1: queue list-only jobs and watch until the library shape is known.
    LaunchedEffect(Unit) {
        val snap = withContext(Dispatchers.IO) { loadListSnapshot(context) }
        if (snap.lists.isNotEmpty() && snap.novelsByList.values.any { it.isNotEmpty() }) {
            // Restored from a backup or already synced once — straight to picking.
            lists = snap.lists
            counts = snap.novelsByList.mapValues { it.value.size }
            phase = "pick"
        } else {
            val hydrateJobs = SyncQueue.build(context, priorityListIds = emptySet())
                .filter { it.type == "list" }
            if (hydrateJobs.isEmpty()) {
                // No lists need scraping — read what we can from the snapshot.
                lists = snap.lists
                counts = snap.novelsByList.mapValues { it.value.size }
                phase = if (lists.isEmpty()) "empty" else "pick"
            } else {
                SyncEngineService.startWith(context, hydrateJobs)
            }
        }
    }

    // Watch the engine while it hydrates; when list jobs finish, move on.
    LaunchedEffect(phase) {
        if (phase != "lists") return@LaunchedEffect
        while (true) {
            delay(2.seconds)
            engineStatus = SyncEngineService.statusText(context)
            val listJobs = withContext(Dispatchers.IO) { SyncQueue.load(context).filter { it.type == "list" } }
            if (listJobs.isNotEmpty()) listProgress = listJobs.count { it.done } to listJobs.size
            if (listJobs.none { !it.done }) {
                val snap = withContext(Dispatchers.IO) { loadListSnapshot(context) }
                lists = snap.lists
                counts = snap.novelsByList.mapValues { it.value.size }
                phase = if (lists.isEmpty()) "empty" else "pick"
                return@LaunchedEffect
            }
        }
    }

    fun startFullDownload() {
        val allJobs = SyncQueue.build(context, priorityListIds = selected)
        SyncEngineService.startWith(context, allJobs)
        prefs.edit { putBoolean("onboarding_done", true) }
        onFinished()
    }

    val totalSeries = counts.values.sum()
    val estimate = estimateSetup(totalSeries, lists.size)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenInsets()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Set up your library", style = MaterialTheme.typography.headlineSmall)
        when (phase) {
            "lists" -> {
                val progress = listProgress
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            "Reading your reading lists…",
                            style = MaterialTheme.typography.titleMedium
                        )
                        val line = when {
                            progress == null -> "starting…"
                            else -> {
                                val (done, total) = progress
                                val minutesLeft = ((total - done) * 9 + 59) / 60
                                "$done of $total lists · " + when {
                                    done >= total -> "finishing…"
                                    minutesLeft <= 0 -> "almost done"
                                    minutesLeft == 1 -> "about a minute left"
                                    else -> "about $minutesLeft minutes left"
                                }
                            }
                        }
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            engineStatus,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    "One list every few seconds, so Novel Updates is not flooded. This pause is normal — it only happens on a first-time setup.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            "empty" -> {
                Text(
                    "No reading lists were found on your account. Create lists on Novel Updates first, or restore a backup from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Row(modifier = Modifier.padding(top = 16.dp)) {
                    Button(onClick = {
                        prefs.edit { putBoolean("onboarding_done", true) }
                        onFinished()
                    }) { Text("Continue") }
                    TextButton(onClick = onOpenSettings) { Text("Restore a backup") }
                }
            }
            "pick" -> {
                // The large-library warning (always shown — sizes are known now).
                Text(
                    "Your library: $totalSeries series across ${lists.size} lists.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    "A full first download takes about:\n" +
                        "• covers — ${formatEstimate(estimate.coverMillis)}\n" +
                        "• chapter lists — ${formatEstimate(estimate.chapterMillis)}\n" +
                        "• everything — ${formatEstimate(estimate.totalMillis)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "These are conservative estimates. Everything downloads in the background with a progress notification — you can keep using your phone, and it resumes if interrupted.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (prefs.getString("rss_url", "").orEmpty().isNotBlank()) {
                    Text(
                        "✓ Chapter notifications enabled — your feed link was found on the reading-list page automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    "Pick priority lists (recommended: only the 1–3 lists you actively read). Covers load first for everything; your priority lists then get their chapter lists before the rest, so your reading state is ready sooner.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                lists.forEach { list ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (list.id in selected) selected - list.id else selected + list.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = list.id in selected,
                            onCheckedChange = {
                                selected = if (list.id in selected) selected - list.id else selected + list.id
                            }
                        )
                        Column {
                            Text(list.name)
                            Text(
                                "${counts[list.id] ?: 0} series",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Row(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
                    Button(onClick = { startFullDownload() }) { Text("Start download") }
                    TextButton(
                        onClick = {
                            prefs.edit { putBoolean("onboarding_done", true) }
                            onOpenSettings()
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Restore a backup instead") }
                }
            }
        }
    }
}
