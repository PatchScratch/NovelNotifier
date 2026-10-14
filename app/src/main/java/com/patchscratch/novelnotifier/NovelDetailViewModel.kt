package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Series-detail scrape state (Phase 4b) on a SiteClient Session — the
 * screen's hidden WebView, its recomposition-driven command dispatch
 * (`webView.tag` dedup + client reassigned every update), and the
 * dual-purpose Cloudflare pane are gone.
 *
 * Sequencing is explicit now:
 *   open(novel) → cache paints + policy decides whether network is needed;
 *   requestPage(n) → session loads ?pg=n, parses, saves cache, then runs
 *   any pending write command (CHANGE LIST / MARK AS READ) on the loaded
 *   page via evaluateJavascript — the "must be sitting on a series page"
 *   precondition the old update-block enforced by checking webView.url.
 *
 * The session opens lazily on first network need and stays open while the
 * screen is composed (leave() closes it and the pacer queue resumes).
 * Series with a complete fresh cache never open one at all.
 *
 * Cloudflare during a load escalates through SiteClient.cfNeeded to the
 * root ChallengeOverlay.
 */
class NovelDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    /** INFO + RELEASES we currently show. Starts empty, then cache, then scrape. */
    var page by mutableStateOf(SeriesPage())
        private set
    var seriesSid by mutableStateOf("")
        private set

    /** Free-form status line; the screen also writes transient hints here. */
    var listStatus by mutableStateOf("")
    var loadedPage by mutableIntStateOf(0)
        private set
    var wantPage by mutableIntStateOf(1)
        private set
    var maxPage by mutableIntStateOf(1)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var pageLoadFailed by mutableStateOf(false)
        private set
    private var allowNetwork = false
    private var currentUrl = ""

    // Write commands waiting for a loaded series page.
    // move: "listId:::listName"; readRid: checkbox id ("0" = clear);
    // readChapter: resolves a rid after the next scrape when the cache had none.
    private var pendingMoveId: String? = null
    private var pendingReadRid: String? = null
    private var pendingReadChapter: String? = null
    private var lastReadNum = -1
    private var knownListName = ""
    private var novel = Novel(title = "", latestChapter = "", seriesUrl = "")

    /** Cross-VM side effects, drained once by the screen. */
    sealed interface Event {
        data class CoverFound(val url: String) : Event
        data class SetLastRead(val chapter: String, val url: String) : Event
    }
    var pendingEvents by mutableStateOf<List<Event>>(emptyList())

    private var session: SiteClient.Session? = null
    /** True when the session's WebView last finished on a /series/ page. */
    private var onSeriesPage = false

    /** Cache-first policy. Re-runs in full whenever a different series opens. */
    fun open(novel: Novel, lastRead: Pair<String, String>?) {
        if (novel.seriesUrl == this.novel.seriesUrl) return
        this.novel = novel
        reset()
        lastReadNum = lastRead?.first?.let(::chapterNum) ?: -1
        viewModelScope.launch {
            knownListName = withContext(Dispatchers.IO) {
                listNameForTitle(loadListSnapshot(getApplication()), novel.title, novel.seriesUrl)
            }
            val cached = withContext(Dispatchers.IO) { loadSeriesCache(getApplication(), novel.seriesUrl) }
            if (cached != null) {
                val loaded = if (isRealListName(cached.first.currentList)) {
                    cached.first
                } else {
                    cached.first.copy(currentList = knownListName)
                }
                page = loaded
                seriesSid = loaded.sid.ifBlank { seriesSid }
                maxPage = loaded.maxPage.coerceAtLeast(1)
                loadedPage = cached.second
                wantPage = cached.second.coerceAtLeast(1)
                loadingMore = false
                pageLoadFailed = false
                if (loaded.coverUrl.startsWith("http")) pendingEvents += Event.CoverFound(loaded.coverUrl)
                if (loaded.currentList != cached.first.currentList) {
                    withContext(Dispatchers.IO) {
                        saveSeriesCache(getApplication(), novel.seriesUrl, loaded, cached.second)
                    }
                }
                val cachedRead = highestReadRelease(loaded.releases)
                if (cachedRead != null) {
                    val siteNum = chapterNum(cachedRead.chapter)
                    if (siteNum > 0 && siteNum >= lastReadNum) {
                        pendingEvents += Event.SetLastRead(cachedRead.chapter, cachedRead.url)
                    }
                }
            } else if (knownListName.isNotBlank()) {
                page = page.copy(currentList = knownListName)
            }
            val stale = withContext(Dispatchers.IO) { seriesNeedsRefresh(getApplication(), novel) }
            val cacheHasTicks = cached?.first?.releases?.any { it.read } == true
            val complete = cached != null && cacheHasAllPages(
                cached.first.maxPage,
                cached.second,
                cached.first.releases.size
            )
            when {
                cached == null -> {
                    allowNetwork = true
                    loadingMore = true
                    wantPage = 1
                    loadedPage = 0
                    listStatus = "Downloading chapter list..."
                    loadCurrent()
                }
                !complete -> {
                    // Scroll-driven from here: opening a series never
                    // machine-guns the site for every remaining page.
                    allowNetwork = true
                    loadingMore = false
                    listStatus = "Cached ${cached.first.releases.size} chapters — scroll RELEASES for older ones"
                }
                !cacheHasTicks && cached.first.releases.any { it.rid.isNotBlank() } -> {
                    allowNetwork = true
                    loadingMore = true
                    wantPage = 1
                    listStatus = "Loading read ticks from Novel Updates..."
                    loadCurrent()
                }
                stale -> {
                    allowNetwork = false
                    listStatus = "Newer chapter on the list — use ⋮ Refresh when you want it"
                }
                else -> allowNetwork = false
            }
        }
    }

    private fun reset() {
        page = SeriesPage()
        seriesSid = ""
        listStatus = ""
        loadedPage = 0
        wantPage = 1
        maxPage = 1
        loadingMore = false
        pageLoadFailed = false
        allowNetwork = false
        pendingMoveId = null
        pendingReadRid = null
        pendingReadChapter = null
        pendingEvents = emptyList()
        onSeriesPage = false
        currentUrl = ""
    }

    fun leave() {
        session?.close()
        session = null
        onSeriesPage = false
    }

    /** ⋮ Refresh / RETRY / first open: load [pageNumber] of the RELEASES table. */
    fun requestPage(pageNumber: Int) {
        if (pageNumber < 1) return
        allowNetwork = true
        wantPage = pageNumber
        pageLoadFailed = false
        loadingMore = true
        loadCurrent()
    }

    fun requestNextPage() {
        if (loadingMore || pageLoadFailed || loadedPage == 0) return
        if (loadedPage < maxPage) requestPage(loadedPage + 1)
    }

    fun retryPage() {
        requestPage(wantPage)
    }

    /** 8 s hung-load safety net (a slow page is NOT proof of a CF check). */
    fun noteLoadTimedOut() {
        if (!loadingMore) return
        DebugLog.log("SERIES", "load timed out pg=$wantPage (series ${novel.title})")
        pageLoadFailed = true
        loadingMore = false
        listStatus = "Refresh timed out — tap RETRY or ⋮ Refresh later"
    }

    /** CHANGE LIST: queue the move; fires on the next loaded series page. */
    fun queueMoveList(listId: String, listName: String) {
        pendingMoveId = "$listId:::$listName"
        listStatus = "Moving to $listName..."
        ensureCommandPage()
    }

    /**
     * MARK AS READ / row checkbox: [rid] known, or resolved from the next
     * scrape by [chapter] when the cache carried none. rid "0" clears.
     */
    fun queueReadMarker(rid: String?, chapter: String?) {
        pendingReadRid = rid
        pendingReadChapter = chapter
        ensureCommandPage()
    }

    /** Commands need a series page loaded; if we're not on one, load it. */
    private fun ensureCommandPage() {
        if (onSeriesPage) {
            runPendingCommands()
        } else {
            allowNetwork = true
            loadingMore = true
            loadCurrent()
        }
    }

    private fun loadCurrent() {
        val s = ensureSession()
        val base = novel.seriesUrl.trimEnd('/')
        val target = if (wantPage <= 1) "$base/" else "$base/?pg=$wantPage"
        s.loadUrl(target, seriesPageJs()) { raw ->
            currentUrl = target
            handleParse(if (raw == "CF") "CF" else unwrapJsResult(raw))
        }
    }

    private fun ensureSession(): SiteClient.Session {
        session?.let { return it }
        val s = SiteClient.openSession(SiteClient.Site.NU)
        session = s
        return s
    }

    private fun handleParse(raw: String) {
        when {
            raw == "CF" -> {
                markSiteCheckNeeded(prefs)
                loadingMore = false
                pageLoadFailed = true
                listStatus = "Site check needed — try again shortly"
                DebugLog.log("CF", "series scrape hit Cloudflare (${novel.title})")
                return
            }
            isLoginRedirect(currentUrl) || raw.contains("wp-login.php") -> {
                loadingMore = false
                listStatus = "Session expired — log in from Settings"
                DebugLog.log("SYNC", "series scrape hit login redirect (${novel.title})")
                return
            }
        }
        onSeriesPage = true
        val parsed = parseSeriesPage(raw)
        if (parsed.maxPage > maxPage) maxPage = parsed.maxPage
        if (parsed.coverUrl.startsWith("http")) pendingEvents += Event.CoverFound(parsed.coverUrl)
        val incoming = parsed.releases
        val existingNums = page.releases.map { chapterNum(it.chapter) }.filter { it > 0 }
        val incomingNums = incoming.map { chapterNum(it.chapter) }.filter { it > 0 }
        val existingMin = existingNums.minOrNull()
        val incomingMax = incomingNums.maxOrNull()
        // If LOAD MORE jumped more than ~8 chapters, we skipped a table page.
        val skippedPage =
            existingMin != null &&
                incomingMax != null &&
                wantPage == loadedPage + 1 &&
                existingMin - incomingMax > 8
        if (skippedPage) {
            pageLoadFailed = true
            loadingMore = false
            listStatus = "Skipped a page — tap RETRY"
            return
        }
        val mergedReleases = if (page.releases.isEmpty()) incoming else mergeReleases(page.releases, incoming)
        page = page.copy(
            title = parsed.title.ifBlank { page.title },
            author = parsed.author.ifBlank { page.author },
            rating = parsed.rating.ifBlank { page.rating },
            description = parsed.description.ifBlank { page.description },
            genres = parsed.genres.ifBlank { page.genres },
            tags = parsed.tags.ifBlank { page.tags },
            type = parsed.type.ifBlank { page.type },
            status = parsed.status.ifBlank { page.status },
            coverUrl = parsed.coverUrl.ifBlank { page.coverUrl },
            associatedNames = parsed.associatedNames.ifBlank { page.associatedNames },
            artist = parsed.artist.ifBlank { page.artist },
            year = parsed.year.ifBlank { page.year },
            originalPublisher = parsed.originalPublisher.ifBlank { page.originalPublisher },
            completelyTranslated = parsed.completelyTranslated.ifBlank { page.completelyTranslated },
            currentList = listOf(parsed.currentList, page.currentList, knownListName)
                .firstOrNull(::isRealListName)
                .orEmpty(),
            lists = parsed.lists.ifEmpty { page.lists },
            releases = mergedReleases,
            maxPage = maxOf(page.maxPage, parsed.maxPage),
            sid = parsed.sid.ifBlank { page.sid }
        )
        if (parsed.sid.isNotBlank()) seriesSid = parsed.sid
        val siteRead = highestReadRelease(mergedReleases)
        if (siteRead != null) {
            val siteNum = chapterNum(siteRead.chapter)
            if (siteNum > 0 && siteNum >= lastReadNum) {
                pendingEvents += Event.SetLastRead(siteRead.chapter, siteRead.url)
            }
        }
        // A queued read whose rid wasn't in the cache resolves now.
        val wantChapter = pendingReadChapter
        if (pendingReadRid.isNullOrBlank() && !wantChapter.isNullOrBlank()) {
            val match = mergedReleases.find { release ->
                chapterNum(release.chapter) == chapterNum(wantChapter) ||
                    release.chapter.contains(wantChapter)
            }
            if (match != null && match.rid.isNotBlank()) pendingReadRid = match.rid
            pendingReadChapter = null
        }
        if (incoming.isNotEmpty()) {
            loadedPage = wantPage
            pageLoadFailed = false
            val pages = maxOf(page.maxPage, parsed.maxPage, maxPage).coerceAtLeast(1)
            maxPage = pages
            listStatus = if (wantPage < pages) {
                "Loaded page $wantPage of $pages — scroll for older chapters"
            } else {
                "Cached ${mergedReleases.size} chapters"
            }
            DebugLog.log(
                "SERIES",
                "parsed pg=$wantPage rows=${incoming.size} total=${mergedReleases.size} maxPage=$maxPage"
            )
            val pageToSave = page.copy(
                releases = mergedReleases,
                maxPage = pages,
                sid = parsed.sid.ifBlank { page.sid }
            )
            val savedPageNum = wantPage
            viewModelScope.launch(Dispatchers.IO) {
                saveSeriesCache(getApplication(), novel.seriesUrl, pageToSave, savedPageNum)
            }
        } else if (wantPage > 1 || page.releases.isNotEmpty()) {
            pageLoadFailed = true
        }
        loadingMore = false
        runPendingCommands()
    }

    /**
     * Fire one queued write command on the loaded series page (move first —
     * the priority the old update-block used). Re-fires after the next
     * parse if more remain.
     */
    private fun runPendingCommands() {
        val s = session ?: return
        val moveId = pendingMoveId
        when {
            moveId != null -> {
                pendingMoveId = null
                val moveListId = moveId.substringBefore(":::")
                val moveName = moveId.substringAfter(":::", "")
                // CHANGE LIST: the site's own move call — GET updatelist.php
                // (params verified from NU's ajax_search_post.js, 2026-10-10).
                s.evaluateJavascript(moveListJs(moveListId, seriesSid)) { jsResult ->
                    val raw = unwrapJsResult(jsResult)
                    if (raw.startsWith("move 20")) {
                        if (moveName.isNotBlank()) {
                            page = page.copy(currentList = moveName)
                            viewModelScope.launch(Dispatchers.IO) {
                                moveTitleToList(getApplication(), novel.title, novel.seriesUrl, moveListId, moveName)
                            }
                        }
                        listStatus = "Moved on Novel Updates"
                        DebugLog.log("SERIES", "moveList $moveListId → $raw")
                    } else {
                        listStatus = raw.ifBlank { "Could not change list on the site" }
                        DebugLog.log("SERIES", "moveList failed: $raw")
                    }
                    runPendingCommands()
                }
            }
            pendingReadRid != null -> {
                val markerRid = pendingReadRid ?: ""
                pendingReadRid = null
                // READ POSITION: ONE call moves NU's single tick (the site
                // clears the old marker itself); rid="0" clears it.
                s.evaluateJavascript(moveReadMarkerJs(markerRid, seriesSid)) { jsResult ->
                    val raw = unwrapJsResult(jsResult)
                    listStatus = when {
                        raw.startsWith("ok") -> "Read position updated on Novel Updates"
                        raw.startsWith("missing") -> "Could not update — series id missing, try ⋮ Refresh"
                        else -> raw.ifBlank { "Could not update read position on the site" }
                    }
                    DebugLog.log("SERIES", "moveMarker rid=$markerRid → $raw")
                    // Mirror the move into the cached page so the UI and the
                    // next cache read reflect it without waiting for a re-scrape.
                    val updated = page.copy(releases = page.releases.map { rel ->
                        when (markerRid) {
                            "0" -> if (chapterNum(rel.chapter) == lastReadNum) rel.copy(read = false) else rel
                            rel.rid -> rel.copy(read = true)
                            else -> rel
                        }
                    })
                    page = updated
                    viewModelScope.launch(Dispatchers.IO) {
                        saveSeriesCache(getApplication(), novel.seriesUrl, updated, maxOf(loadedPage, 1))
                    }
                }
            }
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }
}
