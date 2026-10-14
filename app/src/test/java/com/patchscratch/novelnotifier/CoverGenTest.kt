package com.patchscratch.novelnotifier

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGenTest {

    @Test
    fun promptFor_includesTitleGenreSynopsis_andForbidsText() {
        val p = CoverGenClient.promptFor("婚約破棄", "著者A", "Isekai Romance", "男爵令嬢アナベルは……" + "とても長い".repeat(300))
        assertTrue(p.contains("婚約破棄"))
        assertTrue(p.contains("著者A"))
        assertTrue(p.contains("Isekai Romance"))
        assertTrue(p.contains("男爵令嬢"))
        // typography instruction present, extraneous text still banned
        assertTrue(p.contains("integrated cover typography"))
        assertTrue(p.contains("NO other"))
        // synopsis capped around 500 chars
        assertTrue(p.length < 1400)
        // embedded double quotes neutralised (prompt is JSON-embedded)
        val q = CoverGenClient.promptFor("He said \"hi\"", "", "", "")
        assertTrue(q.contains("'hi'"))
    }

    @Test
    fun promptFor_customTemplateSubstitutesPlaceholders() {
        val p = CoverGenClient.promptFor("TitleX", "AuthY", "Comedy", "Story of Z", "Paint {title} by {author} in {genre} mood.")
        assertEquals("Paint TitleX by AuthY in Comedy mood.", p)
    }

    @Test
    fun promptFor_customTemplateWithoutPlaceholdersGetsMetadataAppended() {
        val p = CoverGenClient.promptFor("TitleX", "AuthY", "Comedy", "Story of Z", "Watercolor style only.")
        assertTrue(p.startsWith("Watercolor style only."))
        assertTrue(p.contains("TitleX"))
        assertTrue(p.contains("AuthY"))
        assertTrue(p.contains("Comedy"))
        assertTrue(p.contains("Story of Z"))
    }

    @Test
    fun defaultPrompt_carriesPlaceholders() {
        val d = CoverGenClient.defaultPrompt()
        assertTrue(d.contains("{title}"))
        assertTrue(d.contains("{author}"))
        assertTrue(d.contains("{genre}"))
        assertTrue(d.contains("{synopsis}"))
    }

    @Test
    fun requestBody_carriesModelPromptResolutionAspect() {
        val b = JSONObject(CoverGenClient.requestBody("m/x", "a prompt"))
        assertEquals("m/x", b.getString("model"))
        assertEquals("a prompt", b.getString("prompt"))
        assertEquals("1K", b.getString("resolution"))
        assertEquals("2:3", b.getString("aspect_ratio"))
    }

    @Test
    fun parseReply_decodesB64AndMapsExtensions() {
        val png = JSONObject()
            .put("data", org.json.JSONArray().put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))).put("media_type", "image/png")))
            .toString()
        val parsed = CoverGenClient.parseReply(png)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.first.size)
        assertEquals("png", parsed.second)

        val jpg = JSONObject()
            .put("data", org.json.JSONArray().put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(byteArrayOf(9))).put("media_type", "image/jpeg")))
            .toString()
        assertEquals("jpg", CoverGenClient.parseReply(jpg)!!.second)

        // no data array → null
        assertNull(CoverGenClient.parseReply(JSONObject().put("created", 1).toString()))
        // blank b64 → null
        val blank = JSONObject().put("data", org.json.JSONArray().put(JSONObject().put("b64_json", ""))).toString()
        assertNull(CoverGenClient.parseReply(blank))
    }

    // --- friendlyError: the user-facing failure reason (Wayne's oversight
    // report 2026-10-14: cover failures showed no reason) ---

    @Test
    fun friendlyError_surfacesProviderModerationMessage() {
        // The exact live failure from Wayne's phone: Seedream 400.
        val reason = CoverGenClient.friendlyError(
            """covergen HTTP 400: {"error":{"message":"Seedream blocked this request through content moderation.","code":400,"metadata":{"provider_name":"Seed"}}}"""
        )
        assertTrue(reason.contains("Seedream blocked this request through content moderation"))
    }

    @Test
    fun friendlyError_classifiesActionableCodes() {
        assertTrue(CoverGenClient.friendlyError("covergen HTTP 401: {}").contains("API key rejected"))
        assertTrue(CoverGenClient.friendlyError("covergen HTTP 402: no credits").contains("out of credits"))
        assertTrue(CoverGenClient.friendlyError("covergen HTTP 429: slow down").contains("Rate limited"))
        assertTrue(CoverGenClient.friendlyError("covergen HTTP 503: upstream down").contains("Provider error"))
    }

    @Test
    fun friendlyError_plainNetworkFailureHasFallback() {
        assertEquals("Network request failed", CoverGenClient.friendlyError(""))
        assertTrue(CoverGenClient.friendlyError("java.net.ConnectException").contains("ConnectException"))
    }

    // --- shouldFallback: when to escalate to FALLBACK_MODEL ---

    @Test
    fun shouldFallback_moderationRefusalsEscalate() {
        // The exact live failure from Wayne's phone: Seedream 400.
        val livePayload = """{"error":{"message":"Seedream blocked this request through content moderation.","code":400,"metadata":{"provider_name":"Seed"}}}"""
        assertTrue(CoverGenClient.shouldFallback(400, livePayload))
        assertTrue(CoverGenClient.shouldFallback(400, """{"error":{"message":"Your prompt was flagged by our safety systems."}}"""))
        assertTrue(CoverGenClient.shouldFallback(400, """{"error":{"message":"request prohibited"}}"""))
    }

    @Test
    fun shouldFallback_providerOutagesEscalate() {
        assertTrue(CoverGenClient.shouldFallback(500, ""))
        assertTrue(CoverGenClient.shouldFallback(503, "upstream down"))
    }

    @Test
    fun shouldFallback_accountAndRequestErrorsDoNotEscalate() {
        assertFalse(CoverGenClient.shouldFallback(401, "bad key"))      // both models need the same key
        assertFalse(CoverGenClient.shouldFallback(402, "no credits"))
        assertFalse(CoverGenClient.shouldFallback(429, "slow down"))
        assertFalse(CoverGenClient.shouldFallback(400, """{"error":{"message":"Invalid aspect_ratio"}}""")) // our bug, not moderation
        assertFalse(CoverGenClient.shouldFallback(200, ""))             // malformed success
    }

    @Test
    fun modelLadder_seedreamDefault_nanoBananaFallback() {
        assertEquals("bytedance-seed/seedream-5-0-flash", CoverGenClient.DEFAULT_MODEL)
        assertEquals("google/gemini-nano-banana-2.1", CoverGenClient.FALLBACK_MODEL)
    }
}
