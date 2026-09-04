package com.patchscratch.novelnotifier

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Chapter notifications. Called from Settings → Check now and from UpdateCheckWorker.
 *
 * Rules:
 *   1. Incoming c-number must be HIGHER than list cache, series cache, and last-read.
 *   2. shouldNotify is false if the series bell is off, or every list that contains
 *      the title has Notify off.
 *   3. Alerts are grouped under CHAPTERS_GROUP so the shade shows one NovelNotifier banner.
 *   4. Equal or lower c-numbers never notify and never downgrade cache.
 */

const val CHAPTERS_CHANNEL = "chapters_channel"
const val CHAPTERS_GROUP = "com.patchscratch.novelnotifier.NEW_CHAPTERS"
private const val SUMMARY_ID = 1001

data class ChapterUpdate(
    val novel: Novel,
    val incomingChapter: String,
    val previousChapter: String
)

/** False if the series bell is off, or every list containing [title] has notify off. */
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

/**
 * One child notification per series (tag "chapter" + seriesUrl hash)
 * plus one InboxStyle summary so the shade shows a single NovelNotifier banner.
 */
fun postChapterNotifications(context: Context, updates: List<ChapterUpdate>) {
    if (updates.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureChapterChannel(context)
    val manager = NotificationManagerCompat.from(context)
    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    updates.forEach { update ->
        val id = update.novel.seriesUrl.ifBlank { update.novel.title }.hashCode()
        val tap = if (update.novel.seriesUrl.startsWith("http")) {
            PendingIntent.getActivity(
                context,
                id,
                Intent(Intent.ACTION_VIEW, Uri.parse(update.novel.seriesUrl)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            openApp
        }
        val item = NotificationCompat.Builder(context, CHAPTERS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(update.novel.title)
            .setContentText(update.incomingChapter)
            .setGroup(CHAPTERS_GROUP)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify("chapter", id, item)
    }
    val inbox = NotificationCompat.InboxStyle().setBigContentTitle("New chapters")
    updates.take(7).forEach { update ->
        inbox.addLine("${update.novel.title} — ${update.incomingChapter}")
    }
    if (updates.size > 7) inbox.addLine("+${updates.size - 7} more")
    val summary = NotificationCompat.Builder(context, CHAPTERS_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle("NovelNotifier")
        .setContentText("${updates.size} new chapter" + if (updates.size == 1) "" else "s")
        .setStyle(inbox)
        .setGroup(CHAPTERS_GROUP)
        .setGroupSummary(true)
        .setContentIntent(openApp)
        .setAutoCancel(true)
        .build()
    manager.notify(SUMMARY_ID, summary)
}
