package com.patchscratch.novelnotifier.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeSuggestionsTest {

    private fun series(id: String, name: String, key: String = name.lowercase(), volumes: Int = 1) =
        SeriesRow(id, name, key, "", "", (0 until volumes).map {
            Volume("v$it", "$name vol $it", "", 1, 0L, false, 0)
        })

    @Test
    fun `same key different ids is proposed`() {
        val s = listOf(series("s1", "Ms. Elf!"), series("s2", "MS ELF!", key = "ms. elf!"))
        val out = MergeSuggestions.forSeries(s)
        assertEquals(1, out.size)
        assertEquals("same key", out[0].reason)
    }

    @Test
    fun `punctuation drift is caught by fuzzy match`() {
        val s = listOf(
            series("s1", "Welcome to Japan, Ms. Elf!", key = "welcome to japan, ms. elf!"),
            series("s2", "Welcome to Japan Ms. Elf!", key = "welcome to japan ms. elf!")
        )
        val out = MergeSuggestions.forSeries(s)
        assertEquals(1, out.size)
        assertEquals("similar name", out[0].reason)
    }

    @Test
    fun `smaller series is merged into the bigger one`() {
        val s = listOf(
            series("s1", "Re:Zero", volumes = 12),
            series("s2", "Re:Zero", volumes = 1)
        )
        val out = MergeSuggestions.forSeries(s)
        assertEquals("s2", out[0].from.id)
        assertEquals("s1", out[0].into.id)
    }

    @Test
    fun `unrelated short names are not proposed`() {
        val s = listOf(series("s1", "86"), series("s2", "87"), series("s3", "88"))
        assertTrue(MergeSuggestions.forSeries(s).isEmpty())
    }

    @Test
    fun `identical single series yields nothing`() {
        val s = listOf(series("s1", "Solo"))
        assertTrue(MergeSuggestions.forSeries(s).isEmpty())
    }

    @Test
    fun `levenshtein basics`() {
        assertEquals(0, MergeSuggestions.levenshtein("abc", "abc"))
        assertEquals(1, MergeSuggestions.levenshtein("abc", "abd"))
        assertEquals(2, MergeSuggestions.levenshtein("kitten", "sitting") - 1)
    }
}
