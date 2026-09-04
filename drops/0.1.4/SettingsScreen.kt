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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * RSS URL, login, cover prefetch, check interval, Check now, display mode.
 * Uses screenInsets() so Cancel sits above the Android nav bar.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@Composable
fun SettingsScreen(
    currentUrl: String,
    currentHours: Int,
    loginState: String,
    coverPrefetchStatus: String,
    onStartCoverPrefetch: () -> Unit,
    onStopCoverPrefetch: () -> Unit,
    onCheckNow: () -> Unit,
    onSave: (String, Int) -> Unit,
    onStartLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    onCancel: () -> Unit
) {
    val prefs = LocalContext.current.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    var url by remember { mutableStateOf(currentUrl) }
    var hours by remember { mutableIntStateOf(currentHours) }
    var layoutMode by remember { mutableStateOf(loadLayoutMode(prefs)) }
    val options = listOf(1, 6, 12, 24)

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
            label = { Text("Novel Updates RSS URL") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
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
        Text(text = "Covers", style = MaterialTheme.typography.titleMedium)
        Text(
            text = coverPrefetchStatus,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Reads every list that is not Update-off, then downloads missing covers. Use sparingly.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = onStartCoverPrefetch, modifier = Modifier.fillMaxWidth()) {
            Text("Prefetch missing covers")
        }
        TextButton(onClick = onStopCoverPrefetch, modifier = Modifier.fillMaxWidth()) {
            Text("Stop cover prefetch")
        }

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
