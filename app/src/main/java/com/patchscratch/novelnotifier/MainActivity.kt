package com.patchscratch.novelnotifier

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patchscratch.novelnotifier.ui.theme.NovelNotifierTheme

/**
 * App entry + navigation shell (Phase 4b: state lives in ViewModels).
 *
 * MainActivity — Android launcher. Calls NovelApp().
 * NovelApp     — collects ViewModel state and picks one screen from the
 *                Router's stack: Login, Settings, Edit Lists, reading
 *                list, series detail, or reader.
 *
 * Data flow:
 *   AppViewModel (router, site workspace, sync-status poll)
 *   NuViewModel / SyosetuViewModel / KakuyomuViewModel (per-site stores,
 *   persisted via SharedPreferences in Prefs.kt + JSON files in Cache.kt)
 *   → screens below → SyncEngineService / UpdateCheckWorker for background
 *
 * getValue is used by `by` delegates across this file and the ViewModels.
 * Android Studio may grey it out — it is not unused. Do not delete it.
 */

// FragmentActivity (a ComponentActivity subclass): everything Compose needs
// keeps working, and it is what androidx.biometric's BiometricPrompt requires
// to host the system fingerprint / lockscreen-PIN dialog for NSFW mode.
class MainActivity : FragmentActivity() {

    /** A notification tap asking for a series detail page. */
    data class SeriesDeepLink(val url: String, val title: String, val chapter: String)

    companion object {
        /** Set when a notification asks us to open the login screen (session expired). */
        @Volatile var openLoginRequested = false

        /**
         * Chapter-alert deep links. Notifications carry open_series_* extras.
         * Cold start: onCreate runs long before NovelApp composes, and a
         * no-replay SharedFlow drops a value emitted with no subscribers — so
         * the request parks in a volatile that NovelApp consumes on first
         * composition. Warm taps (onNewIntent) go through the flow, which the
         * running collector receives instantly. No replay: a recreated process
         * must not reopen a stale series.
         */
        @Volatile var openSeriesRequested: SeriesDeepLink? = null

        private val seriesRequests =
            kotlinx.coroutines.flow.MutableSharedFlow<SeriesDeepLink>(extraBufferCapacity = 4)
        val openSeriesRequests = seriesRequests
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra("open_login", false) == true) openLoginRequested = true
        handleSeriesDeepLink(intent)
        setContent {
            // UI language (Settings → Display); chrome recomposes on change.
            UiLang.current = getSharedPreferences("novel_prefs", MODE_PRIVATE)
                .getString("ui_lang", UiLang.EN) ?: UiLang.EN
            // Hoisted so Settings can re-theme the whole app without a restart.
            // customTheme is the imported JSON theme; reloaded on every import.
            var themeMode by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(loadThemeMode(getSharedPreferences("novel_prefs", MODE_PRIVATE)))
            }
            var customTheme by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CustomTheme?>(loadCustomTheme(this)) }
            NovelNotifierTheme(mode = themeMode, custom = customTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovelApp(
                        onThemeChange = { themeMode = it },
                        onCustomThemeReady = { customTheme = it }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_login", false)) openLoginRequested = true
        handleSeriesDeepLink(intent)
    }

    private fun handleSeriesDeepLink(intent: android.content.Intent?) {
        val url = intent?.getStringExtra(EXTRA_OPEN_SERIES_URL) ?: return
        val link = SeriesDeepLink(
            url = url,
            title = intent.getStringExtra(EXTRA_OPEN_SERIES_TITLE).orEmpty(),
            chapter = intent.getStringExtra(EXTRA_OPEN_SERIES_CHAPTER).orEmpty()
        )
        // Park for the cold-start consumer; the flow covers the running app.
        openSeriesRequested = link
        seriesRequests.tryEmit(link)
    }
}

@Composable
fun NovelApp(
    onThemeChange: (String) -> Unit = {},
    onCustomThemeReady: (CustomTheme?) -> Unit = {}
) {
    val context = LocalContext.current
    val app = viewModel<AppViewModel>()
    val nu = viewModel<NuViewModel>()
    val syosetu = viewModel<SyosetuViewModel>()
    val kakuyomu = viewModel<KakuyomuViewModel>()
    val readerVm = viewModel<ReaderViewModel>()
    val router = app.router

    // Foreground tracking gates AppViewModel's queue auto-resume (Android
    // 12+ forbids background foreground-service starts). ON_STOP also
    // re-locks NSFW mode: the app must never come back from the background
    // still unlocked.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            app.setForeground(event != Lifecycle.Event.ON_STOP)
            if (event == Lifecycle.Event.ON_STOP) app.lockNsfw()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // First-run guide (fresh login + empty library + onboarding not done).
    LaunchedEffect(Unit) {
        if (nu.needsOnboarding()) router.push(Route.Onboarding)
    }

    // A notification tap lands on the NU series detail page. Login is left
    // alone — never yank the user out of an auth flow. The reader, if open,
    // stays on top (Router.resetTo keeps it).
    fun openDeepLink(link: MainActivity.SeriesDeepLink) {
        // SFW deep-link guard: a notification (or stale intent) must not be
        // able to walk around the hide filter into a hidden series' detail.
        if (!app.nsfwUnlocked &&
            link.title in NsfwFilter.hiddenNuTitles(
                loadListSnapshot(context).novelsByList, app.hiddenLists
            )
        ) {
            nu.statusMessage = t(
                "That series is in a hidden list — unlock to open it.",
                "その作品は非表示リストにあります — ロック解除して開いてください。"
            )
            return
        }
        nu.openFromNotification(link)
        router.resetTo(Route.NuDetail(link.url))
    }

    // SFW/NSFW mode: unlocking needs the hosting activity for the system
    // biometric dialog; hiding again is always free.
    val nsfwActivity = context as? androidx.fragment.app.FragmentActivity
    fun toggleNsfw() {
        if (app.nsfwUnlocked) app.lockNsfw()
        else nsfwActivity?.let { app.unlockNsfw(it) }
    }

    // Cold start first (volatile parked by onCreate), then live taps while running.
    LaunchedEffect(Unit) {
        MainActivity.openSeriesRequested?.let { openDeepLink(it) }
        MainActivity.openSeriesRequests.collect { openDeepLink(it) }
    }

    // Android 13+ must grant POST_NOTIFICATIONS before the sync engine can show its counter.
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < 33) {
            app.startCoverSync(nu)
        } else {
            app.onNotifyPermissionDenied()
        }
    }

    // System back fallback. Screens with their own BackHandler (reader,
    // login screens, the list-options dialog) intercept first and route
    // through the same pop callbacks.
    BackHandler(enabled = router.canGoBack) { router.pop() }

    // Cover-enriched detail payload (never let a blank site cover win over
    // one we already have cached).
    val selectedWithCover = nu.selected?.let { novel ->
        val cached = usableCoverUrl(nu.coverCache[novel.title])
        if (cached.isNotBlank() && usableCoverUrl(novel.coverUrl).isBlank()) novel.copy(coverUrl = cached) else novel
    }

    // Persistent Cloudflare → one root-level challenge pane over every
    // screen (single CF hits are handled by SiteClient's pacing; a repeat
    // within the cooldown window needs a human).
    val cfChallenge by SiteClient.cfNeeded.collectAsState()

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
    // Only one screen is composed at a time: the top of the router stack.
    when (val route = router.top) {
        Route.XlateFiles -> {
            val target = readerVm.target
            if (target != null) XlateFilesScreen(
                context = context,
                seriesUrl = target.seriesUrl,
                seriesTitle = target.seriesTitle,
                onBack = { router.pop() }
            )
        }

        Route.Reader -> {
            val target = readerVm.target
            if (target != null) {
                val shioriBm = syosetu.bookmarks.firstOrNull { it.url == target.seriesUrl }
                val shioriPos = shioriBm?.let { maxOf(it.shioriPos, 0) } ?: -1
                ReaderScreen(
                    target = target,
                    onBack = {
                        readerVm.close()
                        router.pop()
                    },
                    onOpenXlateFiles = { router.push(Route.XlateFiles) },
                    shioriPos = shioriPos,
                    onShioriAuto = { ep ->
                        shioriBm?.let { syosetu.updateShioriAuto(it, ep) }
                    },
                    onShioriManual = { ep ->
                        shioriBm?.let { syosetu.updateShioriManual(it, ep) }
                    },
                    kakuyomuHistory = kakuyomu.loggedIn && target.seriesUrl.contains("kakuyomu.jp"),
                    onKakuyomuHistory = { episodeId, ordinal, manual ->
                        kakuyomu.recordReadingHistory(episodeId, ordinal, manual)
                    }
                )
            }
        }

        Route.NuLogin -> LoginWebScreen(
            onLoggedIn = { cookies ->
                nu.onLoggedIn(cookies)
                router.replace(
                    if (!nu.isOnboardingDone()) Route.Onboarding else Route.Settings
                )
            },
            onCancel = { router.replace(Route.Settings) }
        )

        Route.Settings -> SettingsScreen(
            site = app.settingsSite,
            nsfwUnlocked = app.nsfwUnlocked,
            onRequestNsfwUnlock = { toggleNsfw() },
            kakuyomuR18 = kakuyomu.r18,
            onKakuyomuR18 = { on -> kakuyomu.updateR18(on) },
            kakuyomuLoggedIn = kakuyomu.loggedIn,
            kakuyomuStatus = kakuyomu.status,
            onKakuyomuLogin = { router.replace(Route.KakuyomuLogin) },
            onKakuyomuLogout = { kakuyomu.onLoggedOut() },
            onKakuyomuSync = { kakuyomu.syncBookmarks() },
            currentUrl = nu.rssUrl,
            onSyosetuLogin = { router.replace(Route.SyosetuLogin) },
            onSyosetuLogout = { syosetu.onLoggedOut() },
            onSyosetuSync = { syosetu.syncNow() },
            syosetuLoggedIn = syosetu.loggedIn,
            syosetuStatus = syosetu.status,
            syosetuR18 = syosetu.r18,
            onSyosetuR18 = { on -> syosetu.updateR18(on) },
            syosetuSubsites = syosetu.subsites,
            onSyosetuSubsites = { set -> syosetu.updateSubsites(set) },
            // R18 prefs are remembered but only EFFECTIVE while NSFW mode
            // is unlocked — SFW hides every R18 surface.
            currentHours = nu.checkHours,
            loginState = nu.loginState,
            syncStatus = app.syncStatus,
            onThemeChange = onThemeChange,
            onCustomThemeReady = onCustomThemeReady,
            onStartCoverSync = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    app.startCoverSync(nu)
                }
            },
            onStopCoverSync = { app.stopCoverSync() },
            onCheckNow = {
                router.pop()
                app.checkNow(nu)
            },
            onSave = { url, hours ->
                nu.saveSettings(url, hours)
                router.pop()
            },
            onStartLogin = { router.replace(Route.NuLogin) },
            onLoggedOut = { nu.onLoggedOut() },
            onCancel = { router.pop() }
        )

        is Route.EditLists -> EditListsScreen(
            url = route.url,
            title = route.title,
            onBack = { router.pop() }
        )

        Route.Onboarding -> OnboardingScreen(
            onFinished = { router.pop() },
            onOpenSettings = { router.replace(Route.Settings) }
        )

        Route.Search -> SiteSearchScreen(
            onOpenSeries = { found ->
                nu.openDetail(found)
                router.replace(Route.NuDetail(found.seriesUrl))
            },
            onBack = { router.pop() }
        )

        Route.Discover -> DiscoverScreen(
            onOpenSeries = { found ->
                nu.openDetail(found)
                router.replace(Route.NuDetail(found.seriesUrl))
            },
            onBack = { router.pop() }
        )

        is Route.KakuyomuDetail -> {
            val work = kakuyomu.selectedWork
            if (work != null) KakuyomuDetailScreen(
                work = work,
                bookmarked = kakuyomu.bookmarks.any { it.id == work.id },
                onToggleBookmark = { kakuyomu.toggleBookmark(work) },
                categories = kakuyomu.categories,
                onMove = { workId, catId -> kakuyomu.moveToCategory(workId, catId) },
                onCreateAndMove = { workId, name ->
                    kakuyomu.addCategory(name)
                    // addCategory generated the id; move needs it after.
                    val created = kakuyomu.categories.cats.lastOrNull()
                    if (created != null) kakuyomu.moveToCategory(workId, created.id)
                },
                onBack = {
                    kakuyomu.closeDetail()
                    router.pop()
                },
                onOpenReader = { chapters, startAt ->
                    readerVm.open(
                        ReaderTarget(
                            seriesTitle = work.title,
                            seriesUrl = work.url,
                            chapters = chapters,
                            startIndex = startAt.coerceAtLeast(0),
                            meta = work.toBookMeta()
                        )
                    )
                    router.push(Route.Reader)
                }
            )
        }

        is Route.SyosetuDetail -> {
            val novel = syosetu.selected
            if (novel != null) SyosetuDetailScreen(
                novel = novel,
                bookmark = syosetu.bookmarks.firstOrNull { it.url == novel.url },
                categories = syosetu.categories,
                onChangeCategory = { catId ->
                    syosetu.bookmarks.firstOrNull { it.url == novel.url }
                        ?.let { bm -> syosetu.moveBookmark(bm.userfavId, catId) }
                },
                onRemoveBookmark = {
                    syosetu.bookmarks.firstOrNull { it.url == novel.url }
                        ?.let { bm -> syosetu.removeBookmark(bm.userfavId) }
                },
                onBack = {
                    syosetu.closeDetail()
                    router.pop()
                },
                onOpenReader = { chapters, startAt ->
                    readerVm.open(
                        ReaderTarget(
                            seriesTitle = novel.title,
                            seriesUrl = novel.url,
                            chapters = chapters,
                            startIndex = startAt.coerceAtLeast(0),
                            meta = novel.toBookMeta()
                        )
                    )
                    router.push(Route.Reader)
                }
            )
        }

        is Route.SyosetuLibrary -> SyosetuBookmarksScreen(
            bookmarks = syosetu.bookmarks,
            category = route.category,
            onOpenNovel = { novel ->
                syosetu.openDetail(novel)
                router.push(Route.SyosetuDetail(novel.url))
            },
            categories = syosetu.categories,
            onChangeCategory = { bm, catId ->
                if (bm.userfavId.isNotBlank()) syosetu.moveBookmark(bm.userfavId, catId)
            },
            onRemove = { bm ->
                if (bm.userfavId.isNotBlank()) syosetu.removeBookmark(bm.userfavId)
            },
            onBack = { router.pop() },
            nsfwUnlocked = app.nsfwUnlocked,
            hiddenCategories = app.hiddenSyosetuCats
        )

        is Route.KakuyomuLibrary -> KakuyomuBookmarksScreen(
            bookmarks = kakuyomu.bookmarks,
            onOpenWork = { work ->
                kakuyomu.openDetail(work)
                router.push(Route.KakuyomuDetail(work.id))
            },
            onBack = { router.pop() },
            categories = kakuyomu.categories,
            categoryId = route.categoryId,
            onMove = { workId, catId -> kakuyomu.moveToCategory(workId, catId) },
            onCreateAndMove = { workId, name ->
                kakuyomu.addCategory(name)
                val created = kakuyomu.categories.cats.lastOrNull()
                if (created != null) kakuyomu.moveToCategory(workId, created.id)
            },
            nsfwUnlocked = app.nsfwUnlocked
        )

        is Route.Categories -> CategoriesScreen(
            site = route.site,
            syosetuCategories = syosetu.categories,
            onBack = { router.pop() },
            onEditCategories = {
                // No standalone editor URL exists (verified the hard way:
                // /favnovelmain/editcategory/ 404s with a live session).
                // Per Syosetu's help, category names are edited from the
                // 「カテゴリ名編集」 control at the top of the bookmark list.
                router.push(
                    Route.EditLists(
                        url = "https://syosetu.com/favnovelmain/list/",
                        title = t("Edit 分類", "分類編集")
                    )
                )
            }
        )

        Route.SyosetuLogin -> SyosetuLoginScreen(
            onLoggedIn = {
                syosetu.onLoggedIn()
                router.pop()
            },
            onCancel = { router.pop() }
        )

        Route.KakuyomuLogin -> KakuyomuLoginScreen(
            onLoggedIn = {
                kakuyomu.onLoggedIn()
                router.pop()
            },
            onCancel = { router.pop() }
        )

        Route.Home -> when (app.activeSite) {
            "syosetu" -> SyosetuHome(
                activeSite = app.activeSite,
                onSwitchSite = { app.switchSite(it) },
                onOpenSettings = { app.openSettings("syosetu") },
                onOpenNovel = { novel ->
                    syosetu.openDetail(novel)
                    router.push(Route.SyosetuDetail(novel.url))
                },
                loggedIn = syosetu.loggedIn,
                bookmarks = syosetu.bookmarks,
                // R18 remembered prefs only take effect while unlocked.
                r18Enabled = syosetu.r18 && app.nsfwUnlocked,
                enabledSubsites = syosetu.subsites,
                onOpenBookmarks = { cat -> router.push(Route.SyosetuLibrary(cat)) },
                onOpenCategories = { router.push(Route.Categories("syosetu")) },
                nsfwUnlocked = app.nsfwUnlocked,
                onToggleNsfw = { toggleNsfw() },
                hiddenCategories = app.hiddenSyosetuCats
            )

            "kakuyomu" -> KakuyomuHome(
                activeSite = app.activeSite,
                onSwitchSite = { app.switchSite(it) },
                onOpenSettings = { app.openSettings("kakuyomu") },
                onOpenWork = { work ->
                    kakuyomu.openDetail(work)
                    router.push(Route.KakuyomuDetail(work.id))
                },
                // R18 remembered prefs only take effect while unlocked
                // (isSexual rows default-hide otherwise).
                r18Enabled = kakuyomu.r18 && app.nsfwUnlocked,
                bookmarks = kakuyomu.bookmarks,
                categories = kakuyomu.categories,
                onOpenBookmarks = { catId -> router.push(Route.KakuyomuLibrary(catId)) },
                onOpenCategories = { router.push(Route.Categories("kakuyomu")) },
                loggedIn = kakuyomu.loggedIn,
                nsfwUnlocked = app.nsfwUnlocked,
                onToggleNsfw = { toggleNsfw() }
            )

            else -> {
                if (nu.chapterFetchQueue.isNotEmpty()) {
                    SeriesChapterFetcher(
                        queue = nu.chapterFetchQueue,
                        onFinished = { title, ok -> nu.onChapterFetchFinished(title, ok) }
                    )
                }
                NovelListScreen(
                    rssNovels = nu.novels,
                    rssStatus = nu.statusMessage,
                    checkHours = nu.checkHours,
                    loginState = nu.loginState,
                    readTitles = nu.readTitles,
                    lastRead = nu.lastRead,
                    coverCache = nu.coverCache,
                    notifyOff = nu.notifyOff,
                    updateOff = nu.updateOff,
                    isRefreshing = nu.isRefreshing,
                    onRefresh = { nu.refresh() },
                    onNovelClick = { novel ->
                        nu.openDetail(novel)
                        router.push(Route.NuDetail(novel.seriesUrl))
                    },
                    onCoverFound = { title, url -> nu.rememberCover(title, url) },
                    onListsLoaded = { parsed -> nu.onListsLoaded(parsed) },
                    onRssUrlFound = { url -> nu.onRssUrlFound(url) },
                    onReadFeedFound = { url -> nu.onReadFeedFound(url) },
                    onOpenSearch = { router.push(Route.Search) },
                    onOpenDiscover = { router.push(Route.Discover) },
                    activeSite = app.activeSite,
                    onSwitchSite = { app.switchSite(it) },
                    onOpenSettings = { app.openSettings("nu") },
                    onEditLists = { router.push(Route.EditLists()) },
                    onSaveListOptions = { newNotifyOff, newUpdateOff ->
                        nu.saveListOptions(newNotifyOff, newUpdateOff)
                    },
                    onLastReads = { incoming -> nu.mergeIncomingLastReads(incoming) },
                    nsfwUnlocked = app.nsfwUnlocked,
                    onToggleNsfw = { toggleNsfw() },
                    hiddenLists = app.hiddenLists,
                    onHiddenLists = { app.updateHiddenLists(it) },
                    pauseLocalCovers = app.syncStatus.startsWith("Running") ||
                        app.syncStatus.startsWith("Lists") ||
                        app.syncStatus.startsWith("Covers") ||
                        app.syncStatus.startsWith("Chapters") ||
                        app.syncStatus.contains("waiting") ||
                        app.syncStatus.contains("resuming")
                )
            }
        }

        is Route.NuDetail -> {
            val novel = selectedWithCover
            if (novel != null) NovelDetailScreen(
                novel = novel,
                lastRead = nu.lastRead[novel.title],
                notifyOn = novel.title !in nu.seriesNotifyOff,
                availableLists = nu.readingLists,
                onMarkRead = { nu.markAsRead(novel.title) },
                onSetLastRead = { chapter, url, read ->
                    nu.setLastRead(novel.title, chapter, url, read)
                },
                onToggleNotify = { nu.toggleSeriesNotify(novel.title) },
                onCoverFound = { url -> nu.rememberCover(novel.title, url) },
                onOpenReader = { chapters, startAt, meta ->
                    readerVm.open(
                        ReaderTarget(
                            seriesTitle = novel.title,
                            seriesUrl = novel.seriesUrl,
                            chapters = chapters,
                            startIndex = if (startAt >= 0) startAt else 0,
                            meta = meta
                        )
                    )
                    router.push(Route.Reader)
                },
                onBackClick = {
                    nu.closeDetail()
                    router.pop()
                }
            )
        }
    }

    // The challenge pane rides above whatever screen is showing.
    cfChallenge?.let { challenge ->
        ChallengeOverlay(
            challenge = challenge,
            onSolved = { SiteClient.clearCloudflare(challenge.site) }
        )
    }

    // Unlock attempted with no lockscreen credential at all.
    if (app.needsDeviceLock) {
        NeedsDeviceLockDialog(onDismiss = { app.dismissNeedsDeviceLock() })
    }
    }
}

@Preview(showBackground = true)
@Composable
fun NovelAppPreview() {
    NovelNotifierTheme(mode = THEME_SYSTEM) {
        NovelApp()
    }
}
