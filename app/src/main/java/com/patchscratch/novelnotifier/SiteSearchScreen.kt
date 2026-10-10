package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Site search, two layers (both the site's own machinery):
 *
 *  Suggestions — as you type (debounced), NU's live nd_ajaxsearchmain AJAX
 *  fired same-origin from one base page; instant, for jumping to a known title.
 *
 *  Full results — "Search all" (or the keyboard's search key) loads the
 *  desktop results page, /series-finder/?sf=1&sh=<term>&sort=sdate&order=desc,
 *  server-rendered and paged (&pg=N, ~41 rows/page, pager carries the total).
 *  "Load more" pages through; each page is an explicit, human-paced action.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteSearchScreen(
    onOpenSeries: (Novel) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<SearchResult>()) }
    var fullResults by remember { mutableStateOf(emptyList<SearchResult>()) }
    var status by remember { mutableStateOf("Connecting to Novel Updates…") }
    var ready by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var searchSeq by remember { mutableIntStateOf(0) }

    // Full-results mode state.
    var fullMode by remember { mutableStateOf(false) }
    var finderPage by remember { mutableIntStateOf(0) }
    var finderMax by remember { mutableIntStateOf(1) }
    var finderLoading by remember { mutableStateOf(false) }
    var finderSeq by remember { mutableIntStateOf(0) }

    /** Load one series-finder page through the hidden WebView. */
    fun loadFinderPage(page: Int) {
        val wv = webView ?: return
        if (finderLoading) return
        finderLoading = true
        finderPage = page
        status = "Loading results page $page…"
        wv.loadUrl(
            "https://www.novelupdates.com/series-finder/?sf=1&sh=" +
                android.net.Uri.encode(query.trim()) + "&sort=sdate&order=desc" +
                if (page > 1) "&pg=$page" else ""
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenInsets()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("←") }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    fullMode = false
                    fullResults = emptyList()
                    finderMax = 1
                },
                label = { Text("Search Novel Updates") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (ready && query.trim().length >= 2) {
                            fullMode = true
                            suggestions = emptyList()
                            loadFinderPage(1)
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
            )
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val shown = if (fullMode) fullResults else suggestions
        if (!fullMode && ready && query.trim().length >= 2) {
            TextButton(
                onClick = {
                    fullMode = true
                    suggestions = emptyList()
                    loadFinderPage(1)
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Search all results →") }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown) { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSeries(result.toNovel()) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverThumb(
                        url = result.coverUrl,
                        seriesUrl = result.seriesUrl,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        result.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            if (fullMode && !finderLoading && fullResults.isNotEmpty() && finderPage < finderMax) {
                item {
                    Button(
                        onClick = { loadFinderPage(finderPage + 1) },
                        modifier = Modifier.padding(16.dp)
                    ) { Text("Load more (page ${finderPage + 1} of $finderMax)") }
                }
            }
        }
    }

    // Hidden WebView: one base page load gives the suggestion AJAX a same-origin
    // home; full-result pages load here as real navigations.
    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .offset(x = 4000.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val pageUrl = url.orEmpty()
                        when {
                            pageUrl.contains("series-finder") -> {
                                view?.evaluateJavascript(finderPageJs()) { result ->
                                    val raw = unwrapJsResult(result)
                                    if (raw == "CF") {
                                        status = "Site check needed — try again shortly"
                                        finderLoading = false
                                        return@evaluateJavascript
                                    }
                                    val page = parseFinderResults(raw)
                                    if (page.results.isEmpty()) {
                                        status = "Could not read page $finderPage — tap Load more / search again"
                                        finderLoading = false
                                        DebugLog.log("SEARCH", "finder pg=$finderPage empty, raw ${raw.length}B head ${raw.take(200)}")
                                        return@evaluateJavascript
                                    }
                                    finderMax = page.maxPage
                                    fullResults = if (finderPage <= 1) page.results else fullResults + page.results
                                    finderLoading = false
                                    status = "Page $finderPage of $finderMax — ${fullResults.size} results"
                                    DebugLog.log("SEARCH", "finder '$query' pg=$finderPage rows=${page.results.size} max=${page.maxPage}")
                                }
                            }
                            isLoginRedirect(url) -> status = "Session expired — log in from Settings"
                            isCloudflareUrl(url) || isCloudflareTitle(view?.title) ->
                                status = "Site check needed — open your reading list once, then come back"
                            else -> {
                                ready = true
                                status = if (query.isBlank()) "Type at least 2 letters" else status
                            }
                        }
                    }
                }
                loadUrl("https://www.novelupdates.com/")
            }
        },
        update = { view -> if (webView == null) webView = view }
    )

    // Suggestions: search 600 ms after typing stops (only in suggestion mode).
    LaunchedEffect(query, ready, fullMode) {
        if (!ready || fullMode) return@LaunchedEffect
        if (query.trim().length < 2) {
            suggestions = emptyList()
            status = "Type at least 2 letters"
            return@LaunchedEffect
        }
        delay(600)
        searchSeq += 1
    }

    LaunchedEffect(searchSeq, fullMode) {
        if (searchSeq == 0 || !ready || fullMode) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        val term = query.trim()
        status = "Searching…"
        wv.evaluateJavascript(searchFireJs(term), null)
        var raw = "pending"
        var tries = 0
        while (tries < 16 && raw == "pending") {
            delay(400)
            tries += 1
            raw = unwrapJsResult(wv.evaluate(SEARCH_READ_JS))
        }
        if (raw == "pending" || raw.isBlank()) {
            status = "Search timed out — try again"
            DebugLog.log("SEARCH", "timeout for '$term'")
            return@LaunchedEffect
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

private fun SearchResult.toNovel(): Novel = Novel(
    title = title,
    latestChapter = "",
    seriesUrl = seriesUrl,
    coverUrl = coverUrl
)

/** Await an evaluateJavascript result from a coroutine. */
private suspend fun WebView.evaluate(script: String): String =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resumeWith(Result.success(result))
        }
    }
