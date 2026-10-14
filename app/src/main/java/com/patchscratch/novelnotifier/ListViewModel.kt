package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NU reading-list home state (Phase 4b) on a SiteClient Session — the
 * screen's hidden WebView and its `siteSync`/`listReloadKey` recomposition
 * machines are gone. Loads are explicit: one session per scrape burst,
 * opened and closed here, so the shared NU WebView returns to the pacer
 * queue the moment a scrape finishes.
 *
 * Paint order (unchanged from the flag era):
 *   1. list_cache.json paints instantly.
 *   2. A scrape runs when the cache is stale/empty, the drawer opens an
 *      uncached list, the user pulls to refresh, or a previous CF block
 *      asked for a re-check.
 *   3. Site rows merge into the cache; an empty scrape with real cached
 *      c-numbers is kept, not wiped (CF/bot pages return no series).
 *
 * Cloudflare during a scrape surfaces through SiteClient's escalating
 * cfNeeded → the root ChallengeOverlay (Stage 4); this screen no longer
 * has its own pane.
 */
class ListViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    var lists by mutableStateOf(emptyList<ReadingList>())
        private set
    var selectedList by mutableStateOf("My Reading List")
        private set
    var selectedListId by mutableStateOf("")
        private set
    var listNovels by mutableStateOf(emptyList<Novel>())
        private set
    var novelsByList by mutableStateOf<Map<String, List<Novel>>>(emptyMap())
        private set
    var listStatus by mutableStateOf("")
        private set
    var syncing by mutableStateOf(false)
        private set

    /** Rows missing covers — feeds the (already-migrated) CoverPrefetcher. */
    var coverQueue by mutableStateOf(emptyList<Novel>())
        private set

    /**
     * Cross-VM side effects of a successful scrape, consumed once by the
     * screen (RSS feed capture + read-marker merge live in NuViewModel).
     */
    data class SideEffects(val rssUrl: String, val readRssUrl: String, val lastReads: Map<String, Pair<String, String>>)
    var pendingSideEffects by mutableStateOf<SideEffects?>(null)

    private var initialized = false
    private var session: SiteClient.Session? = null
    /** URL the session last finished on — login-redirect detection. */
    private var lastFinishedUrl = ""

    /** First composition: paint from disk, then scrape if stale or CF-flagged. */
    fun initialize(checkHours: Int) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val snap = withContext(Dispatchers.IO) { loadListSnapshot(getApplication()) }
            if (snap.lists.isNotEmpty()) {
                paintSnapshot(snap)
                val canUpdate = selectedListId.isBlank() || selectedListId !in loadIdSet(prefs, "list_update_off")
                if (canUpdate && listSyncIsStale(prefs, checkHours)) {
                    listStatus = "List older than $checkHours hours — checking site..."
                    scrape()
                }
            } else {
                listStatus = "Loading lists..."
                scrape()
            }
            if (siteCheckNeeded(prefs)) {
                listStatus = "Checking Novel Updates..."
                scrape()
            }
        }
    }

    private fun paintSnapshot(snap: ListSnapshot) {
        lists = snap.lists
        novelsByList = snap.novelsByList
        val pick = snap.lists.find { it.id == snap.selectedId } ?: snap.lists.first()
        selectedListId = pick.id
        selectedList = if (snap.selectedId == pick.id && snap.selectedName.isNotBlank()) {
            snap.selectedName
        } else {
            pick.name
        }
        listNovels = snap.novelsByList[pick.id].orEmpty()
        listStatus = if (listNovels.isEmpty()) {
            "Pull down to refresh $selectedList"
        } else {
            "Cached ${listNovels.size} series · ${lastListSyncLabel(prefs)}"
        }
    }

    /** Drawer tap on a list: cached rows paint now; uncached lists scrape. */
    fun selectList(list: ReadingList) {
        selectedList = list.name
        selectedListId = list.id
        coverQueue = emptyList()
        val cached = novelsByList[list.id].orEmpty()
        listNovels = cached
        if (cached.isNotEmpty()) {
            listStatus = "Cached ${cached.size} series"
        } else if (list.id in loadIdSet(prefs, "list_update_off")) {
            listStatus = "Updates off for ${list.name}"
        } else {
            listStatus = "Loading ${list.name}..."
            scrape()
        }
        persistLists()
    }

    /** Pull-to-refresh on a site list (RSS-mode refresh is handled by the screen). */
    fun pullToRefreshList() {
        if (selectedListId.isBlank()) return
        if (selectedListId in loadIdSet(prefs, "list_update_off")) return
        listStatus = "Checking $selectedList..."
        scrape()
    }

    /** Load the reading-list page (selected list if known) through a Session. */
    fun scrape() {
        if (syncing) return
        syncing = true
        val target = "https://www.novelupdates.com/reading-list/" +
            if (selectedListId.isNotBlank()) "?list=$selectedListId" else ""
        val s = SiteClient.openSession(SiteClient.Site.NU)
        session = s
        lastFinishedUrl = ""
        s.onPageLoaded = { url -> lastFinishedUrl = url }
        s.loadUrl(target, listPageJs()) { raw ->
            s.onPageLoaded = null
            s.close()
            session = null
            syncing = false
            handleScrape(raw)
        }
    }

    private fun handleScrape(rawQuoted: String) {
        val raw = if (rawQuoted == "CF") "CF" else unwrapJsResult(rawQuoted)
        when {
            raw == "CF" -> {
                markSiteCheckNeeded(prefs)
                listStatus = "Site check needed — try again shortly"
                DebugLog.log("SYNC", "list scrape hit Cloudflare")
            }
            isLoginRedirect(lastFinishedUrl) || raw.contains("wp-login.php") -> {
                listStatus = "Session expired — log in from Settings"
                DebugLog.log("SYNC", "list scrape hit login redirect")
            }
            else -> {
                // Never let a blank/placeholder site cover wipe one we had.
                val oldCoverByUrl = novelsByList[selectedListId].orEmpty()
                    .mapNotNull { n ->
                        n.seriesUrl.takeIf { it.isNotBlank() }
                            ?.let { it to usableCoverUrl(n.coverUrl) }
                    }
                    .toMap()
                val coverCache = loadCoverCache(prefs)
                val result = parseListPageResult(raw, coverCache, oldCoverByUrl)

                // List names ride every page; the first paint discovers them.
                val parsedLists = raw.split(":::NOVELS:::").getOrNull(0).orEmpty()
                    .split("|||").mapNotNull { item ->
                        val bits = item.split("===", limit = 2)
                        if (bits.size == 2) ReadingList(bits[0], bits[1]) else null
                    }.distinctBy { it.name }
                if (parsedLists.isNotEmpty()) lists = parsedLists
                if (selectedListId.isBlank() && parsedLists.isNotEmpty()) {
                    val first = parsedLists.first()
                    selectedListId = first.id
                    selectedList = first.name
                }

                val lastReads = result.lastReads
                val parsedNovels = result.novels
                // Row-capture visibility: what the scraper took from this page.
                DebugLog.log(
                    "SYNC",
                    "list '$selectedList' parsed ${parsedNovels.size} rows: " +
                        parsedNovels.take(30).joinToString { it.title.take(28) }
                )
                val cachedRows = novelsByList[selectedListId].orEmpty().ifEmpty { listNovels }
                val cachedLooksReal = cachedRows.any { chapterNum(it.latestChapter) > 0 }
                // Cloudflare / empty HTML must never wipe a real library.
                if (parsedNovels.isEmpty() && cachedLooksReal) {
                    listNovels = cachedRows
                    listStatus = "Site parse returned nothing — keeping ${cachedRows.size} cached series"
                    return
                }
                val stamped = stampChapterTimes(listNovels, parsedNovels)
                val changed = chapterChangeCount(listNovels, stamped)
                val nextMap = putExclusiveList(novelsByList, selectedListId, stamped)
                listNovels = stamped
                novelsByList = nextMap
                val updateOff = loadIdSet(prefs, "list_update_off")
                coverQueue = if (selectedListId in updateOff) {
                    emptyList()
                } else {
                    parsedNovels.filter {
                        usableCoverUrl(it.coverUrl).isBlank() &&
                            it.seriesUrl.isNotBlank() &&
                            usableCoverUrl(coverCache[it.title]).isBlank()
                    }
                }
                if (parsedNovels.isNotEmpty()) {
                    markListSynced(prefs)
                    clearSiteCheckNeeded(prefs)
                    DebugLog.log(
                        "SYNC",
                        "list '$selectedList' rows=${parsedNovels.size} " +
                            "coversMissing=${parsedNovels.count { usableCoverUrl(it.coverUrl).isBlank() }}"
                    )
                }
                listStatus = when {
                    parsedNovels.isEmpty() -> "No series found in $selectedList"
                    changed > 0 -> "Updated $selectedList · $changed chapter change" + if (changed == 1) "" else "s"
                    else -> "Updated $selectedList · no chapter changes"
                }
                persistLists()
                pendingSideEffects = SideEffects(result.rssUrl, result.readRssUrl, lastReads)
            }
        }
    }

    /** CoverPrefetcher delivered a cover: patch the row and drain the queue. */
    fun coverPrefetched(title: String, url: String) {
        coverQueue = coverQueue.filterNot { it.title == title }
        listNovels = listNovels.map {
            if (it.title == title) it.copy(coverUrl = url) else it
        }
    }

    /** CoverPrefetcher skipped a host: rotate it to the back of the queue. */
    fun coverSkipped(title: String) {
        val skipped = coverQueue.find { it.title == title }
        coverQueue = coverQueue.filterNot { it.title == title } + listOfNotNull(skipped)
    }

    /** 20 s slow-site hint (a slow page is NOT proof of a CF check). */
    fun noteStillWaiting() {
        if (syncing) listStatus = "Still waiting on Novel Updates — the site is slow right now"
    }

    private fun persistLists() {
        val snapshot = ListSnapshot(
            lists = lists,
            selectedId = selectedListId,
            selectedName = selectedList,
            novelsByList = novelsByList
        )
        viewModelScope.launch(Dispatchers.IO) { saveListSnapshot(getApplication(), snapshot) }
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }
}
