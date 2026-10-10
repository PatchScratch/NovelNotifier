package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Site search using Novel Updates' own live search (verified 2026-10-10):
 * the hidden WebView loads one NU page for a same-origin base, then every
 * keystroke (debounced) fires the site's nd_ajaxsearchmain AJAX and we poll
 * the stored response. Results are live suggestions — series link, cover,
 * highlighted title — and open straight into the series detail screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteSearchScreen(
    onOpenSeries: (Novel) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    var status by remember { mutableStateOf("Connecting to Novel Updates…") }
    var ready by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var searchSeq by remember { mutableIntStateOf(0) }

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
                onValueChange = { query = it },
                label = { Text("Search Novel Updates") },
                singleLine = true,
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
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results.size) { index ->
                val result = results[index]
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
        }
    }

    // Hidden WebView: one real page load gives every later search a same-origin
    // base — keystrokes never hit the site as page loads, only as the site's
    // own AJAX.
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
                        when {
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

    // Debounce: search 600 ms after typing stops (the site itself waits for
    // 2+ characters).
    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        if (query.trim().length < 2) {
            results = emptyList()
            status = "Type at least 2 letters"
            return@LaunchedEffect
        }
        delay(600)
        searchSeq += 1
    }

    LaunchedEffect(searchSeq) {
        if (searchSeq == 0 || !ready) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        val term = query.trim()
        status = "Searching…"
        results = emptyList()
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
        results = parsed
        status = if (parsed.isEmpty()) "No matches for \"$term\"" else "${parsed.size} matches — tap one to open"
        DebugLog.log("SEARCH", "'$term' → ${parsed.size} results")
    }
}

/** Await an evaluateJavascript result from a coroutine. */
private suspend fun WebView.evaluate(script: String): String =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resumeWith(Result.success(result))
        }
    }
