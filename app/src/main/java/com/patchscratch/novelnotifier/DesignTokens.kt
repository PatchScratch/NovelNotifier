package com.patchscratch.novelnotifier

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for NovelNotifier — the single source of truth for
 * typography, spacing, and sizing. Every screen uses these instead of
 * hardcoded values; the goal is visual consistency across all three
 * sites and the reader.
 *
 * Typography: 5 sizes, nothing else. Spacing: 4dp increments only.
 * Icons: Material icon objects (in Icons.kt), never unicode glyphs.
 */
object Typo {
    val title = 20.sp    // screen titles, drawer headers
    val body = 14.sp     // paragraphs, list rows, button text
    val label = 12.sp    // meta info, captions, settings labels
    val small = 10.sp    // timestamps, counters, badge text
    // reader font size is user-controlled, not a token
}

object Space {
    val xs = 4.dp       // tight gap between related items
    val sm = 8.dp       // standard padding inside rows/buttons
    val md = 16.dp      // section padding, card margins
    val lg = 24.dp      // between sections, screen edges
}

/** Standard shape radii (Material 3 defaults, centralized for tuning). */
object Shapes {
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
}

/** Minimum touch target (Material guidelines: 48dp). */
object Touch {
    val minTarget = 48.dp
    val iconButton = 40.dp  // visual size (ripple exceeds to 48dp)
}

/**
 * Shared empty/placeholder state (2026-10-15 polish pass): a tonal icon in
 * a circle above the message, centered in the available space. Replaces the
 * bare centered Text the library screens used.
 */
@Composable
fun EmptyState(
    message: String,
    icon: ImageVector = Icons.AutoMirrored.Filled.MenuBook,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/**
 * Standard failure dialog (Wayne's preference, 2026-10-14: popups over
 * inline error text, to keep the layout clean). Shows the provider's
 * reason verbatim; optional Retry re-runs the failed action.
 */
@androidx.compose.runtime.Composable
fun ErrorDialog(
    title: String,
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = {
            androidx.compose.material3.Text(
                message,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            onRetry?.let {
                androidx.compose.material3.TextButton(onClick = it) {
                    androidx.compose.material3.Text(t("Retry", "再試行"))
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(t("Close", "閉じる"))
            }
        }
    )
}
