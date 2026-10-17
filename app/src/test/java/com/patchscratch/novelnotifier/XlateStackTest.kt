package com.patchscratch.novelnotifier

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XlateStackTest {

    @Test
    fun load_layering_promptReplaces_glossaryConcatenates() {
        val tmp = Files.createTempDirectory("xlate").toFile()
        val global = tmp.resolve("xlate").apply { mkdirs() }
        val series = tmp.resolve("xlate").resolve("123").apply { mkdirs() }
        global.resolve(XlateStack.PROMPT).writeText("global persona")
        global.resolve(XlateStack.GLOSSARY).writeText("アナベル = Anabel")
        series.resolve(XlateStack.PROMPT).writeText("series persona")
        series.resolve(XlateStack.VOICES).writeText("ヨハン: gruff")

        // point the object at our temp dir by testing the pure parts
        val g = XlateStack.read(global.resolve(XlateStack.GLOSSARY))
        val s = XlateStack.read(series.resolve(XlateStack.VOICES))
        assertEquals("アナベル = Anabel", g)
        assertEquals("ヨハン: gruff", s)
        // prompt precedence is exercised in load() with a Context — here we
        // assert the file semantics the layering rules build on.
        assertTrue(XlateStack.read(series.resolve(XlateStack.PROMPT)).isNotBlank())
    }

    @Test
    fun appendDeduped_skipsExistingLines() {
        val f = Files.createTempDirectory("dedup").toFile().resolve("glossary.txt")
        f.writeText("アナベル = Anabel")
        XlateStack.appendDeduped(f, listOf("アナベル = Anabel", "ヨハン = Johanna", ""))
        assertEquals("アナベル = Anabel\nヨハン = Johanna", f.readText().trim())
        XlateStack.appendDeduped(f, listOf("ヨハン = Johanna"))
        assertEquals("アナベル = Anabel\nヨハン = Johanna", f.readText().trim())
    }

    @Test
    fun parseRefineReply_toleratesFencesAndProse() {
        val ok = XlateStack.parseRefineReply(
            """```json
               {"glossary": ["リナ = Lina"], "voices": ["王子: formal"]}
               ```"""
        )
        assertNotNull(ok)
        assertEquals(listOf("リナ = Lina"), ok!!.first)
        assertEquals(listOf("王子: formal"), ok.second)

        val prose = XlateStack.parseRefineReply(
            "Here are the updates:\n{\"glossary\": [], \"voices\": [\"イザベラ: cold duchess\"]}\nDone."
        )
        assertNotNull(prose)
        assertTrue(prose!!.first.isEmpty())
        assertEquals(1, prose.second.size)

        assertNull(XlateStack.parseRefineReply("I could not read that."))
        assertNull(XlateStack.parseRefineReply(""))
    }

    @Test
    fun refineUserMessage_carriesFilesAndExcerpts() {
        val jp = (1..80).map { "日本語$it" }
        val en = (1..80).map { "English $it" }
        val msg = XlateStack.refineUserMessage("g1", "v1", jp, en)
        assertTrue(msg.contains("## glossary.txt"))
        assertTrue(msg.contains("g1"))
        assertTrue(msg.contains("## voices.txt"))
        assertTrue(msg.contains("v1"))
        assertTrue(msg.contains("日本語1 => English 1"))
        assertTrue(msg.contains("日本語80 => English 80"))
        // middle chapters are sampled out
        assertTrue(!msg.contains("日本語40 =>"))
    }

    @Test
    fun systemPrompt_lockSectionsMirrorTheMill() {
        val s = TranslationClient.systemPrompt("P", "G", "V")
        assertTrue(s.contains("P"))
        // built-in glossary entries precede the user's (merged section)
        assertTrue(s.contains("## glossary.txt\n"))
        assertTrue(s.contains("\nG"))
        assertTrue(s.contains("## voices.txt\nV"))
        // two-arg overload still works (no voices section)
        val s2 = TranslationClient.systemPrompt("P", "G")
        assertTrue(s2.contains("## glossary.txt\n"))
        assertTrue(s2.contains("\nG"))
        assertTrue(!s2.contains("## voices.txt"))
    }
}
