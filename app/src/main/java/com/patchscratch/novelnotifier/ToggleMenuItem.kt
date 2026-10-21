package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A menu row for a binary toggle: label on the left, a real Switch on the
 * right (Wayne, 2026-10-21: "a visual switch should be added to all binary
 * toggles in menus" — ON/OFF text alone doesn't read as state). The whole
 * row is the tap target; the Switch is display-only so the row's single
 * onClick owns the flip (no double-toggle races).
 */
@Composable
fun ToggleMenuItem(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = checked,
                    onCheckedChange = null, // row owns the click
                    modifier = Modifier
                        .width(52.dp)
                        .padding(start = 8.dp)
                )
            }
        },
        onClick = onToggle
    )
}
