package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ★-modal endpoint parser, against the real novel-page markup
 * (logged-out page captured 2026-10-15). The 分類 list itself comes from
 * the addajax JSONP payload at runtime, not the page HTML.
 */
class SyosetuAddFormTest {

    private fun page(settingUrl: String = ""): String = """
        <div class="c-modal p-bookmark-modal">
          <input type="hidden" class="js-bookmark_url"
                 value="https://syosetu.com/favnovelmain/addajax/favncode/396277/no/0/?token=90a9a18924714009ccaf417011e16eef">
          <input type="hidden" class="js-bookmark_setting_url" value="$settingUrl">
          <input type="checkbox" name="isnotice" value="1" id="bookmarkisnotice">
          <input type="radio" name="jyokyo" value="2" checked="checked" id="bookmark-jyokyo-1">
          <input type="radio" name="jyokyo" value="1" id="bookmark-jyokyo-2">
          <select name="categoryid" class="js-category_select c-form__select"></select>
        </div>
    """.trimIndent()

    @Test
    fun parses_addUrl_settingUrl_defaults() {
        val f = parseSyosetuAddBookmarkForm(
            page("https://syosetu.com/favnovelmain/updateajax/")
        )!!
        assertEquals(
            "https://syosetu.com/favnovelmain/addajax/favncode/396277/no/0/?token=90a9a18924714009ccaf417011e16eef",
            f.addUrl
        )
        assertEquals("https://syosetu.com/favnovelmain/updateajax/", f.settingUrl)
        assertTrue(f.categories.isEmpty())   // JS-filled at runtime; payload supplies them
        assertFalse(f.isnotice)
        assertEquals("2", f.jyokyo)          // public default
        assertTrue(f.freshToken.isBlank())   // until the payload fetch
    }

    @Test
    fun settingUrl_fallsBackToCanonical() {
        val f = parseSyosetuAddBookmarkForm(page(""))!!
        assertEquals("https://syosetu.com/favnovelmain/updateajax/", f.settingUrl)
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
