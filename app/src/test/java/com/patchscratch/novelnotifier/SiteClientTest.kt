package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SiteClient's queue and pacing are testable without a WebView (the
 * pacing logic and CF escalation are pure time-based decisions).
 */
class SiteClientTest {

    @Test
    fun `site enum covers all three sites`() {
        assertEquals(3, SiteClient.Site.entries.size)
        assertTrue(SiteClient.Site.entries.contains(SiteClient.Site.NU))
        assertTrue(SiteClient.Site.entries.contains(SiteClient.Site.SYOSETU))
        assertTrue(SiteClient.Site.entries.contains(SiteClient.Site.KAKUYOMU))
    }

    @Test
    fun `scrapeRequest carries url js and timeout`() {
        val req = SiteClient.ScrapeRequest(
            url = "https://www.novelupdates.com/reading-list/",
            js = "document.body.innerHTML",
            timeoutMs = 45_000,
            onComplete = { }
        )
        assertEquals("https://www.novelupdates.com/reading-list/", req.url)
        assertEquals("document.body.innerHTML", req.js)
        assertEquals(45_000L, req.timeoutMs)
    }

    @Test
    fun `result types are distinct`() {
        assertTrue(SiteClient.ScrapeRequest.Result.Success("<html>") is SiteClient.ScrapeRequest.Result.Success)
        assertTrue(SiteClient.ScrapeRequest.Result.Cloudflare("url") is SiteClient.ScrapeRequest.Result.Cloudflare)
        assertTrue(SiteClient.ScrapeRequest.Result.Error("msg") is SiteClient.ScrapeRequest.Result.Error)
    }

    @Test
    fun `isBusy is false when idle`() {
        // SiteClient is a singleton — before any requests, it should not be busy
        assertFalse(SiteClient.isBusy(SiteClient.Site.NU))
    }

    @Test
    fun `reportCloudflare is callable without crash`() {
        // Just verify it doesn't throw (it sets internal state)
        SiteClient.reportCloudflare(SiteClient.Site.NU)
        SiteClient.reportCloudflare(SiteClient.Site.NU) // escalation path
    }

    @Test
    fun `persistent cloudflare raises cfNeeded and clearCloudflare resets it`() {
        SiteClient.clearCloudflare(SiteClient.Site.NU) // isolate from other tests
        // A single report is treated as transient pacing, not user-actionable.
        SiteClient.reportCloudflare(SiteClient.Site.NU)
        assertEquals(null, SiteClient.cfNeeded.value)
        // A repeat within the escalation window = persistent → challenge.
        SiteClient.reportCloudflare(SiteClient.Site.NU)
        assertEquals(SiteClient.Site.NU, SiteClient.cfNeeded.value)
        SiteClient.clearCloudflare(SiteClient.Site.NU)
        assertEquals(null, SiteClient.cfNeeded.value)
    }

    @Test
    fun `probe and extract script wraps the cf probe in a function scope`() {
        // CF_DETECT_JS ends with a top-level `return 'CF'` — it is only valid
        // JS inside the wrapper's function body. Pin the wrapper shape so a
        // future edit can't silently strand the return outside a function.
        val js = SiteClient.PROBE_AND_EXTRACT_JS
        assertTrue(js.startsWith("(function(){"))
        assertTrue(js.contains("function nuIsCf()"))
        assertTrue(js.contains("if (nuIsCf()) return 'CF'"))
        assertTrue(js.contains("return (document.body ? document.body.innerHTML : '')"))
        assertTrue(js.trimEnd().endsWith("})()"))
    }
}
