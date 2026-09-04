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
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0")
    connection.getInputStream().bufferedReader().use { it.readText() }
}

/**
 * Fetch RSS, find higher c-numbers, write those into the list cache,
 * notify only series that pass shouldNotify. Returns a status line for the UI.
 */
suspend fun runUpdateCheck(context: Context): String {
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    val rssUrl = prefs.getString("rss_url", "").orEmpty()
    if (rssUrl.isBlank()) return "Set your RSS URL in Settings"
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
        "Could not check feed: ${e.message}"
    }
}

/** WorkManager job: same as Check now, but it can run while the app is closed. */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runUpdateCheck(applicationContext)
        return Result.success()
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
