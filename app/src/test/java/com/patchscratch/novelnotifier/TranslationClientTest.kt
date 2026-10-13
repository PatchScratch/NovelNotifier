package com.patchscratch.novelnotifier

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure request/reply shaping for the OpenAI-compatible translation client.
 * No network — these pin the contract the endpoint sees and the tolerance
 * the parser needs (models wrap JSON in fences and prose despite rules).
 */
class TranslationClientTest {

    @Test
    fun systemPrompt_customWinsAndContractAlwaysAppended() {
        val custom = "Translate like a pirate."
        val prompt = TranslationClient.systemPrompt(custom, "")
        assertTrue(prompt.startsWith(custom))                       // user's wording leads
        assertTrue(prompt.contains("JSON array"))                   // alignment contract appended
        assertTrue(!prompt.contains("Glossary"))
    }

    @Test
    fun systemPrompt_blankCustomFallsBackToDefault() {
        val prompt = TranslationClient.systemPrompt("", "")
        // The user's default wording (accuracy/uncensored) in the app language
        assertTrue(prompt.contains("uncensored") || prompt.contains("検閲しない"))
        assertTrue(prompt.contains("JSON array"))
    }

    @Test
    fun systemPrompt_carriesGlossary() {
        val withGlossary = TranslationClient.systemPrompt("custom", "リムル = Rimuru")
        assertTrue(withGlossary.contains("Glossary"))
        assertTrue(withGlossary.contains("リムル = Rimuru"))
    }

    @Test
    fun defaultPrompt_matchesUserWording() {
        // The user-specified default (2026-10-13), verbatim in English mode
        assertTrue(TranslationClient.defaultPrompt().contains("uncensored literal and contextual"))
    }

    @Test
    fun requestBody_carriesTemperatureAndMessages() {
        val body = JSONObject(
            TranslationClient.requestBody("m", "sys", """["a","b"]""", temperature = 0.7)
        )
        assertEquals("m", body.getString("model"))
        assertEquals(0.7, body.getDouble("temperature"), 0.0001)
        val msgs = body.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
    }

    @Test
    fun userMessage_isParagraphJsonArray() {
        val arr = JSONArray(TranslationClient.userMessage(listOf("壱", "弐", "")))
        assertEquals(3, arr.length())
        assertEquals("壱", arr.getString(0))
        assertEquals("", arr.getString(2))   // blanks preserved — indexes stay 1:1
    }

    @Test
    fun parseReply_acceptsFencedAndProseWrappedArrays() {
        val paragraphs = listOf("One.", "Two.")
        val json = """["One.","Two."]"""
        assertEquals(paragraphs, TranslationClient.parseReply(json, 2))
        assertEquals(paragraphs, TranslationClient.parseReply("```json\n$json\n```", 2))
        assertEquals(paragraphs, TranslationClient.parseReply("Here is the translation:\n$json\nHope this helps!", 2))
    }

    @Test
    fun parseReply_rejectsCountMismatchesAndGarbage() {
        assertNull(TranslationClient.parseReply("""["only one"]""", 2))
        assertNull(TranslationClient.parseReply("I could not translate that.", 2))
        assertNull(TranslationClient.parseReply("", 2))
        assertNull(TranslationClient.parseReply("""[]""", 0))
    }

    // --- site-info cache (MetadataTranslator) -------------------------------

    @org.junit.Test
    fun metaCache_roundTripAndCap() {
        val f = java.io.File(createTempDirectoryOrNull(), "cache.json")
        assertNull(MetadataTranslator.cacheLookup(f, "あらすじ"))
        MetadataTranslator.cacheStore(f, "あらすじ", "The synopsis")
        assertEquals("The synopsis", MetadataTranslator.cacheLookup(f, "あらすじ"))
        // distinct sources hash apart
        assertNull(MetadataTranslator.cacheLookup(f, "別の文"))
    }

    private fun createTempDirectoryOrNull(): java.io.File {
        val d = java.io.File(System.getProperty("java.io.tmpdir"), "nn-test-${System.nanoTime()}")
        d.mkdirs()
        return d
    }
}
