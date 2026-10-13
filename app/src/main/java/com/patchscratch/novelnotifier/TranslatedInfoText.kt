package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * A piece of Japanese site info (synopsis, catchphrase, tag line) with its
 * English translation beneath, dimmed — the same visual contract as chapter
 * translations. Original always leads; the translation appears when the
 * site-info engine (Settings) provides one and never replaces the native
 * text (display-layer only, per the boundary rule).
 */
@Composable
fun TranslatedInfoText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    translatedColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var translation by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        translation = MetadataTranslator.translateInfo(context, text)
    }
    androidx.compose.foundation.layout.Column(modifier) {
        Text(text = text, style = style)
        if (!translation.isNullOrBlank() && translation != text) {
            Text(
                text = translation!!,
                style = style,
                color = translatedColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * English-only hint line for Discover ranking rows: the Japanese title is
 * already displayed by the row, so this renders just the dimmed machine
 * translation underneath (ML Kit free/on-device by default). Nothing shows
 * when the engine is off or produces nothing.
 */
@Composable
fun TranslatedHint(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var translation by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        translation = MetadataTranslator.translateInfo(context, text)
        // a fling-cancellation or transient miss gets two quiet retries —
        // blank rows otherwise stick until the row recomposes again
        var attempt = 0
        while (translation.isNullOrBlank() && attempt < 2) {
            kotlinx.coroutines.delay(1500)
            translation = MetadataTranslator.translateInfo(context, text)
            attempt++
        }
    }
    if (!translation.isNullOrBlank() && translation != text) {
        Text(
            text = translation!!,
            style = style,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            modifier = modifier
        )
    }
}
