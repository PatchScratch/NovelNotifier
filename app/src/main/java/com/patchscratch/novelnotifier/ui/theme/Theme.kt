package com.patchscratch.novelnotifier.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.patchscratch.novelnotifier.THEME_BLACK
import com.patchscratch.novelnotifier.THEME_DARK
import com.patchscratch.novelnotifier.THEME_DYNAMIC
import com.patchscratch.novelnotifier.THEME_LIGHT

/**
 * App themes. [mode] is the persisted Settings choice (Prefs.kt constants):
 *   system  — green palette, light or dark following the OS (default)
 *   light   — green palette, always light
 *   dark    — green palette, always dark
 *   black   — AMOLED variant of dark, true-black surfaces
 *   dynamic — wallpaper colors (Material You, Android 12+), light or dark by OS
 */

private val LightScheme = lightColorScheme(
    primary = GreenLightPrimary,
    onPrimary = GreenLightOnPrimary,
    primaryContainer = GreenLightPrimaryContainer,
    onPrimaryContainer = GreenLightOnPrimaryContainer,
    secondary = GreenLightSecondary,
    onSecondary = GreenLightOnSecondary,
    secondaryContainer = GreenLightSecondaryContainer,
    onSecondaryContainer = GreenLightOnSecondaryContainer,
    tertiary = GreenLightTertiary,
    onTertiary = GreenLightOnTertiary,
    tertiaryContainer = GreenLightTertiaryContainer,
    onTertiaryContainer = GreenLightOnTertiaryContainer,
    background = GreenLightBackground,
    onBackground = GreenLightOnBackground,
    surface = GreenLightSurface,
    onSurface = GreenLightOnSurface,
    surfaceVariant = GreenLightSurfaceVariant,
    onSurfaceVariant = GreenLightOnSurfaceVariant,
    outline = GreenLightOutline
)

private val DarkScheme = darkColorScheme(
    primary = GreenDarkPrimary,
    onPrimary = GreenDarkOnPrimary,
    primaryContainer = GreenDarkPrimaryContainer,
    onPrimaryContainer = GreenDarkOnPrimaryContainer,
    secondary = GreenDarkSecondary,
    onSecondary = GreenDarkOnSecondary,
    secondaryContainer = GreenDarkSecondaryContainer,
    onSecondaryContainer = GreenDarkOnSecondaryContainer,
    tertiary = GreenDarkTertiary,
    onTertiary = GreenDarkOnTertiary,
    tertiaryContainer = GreenDarkTertiaryContainer,
    onTertiaryContainer = GreenDarkOnTertiaryContainer,
    background = GreenDarkBackground,
    onBackground = GreenDarkOnBackground,
    surface = GreenDarkSurface,
    onSurface = GreenDarkOnSurface,
    surfaceVariant = GreenDarkSurfaceVariant,
    onSurfaceVariant = GreenDarkOnSurfaceVariant,
    outline = GreenDarkOutline
)

/** Dark greens on true black — battery-friendly on OLED panels. */
private val BlackScheme = DarkScheme.copy(
    background = BlackBackground,
    onBackground = GreenDarkOnBackground,
    surface = BlackSurface,
    onSurface = GreenDarkOnSurface,
    surfaceVariant = BlackSurfaceVariant,
    onSurfaceVariant = BlackOnSurfaceVariant,
    outline = BlackOutline
)

@Composable
fun NovelNotifierTheme(
    mode: String,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = mode == THEME_DARK || mode == THEME_BLACK || (mode != THEME_LIGHT && systemDark)
    val colorScheme = when {
        mode == THEME_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        mode == THEME_BLACK -> BlackScheme
        dark -> DarkScheme
        else -> LightScheme
    }

    // Keep status-bar icon contrast in sync when our theme disagrees with the
    // OS setting (e.g. app dark on a light-system phone).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
