package com.patchscratch.novelnotifier

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
