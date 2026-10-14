package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The NU "Lists ⚙" window (2026-10-14): one row per list with its three
 * switches — Notify (chapter alerts), Updates (pull-to-refresh / cover
 * prefetch / stale sync), Hidden (SFW mode — vanishes until the biometric
 * unlock). This collapsed the old separate "List options", "Categories"
 * and "Edit Lists" drawer rows into one place; the site-side list CRUD
 * (create/rename on novelupdates.com) opens from here.
 *
 * All three flags are drafts — Cancel discards, Save persists and
 * re-filters every surface. getValue / setValue feed the `by` keyword.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListOptionsScreen(
    lists: List<ReadingList>,
    notifyOff: Set<String>,
    updateOff: Set<String>,
    hiddenLists: Set<String> = emptySet(),
    onSave: (Set<String>, Set<String>, Set<String>) -> Unit,
    onCancel: () -> Unit,
    onEditLists: () -> Unit = {},
    shScanLabel: String = "",
    shScanRunning: Boolean = false,
    onToggleShScan: () -> Unit = {}
) {
    var draftNotifyOff by remember { mutableStateOf(notifyOff) }
    var draftUpdateOff by remember { mutableStateOf(updateOff) }
    var draftHidden by remember { mutableStateOf(hiddenLists) }

    // Switch columns align under these header labels (fixed widths).
    val colWidth = 76.dp

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                TextButton(
                    onClick = { onSave(draftNotifyOff, draftUpdateOff, draftHidden) },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Lists",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Text(
                text = "Notify: chapter alerts. Updates: refresh and cover sync " +
                    "(off for archives). Hidden: gone from every screen until " +
                    "\"Reveal hidden (18+)\" is unlocked.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("", modifier = Modifier.weight(1f))
                        Text("Notify", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(colWidth))
                        Text("Updates", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(colWidth))
                        Text("Hidden", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(colWidth))
                    }
                }
                items(lists.size) { index ->
                    val list = lists[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(list.name, modifier = Modifier.weight(1f), maxLines = 1)
                        Switch(
                            checked = list.id !in draftNotifyOff,
                            onCheckedChange = { enabled ->
                                draftNotifyOff = if (enabled) draftNotifyOff - list.id else draftNotifyOff + list.id
                            },
                            modifier = Modifier.width(colWidth)
                        )
                        Switch(
                            checked = list.id !in draftUpdateOff,
                            onCheckedChange = { enabled ->
                                draftUpdateOff = if (enabled) draftUpdateOff - list.id else draftUpdateOff + list.id
                            },
                            modifier = Modifier.width(colWidth)
                        )
                        Switch(
                            checked = list.id in draftHidden,
                            onCheckedChange = { hide ->
                                draftHidden = if (hide) draftHidden + list.id else draftHidden - list.id
                            },
                            modifier = Modifier.width(colWidth)
                        )
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    TextButton(onClick = onEditLists) {
                        Text("Edit lists on novelupdates.com  ↗")
                    }
                    Text(
                        text = "Create, rename or delete lists on the site itself.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = "Scribble Hub",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Scribble Hub-hosted series are ignored in the NU section. " +
                            "The one-time scan visits every uncached series (politely paced) " +
                            "and purges the SH-hosted ones from all lists.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    TextButton(onClick = onToggleShScan) {
                        Text(if (shScanRunning) shScanLabel.ifBlank { "Stop scan" } else "Scan & purge Scribble Hub series")
                    }
                    if (shScanRunning && shScanLabel.isNotBlank()) {
                        Text(
                            text = shScanLabel,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
