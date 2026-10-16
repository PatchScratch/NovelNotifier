package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app updater pure logic: version comparison (tag shapes seen in the
 * wild: v1.0.0, v1.2.1, v2.2) and the releases/latest JSON → UpdateInfo
 * parse (asset pick by the NovelNotifier-v<version>.apk convention).
 */
class UpdaterTest {

    @Test
    fun isNewer_comparesNumericallyPerComponent() {
        assertTrue(isNewer("v2.3", "2.2"))
        assertTrue(isNewer("v2.10", "2.9"))      // numeric, not lexicographic
        assertTrue(isNewer("2.2.1", "2.2"))
        assertTrue(isNewer("v3", "2.99"))
        assertFalse(isNewer("v2.2", "2.2"))
        assertFalse(isNewer("v2.1", "2.2"))
        assertFalse(isNewer("v1.0.0", "2.2"))
    }

    @Test
    fun isNewer_toleratesOddTags() {
        // unparseable components read as 0 — never crash, never "newer"
        // against a sane current version
        assertFalse(isNewer("beta", "2.2"))
        assertFalse(isNewer("", "2.2"))
        assertTrue(isNewer("2.x", "1.9"))
    }

    private val releaseJson = """{
      "tag_name": "v2.3",
      "name": "2.3 — the TTS release",
      "body": "New:\n- Speed slider\n\nFixed:\n- Kokoro silence",
      "assets": [
        {"name": "NovelNotifier-v2.2.apk", "browser_download_url": "https://x/old.apk", "size": 10},
        {"name": "NovelNotifier-v2.3.apk", "browser_download_url": "https://x/new.apk", "size": 142000000},
        {"name": "SHA256SUMS.txt", "browser_download_url": "https://x/sums", "size": 5}
      ]
    }"""

    @Test
    fun parseLatestRelease_picksTheVersionedApkAsset() {
        val info = parseLatestRelease(releaseJson)!!
        assertEquals("v2.3", info.tag)
        assertEquals("2.3", info.version)
        assertEquals("https://x/new.apk", info.apkUrl)
        assertEquals(142000000L, info.apkBytes)
        assertTrue(info.body.contains("Speed slider"))
    }

    @Test
    fun parseLatestRelease_missingApkAssetIsNull() {
        val noApk = """{"tag_name":"v9.9","name":"x","body":"y","assets":[{"name":"SHA256SUMS.txt","browser_download_url":"https://x/s","size":1}]}"""
        assertNull(parseLatestRelease(noApk))
    }

    @Test
    fun parseLatestRelease_garbageIsNull() {
        assertNull(parseLatestRelease("not json at all"))
        assertNull(parseLatestRelease("""{"tag_name":""}"""))
    }

    @Test
    fun parseLatestRelease_blankBodyKeepsTitle() {
        val info = parseLatestRelease("""{"tag_name":"v2.4","name":"the name","body":"","assets":[{"name":"NovelNotifier-v2.4.apk","browser_download_url":"u","size":1}]}""")!!
        assertEquals("", info.body)
        assertEquals("the name", info.title)
    }
}
