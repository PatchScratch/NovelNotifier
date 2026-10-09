package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the merge helpers in Cache.kt / Prefs.kt.
 * Rule under test: incoming data never wipes or downgrades what we already
 * stored, and NU's one-list-per-series rule drops ghost rows.
 */
class CacheHelpersTest {

    private fun novel(title: String, chapter: String, url: String = "", updatedAt: Long = 0L) =
        Novel(title = title, latestChapter = chapter, seriesUrl = url, updatedAt = updatedAt)

    // --- putExclusiveList (NU: a series lives on exactly one list) ---

    @Test
    fun putExclusiveList_keepsOtherListRows() {
        val byList = mapOf(
            "a" to listOf(novel("X", "c1", "u1")),
            "b" to listOf(novel("Y", "c1", "u2"))
        )
        val next = putExclusiveList(byList, "a", listOf(novel("X", "c2", "u1")))
        assertEquals(listOf("c2"), next["a"]!!.map { it.latestChapter })
        assertEquals(1, next["b"]!!.size)
    }

    @Test
    fun putExclusiveList_removesGhostFromOtherLists() {
        val byList = mapOf(
            "a" to listOf(novel("X", "c1", "u1")),
            "b" to emptyList<Novel>()
        )
        // Same series moves to b: cached row in a must disappear.
        val next = putExclusiveList(byList, "b", listOf(novel("x", "c1", "u1")))
        assertEquals(0, next["a"]!!.size)
        assertEquals(1, next["b"]!!.size)
    }

    // --- mergeReleases ---

    @Test
    fun mergeReleases_fillsRidAndKeepsRead() {
        val old = listOf(Release("c1", "g", "d", "u1", rid = "", read = false))
        val incoming = listOf(Release("c1", "g", "d", "u1", rid = "5", read = true))
        val merged = mergeReleases(old, incoming)
        assertEquals(1, merged.size)
        assertEquals("5", merged[0].rid)
        assertEquals(true, merged[0].read)
    }

    @Test
    fun mergeReleases_disjointRowsKeptBothIncomingFirst() {
        val old = listOf(Release("c1", "g", "d", "u1"))
        val incoming = listOf(Release("c2", "g", "d", "u2"))
        val merged = mergeReleases(old, incoming)
        assertEquals(listOf("c2", "c1"), merged.map { it.chapter })
    }

    @Test
    fun mergeReleases_sameChapterDifferentUrlIsNotDuplicate() {
        val old = listOf(Release("c1", "g1", "d", "u1"))
        val incoming = listOf(Release("c1", "g2", "d", "u2"))
        assertEquals(2, mergeReleases(old, incoming).size)
    }

    // --- mergeLastReads (higher chapter wins) ---

    @Test
    fun mergeLastReads_higherIncomingWins() {
        val current = mapOf("A" to ("c5" to "u1"))
        val incoming = mapOf("A" to ("c7" to "u2"), "B" to ("c1" to "u3"))
        val merged = mergeLastReads(current, incoming)
        assertEquals("c7", merged["A"]!!.first)
        assertEquals("c1", merged["B"]!!.first)
    }

    @Test
    fun mergeLastReads_lowerIncomingIgnored() {
        val current = mapOf("A" to ("c9" to "u1"))
        val incoming = mapOf("A" to ("c7" to "u2"))
        assertEquals("c9", mergeLastReads(current, incoming)["A"]!!.first)
    }

    @Test
    fun mergeLastReads_unparseableExistingReplaced() {
        val current = mapOf("A" to ("Start" to "u1"))
        val incoming = mapOf("A" to ("c3" to "u2"))
        assertEquals("c3", mergeLastReads(current, incoming)["A"]!!.first)
    }

    @Test
    fun mergeLastReads_emptyIncomingKeepsCurrent() {
        val current = mapOf("A" to ("c5" to "u1"))
        assertEquals(current, mergeLastReads(current, emptyMap()))
    }

    // --- read-feed vs manual back-track floor ---

    @Test
    fun readFeedItemApplies_matrix() {
        val floorTime = 1_000L
        // No floor: everything applies.
        assertTrue(readFeedItemApplies(0, 0L, 999, 0L))
        // At or below the floor: always applies.
        assertTrue(readFeedItemApplies(5, floorTime, 5, 500L))
        assertTrue(readFeedItemApplies(5, floorTime, 3, 0L))
        // Above the floor: only marks made AFTER the correction apply.
        assertTrue(readFeedItemApplies(5, floorTime, 9, 1_500L))
        assertTrue(readFeedItemApplies(5, floorTime, 9, floorTime))
        assertFalse(readFeedItemApplies(5, floorTime, 9, 999L))
        // Undated items above the floor are treated as stale.
        assertFalse(readFeedItemApplies(5, floorTime, 9, 0L))
    }

    // --- stampChapterTimes / chapterChangeCount ---

    @Test
    fun stampChapterTimes_keepsOldStampWhenChapterUnchanged() {
        val old = listOf(novel("X", "c1", updatedAt = 100L))
        val stamped = stampChapterTimes(old, listOf(novel("X", "c1")))
        assertEquals(100L, stamped[0].updatedAt)
    }

    @Test
    fun stampChapterTimes_newChapterGetsFreshStamp() {
        val old = listOf(novel("X", "c1", updatedAt = 100L))
        val stamped = stampChapterTimes(old, listOf(novel("X", "c2")))
        assertTrue(stamped[0].updatedAt > 100L)
    }

    @Test
    fun stampChapterTimes_newTitleGetsFreshStamp() {
        val stamped = stampChapterTimes(emptyList(), listOf(novel("Y", "c1")))
        assertTrue(stamped[0].updatedAt > 0L)
    }

    @Test
    fun chapterChangeCount_countsOnlyChangedRows() {
        val old = listOf(novel("X", "c1", "u1"), novel("Y", "c5", "u2"))
        val new = listOf(novel("X", "c2", "u1"), novel("Y", "c5", "u2"))
        assertEquals(1, chapterChangeCount(old, new))
    }

    @Test
    fun chapterChangeCount_newRowsDoNotCount() {
        val old = listOf(novel("X", "c1", "u1"))
        val new = listOf(novel("X", "c1", "u1"), novel("Y", "c5", "u2"))
        assertEquals(0, chapterChangeCount(old, new))
    }
}
