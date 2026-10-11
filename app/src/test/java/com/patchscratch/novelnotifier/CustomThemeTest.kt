package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the custom-theme JSON parser (Settings → Display → Import).
 * Rules under test: at least one color object; strict key validation (typos
 * fail loudly); strict #RRGGBB / #AARRGGBB parsing; partial themes are legal
 * (omitted roles fall back to the built-in green at apply time).
 */
class CustomThemeTest {

    @Test
    fun parsesBothModes() {
        val theme = parseCustomTheme(
            """{"name":"Ocean","light":{"primary":"#0B57D0"},"dark":{"primary":"#A8C7FA"}}"""
        )
        assertEquals("Ocean", theme.name)
        assertEquals(mapOf("primary" to 0xFF0B57D0), theme.light)
        assertEquals(mapOf("primary" to 0xFFA8C7FA), theme.dark)
    }

    @Test
    fun missingNameDefaultsToCustom() {
        val theme = parseCustomTheme("""{"dark":{"surface":"#000000"}}""")
        assertEquals("Custom", theme.name)
        assertNull(theme.light)
    }

    @Test
    fun eightDigitArgbIsKeptAsIs() {
        val theme = parseCustomTheme("""{"light":{"surface":"#80FFFFFF"}}""")
        assertEquals(0x80FFFFFF, theme.light!!["surface"])
    }

    @Test
    fun emptyFileRejected() {
        assertThrows(ThemeParseException::class.java) { parseCustomTheme("   ") }
    }

    @Test
    fun invalidJsonRejected() {
        assertThrows(ThemeParseException::class.java) { parseCustomTheme("{not json") }
    }

    @Test
    fun noColorObjectsRejected() {
        assertThrows(ThemeParseException::class.java) { parseCustomTheme("""{"name":"Empty"}""") }
    }

    @Test
    fun unknownKeyRejectedWithValidList() {
        val e = assertThrows(ThemeParseException::class.java) {
            parseCustomTheme("""{"light":{"primery":"#000000"}}""")
        }
        assertTrue("message should name the bad key: ${e.message}", "primery" in (e.message ?: ""))
        assertTrue("message should list valid keys: ${e.message}", "primaryContainer" in (e.message ?: ""))
    }

    @Test
    fun badColorValueRejected() {
        val e = assertThrows(ThemeParseException::class.java) {
            parseCustomTheme("""{"light":{"primary":"green"}}""")
        }
        assertTrue("message should name the bad color: ${e.message}", "primary" in (e.message ?: ""))
    }

    @Test
    fun wrongLengthColorRejected() {
        assertThrows(ThemeParseException::class.java) {
            parseCustomTheme("""{"light":{"primary":"#12345"}}""")
        }
    }

    @Test
    fun parseArgb_acceptsForms() {
        assertEquals(0xFF00FF00, parseArgb("#00FF00"))
        assertEquals(0x8000FF00, parseArgb("#8000FF00"))
        assertNull(parseArgb("00FF00"))
        assertNull(parseArgb("#00FF0"))
        assertNull(parseArgb("#00GG00"))
        assertNull(parseArgb(""))
    }

    @Test
    fun templateIsAlwaysValidAndComplete() {
        // The exported template must round-trip through the strict parser —
        // if this breaks, users get a file the app refuses to re-import.
        val theme = parseCustomTheme(templateThemeJson())
        assertEquals(19, theme.light?.size)
        assertEquals(19, theme.dark?.size)
    }
}
