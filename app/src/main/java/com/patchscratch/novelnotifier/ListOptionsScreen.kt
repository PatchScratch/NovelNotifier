package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
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
 * Per-list switches.
 * Notify-off: skip chapter alerts for that list.
 * Update-off: skip pull-to-refresh, cover prefetch, and stale list sync (use for archives).
 * Cancel/Save sit in the bottom bar above the Android nav buttons.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListOptionsScreen(
    lists: List<ReadingList>,
    notifyOff: Set<String>,
    updateOff: Set<String>,
    onSave: (Set<String>, Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    var draftNotifyOff by remember { mutableStateOf(notifyOff) }
    var draftUpdateOff by remember { mutableStateOf(updateOff) }

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
                    onClick = { onSave(draftNotifyOff, draftUpdateOff) },
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
                text = "List options",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "Enable list notifications",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(lists.size) { index ->
                    val list = lists[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(list.name, modifier = Modifier.weight(1f))
                        Switch(
                            checked = list.id !in draftNotifyOff,
                            onCheckedChange = { enabled ->
                                draftNotifyOff = if (enabled) draftNotifyOff - list.id else draftNotifyOff + list.id
                            }
                        )
                    }
                }
                item {
                    Text(
                        text = "Check for updates",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Text(
                        text = "Turn this off for archived lists to reduce requests",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(lists.size) { index ->
                    val list = lists[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(list.name, modifier = Modifier.weight(1f))
                        Switch(
                            checked = list.id !in draftUpdateOff,
                            onCheckedChange = { enabled ->
                                draftUpdateOff = if (enabled) draftUpdateOff - list.id else draftUpdateOff + list.id
                            }
                        )
                    }
                }
            }
        }
    }
}
