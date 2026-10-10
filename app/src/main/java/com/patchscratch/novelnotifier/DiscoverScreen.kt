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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Discover — Novel Updates' ranking grids (original app's Discover page).
 * Rank tabs verified live (2026-10-10): popmonth, popular, week, month,
 * sixmonths. The grid is the same div.search_title markup as series-finder,
 * so finderPageJs/parseFinderResults serve both. Paged by &pg=; NU's pager
 * advertises pg=1000 as a "last page" sentinel, so an empty page simply ends
 * the list. Each page loads only on an explicit Load-more tap.
 */
private val RANK_TABS = listOf(
    "popmonth" to "Popular This Month",
    "popular" to "All-Time",
    "week" to "This Week",
    "month" to "This Month",
    "sixmonths" to "Last 6 Months"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscoverScreen(
    onOpenSeries: (Novel) -> Unit,
    onBack: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    var status by remember { mutableStateOf("Connecting to Novel Updates…") }
    var page by remember { mutableIntStateOf(0) }
    var maxPage by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Infinite scroll: one auto-load at a time, with a small politeness gap so
    // flinging through the list cannot machine-gun page loads at the site.
    val listState = rememberLazyListState()
    var lastAutoLoadAt by remember { mutableLongStateOf(0L) }
    val rankKey = RANK_TABS[tab].first

    fun loadPage(next: Int) {
        val wv = webView ?: return
        if (loading) return
        loading = true
        page = next
        if (next == 1) results = emptyList()
        status = "Loading $rankKey page $next…"
        wv.loadUrl("https://www.novelupdates.com/series-ranking/?rank=$rankKey" + if (next > 1) "&pg=$next" else "")
    }

    // Switching tabs restarts from page 1.
    LaunchedEffect(tab) {
        if (webView != null) loadPage(1)
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 4 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, loading, page, maxPage, tab) {
        if (!nearEnd || loading || page >= maxPage) return@LaunchedEffect
        if (System.currentTimeMillis() - lastAutoLoadAt < 1_500L) return@LaunchedEffect
        lastAutoLoadAt = System.currentTimeMillis()
        loadPage(page + 1)
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
            Text("Discover", style = MaterialTheme.typography.titleMedium)
        }
        ScrollableTabRow(selectedTabIndex = tab) {
            RANK_TABS.forEachIndexed { index, (_, label) ->
                Tab(
                    selected = tab == index,
                    onClick = { if (tab != index) tab = index },
                    text = { Text(label, maxLines = 1) }
                )
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(results) { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOpenSeries(
                                Novel(
                                    title = result.title,
                                    latestChapter = "",
                                    seriesUrl = result.seriesUrl,
                                    coverUrl = result.coverUrl
                                )
                            )
                        }
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
            item {
                Text(
                    when {
                        loading -> "Loading more…"
                        results.isNotEmpty() && page >= maxPage -> "End of results"
                        else -> " "
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    // Hidden WebView hosting the grid pages.
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
                            pageUrl.contains("series-ranking") -> {
                                view?.evaluateJavascript(finderPageJs()) { result ->
                                    val raw = unwrapJsResult(result)
                                    if (raw == "CF") {
                                        status = "Site check needed — try again shortly"
                                        loading = false
                                        return@evaluateJavascript
                                    }
                                    val grid = parseFinderResults(raw)
                                    if (grid.results.isEmpty()) {
                                        // NU's pager advertises pg=1000 as a sentinel;
                                        // an empty page is simply the end of the list.
                                        maxPage = page
                                        loading = false
                                        status = "${results.size} series — end of $rankKey"
                                        DebugLog.log("DISCOVER", "$rankKey pg=$page empty → end")
                                        return@evaluateJavascript
                                    }
                                    maxPage = grid.maxPage
                                    results = results + grid.results
                                    loading = false
                                    status = "${results.size} series — page $page of $maxPage"
                                    DebugLog.log("DISCOVER", "$rankKey pg=$page rows=${grid.results.size}")
                                }
                            }
                            isLoginRedirect(url) -> {
                                status = "Session expired — log in from Settings"
                                loading = false
                            }
                            isCloudflareUrl(url) || isCloudflareTitle(view?.title) -> {
                                status = "Site check needed — open your reading list once, then come back"
                                loading = false
                            }
                        }
                    }
                }
                // The initial grid load is driven by LaunchedEffect(tab), not
                // here — a factory load would race it and fetch page 1 twice.
            }
        },
        update = { view -> if (webView == null) webView = view }
    )
}
