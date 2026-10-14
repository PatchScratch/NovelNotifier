package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ★-modal parser, against the real novel-page markup (logged-out page
 * captured 2026-10-15; the categoryid select fills only when logged in).
 */
class SyosetuAddFormTest {

    private fun page(categories: String = ""): String = """
        <div class="c-modal p-bookmark-modal">
          <input type="hidden" class="js-bookmark_url"
                 value="https://syosetu.com/favnovelmain/addajax/favncode/396277/no/0/?token=90a9a18924714009ccaf417011e16eef">
          <input type="checkbox" name="isnotice" value="1" id="bookmarkisnotice">
          <input type="radio" name="jyokyo" value="2" checked="checked" id="bookmark-jyokyo-1">
          <input type="radio" name="jyokyo" value="1" id="bookmark-jyokyo-2">
          <select name="categoryid" class="js-category_select c-form__select">$categories</select>
          <input type="hidden" name="token" value="90a9a18924714009ccaf417011e16eef">
        </div>
    """.trimIndent()

    @Test
    fun parses_addUrl_token_defaults_emptyCategoriesWhenLoggedOut() {
        val f = parseSyosetuAddBookmarkForm(page())!!
        assertEquals(
            "https://syosetu.com/favnovelmain/addajax/favncode/396277/no/0/?token=90a9a18924714009ccaf417011e16eef",
            f.addUrl
        )
        assertTrue(f.categories.isEmpty())
        assertFalse(f.isnotice)
        assertEquals("2", f.jyokyo)   // public default
    }

    @Test
    fun parses_categoryOptions_whenLoggedIn() {
        val f = parseSyosetuAddBookmarkForm(
            page("""<option value="1" selected>読書中</option><option value="2">R18</option>""")
        )!!
        assertEquals(listOf(1 to "読書中", 2 to "R18"), f.categories)
    }

    @Test
    fun isnotice_checkedStateIsDetected() {
        val f = parseSyosetuAddBookmarkForm(
            page().replace("""name="isnotice" value="1"""", """name="isnotice" value="1" checked="checked"""")
        )!!
        assertTrue(f.isnotice)
    }

    @Test
    fun missingBookmarkUrl_returnsNull() {
        assertNull(parseSyosetuAddBookmarkForm("<html><body>no modal here</body></html>"))
    }
}
