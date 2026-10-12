package com.patchscratch.novelnotifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App UI language: EN or 日本語 (user request 2026-10-12). One toggle in
 * Settings → Display; every screen's chrome reads [current] through [t],
 * which is a Compose state read — flipping the toggle recomposes whatever
 * is on screen. Novel CONTENT stays in its native language either way.
 */
object UiLang {
    const val EN = "en"
    const val JP = "jp"

    var current by mutableStateOf(EN)

    fun isJp(): Boolean = current == JP
}

/** Pick the string for the current language: t("Settings", "設定"). */
fun t(en: String, jp: String): String = if (UiLang.isJp()) jp else en
