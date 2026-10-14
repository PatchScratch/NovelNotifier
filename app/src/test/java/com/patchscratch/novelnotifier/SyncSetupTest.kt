package com.patchscratch.novelnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/** Minimal prefs fake backing the backup regression test (see AlertStoreTest
 *  for the full-featured twin). */
class FakePrefsForBackup : android.content.SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    fun putRaw(key: String, value: Any?) { values[key] = value }
    override fun getAll() = values
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

/**
 * Tests for the onboarding math and the shared list-page parser — the pieces
 * that decide what the user is told and in what order the library downloads.
 */
class SyncSetupTest {

    // --- conservative estimator ---

    @Test
    fun estimateSetup_coversAreOneHitPerSeries() {
        val e = estimateSetup(seriesCount = 100, listCount = 10)
        // 100 series * 9s + 10 list hits * 9s = 990s
        assertEquals(990_000L, e.coverMillis)
        // 100 * 5 pages * 9s
        assertEquals(4_500_000L, e.chapterMillis)
    }

    @Test
    fun estimateSetup_userLibraryIsAboutADay() {
        // The real library that drove this design: 1419 series, 15 lists.
        val e = estimateSetup(seriesCount = 1419, listCount = 15)
        assertEquals(1419, e.seriesCount)
        assertTrue("total should exceed 20 hours: ${e.totalMillis}", e.totalMillis > 20 * 3_600_000L)
        assertEquals("≈ 21 hours", formatEstimate(e.totalMillis))
    }

    @Test
    fun formatEstimate_humanRounding() {
        assertEquals("under an hour", formatEstimate(20 * 60_000L))
        assertEquals("≈ 3.5 hours", formatEstimate((3.5 * 3_600_000).toLong()))
        assertEquals("≈ 18 hours", formatEstimate(18 * 3_600_000L))
        assertEquals("about 1 day", formatEstimate(24 * 3_600_000L))
        assertEquals("about 2.5 days", formatEstimate((2.5 * 24 * 3_600_000).toLong()))
    }

    // --- parseListPageResult (engine + list screen share this) ---

    private val payload = listOf(
        "2===Reading List",
        "0===Ongoing",
        "1===---"
    ).joinToString("|||") + ":::NOVELS:::" + listOf(
        "Alpha===c10===https://www.novelupdates.com/series/alpha/===https://cdn.novelupdates.com/a.jpg===c4===https://link/4",
        "Beta===c2===https://www.novelupdates.com/series/beta/=====—===",
        "Series Information===info===https://www.novelupdates.com/series/alpha/===",
        "Gamma===c1===https://other.example/novels/gamma/==="
    ).joinToString("|||")

    @Test
    fun parseListPageResult_listsNovelsLastReads() {
        val withRss = payload + ":::RSS:::https://rssnovelupdates.com/rss.php?uid=1&unq=a&type=0&lid=local"
        val r = parseListPageResult(withRss)
        assertEquals(listOf("Reading List", "Ongoing"), r.lists.map { it.name })
        // "Series Information" junk row and the non-NU row are dropped.
        assertEquals(listOf("Alpha", "Beta"), r.novels.map { it.title })
        assertEquals("c10", r.novels[0].latestChapter)
        assertEquals(mapOf("Alpha" to ("c4" to "https://link/4")), r.lastReads)
        assertEquals("https://rssnovelupdates.com/rss.php?uid=1&unq=a&type=0&lid=local", r.rssUrl)
        // Payloads without the RSS section still parse (rssUrl empty).
        assertEquals("", parseListPageResult(payload).rssUrl)
    }

    @Test
    fun parseListPageResult_placeholderSiteImageRejectedAndOldCoverKept() {
        val placeholderPayload = "0===L:::NOVELS:::" +
            "Alpha===c10===https://www.novelupdates.com/series/alpha/===https://www.novelupdates.com/img/noimagefound.jpg==="
        val r = parseListPageResult(
            placeholderPayload,
            coverCache = emptyMap(),
            oldCoverByUrl = mapOf("https://www.novelupdates.com/series/alpha/" to "https://cdn.novelupdates.com/real.jpg")
        )
        assertEquals("https://cdn.novelupdates.com/real.jpg", r.novels[0].coverUrl)
    }

    @Test
    fun parseListPageResult_cachedCoverBeatsSiteImage() {
        val r = parseListPageResult(
            payload,
            coverCache = mapOf("Alpha" to "https://cdn.novelupdates.com/cached.jpg")
        )
        assertEquals("https://cdn.novelupdates.com/cached.jpg", r.novels[0].coverUrl)
    }

    @Test
    fun feedPicking_releasesVsRead() {
        val releases = "https://rssnovelupdates.com/rss.php?uid=1&unq=a&type=0&lid=local"
        val read = "https://rssnovelupdates.com/rss.php?uid=1&unq=a&type=read"
        assertEquals(releases, pickReleasesFeedUrl(listOf(releases, read)))
        assertEquals(read, pickReadFeedUrl(listOf(releases, read)))
        assertEquals(read, pickReadFeedUrl(listOf(read)))
        assertEquals("", pickReadFeedUrl(listOf(releases)))
        assertEquals("", pickReleasesFeedUrl(emptyList()))
    }

    @Test
    fun parseListPageResult_extractsBothFeeds() {
        val releases = "https://rssnovelupdates.com/rss.php?uid=1&unq=a&type=0&lid=local"
        val read = "https://rssnovelupdates.com/rss.php?uid=1&unq=a&type=read"
        val r = parseListPageResult("$payload:::RSS:::$releases|||$read")
        assertEquals(releases, r.rssUrl)
        assertEquals(read, r.readRssUrl)
        // Old single-link payloads still parse.
        val single = parseListPageResult("$payload:::RSS:::$releases")
        assertEquals(releases, single.rssUrl)
        assertEquals("", single.readRssUrl)
    }

    // --- backup prefs: the credentials-exclusion contract ---

    @Test
    fun backupPrefs_neverIncludesLoginOrFeedToken() {
        val json = BackupManager.backupPrefsJson(
            mapOf(
                "rss_url" to "https://rssnovelupdates.com/rss.php?uid=1&unq=SECRET",
                "check_hours" to 12,
                "list_update_off" to setOf("9"),
                "nu_cookies" to "wordpress_logged_in=SECRET",
                "cf_needed" to true,
                "theme_mode" to "black",
                "stack_notifications" to false
            )
        )
        // The feed link carries a private access token and is auto-captured on
        // login anyway (user decision 2026-10-10): it must not ship in backups.
        assertFalse(json.has("rss_url"))
        assertFalse(json.has("nu_cookies"))
        assertFalse(json.has("cf_needed"))
        assertEquals(12, json.optInt("check_hours"))
        assertTrue(json.has("list_update_off"))
        // Display + notification choices travel between devices.
        assertEquals("black", json.optString("theme_mode"))
        assertEquals(false, json.optBoolean("stack_notifications", true))
    }

    // --- SFW/NSFW hiding flags must travel between devices ---

    @Test
    fun backupPrefs_carriesHiddenFlagsAndNothingElseNew() {
        val json = BackupManager.backupPrefsJson(
            mapOf(
                "hidden_lists" to setOf("2"),
                "syosetu_hidden_cats" to setOf("R18")
            )
        )
        val lists = json.optJSONArray("hidden_lists")
        assertEquals(1, lists.length())
        assertEquals("2", lists.optString(0))
        val cats = json.optJSONArray("syosetu_hidden_cats")
        assertEquals(1, cats.length())
        assertEquals("R18", cats.optString(0))
        // Unlock state itself never persists — the mode starts locked.
        assertFalse(json.has("nsfw_unlocked"))
    }

    // --- backup export regression: mixed-type prefs must not crash ---
    // v1.1..v1.2 shipped a ClassCastException: the export snapshot read every
    // ported key as a StringSet, so a String theme_mode (or Boolean stack
    // toggle) killed "Back up library". Found while re-signing devices.

    @Test
    fun backupValues_mixedTypePrefsDoNotCrash() {
        val prefs = com.patchscratch.novelnotifier.FakePrefsForBackup().apply {
            putRaw("theme_mode", "black")             // String
            putRaw("stack_notifications", false)      // Boolean
            putRaw("custom_theme_name", "Ocean")      // String
            putRaw("check_hours", 6)                  // Int
            putRaw("list_notify_off", setOf("3"))     // real set
        }
        val values = BackupManager.backupPrefValues(prefs)
        assertEquals("black", values["theme_mode"])
        assertEquals(false, values["stack_notifications"])
        assertEquals(setOf("3"), values["list_notify_off"])
    }
}
