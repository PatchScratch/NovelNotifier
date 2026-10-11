package com.patchscratch.novelnotifier

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * RSS URL, login, library sync, backup/restore, check interval, display mode.
 * Uses screenInsets() so Cancel sits above the Android nav bar.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@Composable
fun SettingsScreen(
    currentUrl: String,
    currentHours: Int,
    loginState: String,
    syncStatus: String,
    onStartCoverSync: () -> Unit,
    onStopCoverSync: () -> Unit,
    onCheckNow: () -> Unit,
    onSave: (String, Int) -> Unit,
    onStartLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    var url by remember { mutableStateOf(currentUrl) }
    var hours by remember { mutableIntStateOf(currentHours) }
    var layoutMode by remember { mutableStateOf(loadLayoutMode(prefs)) }
    var stackAlerts by remember { mutableStateOf(loadStackAlerts(prefs)) }
    var backupMessage by remember { mutableStateOf("") }
    val options = listOf(1, 6, 12, 24)

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch { backupMessage = BackupManager.export(context, uri) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch { backupMessage = BackupManager.restore(context, uri) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenInsets()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(text = "Account", style = MaterialTheme.typography.titleMedium)
        Text(
            text = loginState,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = onStartLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Log in on Novel Updates")
        }
        TextButton(onClick = onLoggedOut, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("RSS feed URL (optional)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
        )
        Text(
            text = "Optional. With a feed URL chapter checks are one cheap request. Without one, the app checks your reading lists directly in the background — nothing to configure. Existing feed URLs travel with backups.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = "Display", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Home layout. Grid columns follow screen width (3 on a phone, more on a tablet).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                layoutMode = LAYOUT_LIST
                saveLayoutMode(prefs, LAYOUT_LIST)
            }
        ) {
            RadioButton(selected = layoutMode == LAYOUT_LIST, onClick = {
                layoutMode = LAYOUT_LIST
                saveLayoutMode(prefs, LAYOUT_LIST)
            })
            Text("Single column")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                layoutMode = LAYOUT_GRID
                saveLayoutMode(prefs, LAYOUT_GRID)
            }
        ) {
            RadioButton(selected = layoutMode == LAYOUT_GRID, onClick = {
                layoutMode = LAYOUT_GRID
                saveLayoutMode(prefs, LAYOUT_GRID)
            })
            Text("Dynamic grid")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = "Library sync", style = MaterialTheme.typography.titleMedium)
        Text(
            text = syncStatus,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Downloads missing covers for every list, paced against the site. Progress shows in a notification.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = onStartCoverSync, modifier = Modifier.fillMaxWidth()) {
            Text("Prefetch missing covers")
        }
        TextButton(onClick = onStopCoverSync, modifier = Modifier.fillMaxWidth()) {
            Text("Stop sync")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = "Backup & restore", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "One zip with your lists, chapter caches and cover images. Save it anywhere your file picker reaches — Google Drive, OneDrive, USB. Backups never contain your password, login, or feed links — a restored device asks for a fresh login and picks the rest up automatically.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = { backupLauncher.launch("novelnotifier-library.zip") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Back up library") }
        TextButton(
            onClick = {
                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore from backup") }
        if (backupMessage.isNotBlank()) {
            Text(
                text = backupMessage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = "Notifications", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    stackAlerts = !stackAlerts
                    saveStackAlerts(prefs, stackAlerts)
                }
                .padding(vertical = 4.dp)
        ) {
            Switch(
                checked = stackAlerts,
                onCheckedChange = {
                    stackAlerts = it
                    saveStackAlerts(prefs, it)
                }
            )
            Text(
                "Stack chapter alerts into one banner",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            text = "Off posts one notification per series. Un-read alerts survive reboots either way.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = "Checks", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Check for updates every",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { hours = option }
            ) {
                RadioButton(selected = hours == option, onClick = { hours = option })
                Text("$option hour" + if (option == 1) "" else "s")
            }
        }
        Button(
            onClick = onCheckNow,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) { Text("Check now") }
        Button(
            onClick = { onSave(url, hours) },
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) { Text("Save") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
