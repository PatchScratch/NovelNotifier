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
