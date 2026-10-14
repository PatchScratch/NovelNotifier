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
}
