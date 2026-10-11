package com.patchscratch.novelnotifier

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the persisted chapter-alert store that powers boot recovery.
 * Rule under test: the store is a merge keyed by alert id, so a new check
 * never drops an older alert still sitting in the shade, and dismissals
 * remove exactly what the user dismissed.
 */
class AlertStoreTest {

    /** Minimal in-memory SharedPreferences (org.json is real; the framework is not). */
    private class FakePrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, Any?> = values
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}

        inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableListOf<String>()

            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { removed.add(key) }
            override fun clear() = also { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                values.putAll(pending)
                removed.forEach { values.remove(it) }
            }
        }
    }

    private fun update(title: String, chapter: String, url: String = "https://www.novelupdates.com/series/$title") =
        ChapterUpdate(
            novel = Novel(title = title, latestChapter = chapter, seriesUrl = url),
            incomingChapter = chapter,
            previousChapter = chapter
        )

    @Test
    fun roundTrip_savesAndLoads() {
        val prefs = FakePrefs()
        saveActiveAlerts(prefs, listOf(update("A", "c10"), update("B", "c3")))
        val loaded = loadActiveAlerts(prefs)
        assertEquals(setOf("A", "B"), loaded.map { it.novel.title }.toSet())
        assertEquals("c10", loaded.first { it.novel.title == "A" }.incomingChapter)
    }

    @Test
    fun save_mergesWithExistingAlertsById() {
        val prefs = FakePrefs()
        saveActiveAlerts(prefs, listOf(update("Old", "c1")))
        // A later check must not drop the still-unread older alert.
        saveActiveAlerts(prefs, listOf(update("New", "c9")))
        assertEquals(setOf("Old", "New"), loadActiveAlerts(prefs).map { it.novel.title }.toSet())
    }

    @Test
    fun save_sameSeriesReplacesOlderChapter() {
        val prefs = FakePrefs()
        saveActiveAlerts(prefs, listOf(update("A", "c1")))
        saveActiveAlerts(prefs, listOf(update("A", "c2")))
        val loaded = loadActiveAlerts(prefs)
        assertEquals(1, loaded.size)
        assertEquals("c2", loaded.first().incomingChapter)
    }

    @Test
    fun remove_dropsOnlyThatAlert() {
        val prefs = FakePrefs()
        val a = update("A", "c1")
        saveActiveAlerts(prefs, listOf(a, update("B", "c2")))
        removeActiveAlert(prefs, a.alertId())
        assertEquals(listOf("B"), loadActiveAlerts(prefs).map { it.novel.title })
    }

    @Test
    fun clear_emptiesTheStore() {
        val prefs = FakePrefs()
        saveActiveAlerts(prefs, listOf(update("A", "c1")))
        clearActiveAlerts(prefs)
        assertEquals(0, loadActiveAlerts(prefs).size)
    }

    @Test
    fun load_corruptJsonReturnsEmpty() {
        val prefs = FakePrefs()
        prefs.edit().putString("active_alerts", "{not json").apply()
        assertEquals(0, loadActiveAlerts(prefs).size)
    }

    @Test
    fun load_skipsBlankRows() {
        val prefs = FakePrefs()
        // Hand-written store with one incomplete row.
        prefs.edit().putString(
            "active_alerts",
            """[{"id":1,"t":"A","u":"https://x","c":"c1"},{"id":2,"t":"","u":"","c":"c2"}]"""
        ).apply()
        val loaded = loadActiveAlerts(prefs)
        assertEquals(listOf("A"), loaded.map { it.novel.title })
    }
}
