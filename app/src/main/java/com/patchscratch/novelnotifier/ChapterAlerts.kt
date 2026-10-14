package com.patchscratch.novelnotifier

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chapter notifications. Called from Settings → Check now, UpdateCheckWorker,
 * and BootReceiver (re-post after reboot / app update).
 *
 * Rules:
 *   1. Incoming c-number must be HIGHER than list cache, series cache, and last-read.
 *   2. shouldNotify is false if the series bell is off, or every list that contains
 *      the title has Notify off.
 *   3. With the stack toggle on (default), alerts are grouped under CHAPTERS_GROUP
 *      so the shade shows one NovelNotifier banner; with it off each series posts
 *      a standalone notification.
 *   4. Equal or lower c-numbers never notify and never downgrade cache.
 *   5. Tapping an alert deep-links into the series detail screen (not the browser).
 *   6. Un-dismissed alerts are persisted in the prefs; BootReceiver re-posts them
 *      silently so a reboot does not eat chapters the user has not seen yet
 *      (the original app's NotificationClearedReceiver pattern, reversed).
 */

const val CHAPTERS_CHANNEL = "chapters_channel"
const val CHAPTERS_GROUP = "com.patchscratch.novelnotifier.NEW_CHAPTERS"
const val ACTION_ALERT_DISMISSED = "com.patchscratch.novelnotifier.ALERT_DISMISSED"
const val ACTION_ALERTS_CLEARED = "com.patchscratch.novelnotifier.ALERTS_CLEARED"
const val EXTRA_ALERT_ID = "alert_id"
private const val SUMMARY_ID = 1001
private const val PREF_ACTIVE_ALERTS = "active_alerts"

/** Intent extras MainActivity reads to open the detail screen. */
const val EXTRA_OPEN_SERIES_URL = "open_series_url"
const val EXTRA_OPEN_SERIES_TITLE = "open_series_title"
const val EXTRA_OPEN_SERIES_CHAPTER = "open_series_chapter"

data class ChapterUpdate(
    val novel: Novel,
    val incomingChapter: String,
    val previousChapter: String
)

/** Stable per-series notification id (also the key in the persisted alert store). */
fun ChapterUpdate.alertId(): Int = novel.seriesUrl.ifBlank { novel.title }.hashCode()

/** False if the series bell is off, every list containing [title] has
 *  notify off, or the series lives in a hidden list (SFW mode — hidden
 *  series never notify, since checks run while the app is backgrounded
 *  and therefore locked). */
fun shouldNotify(
    context: Context,
    title: String,
    notifyOff: Set<String>,
    seriesNotifyOff: Set<String>
): Boolean {
    if (title in seriesNotifyOff) return false
    val snap = loadListSnapshot(context)
    val listIds = snap.novelsByList.filterValues { rows ->
        rows.any { it.title.equals(title, ignoreCase = true) }
    }.keys
    if (listIds.isEmpty()) return true
    if (listIds.any { it in loadIdSet(context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE), "hidden_lists") }) {
        return false
    }
    return listIds.any { it !in notifyOff }
}

/** Highest c-number we already know: series cache, list row, or last-read tick. */
fun knownChapterNum(context: Context, match: Novel, lastReadChapter: String?): Int {
    val savedPage = chapterNum(cachedChapter(context, match.seriesUrl))
    val savedList = chapterNum(match.latestChapter)
    val savedRead = chapterNum(lastReadChapter.orEmpty())
    return maxOf(savedPage, savedList, savedRead)
}

/** RSS rows whose c-number is higher than anything we already stored. */
fun findChapterUpdates(
    context: Context,
    rssNovels: List<Novel>,
    lastRead: Map<String, Pair<String, String>>
): List<ChapterUpdate> {
    val known = loadListSnapshot(context).novelsByList.values.flatten()
        .distinctBy { it.title.lowercase() }
        .associateBy { it.title.lowercase() }
    return rssNovels.mapNotNull { rss ->
        val match = known[rss.title.lowercase()] ?: return@mapNotNull null
        val incoming = chapterNum(rss.latestChapter)
        if (incoming < 0) return@mapNotNull null
        val saved = knownChapterNum(context, match, lastRead[match.title]?.first)
        if (saved > 0 && incoming <= saved) return@mapNotNull null
        ChapterUpdate(
            novel = match.copy(latestChapter = rss.latestChapter),
            incomingChapter = rss.latestChapter,
            previousChapter = if (saved > 0) "c$saved" else match.latestChapter
        )
    }.distinctBy { it.novel.title }
}

/** Create the "New chapters" notification channel (required on Android 8+). */
fun ensureChapterChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        CHAPTERS_CHANNEL,
        "New chapters",
        NotificationManager.IMPORTANCE_DEFAULT
    )
    channel.description = "Grouped Novel Updates chapter alerts"
    manager.createNotificationChannel(channel)
}

// --- persisted alert store (boot recovery) ---------------------------------
// SharedPreferences keeps the un-dismissed alerts. A swipe / tap-dismiss sends
// ACTION_ALERT_DISMISSED (one id) or ACTION_ALERTS_CLEARED (summary); BootReceiver
// re-posts whatever is left. saveActiveAlerts MERGES by id so a new check never
// drops an older alert that is still sitting in the shade.

fun saveActiveAlerts(prefs: SharedPreferences, updates: List<ChapterUpdate>) {
    val stored = loadActiveAlertIds(prefs).toMutableMap()
    updates.forEach { update -> stored[update.alertId()] = update }
    val json = JSONArray().apply {
        stored.values.forEach { update ->
            put(
                JSONObject()
                    .put("id", update.alertId())
                    .put("t", update.novel.title)
                    .put("u", update.novel.seriesUrl)
                    .put("c", update.incomingChapter)
            )
        }
    }
    prefs.edit { putString(PREF_ACTIVE_ALERTS, json.toString()) }
}

/** Alerts still believed to be in the shade (previousChapter is not needed to re-post). */
fun loadActiveAlerts(prefs: SharedPreferences): List<ChapterUpdate> {
    val raw = prefs.getString(PREF_ACTIVE_ALERTS, "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return try {
        val json = JSONArray(raw)
        (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("t")
            val chapter = item.optString("c")
            if (title.isBlank() || chapter.isBlank()) return@mapNotNull null
            ChapterUpdate(
                novel = Novel(title = title, latestChapter = chapter, seriesUrl = item.optString("u")),
                incomingChapter = chapter,
                previousChapter = chapter
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun loadActiveAlertIds(prefs: SharedPreferences): Map<Int, ChapterUpdate> =
    loadActiveAlerts(prefs).associateBy { it.alertId() }

fun removeActiveAlert(prefs: SharedPreferences, id: Int) {
    val kept = loadActiveAlertIds(prefs).filterKeys { it != id }.values.toList()
    writeActiveAlerts(prefs, kept)
}

fun clearActiveAlerts(prefs: SharedPreferences) {
    prefs.edit { remove(PREF_ACTIVE_ALERTS) }
}

private fun writeActiveAlerts(prefs: SharedPreferences, updates: List<ChapterUpdate>) {
    if (updates.isEmpty()) {
        prefs.edit { remove(PREF_ACTIVE_ALERTS) }
        return
    }
    val json = JSONArray()
    updates.forEach { update ->
        json.put(
            JSONObject()
                .put("id", update.alertId())
                .put("t", update.novel.title)
                .put("u", update.novel.seriesUrl)
                .put("c", update.incomingChapter)
        )
    }
    prefs.edit { putString(PREF_ACTIVE_ALERTS, json.toString()) }
}

// --- notifications -----------------------------------------------------------

/**
 * One notification per series plus (when stacking is on) one InboxStyle summary
 * so the shade shows a single NovelNotifier banner. [silent] is used by boot
 * recovery: re-posted alerts must not chime again.
 */
fun postChapterNotifications(context: Context, updates: List<ChapterUpdate>, silent: Boolean = false) {
    if (updates.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureChapterChannel(context)
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    val stack = loadStackAlerts(prefs)
    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    updates.forEach { update ->
        val id = update.alertId()
        // Tap opens the series inside the app; MainActivity forwards the extras
        // to NovelApp, which shows NovelDetailScreen for this series.
        val tap = if (update.novel.seriesUrl.startsWith("http")) {
            PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OPEN_SERIES_URL, update.novel.seriesUrl)
                    .putExtra(EXTRA_OPEN_SERIES_TITLE, update.novel.title)
                    .putExtra(EXTRA_OPEN_SERIES_CHAPTER, update.incomingChapter),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            openApp
        }
        // Swipe / tap-dismiss: drop this alert from the boot-recovery store.
        val dismissed = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, AlertDismissedReceiver::class.java)
                .setAction(ACTION_ALERT_DISMISSED)
                .setData(Uri.parse("alert:$id"))
                .putExtra(EXTRA_ALERT_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHAPTERS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(update.novel.title)
            .setContentText(update.incomingChapter)
            .setContentIntent(tap)
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
        if (silent) builder.setSilent(true)
        if (stack) builder.setGroup(CHAPTERS_GROUP)
        manager(context).notify("chapter", id, builder.build())
    }
    if (!stack) {
        saveActiveAlerts(prefs, updates)
        return
    }
    val inbox = NotificationCompat.InboxStyle().setBigContentTitle("New chapters")
    updates.take(7).forEach { update ->
        inbox.addLine("${update.novel.title} — ${update.incomingChapter}")
    }
    if (updates.size > 7) inbox.addLine("+${updates.size - 7} more")
    val cleared = PendingIntent.getBroadcast(
        context,
        SUMMARY_ID,
        Intent(context, AlertDismissedReceiver::class.java)
            .setAction(ACTION_ALERTS_CLEARED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val summary = NotificationCompat.Builder(context, CHAPTERS_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle("NovelNotifier")
        .setContentText("${updates.size} new chapter" + if (updates.size == 1) "" else "s")
        .setStyle(inbox)
        .setGroup(CHAPTERS_GROUP)
        .setGroupSummary(true)
        .setContentIntent(openApp)
        .setDeleteIntent(cleared)
        .setAutoCancel(true)
    if (silent) summary.setSilent(true)
    manager(context).notify(SUMMARY_ID, summary.build())
    saveActiveAlerts(prefs, updates)
}

private fun manager(context: Context) = NotificationManagerCompat.from(context)
