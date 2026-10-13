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
    fun systemPrompt_carriesRoleAndGlossary() {
        val plain = TranslationClient.systemPrompt("")
        assertTrue(plain.contains("Japanese-to-English", ignoreCase = true))
        assertTrue(plain.contains("JSON array"))
        assertTrue(!plain.contains("Glossary"))

        val withGlossary = TranslationClient.systemPrompt("リムル = Rimuru")
        assertTrue(withGlossary.contains("Glossary"))
        assertTrue(withGlossary.contains("リムル = Rimuru"))
    }

    @Test
    fun requestBody_isValidChatCompletionsShape() {
        val body = JSONObject(
            TranslationClient.requestBody("m", "sys", """["a","b"]""")
        )
        assertEquals("m", body.getString("model"))
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
}
