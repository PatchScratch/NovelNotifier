package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression (2026-10-15, found by the translator-host census): the old
 * unwrapJsResult broke payloads whose PROSE contains straight ASCII quotes
 * — chapters full of dialogue failed extraction in the reader, TTS, ePub
 * and the probe alike. JSONTokener now decodes with real string boundaries.
 */
class DoubleUnwrapTest {

    @Test
    fun proseWithStraightQuotes_survivesDecodeAndParse() {
        val payload = "{\"title\":\"Ch. 743\",\"paragraphs\":[\"Hello~, he said \\\"hi\\\".\",\"Line 2\",\"Line 3\"]}"
        // evaluateJavascript hands back the payload JSON-encoded:
        val encoded = org.json.JSONObject(payload).toString()
        val unwrapped = unwrapJsResult(encoded)
        val parsed = parseChapterExtract(unwrapped)
        assertEquals(3, parsed?.paragraphs?.size)
        assertEquals("Hello~, he said \"hi\".", parsed?.paragraphs?.first())
    }

    @Test
    fun decodeHandlesUnicodeAndControlEscapes() {
        val encoded = "\"caf\\u00e9 \\n line\""
        assertEquals("caf\u00e9 \n line".replace("\\u00e9", "caf\u00e9".substring(3)).let { "caf\u00e9" + " \n line" },
            unwrapJsResult(encoded))
    }

    @Test
    fun plainStringsPassThroughUnchanged() {
        assertEquals("plain text", unwrapJsResult("\"plain text\""))
        assertEquals("already bare", unwrapJsResult("already bare"))
        assertEquals("", unwrapJsResult("null"))
        assertNull(parseChapterExtract("FAIL"))
    }
}
