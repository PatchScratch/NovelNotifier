package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.time.Duration.Companion.seconds

/**
 * NU-library state (Phase 4b): RSS feed rows, read markers, covers, list
 * options, login state — and the mutators that persist them to prefs.
 * Previously `remember` blocks + local functions inside NovelApp.
 *
 * Persisted prefs stay the source of truth; the ViewModel is the live
 * in-memory mirror, exactly like the composition state it replaces.
 */
class NuViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    var novels by mutableStateOf(emptyList<Novel>())
        private set
    var statusMessage by mutableStateOf("Log in from Settings, then pull down to load your lists")
    var selected by mutableStateOf<Novel?>(null)
        private set
    var readingLists by mutableStateOf(emptyList<ReadingList>())
        private set

    var readTitles by mutableStateOf(prefs.getStringSet("read_titles", emptySet()) ?: emptySet())
        private set
    var lastRead by mutableStateOf(loadLastRead(prefs))
        private set
    var coverCache by mutableStateOf(loadCoverCache(prefs))
    var notifyOff by mutableStateOf(loadIdSet(prefs, "list_notify_off"))
        private set
    var updateOff by mutableStateOf(loadIdSet(prefs, "list_update_off"))
        private set
    var seriesNotifyOff by mutableStateOf(loadIdSet(prefs, "series_notify_off"))
        private set

    var rssUrl by mutableStateOf(prefs.getString("rss_url", "") ?: "")
        private set
    var checkHours by mutableIntStateOf(prefs.getInt("check_hours", 12))
        private set

    private var rssRetries by mutableIntStateOf(0)
    var isRefreshing by mutableStateOf(false)
        private set
    var chapterFetchQueue by mutableStateOf(emptyList<Novel>())
        private set

    var loginState by mutableStateOf(
        if (prefs.getString("nu_cookies", "").isNullOrBlank()) "Not logged in"
        else "Logged in to Novel Updates"
    )
        private set

    init {
        // The engine downloads covers in its own process context; the home
        // grid re-reads the prefs mirror every 2 s (kept from NovelApp).
        viewModelScope.launch {
            while (true) {
                delay(2.seconds)
                coverCache = loadCoverCache(prefs)
            }
        }
        viewModelScope.launch { refreshRss() }
        scheduleUpdateChecks(app, checkHours)
    }

    // --- home list -----------------------------------------------------------

    /** Pull-to-refresh / "Check now": re-run the RSS pipeline. */
    fun refresh() {
        viewModelScope.launch { refreshRss() }
    }

    private suspend fun refreshRss() {
        if (rssUrl.isBlank()) {
            // No RSS feed configured — the home list loads from the site
            // cache and background checks walk the reading lists instead.
            novels = emptyList()
            isRefreshing = false
            return
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
                val due = withContext(Dispatchers.IO) { rssUpdatesToFetch(getApplication(), novels) }
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
                refreshRss()
            }
        }
        isRefreshing = false
    }

    fun onChapterFetchFinished(title: String, ok: Boolean) {
        chapterFetchQueue = chapterFetchQueue.filterNot { it.title == title }
        if (ok) statusMessage = "Merged new chapters for $title"
    }

    // --- covers --------------------------------------------------------------

    fun rememberCover(title: String, url: String) {
        if (url.isBlank() || isPlaceholderCover(url)) return
        val updated = coverCache + (title to url)
        coverCache = updated
        saveCoverCache(prefs, updated)
        selected = selected?.let {
            if (it.title == title) it.copy(coverUrl = url) else it
        }
    }

    // --- detail + markers ----------------------------------------------------

    fun openDetail(novel: Novel) {
        selected = novel
    }

    fun closeDetail() {
        selected = null
    }

    /** Notification deep link: enough to construct the entry Novel; the
     *  detail screen loads everything else (lists, releases, cover) itself. */
    fun openFromNotification(link: MainActivity.SeriesDeepLink) {
        MainActivity.openSeriesRequested = null
        selected = Novel(
            title = link.title,
            latestChapter = link.chapter,
            seriesUrl = link.url,
            coverUrl = coverCache[link.title].orEmpty()
        )
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

    fun mergeIncomingLastReads(incoming: Map<String, Pair<String, String>>) {
        lastRead = mergeLastReads(lastRead, incoming)
        saveLastRead(prefs, lastRead)
    }

    fun toggleSeriesNotify(title: String) {
        seriesNotifyOff = if (title in seriesNotifyOff) seriesNotifyOff - title else seriesNotifyOff + title
        prefs.edit { putStringSet("series_notify_off", seriesNotifyOff) }
    }

    // --- list options + feed capture ------------------------------------------

    fun onListsLoaded(parsed: List<ReadingList>) {
        if (parsed.isNotEmpty()) readingLists = parsed
    }

    fun onRssUrlFound(url: String) {
        rssUrl = url
        prefs.edit { putString("rss_url", url) }
        statusMessage = "RSS feed connected — chapter checks enabled"
    }

    fun onReadFeedFound(url: String) {
        prefs.edit { putString("rss_read_url", url) }
        statusMessage = "Read sync enabled — marks carry between devices"
    }

    fun saveListOptions(newNotifyOff: Set<String>, newUpdateOff: Set<String>) {
        notifyOff = newNotifyOff
        updateOff = newUpdateOff
        prefs.edit {
            putStringSet("list_notify_off", newNotifyOff)
            putStringSet("list_update_off", newUpdateOff)
        }
    }

    // --- login + sync ---------------------------------------------------------

    fun saveSettings(newUrl: String, newHours: Int) {
        rssUrl = newUrl.trim()
        checkHours = newHours
        prefs.edit {
            putString("rss_url", rssUrl)
            putInt("check_hours", checkHours)
        }
        scheduleUpdateChecks(getApplication(), checkHours)
    }

    /** True when the first-run guide should show (fresh login, empty library). */
    suspend fun needsOnboarding(): Boolean {
        val (hasLists, hasRows, onboarded) = withContext(Dispatchers.IO) {
            val snap = loadListSnapshot(getApplication())
            Triple(
                snap.lists.isNotEmpty(),
                snap.novelsByList.values.any { it.isNotEmpty() },
                prefs.getBoolean("onboarding_done", false)
            )
        }
        val loggedOut = prefs.getString("nu_cookies", "").isNullOrBlank()
        return !loggedOut && !onboarded && !(hasLists && hasRows)
    }

    fun isOnboardingDone(): Boolean = prefs.getBoolean("onboarding_done", false)

    fun onLoggedIn(cookieHeader: String) {
        loginState = "Logged in to Novel Updates"
        prefs.edit { putString("nu_cookies", cookieHeader) }
        // A parked sync queue (session expiry) resumes with the fresh session.
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { SyncQueue.pending(getApplication()) }.isNotEmpty()) {
                SyncEngineService.resume(getApplication())
            }
        }
    }

    fun onLoggedOut() {
        loginState = "Not logged in"
        prefs.edit { remove("nu_cookies") }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
