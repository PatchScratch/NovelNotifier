package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * After RSS reports a *higher* c-number, scrape that one series page and merge
 * new RELEASES rows into series_cache. Used from MainActivity while the list
 * screen is showing (chapterFetchQueue).
 *
 * Rules:
 *   8 seconds between hits so we do not trip Cloudflare.
 *   "Just a moment" → one retry, then skip that title.
 *   mergeReleases never drops old rows and never duplicates chapter+url.
 *   applyListChapter writes the new c-number into list_cache.json.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SeriesChapterFetcher(
    queue: List<Novel>,
    onFinished: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val next = queue.firstOrNull() ?: return
    var canLoad by remember(next.seriesUrl) { mutableStateOf(false) }
    var tries by remember(next.seriesUrl) { mutableIntStateOf(0) }

    // Gap before each series-page hit. Keyed on seriesUrl so a new title resets the timer.
    LaunchedEffect(next.seriesUrl) {
        canLoad = false
        delay(8.seconds)
        canLoad = true
    }

    if (!canLoad) return

    // Hidden 1dp WebView, parked off-screen.
    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .offset(x = 4000.dp),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
            }
        },
        update = { webView ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val script = seriesPageJs()
                    view?.evaluateJavascript(script) { result ->
                        val raw = unwrapJsResult(result)
                        if (raw == "CF" || raw.isBlank()) {
                            if (tries < 1) {
                                tries += 1
                                canLoad = false
                            } else {
                                onFinished(next.title, false)
                            }
                            return@evaluateJavascript
                        }
                        val title = next.title
                        val seriesUrl = next.seriesUrl
                        val latestChapter = next.latestChapter
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val parsed = parseSeriesPage(raw)
                                val cachedPair = loadSeriesCache(context, seriesUrl)
                                val old = cachedPair?.first ?: SeriesPage()
                                val merged = mergeReleases(old.releases, parsed.releases)
                                val page = old.copy(
                                    title = parsed.title.ifBlank { old.title },
                                    author = parsed.author.ifBlank { old.author },
                                    rating = parsed.rating.ifBlank { old.rating },
                                    description = parsed.description.ifBlank { old.description },
                                    genres = parsed.genres.ifBlank { old.genres },
                                    tags = parsed.tags.ifBlank { old.tags },
                                    type = parsed.type.ifBlank { old.type },
                                    status = parsed.status.ifBlank { old.status },
                                    coverUrl = usableCoverUrl(parsed.coverUrl).ifBlank { old.coverUrl },
                                    sid = parsed.sid.ifBlank { old.sid },
                                    releases = merged,
                                    maxPage = maxOf(old.maxPage, parsed.maxPage)
                                )
                                saveSeriesCache(context, seriesUrl, page, maxOf(1, cachedPair?.second ?: 1))
                                applyListChapter(context, title, latestChapter)
                                DebugLog.log(
                                    "RSSSYNC",
                                    "'$title' rows=${parsed.releases.size} total=${merged.size} ok=${parsed.releases.isNotEmpty()}"
                                )
                                parsed.releases.isNotEmpty()
                            }
                            onFinished(title, ok)
                        }
                    }
                }
            }
            val target = next.seriesUrl.trimEnd('/') + "/"
            if ((webView.url ?: "") != target) webView.loadUrl(target)
        }
    )
}
