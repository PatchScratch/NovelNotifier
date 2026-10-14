package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Router replaces NovelApp's flag-based when-block + hand-mirrored
 * BackHandler. These tests pin the ordering rules that the flag era
 * enforced (and the two deliberate fixes documented on Router).
 */
class RouterTest {

    @Test
    fun `starts at home with nothing to go back to`() {
        val r = Router()
        assertEquals(Route.Home, r.top)
        assertFalse(r.canGoBack)
        assertFalse(r.pop())
        assertEquals(Route.Home, r.top)
    }

    @Test
    fun `push and pop roundtrip`() {
        val r = Router()
        r.push(Route.Settings)
        assertEquals(Route.Settings, r.top)
        assertTrue(r.canGoBack)
        assertTrue(r.pop())
        assertEquals(Route.Home, r.top)
    }

    @Test
    fun `pushing the same top twice is a no-op`() {
        val r = Router()
        r.push(Route.Settings)
        r.push(Route.Settings)
        assertEquals(listOf<Route>(Route.Home, Route.Settings), r.stack)
    }

    @Test
    fun `site login replaces settings - settings must close first`() {
        val r = Router()
        r.push(Route.Settings)
        r.replace(Route.SyosetuLogin)
        assertEquals(listOf<Route>(Route.Home, Route.SyosetuLogin), r.stack)
        // Back from the login screen returns Home (the flag era never
        // re-opened Settings for the JP sites' logins).
        r.pop()
        assertEquals(Route.Home, r.top)
    }

    @Test
    fun `nu login cancel re-opens settings`() {
        val r = Router()
        r.push(Route.Settings)
        r.replace(Route.NuLogin)
        assertEquals(Route.NuLogin, r.top)
        r.replace(Route.Settings)
        assertEquals(listOf<Route>(Route.Home, Route.Settings), r.stack)
    }

    @Test
    fun `xlate files sits above the reader`() {
        val r = Router()
        r.push(Route.Reader)
        r.push(Route.XlateFiles)
        assertEquals(Route.XlateFiles, r.top)
        r.pop()
        assertEquals(Route.Reader, r.top)
    }

    @Test
    fun `library stays below its detail screen - back returns to the library`() {
        val r = Router()
        r.push(Route.SyosetuLibrary(""))
        r.push(Route.SyosetuDetail("https://ncode.syosetu.com/n2267be/"))
        assertEquals(Route.SyosetuDetail("https://ncode.syosetu.com/n2267be/"), r.top)
        r.pop()
        assertEquals(Route.SyosetuLibrary(""), r.top)
    }

    @Test
    fun `search result replaces search so back lands on home`() {
        val r = Router()
        r.push(Route.Search)
        r.replace(Route.NuDetail("https://www.novelupdates.com/series/x/"))
        r.pop()
        assertEquals(Route.Home, r.top)
    }

    @Test
    fun `deep link closes overlays and shows the series above home`() {
        val r = Router()
        r.push(Route.Settings)
        r.push(Route.Search)
        r.resetTo(Route.NuDetail("https://www.novelupdates.com/series/y/"))
        assertEquals(
            listOf(Route.Home, Route.NuDetail("https://www.novelupdates.com/series/y/")),
            r.stack
        )
    }

    @Test
    fun `deep link keeps an open reader on top`() {
        val r = Router()
        r.push(Route.Reader)
        r.resetTo(Route.NuDetail("https://www.novelupdates.com/series/z/"))
        assertEquals(
            listOf(Route.Home, Route.NuDetail("https://www.novelupdates.com/series/z/"), Route.Reader),
            r.stack
        )
    }

    @Test
    fun `reader back unwinds to whatever was below it`() {
        val r = Router()
        r.push(Route.SyosetuLibrary("3"))
        r.push(Route.Reader)
        r.pop()
        assertEquals(Route.SyosetuLibrary("3"), r.top)
    }

    @Test
    fun `session-expiry login surfaces over the reader`() {
        // AppViewModel's poll pushes NuLogin whatever is on top — pin that
        // the stack model puts it above the reader (the deliberate change
        // from the flag era, where the reader hid it until back).
        val r = Router()
        r.push(Route.Reader)
        r.push(Route.NuLogin)
        assertEquals(Route.NuLogin, r.top)
        r.pop()
        assertEquals(Route.Reader, r.top)
    }
}
