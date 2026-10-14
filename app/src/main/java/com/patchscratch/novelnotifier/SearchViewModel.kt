package com.patchscratch.novelnotifier

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Site search state (Phase 4b) on a SiteClient Session — the screen's
 * private hidden WebView is gone. Two layers, both the site's own machinery:
 *
 *  Suggestions — as you type (debounced 600 ms), NU's live nd_ajaxsearchmain
 *  AJAX fired same-origin from one base page; instant, for jumping to a
 *  known title.
 *
 *  Full results — "Search all" (or the keyboard's search key) loads the
 *  desktop results page, /series-finder/?sf=1&sh=<term>&sort=sdate&order=desc,
 *  server-rendered and paged (&pg=N, ~41 rows/page, pager carries the total).
 *
 * The Session holds the shared NU WebView EXCLUSIVELY while the search
 * screen is composed (the queue-based scrapers wait); [leave] releases it
 * the moment the screen exits. Nothing else can navigate the base page
 * away mid-search, which is exactly what the same-origin AJAX trick needs.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf(emptyList<SearchResult>())
        private set
    var fullResults by mutableStateOf(emptyList<SearchResult>())
        private set
    var status by mutableStateOf("Connecting to Novel Updates…")
        private set
    var ready by mutableStateOf(false)
        private set

    var fullMode by mutableStateOf(false)
        private set
    var finderPage by mutableIntStateOf(0)
        private set
    var finderMax by mutableIntStateOf(1)
        private set
    var finderLoading by mutableStateOf(false)
        private set

    private var session: SiteClient.Session? = null
    private var suggestJob: Job? = null

    /** Guards against finder replies landing after the user retyped or left. */
    private var loadSeq = 0

    /** Screen entered composition: take the NU WebView and load the base page
     *  that gives the suggestion AJAX its same-origin home. */
    fun enter() {
        if (session != null) return
        val s = SiteClient.openSession(SiteClient.Site.NU)
        session = s
        status = "Connecting to Novel Updates…"
        s.loadUrl("https://www.novelupdates.com/") { raw ->
            if (raw == "CF") {
                status = "Site check needed — try again shortly"
            } else {
                ready = true
                if (query.isBlank()) status = "Type at least 2 letters"
            }
        }
    }

    /** Screen left composition: release the exclusive WebView immediately. */
    fun leave() {
        suggestJob?.cancel()
        suggestJob = null
        loadSeq++          // in-flight finder replies become stale
        session?.close()
        session = null
        ready = false
        finderLoading = false
    }

    fun onQueryChange(text: String) {
        query = text
        fullMode = false
        fullResults = emptyList()
        finderMax = 1
        loadSeq++
        suggestJob?.cancel()
        if (!ready || text.trim().length < 2) {
            suggestions = emptyList()
            status = "Type at least 2 letters"
            return
        }
        suggestJob = viewModelScope.launch {
            delay(600)
            fireSuggestions()
        }
    }

    /** Keyboard search key or "Search all results →". */
    fun startFullSearch() {
        if (!ready || query.trim().length < 2) return
        fullMode = true
        suggestions = emptyList()
        suggestJob?.cancel()
        loadFinderPage(1)
    }

    /** Load one series-finder page (explicit action or infinite scroll). */
    fun loadFinderPage(page: Int) {
        val s = session ?: return
        if (finderLoading) return
        finderLoading = true
        finderPage = page
        status = "Loading results page $page…"
        val seq = ++loadSeq
        val url =
            "https://www.novelupdates.com/series-finder/?sf=1&sh=" +
                android.net.Uri.encode(query.trim()) + "&sort=sdate&order=desc" +
                if (page > 1) "&pg=$page" else ""
        s.loadUrl(url, finderPageJs()) { raw ->
            if (seq != loadSeq) return@loadUrl
            val unwrapped = if (raw == "CF") "CF" else unwrapJsResult(raw)
            when {
                unwrapped == "CF" -> {
                    status = "Site check needed — try again shortly"
                    finderLoading = false
                }
                unwrapped.contains("wp-login") -> {
                    status = "Session expired — log in from Settings"
                    finderLoading = false
                }
                else -> {
                    val parsed = parseFinderResults(unwrapped)
                    if (parsed.results.isEmpty()) {
                        status = "Could not read page $finderPage — tap Load more / search again"
                        finderLoading = false
                        DebugLog.log("SEARCH", "finder pg=$finderPage empty, raw ${unwrapped.length}B head ${unwrapped.take(200)}")
                    } else {
                        finderMax = parsed.maxPage
                        fullResults = if (finderPage <= 1) parsed.results else fullResults + parsed.results
                        finderLoading = false
                        status = "Page $finderPage of $finderMax — ${fullResults.size} results"
                        DebugLog.log("SEARCH", "finder '$query' pg=$finderPage rows=${parsed.results.size} max=${parsed.maxPage}")
                    }
                }
            }
        }
    }

    private suspend fun fireSuggestions() {
        val s = session ?: return
        val term = query.trim()
        status = "Searching…"
        s.evaluateJavascript(searchFireJs(term)) { }
        var raw = "pending"
        var tries = 0
        while (tries < 16 && raw == "pending") {
            delay(400)
            tries += 1
            raw = unwrapJsResult(s.awaitEval(SEARCH_READ_JS))
        }
        if (raw == "pending" || raw.isBlank()) {
            status = "Search timed out — try again"
            DebugLog.log("SEARCH", "timeout for '$term'")
            return
        }
        val parsed = parseSearchResults(raw)
        suggestions = parsed
        status = if (parsed.isEmpty()) "No matches for \"$term\"" else "${parsed.size} quick matches — or Search all"
        DebugLog.log(
            "SEARCH",
            if (parsed.isEmpty()) "'$term' → 0 results; raw ${raw.length}B head: ${raw.take(300)}"
            else "'$term' → ${parsed.size} suggestions"
        )
    }
}

/** Await a Session.evaluateJavascript result from a coroutine. */
private suspend fun SiteClient.Session.awaitEval(script: String): String =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resumeWith(Result.success(result))
        }
    }
