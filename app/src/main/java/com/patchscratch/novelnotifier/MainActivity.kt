package com.patchscratch.novelnotifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.patchscratch.novelnotifier.ui.theme.NovelNotifierTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.time.Duration.Companion.seconds

/**
 * App entry + navigation. This file does not draw the list or series page.
 *
 * MainActivity — Android launcher. Calls NovelApp().
 * NovelApp     — holds shared state (RSS, prefs, queues) and picks one screen:
 *                Login, Settings, Edit Lists, reading list, or series detail.
 *
 * Data flow:
 *   SharedPreferences (Prefs.kt) + JSON files (Cache.kt)
 *   → RSS fetch here
 *   → NovelListScreen / NovelDetailScreen
 *   → CoverPrefetchService / UpdateCheckWorker for background work
 *
 * getValue / setValue are used by the `by` keyword (property delegates).
 * Android Studio may grey them out — they are not unused. Do not delete them.
 */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovelNotifierTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovelApp()
                }
            }
        }
    }
}

@Composable
fun NovelApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { applySavedCookies(prefs) }

    // --- persisted UI state ---
    var novels by remember { mutableStateOf(emptyList<Novel>()) }
    var statusMessage by remember { mutableStateOf("Set your RSS URL in Settings") }
    var selectedNovel by remember { mutableStateOf<Novel?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLoginWeb by remember { mutableStateOf(false) }
    var showEditLists by remember { mutableStateOf(false) }
    var readingLists by remember { mutableStateOf(emptyList<ReadingList>()) }
    var readTitles by remember {
        mutableStateOf(prefs.getStringSet("read_titles", emptySet()) ?: emptySet())
    }
    var lastRead by remember { mutableStateOf(loadLastRead(prefs)) }
    var coverCache by remember { mutableStateOf(loadCoverCache(prefs)) }
    var notifyOff by remember { mutableStateOf(loadIdSet(prefs, "list_notify_off")) }
    var updateOff by remember { mutableStateOf(loadIdSet(prefs, "list_update_off")) }
    var seriesNotifyOff by remember { mutableStateOf(loadIdSet(prefs, "series_notify_off")) }
    var rssUrl by remember { mutableStateOf(prefs.getString("rss_url", "") ?: "") }
    var checkHours by remember { mutableIntStateOf(prefs.getInt("check_hours", 12)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var chapterFetchQueue by remember { mutableStateOf(emptyList<Novel>()) }
    var coverPrefetchStatus by remember { mutableStateOf(CoverPrefetchService.statusText(context)) }
    var loginState by remember {
        mutableStateOf(
            if (prefs.getString("nu_cookies", "").isNullOrBlank()) "Not logged in"
            else "Logged in to Novel Updates"
        )
    }

    // Android 13+ must grant POST_NOTIFICATIONS before the cover service can show its bar.
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < 33) {
            CoverPrefetchService.startAll(context, coverCache, updateOff)
            coverPrefetchStatus = CoverPrefetchService.statusText(context)
            if (coverPrefetchStatus != "Idle") statusMessage = coverPrefetchStatus
        } else {
            coverPrefetchStatus = "Notification permission needed for background prefetch"
        }
    }

    // Cover service writes status to prefs. Poll so Settings can show "197 covers left".
    LaunchedEffect(Unit) {
        while (true) {
            delay(2.seconds)
            val latest = CoverPrefetchService.statusText(context)
            if (latest != coverPrefetchStatus) coverPrefetchStatus = latest
            coverCache = loadCoverCache(prefs)
        }
    }

    fun rememberCover(title: String, url: String) {
        if (url.isBlank()) return
        val updated = coverCache + (title to url)
        coverCache = updated
        saveCoverCache(prefs, updated)
        selectedNovel = selectedNovel?.let {
            if (it.title == title) it.copy(coverUrl = url) else it
        }
    }

    // RSS is the cheap change detector. Pull-to-refresh and Check now bump refreshKey.
    LaunchedEffect(rssUrl, refreshKey) {
        if (rssUrl.isBlank()) {
            statusMessage = "Set your RSS URL in Settings"
            novels = emptyList()
            isRefreshing = false
            return@LaunchedEffect
        }
        isRefreshing = true
        statusMessage = "Loading Novel Updates feed..."
        try {
            val xml = withContext(Dispatchers.IO) {
                val connection = URL(rssUrl).openConnection()
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"
                )
                connection.getInputStream().bufferedReader().use { it.readText() }
            }
            val titles = parseRssTitles(xml)
            if (titles.isNotEmpty()) {
                novels = titles.take(20).map { rawTitle ->
                    novelFromRssTitle(rawTitle, coverCache[rawTitle].orEmpty()).let { parsed ->
                        parsed.copy(coverUrl = coverCache[parsed.title].orEmpty())
                    }
                }
                val due = rssUpdatesToFetch(context, novels)
                chapterFetchQueue = due
                statusMessage = if (due.isEmpty()) {
                    "Loaded Novel Updates feed (${titles.size} items)"
                } else {
                    "Feed loaded · ${due.size} series have new chapters"
                }
            } else {
                statusMessage = "Feed loaded, but no titles found"
            }
        } catch (e: Exception) {
            statusMessage = "Could not load feed: ${e.message}"
        }
        isRefreshing = false
    }

    fun markAsRead(title: String) {
        val updated = readTitles + title
        readTitles = updated
        prefs.edit { putStringSet("read_titles", updated) }
    }

    fun setLastRead(title: String, chapter: String, url: String, read: Boolean) {
        lastRead = if (read) lastRead + (title to (chapter to url)) else lastRead - title
        prefs.edit {
            putStringSet(
                "last_read",
                lastRead.map { "${it.key}|||${it.value.first}|||${it.value.second}" }.toSet()
            )
        }
    }

    fun toggleSeriesNotify(title: String) {
        seriesNotifyOff = if (title in seriesNotifyOff) seriesNotifyOff - title else seriesNotifyOff + title
        prefs.edit { putStringSet("series_notify_off", seriesNotifyOff) }
    }

    fun saveSettings(newUrl: String, newHours: Int) {
        rssUrl = newUrl.trim()
        checkHours = newHours
        prefs.edit {
            putString("rss_url", rssUrl)
            putInt("check_hours", checkHours)
        }
        showSettings = false
        scheduleUpdateChecks(context, checkHours)
    }

    LaunchedEffect(checkHours) {
        scheduleUpdateChecks(context, checkHours)
    }

    fun onLoggedIn(cookieHeader: String) {
        loginState = "Logged in to Novel Updates"
        prefs.edit { putString("nu_cookies", cookieHeader) }
        showLoginWeb = false
        showSettings = true
    }

    fun onLoggedOut() {
        loginState = "Not logged in"
        prefs.edit { remove("nu_cookies") }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    val selectedWithCover = selectedNovel?.let { novel ->
        val cached = coverCache[novel.title].orEmpty()
        if (cached.isNotBlank() && novel.coverUrl.isBlank()) novel.copy(coverUrl = cached) else novel
    }

    // Only one screen is composed at a time. Order matters: login overlays settings.
    when {
        showLoginWeb -> LoginWebScreen(
            onLoggedIn = { cookies -> onLoggedIn(cookies) },
            onCancel = {
                showLoginWeb = false
                showSettings = true
            }
        )
        showSettings -> SettingsScreen(
            currentUrl = rssUrl,
            currentHours = checkHours,
            loginState = loginState,
            coverPrefetchStatus = coverPrefetchStatus,
            onStartCoverPrefetch = {
                val startJob = {
                    CoverPrefetchService.startAll(context, coverCache, updateOff)
                    coverPrefetchStatus = CoverPrefetchService.statusText(context)
                    if (coverPrefetchStatus != "Idle") statusMessage = coverPrefetchStatus
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startJob()
                }
            },
            onStopCoverPrefetch = {
                CoverPrefetchService.stop(context)
                coverPrefetchStatus = "Stopped"
            },
            onCheckNow = {
                showSettings = false
                scope.launch {
                    statusMessage = "Checking feed..."
                    statusMessage = runUpdateCheck(context)
                    refreshKey += 1
                }
            },
            onSave = { url, hours -> saveSettings(url, hours) },
            onStartLogin = {
                showSettings = false
                showLoginWeb = true
            },
            onLoggedOut = { onLoggedOut() },
            onCancel = { showSettings = false }
        )
        showEditLists -> EditListsScreen(onBack = { showEditLists = false })
        selectedWithCover == null -> {
            if (chapterFetchQueue.isNotEmpty()) {
                SeriesChapterFetcher(
                    queue = chapterFetchQueue,
                    onFinished = { title, ok ->
                        chapterFetchQueue = chapterFetchQueue.filterNot { it.title == title }
                        if (ok) statusMessage = "Merged new chapters for $title"
                    }
                )
            }
            NovelListScreen(
                rssNovels = novels,
                rssStatus = statusMessage,
                checkHours = checkHours,
                loginState = loginState,
                readTitles = readTitles,
                lastRead = lastRead,
                coverCache = coverCache,
                notifyOff = notifyOff,
                updateOff = updateOff,
                isRefreshing = isRefreshing,
                onRefresh = { refreshKey += 1 },
                onNovelClick = { novel -> selectedNovel = novel },
                onCoverFound = { title, url -> rememberCover(title, url) },
                onListsLoaded = { parsed -> if (parsed.isNotEmpty()) readingLists = parsed },
                onOpenSettings = { showSettings = true },
                onEditLists = { showEditLists = true },
                onSaveListOptions = { newNotifyOff, newUpdateOff ->
                    notifyOff = newNotifyOff
                    updateOff = newUpdateOff
                    prefs.edit {
                        putStringSet("list_notify_off", newNotifyOff)
                        putStringSet("list_update_off", newUpdateOff)
                    }
                },
                pauseLocalCovers = coverPrefetchStatus.contains("left") ||
                    coverPrefetchStatus.startsWith("Queued") ||
                    coverPrefetchStatus.contains("list")
            )
        }
        else -> NovelDetailScreen(
            novel = selectedWithCover,
            lastRead = lastRead[selectedWithCover.title],
            notifyOn = selectedWithCover.title !in seriesNotifyOff,
            availableLists = readingLists,
            onMarkRead = { markAsRead(selectedWithCover.title) },
            onSetLastRead = { chapter, url, read ->
                setLastRead(selectedWithCover.title, chapter, url, read)
            },
            onToggleNotify = { toggleSeriesNotify(selectedWithCover.title) },
            onCoverFound = { url -> rememberCover(selectedWithCover.title, url) },
            onBackClick = { selectedNovel = null }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun NovelAppPreview() {
    NovelNotifierTheme {
        NovelApp()
    }
}
