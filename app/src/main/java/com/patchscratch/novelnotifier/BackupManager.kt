package com.patchscratch.novelnotifier

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Library backup as ONE zip, moved between devices with Android's file picker
 * (which reaches Google Drive, OneDrive, USB storage…). Stage C will add a
 * native Google Drive app-folder target writing the exact same zip.
 *
 * SECURITY CONTRACT (user decision 2026-10-09): a backup never contains the
 * Novel Updates login — no cookies, no credentials. A restored device must do
 * a fresh login; login stays persistent per-device afterwards as usual.
 */
object BackupManager {

    private const val MANIFEST = "manifest.json"
    private const val BACKUP_PREFS = "backup_prefs.json"

    /**
     * Prefs worth carrying to a new device. Deliberately absent: nu_cookies
     * (login) and rss_url (the feed link carries a private access token — it
     * is re-captured automatically from the reading-list page on first sync).
     */
    private val PORTED_KEYS = listOf(
        "check_hours",
        "list_notify_off", "list_update_off", "series_notify_off",
        "last_read", "read_titles",
        "layout_mode", "sort_mode",
        "theme_mode", "stack_notifications",
        "custom_theme_name",
        "kakuyomu_bookmarks",
        "hidden_lists", "syosetu_hidden_cats"
    )

    /** Pure so tests can verify the credentials-exclusion rule. */
    fun backupPrefsJson(values: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        PORTED_KEYS.forEach { key ->
            val value = values[key]
            when (value) {
                null -> {}
                is Int -> json.put(key, value)
                is Boolean -> json.put(key, value)
                is String -> json.put(key, value)
                is Set<*> -> json.put(key, org.json.JSONArray(value))
            }
        }
        return json
    }

    private fun snapshotCounts(context: Context): JSONObject {
        val snap = loadListSnapshot(context)
        return JSONObject()
            .put("series", snap.novelsByList.values.flatten().distinctBy { it.seriesUrl.ifBlank { it.title } }.size)
            .put("lists", snap.lists.size)
            .put("seriesCacheFiles", context.filesDir.resolve("series_cache").listFiles()?.size ?: 0)
            .put("coverFiles", CoverStore.count(context))
    }

    /** Pure so tests can verify the crash rule: only STRING_SET_KEYS may be
     *  read as sets — reading a String/Boolean key with getStringSet throws
     *  ClassCastException (shipped bug: v1.1..v1.2 crashed Back up library
     *  for anyone with a theme set; caught while re-signing devices). */
    private val STRING_SET_KEYS = listOf(
        "list_notify_off", "list_update_off", "series_notify_off",
        "last_read", "read_titles"
    )

    fun backupPrefValues(prefs: android.content.SharedPreferences): Map<String, Any?> = buildMap {
        put("check_hours", prefs.getInt("check_hours", 12))
        STRING_SET_KEYS.forEach { put(it, prefs.getStringSet(it, null)) }
        put("layout_mode", prefs.getString("layout_mode", null))
        put("sort_mode", prefs.getString("sort_mode", null))
        put("theme_mode", prefs.getString("theme_mode", null))
        put("stack_notifications", prefs.getBoolean("stack_notifications", true))
        put("custom_theme_name", prefs.getString("custom_theme_name", null))
    }

    suspend fun export(context: Context, uri: android.net.Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: return@withContext "Could not open the destination"
        val manifest = JSONObject()
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("app", context.packageName)
            .put("containsLogin", false)
            .put("counts", snapshotCounts(context))
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        val prefValues = backupPrefValues(prefs)
        val customThemeFile = context.filesDir.resolve("custom_theme.json")
        ZipOutputStream(output.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, manifest.toString().toByteArray())
            put(BACKUP_PREFS, backupPrefsJson(prefValues).toString().toByteArray())
            listOfNotNull(context.filesDir.resolve("list_cache.json").takeIf { it.exists() }).forEach {
                put("data/list_cache.json", it.readBytes())
            }
            listOfNotNull(customThemeFile.takeIf { it.exists() }).forEach {
                put("data/custom_theme.json", it.readBytes())
            }
            // Client-side Kakuyomu categories (assignments + hidden flags).
            listOfNotNull(context.filesDir.resolve("categories.json").takeIf { it.exists() }).forEach {
                put("data/categories.json", it.readBytes())
            }
            // Translator-host probe census (diagnostics; regenerable but handy).
            listOfNotNull(context.filesDir.resolve(HostProbeService.REPORT).takeIf { it.exists() }).forEach {
                put("data/" + HostProbeService.REPORT, it.readBytes())
            }
            // JP notification baselines (B3): Kakuyomu follows stamps —
            // without them a restore would re-seed and miss an update.
            listOfNotNull(context.filesDir.resolve("jp_notify_state.json").takeIf { it.exists() }).forEach {
                put("data/jp_notify_state.json", it.readBytes())
            }
            context.filesDir.resolve("series_cache").listFiles().orEmpty().forEach {
                put("data/series_cache/${it.name}", it.readBytes())
            }
            CoverStore.dir(context).listFiles().orEmpty().forEach {
                put("data/covers/${it.name}", it.readBytes())
            }
            // AI-provenance markers ride along so restored devices know
            // which covers are ours (Regenerate vs Generate).
            context.filesDir.resolve("covers_ai").listFiles().orEmpty().forEach {
                put("data/covers_ai/${it.name}", it.readBytes())
            }
        }
        DebugLog.log("BACKUP", "exported ${manifest.optJSONObject("counts")}")
        "Backup saved — ${CoverStore.count(context)} covers, ${manifest.optJSONObject("counts")?.optInt("seriesCacheFiles") ?: 0} series caches"
    }

    suspend fun restore(context: Context, uri: android.net.Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext "Could not open the backup"
        var restoredFiles = 0
        var restoredCovers = 0
        var sawManifest = false
        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == MANIFEST -> sawManifest = true
                    name == BACKUP_PREFS -> applyPrefs(context, zip.readBytes())
                    name == "data/list_cache.json" -> {
                        context.filesDir.resolve("list_cache.json").writeBytes(zip.readBytes())
                        restoredFiles++
                    }
                    name == "data/custom_theme.json" -> {
                        context.filesDir.resolve("custom_theme.json").writeBytes(zip.readBytes())
                        restoredFiles++
                    }
                    name == "data/categories.json" -> {
                        context.filesDir.resolve("categories.json").writeBytes(zip.readBytes())
                        restoredFiles++
                    }
                    name == "data/" + HostProbeService.REPORT -> {
                        context.filesDir.resolve(HostProbeService.REPORT).writeBytes(zip.readBytes())
                        restoredFiles++
                    }
                    name == "data/jp_notify_state.json" -> {
                        context.filesDir.resolve("jp_notify_state.json").writeBytes(zip.readBytes())
                        restoredFiles++
                    }
                    name.startsWith("data/series_cache/") -> {
                        val target = context.filesDir.resolve("series_cache").apply { mkdirs() }
                            .resolve(name.substringAfterLast('/'))
                        target.writeBytes(zip.readBytes())
                        restoredFiles++
                    }
                    name.startsWith("data/covers/") -> {
                        val target = CoverStore.dir(context).resolve(name.substringAfterLast('/'))
                        target.writeBytes(zip.readBytes())
                        restoredCovers++
                    }
                    name.startsWith("data/covers_ai/") -> {
                        val target = context.filesDir.resolve("covers_ai").apply { mkdirs() }
                            .resolve(name.substringAfterLast('/'))
                        target.writeBytes(zip.readBytes())
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!sawManifest) return@withContext "That file is not a NovelNotifier backup"
        DebugLog.log("BACKUP", "restored files=$restoredFiles covers=$restoredCovers")
        "Restored $restoredFiles caches and $restoredCovers covers — log in to finish"
    }

    // --- covers-only transfer (phone ↔ tablet sync) ----------------------------

    /**
     * A small zip of just covers/ + covers_ai/ — the lightweight way to push
     * generated art (and any covers) to another device without touching the
     * library. Site-fetched covers also self-sync via "Prefetch missing
     * covers"; only generated art has no upstream to re-download.
     */
    suspend fun exportCovers(context: Context, uri: android.net.Uri): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri)
                ?: return@withContext "Could not open the destination"
            var n = 0
            ZipOutputStream(output.buffered()).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                put(MANIFEST, JSONObject()
                    .put("version", 1)
                    .put("app", context.packageName)
                    .put("coversOnly", true)
                    .toString().toByteArray())
                CoverStore.dir(context).listFiles().orEmpty().forEach {
                    put("data/covers/${it.name}", it.readBytes()); n++
                }
                context.filesDir.resolve("covers_ai").listFiles().orEmpty().forEach {
                    put("data/covers_ai/${it.name}", it.readBytes())
                }
            }
            DebugLog.log("COVERS", "exported $n covers")
            "Covers shared — $n cover files"
        }

    /** Merge a covers zip in (never deletes; existing files are replaced). */
    suspend fun importCovers(context: Context, uri: android.net.Uri): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext "Could not open the file"
            var covers = 0
            var sawManifest = false
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == MANIFEST -> sawManifest = true
                        name.startsWith("data/covers/") -> {
                            CoverStore.dir(context).resolve(name.substringAfterLast('/'))
                                .writeBytes(zip.readBytes())
                            covers++
                        }
                        name.startsWith("data/covers_ai/") -> {
                            context.filesDir.resolve("covers_ai").apply { mkdirs() }
                                .resolve(name.substringAfterLast('/')).writeBytes(zip.readBytes())
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!sawManifest) return@withContext "That file is not a NovelNotifier covers file"
            DebugLog.log("COVERS", "imported $covers covers")
            "Imported $covers covers"
        }

    private fun applyPrefs(context: Context, bytes: ByteArray) {
        val json = JSONObject(String(bytes))
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            json.keys().forEach { key ->
                when (key) {
                    "check_hours" -> putInt(key, json.optInt(key, 12))
                    "layout_mode", "sort_mode", "theme_mode", "custom_theme_name" ->
                        putString(key, json.optString(key))
                    "stack_notifications" -> putBoolean(key, json.optBoolean(key, true))
                    else -> {
                        val arr = json.optJSONArray(key)
                        if (arr != null) {
                            putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
                        }
                    }
                }
            }
        }
    }
}
