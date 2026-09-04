package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier

/**
 * UI rule: no button, label, or WebView chrome sits under the status bar
 * or the Android navigation bar.
 *
 * Use [screenInsets] on full-screen Columns (Settings, Login).
 * Use statusBarsPadding on top bars and navigationBarsPadding on bottom
 * Cancel/Save/Done rows when the screen is a Scaffold.
 */
fun Modifier.screenInsets(): Modifier = this.statusBarsPadding().navigationBarsPadding()
