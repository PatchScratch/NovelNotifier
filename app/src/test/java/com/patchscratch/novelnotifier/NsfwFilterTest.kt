package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwFilterTest {

    private fun novel(title: String) = Novel(title, "c1")
    private fun bm(cat: String) = SyosetuBookmark("n1", "t", cat, "2026-10-14")
    private fun work(id: String) = KakuyomuWork(id, "t", "", "FANTASY", "a", false, 1, 0, 0, "", "", "")

    // --- NU: hidden lists via the snapshot reverse index ---

    @Test
    fun hiddenNuTitles_mapsHiddenListIdsToTitles() {
        val byList = mapOf(
            "1" to listOf(novel("A"), novel("B")),
            "2" to listOf(novel("C")),
            "3" to listOf(novel("B"), novel("D"))
        )
        // B lives in BOTH a hidden and a visible list — hiding wins; every
        // title of every hidden list is included.
        assertEquals(setOf("B", "C", "D"), NsfwFilter.hiddenNuTitles(byList, setOf("2", "3")))
        assertEquals(emptySet<String>(), NsfwFilter.hiddenNuTitles(byList, emptySet()))
        assertEquals(emptySet<String>(), NsfwFilter.hiddenNuTitles(byList, setOf("9")))
    }

    @Test
    fun filterNu_dropsHiddenTitles_onlyWhenLocked() {
        val rows = listOf(novel("A"), novel("C"))
        val hidden = setOf("C")
        assertEquals(rows, NsfwFilter.filterNu(rows, hidden, unlocked = true))
        assertEquals(listOf(novel("A")), NsfwFilter.filterNu(rows, hidden, unlocked = false))
        assertEquals(rows, NsfwFilter.filterNu(rows, emptySet(), unlocked = false))
    }

    // --- Syosetu: hidden 分類 names ---

    @Test
    fun filterSyosetu_dropsHiddenCategories_onlyWhenLocked() {
        val rows = listOf(bm("Reading"), bm("R18"), bm(""))
        val hidden = setOf("R18")
        assertEquals(rows, NsfwFilter.filterSyosetu(rows, hidden, unlocked = true))
        assertEquals(listOf(bm("Reading"), bm("")), NsfwFilter.filterSyosetu(rows, hidden, unlocked = false))
    }

    // --- Kakuyomu: hidden client-side categories ---

    @Test
    fun hiddenKakuyomuWorkIds_followsAssignments() {
        var s = CategoryStore.parse("")
        s = CategoryStore.add(s, "Safe")
        s = CategoryStore.add(s, "Spicy")
        s = CategoryStore.setHidden(s, s.cats[1].id, true)
        s = CategoryStore.move(s, "w1", s.cats[0].id)
        s = CategoryStore.move(s, "w2", s.cats[1].id)
        assertEquals(setOf("w2"), NsfwFilter.hiddenKakuyomuWorkIds(s))
        // Unhiding empties the set.
        assertEquals(emptySet<String>(), NsfwFilter.hiddenKakuyomuWorkIds(
            CategoryStore.setHidden(s, s.cats[1].id, false)))
    }

    @Test
    fun filterKakuyomu_dropsHiddenWorks_onlyWhenLocked() {
        val rows = listOf(work("w1"), work("w2"))
        assertEquals(rows, NsfwFilter.filterKakuyomu(rows, setOf("w2"), unlocked = true))
        assertEquals(listOf(work("w1")), NsfwFilter.filterKakuyomu(rows, setOf("w2"), unlocked = false))
    }
}
