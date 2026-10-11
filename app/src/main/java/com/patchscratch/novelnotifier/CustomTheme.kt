package com.patchscratch.novelnotifier

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.File

/**
 * User-supplied themes from a JSON file (Settings → Display → Import).
 *
 * File format (see README → "Custom themes"):
 *   {
 *     "name": "My Theme",
 *     "light": { "primary": "#386A20", "background": "#F7FAF0", ... },
 *     "dark":  { ... }
 *   }
 *
 * Rules:
 *   - "light" and "dark" are each optional, but at least one must exist.
 *     Both present → the theme follows the system light/dark switch; only one
 *     → that scheme is always used.
 *   - Color values are "#RRGGBB" or "#AARRGGBB".
 *   - Every color role is optional; anything omitted falls back to the
 *     built-in Novel Updates green, so a one-line theme is legal.
 *   - Unknown keys are REJECTED (with the valid list in the message) — a
 *     typo like "primery" should fail loudly, not silently do nothing.
 *
 * The parsed file lives at filesDir/custom_theme.json; the display name is
 * mirrored into the custom_theme_name pref so Settings can label the radio
 * without touching the filesystem.
 */

/** Color roles a theme file may set — exactly the roles our built-in schemes define. */
val THEME_COLOR_KEYS = listOf(
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground",
    "surface", "onSurface",
    "surfaceVariant", "onSurfaceVariant",
    "outline"
)

class ThemeParseException(message: String) : Exception(message)

/** Parsed theme file. Maps hold ARGB-packed Longs keyed by color role. */
data class CustomTheme(
    val name: String,
    val light: Map<String, Long>?,
    val dark: Map<String, Long>?
)

/** Pure parser (org.json only) so unit tests need no Android framework. */
fun parseCustomTheme(json: String): CustomTheme {
    if (json.isBlank()) throw ThemeParseException("The file is empty")
    val root = try {
        JSONObject(json)
    } catch (e: Exception) {
        throw ThemeParseException("Not valid JSON (${e.message})")
    }
    val name = root.optString("name").trim().ifBlank { "Custom" }
    val light = root.optJSONObject("light")?.let(::parseColorObject)
    val dark = root.optJSONObject("dark")?.let(::parseColorObject)
    if (light == null && dark == null) {
        throw ThemeParseException(
            "No \"light\" or \"dark\" object found — a theme needs at least one"
        )
    }
    return CustomTheme(name = name, light = light, dark = dark)
}

private fun parseColorObject(obj: JSONObject): Map<String, Long> {
    val out = mutableMapOf<String, Long>()
    obj.keys().forEach { key ->
        if (key !in THEME_COLOR_KEYS) {
            throw ThemeParseException(
                "Unknown color \"$key\". Valid keys: ${THEME_COLOR_KEYS.joinToString(", ")}"
            )
        }
        val raw = obj.optString(key).trim()
        val packed = parseArgb(raw)
            ?: throw ThemeParseException("Color \"$key\" is \"$raw\" — expected #RRGGBB or #AARRGGBB")
        out[key] = packed
    }
    if (out.isEmpty()) throw ThemeParseException("A color object needs at least one color")
    return out
}

/** "#RRGGBB" / "#AARRGGBB" → ARGB-packed Long, or null if malformed. */
fun parseArgb(raw: String): Long? {
    if (!raw.startsWith("#")) return null
    val hex = raw.removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)
}

/** Apply the parsed roles on top of a built-in scheme; omitted roles keep the base. */
fun applyThemeColors(base: ColorScheme, colors: Map<String, Long>): ColorScheme {
    var s = base
    colors.forEach { (key, value) ->
        val c = Color(value)
        s = when (key) {
            "primary" -> s.copy(primary = c)
            "onPrimary" -> s.copy(onPrimary = c)
            "primaryContainer" -> s.copy(primaryContainer = c)
            "onPrimaryContainer" -> s.copy(onPrimaryContainer = c)
            "secondary" -> s.copy(secondary = c)
            "onSecondary" -> s.copy(onSecondary = c)
            "secondaryContainer" -> s.copy(secondaryContainer = c)
            "onSecondaryContainer" -> s.copy(onSecondaryContainer = c)
            "tertiary" -> s.copy(tertiary = c)
            "onTertiary" -> s.copy(onTertiary = c)
            "tertiaryContainer" -> s.copy(tertiaryContainer = c)
            "onTertiaryContainer" -> s.copy(onTertiaryContainer = c)
            "background" -> s.copy(background = c)
            "onBackground" -> s.copy(onBackground = c)
            "surface" -> s.copy(surface = c)
            "onSurface" -> s.copy(surface = c)
            "surfaceVariant" -> s.copy(surfaceVariant = c)
            "onSurfaceVariant" -> s.copy(onSurfaceVariant = c)
            "outline" -> s.copy(outline = c)
            else -> s
        }
    }
    return s
}

private fun themeFile(context: Context): File = File(context.filesDir, "custom_theme.json")

/** The stored theme, or null when none was imported (or the file went bad). */
fun loadCustomTheme(context: Context): CustomTheme? {
    val file = themeFile(context)
    if (!file.exists()) return null
    return try {
        parseCustomTheme(file.readText())
    } catch (e: ThemeParseException) {
        DebugLog.log("THEME", "stored theme unreadable: ${e.message}")
        null
    }
}

/**
 * Read the picked file, validate it, store it, and remember its display name.
 * Returns the parsed theme, or throws ThemeParseException with a user-readable
 * reason — Settings shows it verbatim.
 */
fun importCustomTheme(context: Context, uri: android.net.Uri): CustomTheme {
    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: throw ThemeParseException("Could not read the selected file")
    val parsed = parseCustomTheme(text)
    themeFile(context).writeText(text)
    context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE).edit()
        .putString("custom_theme_name", parsed.name)
        .apply()
    DebugLog.log("THEME", "imported \"${parsed.name}\"")
    return parsed
}

/** A complete, valid starter theme (the built-in green) users can edit. */
fun templateThemeJson(): String {
    fun obj(colors: List<Pair<String, String>>): JSONObject {
        val o = JSONObject()
        colors.forEach { (k, v) -> o.put(k, v) }
        return o
    }
    return JSONObject()
        .put(
            "name",
            "My theme (edit the colors, keep or delete any line, then import)"
        )
        .put(
            "light",
            obj(
                listOf(
                    "primary" to "#386A20", "onPrimary" to "#FFFFFF",
                    "primaryContainer" to "#B7F397", "onPrimaryContainer" to "#072100",
                    "secondary" to "#55624C", "onSecondary" to "#FFFFFF",
                    "secondaryContainer" to "#D8E7CB", "onSecondaryContainer" to "#131F0D",
                    "tertiary" to "#19686A", "onTertiary" to "#FFFFFF",
                    "tertiaryContainer" to "#B0ECEC", "onTertiaryContainer" to "#002021",
                    "background" to "#F7FAF0", "onBackground" to "#191D16",
                    "surface" to "#F7FAF0", "onSurface" to "#191D16",
                    "surfaceVariant" to "#DEE5D9", "onSurfaceVariant" to "#43483F",
                    "outline" to "#73796E"
                )
            )
        )
        .put(
            "dark",
            obj(
                listOf(
                    "primary" to "#9CCC78", "onPrimary" to "#163800",
                    "primaryContainer" to "#24500D", "onPrimaryContainer" to "#B7F397",
                    "secondary" to "#BCCBB0", "onSecondary" to "#283422",
                    "secondaryContainer" to "#3E4B37", "onSecondaryContainer" to "#D8E7CB",
                    "tertiary" to "#95D0D0", "onTertiary" to "#003738",
                    "tertiaryContainer" to "#004F51", "onTertiaryContainer" to "#B0ECEC",
                    "background" to "#10140E", "onBackground" to "#E1E4D8",
                    "surface" to "#10140E", "onSurface" to "#E1E4D8",
                    "surfaceVariant" to "#43483F", "onSurfaceVariant" to "#C2C9BC",
                    "outline" to "#8C9287"
                )
            )
        )
        .toString(2)
}
