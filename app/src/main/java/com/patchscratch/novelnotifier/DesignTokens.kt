package com.patchscratch.novelnotifier

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
