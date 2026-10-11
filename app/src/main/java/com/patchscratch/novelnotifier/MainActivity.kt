package com.patchscratch.novelnotifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
 *   → SyncEngineService / UpdateCheckWorker for background work
 *
 * getValue / setValue are used by the `by` keyword (property delegates).
 * Android Studio may grey them out — they are not unused. Do not delete them.
 */

class MainActivity : ComponentActivity() {

    /** A notification tap asking for a series detail page. */
    data class SeriesDeepLink(val url: String, val title: String, val chapter: String)

    companion object {
        /** Set when a notification asks us to open the login screen (session expired). */
        @Volatile var openLoginRequested = false

        /** Set by `am start --ez probe_endpoints true` — the temporary Phase-2 spike. */
        @Volatile var probeRequested = false

        /**
         * Chapter-alert deep links. Notifications carry open_series_* extras;
         * they may arrive before composition (cold start) or while running
         * (onNewIntent), so a SharedFlow bridges both into NovelApp. No replay:
         * a restored process must not reopen a stale series.
         */
        private val seriesRequests =
            kotlinx.coroutines.flow.MutableSharedFlow<SeriesDeepLink>(extraBufferCapacity = 4)
        val openSeriesRequests = seriesRequests
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra("open_login", false) == true) openLoginRequested = true
        if (intent?.getBooleanExtra("probe_endpoints", false) == true) probeRequested = true
        handleSeriesDeepLink(intent)
        setContent {
            // Hoisted so Settings can re-theme the whole app without a restart.
            var themeMode by remember {
                mutableStateOf(loadThemeMode(getSharedPreferences("novel_prefs", MODE_PRIVATE)))
            }
            NovelNotifierTheme(mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovelApp(onThemeChange = { themeMode = it })
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_login", false)) openLoginRequested = true
        if (intent.getBooleanExtra("probe_endpoints", false)) probeRequested = true
        handleSeriesDeepLink(intent)
    }

    private fun handleSeriesDeepLink(intent: android.content.Intent?) {
        val url = intent?.getStringExtra(EXTRA_OPEN_SERIES_URL) ?: return
        seriesRequests.tryEmit(
            SeriesDeepLink(
                url = url,
                title = intent.getStringExtra(EXTRA_OPEN_SERIES_TITLE).orEmpty(),
                chapter = intent.getStringExtra(EXTRA_OPEN_SERIES_CHAPTER).orEmpty()
            )
        )
    }
}

@Composable
fun NovelApp(onThemeChange: (String) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { applySavedCookies(prefs) }

    // --- persisted UI state ---
    var novels by remember { mutableStateOf(emptyList<Novel>()) }
    var statusMessage by remember { mutableStateOf("Log in from Settings, then pull down to load your lists") }
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
    var rssRetries by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var chapterFetchQueue by remember { mutableStateOf(emptyList<Novel>()) }
    var syncStatus by remember { mutableStateOf(SyncEngineService.statusText(context)) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showDiscover by remember { mutableStateOf(false) }
    var probeMode by remember { mutableStateOf(false) } // adb-only Phase-2 spike (EndpointProbe.kt)
    var loginState by remember {
        mutableStateOf(
            if (prefs.getString("nu_cookies", "").isNullOrBlank()) "Not logged in"
            else "Logged in to Novel Updates"
        )
    }

    // Settings → Prefetch missing covers: cover-only queue on the sync engine.
    fun startCoverSync() {
        scope.launch {
            val jobs = withContext(Dispatchers.IO) {
                SyncQueue.build(context, priorityListIds = emptySet(), coversOnly = true)
            }
            if (jobs.isEmpty()) {
                syncStatus = "All cached series already have covers"
            } else {
                SyncEngineService.startWith(context, jobs)
                syncStatus = SyncEngineService.statusText(context)
                if (syncStatus != "Idle") statusMessage = syncStatus
            }
        }
    }

    // Android 13+ must grant POST_NOTIFICATIONS before the sync engine can show its counter.
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < 33) {
            startCoverSync()
        } else {
            syncStatus = "Notification permission needed for background sync"
        }
    }

    // Fresh login + empty library + onboarding not done yet → guide the first download.
    LaunchedEffect(Unit) {
        val prefsSnapshot = withContext(Dispatchers.IO) {
            val snap = loadListSnapshot(context)
            Triple(
                snap.lists.isNotEmpty(),
                snap.novelsByList.values.any { it.isNotEmpty() },
                prefs.getBoolean("onboarding_done", false)
            )
        }
        val (hasLists, hasRows, onboarded) = prefsSnapshot
        val loggedOut = prefs.getString("nu_cookies", "").isNullOrBlank()
        showOnboarding = !loggedOut && !onboarded && !(hasLists && hasRows)
    }

    // The engine writes status to prefs. Poll so Settings can show the counter,
    // and auto-resume a queue whose service died mid-run (reboot / task killer /
    // CF pause) — but only while the app is in the FOREGROUND: Android 12+
    // crashes background apps that start foreground services.
    var appForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            appForeground = event != androidx.lifecycle.Lifecycle.Event.ON_STOP
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2.seconds)
            val latest = SyncEngineService.statusText(context)
            if (latest != syncStatus) syncStatus = latest
            if (appForeground && latest.startsWith("Running") && !SyncEngineService.engineAlive) {
                val pending = withContext(Dispatchers.IO) { SyncQueue.pending(context) }
                if (pending.isNotEmpty()) SyncEngineService.resume(context)
            }
            // The session-expired notification asks for the login screen.
            if (MainActivity.openLoginRequested) {
                MainActivity.openLoginRequested = false
                showLoginWeb = true
            }
            if (MainActivity.probeRequested) {
                MainActivity.probeRequested = false
                probeMode = true
            }
            coverCache = loadCoverCache(prefs)
        }
    }

    fun rememberCover(title: String, url: String) {
        if (url.isBlank() || isPlaceholderCover(url)) return
        val updated = coverCache + (title to url)
        coverCache = updated
        saveCoverCache(prefs, updated)
        selectedNovel = selectedNovel?.let {
            if (it.title == title) it.copy(coverUrl = url) else it
        }
    }

    // Tapping a chapter alert lands here: open that series' detail page. The
    // detail screen loads everything else (lists, releases, cover) itself, so
    // a title + URL + chapter is enough to construct the entry Novel. Login is
    // left alone — never yank the user out of an auth flow.
    LaunchedEffect(Unit) {
        MainActivity.openSeriesRequests.collect { link ->
            showSettings = false
            showSearch = false
            showDiscover = false
            showEditLists = false
            showOnboarding = false
            selectedNovel = Novel(
                title = link.title,
                latestChapter = link.chapter,
                seriesUrl = link.url,
                coverUrl = coverCache[link.title].orEmpty()
            )
        }
    }

    // RSS is the cheap change detector. Pull-to-refresh and Check now bump refreshKey.
        LaunchedEffect(rssUrl, refreshKey) {
        if (rssUrl.isBlank()) {
            // No RSS feed configured — the home list loads from the site cache
            // and background checks walk the reading lists instead (see UpdateCheck).
            novels = emptyList()
            isRefreshing = false
            return@LaunchedEffect
        }
        isRefreshing = true
        statusMessage = "Loading Novel Updates feed..."
        try {
            val xml = withContext(Dispatchers.IO) {
                val connection = URL(rssUrl).openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"
                )
                connection.getInputStream().bufferedReader().use { it.readText() }
            }
            val titles = parseRssTitles(xml)
            DebugLog.log("RSS", "fetched ${titles.size} items")
            rssRetries = 0
            if (titles.isNotEmpty()) {
                novels = titles.take(20).map { rawTitle ->
                    val parsed = novelFromRssTitle(rawTitle)
                    parsed.copy(coverUrl = coverCache[parsed.title].orEmpty())
                }
                val due = withContext(Dispatchers.IO) { rssUpdatesToFetch(context, novels) }
                chapterFetchQueue = due
                // Cross-device read sync: apply the read-activity feed alongside.
                val readUrl = prefs.getString("rss_read_url", "").orEmpty()
                if (readUrl.isNotBlank()) {
                    try {
                        val applied = withContext(Dispatchers.IO) {
                            applyReadFeed(
                                prefs,
                                parseRssItems(fetchRssXml(readUrl)).map { novelFromRssTitle(it.title) to it.pubDateMillis }
                            )
                        }
                        if (applied > 0) {
                            lastRead = loadLastRead(prefs)
                            DebugLog.log("READSYNC", "app-open applied $applied read chapters")
                        }
                    } catch (e: Exception) {
                        DebugLog.log("READSYNC", "app-open read feed failed", e)
                    }
                }
                statusMessage = if (due.isEmpty()) {
                    "Loaded Novel Updates feed (${titles.size} items)"
                } else {
                    "Feed loaded · ${due.size} series have new chapters"
                }
            } else {
                statusMessage = "Feed loaded, but no titles found"
            }
        } catch (e: Exception) {
            DebugLog.log("RSS", "fetch failed", e)
            statusMessage = "Could not load feed: ${e.message}"
            // Cold starts can outrun the network (Wi-Fi still waking with the
            // screen off). Back off and try again, up to three times.
            if (rssRetries < 3) {
                rssRetries += 1
                delay((5L * rssRetries).seconds)
                refreshKey += 1
            }
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
        saveLastRead(prefs, lastRead)
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
        // A parked sync queue (session expiry) resumes with the fresh session.
        scope.launch {
            if (withContext(Dispatchers.IO) { SyncQueue.pending(context) }.isNotEmpty()) {
                SyncEngineService.resume(context)
            }
        }
        if (!prefs.getBoolean("onboarding_done", false)) {
            showOnboarding = true
        } else {
            showSettings = true
        }
    }

    fun onLoggedOut() {
        loginState = "Not logged in"
        prefs.edit { remove("nu_cookies") }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    val selectedWithCover = selectedNovel?.let { novel ->
        val cached = usableCoverUrl(coverCache[novel.title])
        if (cached.isNotBlank() && usableCoverUrl(novel.coverUrl).isBlank()) novel.copy(coverUrl = cached) else novel
    }

    // System back returns to the previous screen instead of leaving the app.
    // Login overlays Settings, so close it the way its Cancel button does.
    BackHandler(
        enabled = showLoginWeb || showSettings || showEditLists || showOnboarding || selectedNovel != null
    ) {
        when {
            showLoginWeb -> {
                showLoginWeb = false
                showSettings = true
            }
            showSettings -> showSettings = false
            showEditLists -> showEditLists = false
            showSearch -> showSearch = false
            showDiscover -> showDiscover = false
            showOnboarding -> showOnboarding = false
            selectedNovel != null -> selectedNovel = null
        }
    }

    // Only one screen is composed at a time. Order matters: login overlays settings.
    // The probe screen (adb-only) overlays everything while it runs.
    when {
        probeMode -> EndpointProbeScreen(onFinished = { probeMode = false })
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
            syncStatus = syncStatus,
            onThemeChange = onThemeChange,
            onStartCoverSync = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startCoverSync()
                }
            },
            onStopCoverSync = {
                SyncEngineService.stop(context)
                syncStatus = "Stopped"
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
        showOnboarding -> OnboardingScreen(
            onFinished = { showOnboarding = false },
            onOpenSettings = {
                showOnboarding = false
                showSettings = true
            }
        )
        showSearch -> SiteSearchScreen(
            onOpenSeries = { found ->
                selectedNovel = found
                showSearch = false
            },
            onBack = { showSearch = false }
        )
        showDiscover -> DiscoverScreen(
            onOpenSeries = { found ->
                selectedNovel = found
                showDiscover = false
            },
            onBack = { showDiscover = false }
        )
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
                onRssUrlFound = { url ->
                    rssUrl = url
                    prefs.edit { putString("rss_url", url) }
                    statusMessage = "RSS feed connected — chapter checks enabled"
                },
                onReadFeedFound = { url ->
                    prefs.edit { putString("rss_read_url", url) }
                    statusMessage = "Read sync enabled — marks carry between devices"
                },
                onOpenSearch = { showSearch = true },
                onOpenDiscover = { showDiscover = true },
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
                onLastReads = { incoming ->
                    lastRead = mergeLastReads(lastRead, incoming)
                    saveLastRead(prefs, lastRead)
                },
                pauseLocalCovers = syncStatus.startsWith("Running") ||
                    syncStatus.startsWith("Lists") ||
                    syncStatus.startsWith("Covers") ||
                    syncStatus.startsWith("Chapters") ||
                    syncStatus.contains("waiting") ||
                    syncStatus.contains("resuming")
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
    NovelNotifierTheme(mode = THEME_SYSTEM) {
        NovelApp()
    }
}
