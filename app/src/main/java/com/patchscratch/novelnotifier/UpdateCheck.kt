package com.patchscratch.novelnotifier

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Closed-app chapter check.
 *
 * runUpdateCheck     — RSS only (no WebView). Used by Settings → Check now.
 * UpdateCheckWorker  — same path, run by WorkManager when the phone has a network.
 * scheduleUpdateChecks — unique periodic work named "nu_update_check".
 *
 * Android may batch the interval. 6 hours is a request, not a guarantee.
 * Needs implementation("androidx.work:work-runtime-ktx:2.9.1") in app/build.gradle.kts.
 */

/** Download the personal RSS XML off the main thread. */
suspend fun fetchRssXml(rssUrl: String): String = withContext(Dispatchers.IO) {
    val connection = URL(rssUrl).openConnection()
    connection.connectTimeout = 15_000
    connection.readTimeout = 20_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
    connection.getInputStream().bufferedReader().use { it.readText() }
}

/**
 * Fetch RSS, find higher c-numbers, write those into the list cache,
 * notify only series that pass shouldNotify. Returns a status line for the UI.
 * Without an RSS URL (rssnovelupdates.com no longer accepts new sign-ups),
 * falls back to the engine's paced list checks.
 */
suspend fun runUpdateCheck(context: Context): String {
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    // Cross-device read sync first: the type=read feed carries chapters the user
    // marked read anywhere, and mergeLastReads never rewinds local progress.
    val readUrl = prefs.getString("rss_read_url", "").orEmpty()
    if (readUrl.isNotBlank()) {
        try {
            val applied = applyReadFeed(
                prefs,
                parseRssItems(fetchRssXml(readUrl)).map { novelFromRssTitle(it.title) to it.pubDateMillis }
            )
            if (applied > 0) DebugLog.log("READSYNC", "applied $applied read chapters")
        } catch (e: Exception) {
            DebugLog.log("READSYNC", "read feed failed", e)
        }
    }
    val rssUrl = prefs.getString("rss_url", "").orEmpty()
    if (rssUrl.isBlank()) {
        val jobs = withContext(Dispatchers.IO) { SyncQueue.buildCheckQueue(context) }
        if (jobs.isEmpty()) return "No reading lists to check yet"
        SyncEngineService.startWith(context, jobs)
        return "Checking your reading lists in the background…"
    }
    return try {
        val xml = fetchRssXml(rssUrl)
        val rssNovels = parseRssTitles(xml).map(::novelFromRssTitle)
        if (rssNovels.isEmpty()) return "Feed loaded, but no titles found"
        val found = findChapterUpdates(context, rssNovels, loadLastRead(prefs))
        val notifyOff = loadIdSet(prefs, "list_notify_off")
        val seriesOff = loadIdSet(prefs, "series_notify_off")
        val toAlert = found.filter { shouldNotify(context, it.novel.title, notifyOff, seriesOff) }
        found.forEach { applyListChapter(context, it.novel.title, it.incomingChapter) }
        postChapterNotifications(context, toAlert)
        when {
            found.isEmpty() -> "No new chapters"
            toAlert.isEmpty() -> "New chapters found, all muted"
            else -> "Notified ${toAlert.size} series"
        }
    } catch (e: Exception) {
        DebugLog.log("RSS", "worker check failed", e)
        "Could not check feed: ${e.message}"
    }
}

/** WorkManager job: same as Check now, but it can run while the app is closed. */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Foreground so Android 12+ lets us run the WebView-based engine; if
        // the promotion itself is refused, fall back to the cheap paths only.
        try {
            withContext(Dispatchers.Main) { setForeground(foregroundInfo()) }
        } catch (e: Exception) {
            DebugLog.log("RSS", "worker could not go foreground", e)
        }
        runUpdateCheck(applicationContext)
        // A WebView-based check runs in the SyncEngineService; give it time to
        // drain (paced: one list page every 8 s), then resume any leftover
        // library download if one was stopped by a reboot or CF pause.
        val deadline = System.currentTimeMillis() + 15 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(5_000L)
            val pending = withContext(Dispatchers.IO) { SyncQueue.pending(applicationContext) }
            if (pending.isEmpty() || SyncEngineService.statusText(applicationContext).startsWith("Site check needed")) break
        }
        if (SyncQueue.pending(applicationContext).isNotEmpty() &&
            !SyncEngineService.running(applicationContext)
        ) {
            SyncEngineService.resume(applicationContext)
        }
        return Result.success()
    }

    private fun foregroundInfo(): androidx.work.ForegroundInfo {
        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    "chapter_check", "Chapter checks", android.app.NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, "chapter_check")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("NovelNotifier")
            .setContentText("Checking for new chapters…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 34) {
            androidx.work.ForegroundInfo(44, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(44, notification)
        }
    }
}

/** Replace the unique periodic work named "nu_update_check" with a new interval. */
fun scheduleUpdateChecks(context: Context, hours: Int) {
    val period = hours.coerceAtLeast(1).toLong()
    val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(period, TimeUnit.HOURS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "nu_update_check",
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )
}
