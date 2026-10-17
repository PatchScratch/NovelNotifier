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
        // novel-mill lock-file section header
        assertTrue(withGlossary.contains("## glossary.txt"))
        assertTrue(withGlossary.contains("リムル = Rimuru"))
    }

    // --- user style rules + default glossary (request 2026-10-18) ---

    @Test
    fun systemPrompt_styleRulesRideUnderAnyPrompt() {
        // The rules must survive a custom prompt — they govern output the
        // custom wording can't be trusted to repeat.
        for (base in listOf("", "Translate like a pirate.")) {
            val prompt = TranslationClient.systemPrompt(base, "")
            assertTrue(prompt.contains("Keep 「」『』（）！？…〜ー"))
            assertTrue(prompt.contains("FIRST PERSON (I/me). Never she/he thought."))
            assertTrue(prompt.contains("romaji only"))
            assertTrue("the worked example must ride along", prompt.contains("（I saw it…!"))
        }
    }

    @Test
    fun systemPrompt_defaultGlossaryPrecedesUserEntries() {
        val prompt = TranslationClient.systemPrompt("custom", "リムル = Rimuru")
        assertTrue(prompt.contains("如月千紗 = Chisa Kisaragi"))
        assertTrue(prompt.contains("幼馴染 = childhood friend"))
        assertTrue("user entry still present", prompt.contains("リムル = Rimuru"))
        assertTrue(
            "built-ins first, user entries after",
            prompt.indexOf("幼馴染") < prompt.indexOf("リムル")
        )
    }

    @Test
    fun systemPrompt_bracketRuleDoesNotContradictContract() {
        // The contract used to demand curly quotes for ALL dialogue, which
        // fought the keep-「」 rule; now it only constrains quotes the model
        // introduces itself.
        val prompt = TranslationClient.systemPrompt("", "")
        assertTrue(prompt.contains("Keep the original Japanese brackets 「」『』（）"))
        assertTrue(!prompt.contains("Render dialogue and quotation marks as curly"))
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
    fun userMessage_isIndexedObjectArray() {
        val arr = JSONArray(TranslationClient.userMessage(listOf("壱", "弐", "")))
        assertEquals(3, arr.length())
        assertEquals(0, arr.getJSONObject(0).getInt("i"))
        assertEquals("壱", arr.getJSONObject(0).getString("t"))
        assertEquals("", arr.getJSONObject(2).getString("t"))   // blanks preserved — indexes stay 1:1
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

    @Test
    fun parseReply_acceptsIndexedObjects_withToleratedGaps() {
        val ok = """[{"i":0,"e":"One."},{"i":1,"e":"Two."},{"i":2,"e":"Three."}]"""
        assertEquals(listOf("One.", "Two.", "Three."), TranslationClient.parseReply(ok, 3))
        // one skipped index → blank, accepted
        val gap = """[{"i":0,"e":"One."},{"i":2,"e":"Three."}]"""
        assertEquals(listOf("One.", "", "Three."), TranslationClient.parseReply(gap, 3))
        // three skipped of five → rejected
        val bad = """[{"i":0,"e":"One."},{"i":4,"e":"Five."}]"""
        assertNull(TranslationClient.parseReply(bad, 5))
        // out-of-range index → rejected
        assertNull(TranslationClient.parseReply("""[{"i":0,"e":"One."},{"i":3,"e":"?"}]""", 2))
        // "t" key also accepted as the translation field
        assertEquals(listOf("One."), TranslationClient.parseReply("""[{"i":0,"t":"One."}]""", 1))
    }

    // Observed live (2026-10-13, gpt-4.1-mini via OpenRouter): the model
    // renders dialogue with straight ASCII quotes INSIDE JSON string values.
    @Test
    fun parseReply_repairsUnescapedDialogueQuotes() {
        val bad = """["What is it?","Another elbow came.",""—Twelve years," I thought.","He said "hello" to her.","The prince stopped."]"""
        val parsed = TranslationClient.parseReply(bad, 5)
        assertNotNull(parsed)
        // item 2 legitimately opens with a dialogue quote rendered as "
        assertEquals("\"—Twelve years,\" I thought.", parsed!![2])
        assertEquals("He said \"hello\" to her.", parsed[3])
        assertEquals("The prince stopped.", parsed[4])
    }

    @Test
    fun escapeInnerQuotes_leavesValidJsonAlone() {
        val ok = """["a","b","c"]"""
        assertEquals(ok, TranslationClient.escapeInnerQuotes(ok))
        // raw string: backslash-quote here is the literal escaped-JSON form
        val escaped = """["he said \"hi\"","b"]"""
        assertEquals(escaped, TranslationClient.escapeInnerQuotes(escaped))
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
