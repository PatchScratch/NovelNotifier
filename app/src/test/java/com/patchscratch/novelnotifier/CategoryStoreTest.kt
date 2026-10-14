package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryStoreTest {

    private fun state(): CategoryState {
        var s = CategoryStore.parse("")
        s = CategoryStore.add(s, "Reading")
        s = CategoryStore.add(s, "Ecchi")
        s = CategoryStore.move(s, "w1", s.cats[0].id)
        s = CategoryStore.move(s, "w2", s.cats[1].id)
        return s
    }

    @Test
    fun json_roundTrip_preservesCatsAssignmentsAndHidden() {
        var s = state()
        s = CategoryStore.setHidden(s, s.cats[1].id, true)
        val parsed = CategoryStore.parse(CategoryStore.toJson(s))
        assertEquals(2, parsed.cats.size)
        assertEquals("Reading", parsed.cats[0].name)
        assertFalse(parsed.cats[0].hidden)
        assertTrue(parsed.cats[1].hidden)
        assertEquals(s.cats[0].id, parsed.assign["w1"])
        assertEquals(s.cats[1].id, parsed.assign["w2"])
    }

    @Test
    fun parse_blankOrBroken_fallsBackToEmpty() {
        assertEquals(CategoryState(), CategoryStore.parse(""))
        assertEquals(CategoryState(), CategoryStore.parse("not json {{{"))
    }

    @Test
    fun add_blankNameIsNoop_idsAreUnique() {
        val base = state()
        val same = CategoryStore.add(base, "   ")
        assertEquals(base, same)
        val grown = CategoryStore.add(CategoryStore.add(base, "Third"), "Third")
        assertEquals(4, grown.cats.size) // duplicate NAMES are allowed; ids differ
        assertEquals(4, grown.cats.map { it.id }.distinct().size)
    }

    @Test
    fun delete_clearsAssignmentsOfThatCategoryOnly() {
        val s = state()
        val gone = s.cats[1].id
        val next = CategoryStore.delete(s, gone)
        assertEquals(listOf("Reading"), next.cats.map { it.name })
        assertEquals(s.cats[0].id, next.assign["w1"])
        assertNull(next.assign["w2"])
    }

    @Test
    fun move_singleMembershipAndClearing() {
        val s = state()
        // Moving w1 to w2's category replaces its old assignment; several
        // works may share a category — membership is single per WORK.
        val moved = CategoryStore.move(s, "w1", s.cats[1].id)
        assertEquals(s.cats[1].id, moved.assign["w1"])
        assertEquals(2, moved.assign.values.count { it == s.cats[1].id })
        // null clears.
        val cleared = CategoryStore.move(moved, "w1", null)
        assertFalse(cleared.assign.containsKey("w1"))
    }

    @Test
    fun move_unknownCategoryIsIgnored() {
        val s = state()
        assertEquals(s, CategoryStore.move(s, "w9", "nope"))
    }

    @Test
    fun rename_keepsAssignmentsAndHidden() {
        var s = state()
        val ecchi = s.cats[1]
        s = CategoryStore.setHidden(s, ecchi.id, true)
        val next = CategoryStore.rename(s, ecchi.id, "Spicy")
        assertEquals("Spicy", next.cats[1].name)
        assertTrue(next.cats[1].hidden)
        assertEquals(ecchi.id, next.assign["w2"])
    }
}
