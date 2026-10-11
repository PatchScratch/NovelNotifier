package com.patchscratch.novelnotifier

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit

/**
 * Phase 3.1 — Browser mode: read chapters inside the app.
 *
 * What the reader is: a full-screen WebView that follows the chapter URL
 * wherever it goes (NU redirect links, translator sites, Cloudflare gates —
 * the user can complete any check right here, same shared cookie session).
 * Chapter rows and READ CHAPTER hand over the series' releases in READING
 * ORDER (oldest → newest) so ‹ Prev / Next › walk the book.
 *
 * Per-novel JavaScript toggle: some aggregator sites only render chapters with
 * JS; some spam pop-ups/redirects unless JS is off. The choice is remembered
 * per series (reader_js_off pref, keyed by seriesUrl). Ad hosts are always
 * blocked (requests AND navigations) from the list below.
 *
 * Decoy-link hazard (roadmap): NU hides fake chapter links with CSS tricks —
 * browser mode sidesteps parsing entirely; what the user sees is what loads.
 * Reader-mode extraction (computed-style filtering) is Phase 3.2.
 */

/** One open reading session. [chapters] must be in reading order (ascending). */
data class ReaderTarget(
    val seriesTitle: String,
    val seriesUrl: String,
    val chapters: List<Release>,
    val startIndex: Int
)

/**
 * Ad + tracker hosts blocked inside the reader (requests AND navigations).
 * Match rule: host equals an entry or ends with ".entry".
 */
val READER_BLOCKED_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "google-analytics.com", "googletagmanager.com", "adservice.google.com",
    "admob.com", "app-measurement.com",
    "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
    "openx.net", "casalemedia.com", "criteo.com", "criteo.net",
    "smartadserver.com", "sharethrough.com", "media.net", "bidswitch.net",
    "taboola.com", "outbrain.com", "scorecardresearch.com",
    "quantserve.com", "quantcount.com",
    "amazon-adsystem.com", "hotjar.com", "hotjar.io",
    "mc.yandex.ru", "an.yandex.ru",
    "adsterra.com", "propellerads.com", "popads.net", "popcash.net",
    "adcash.com", "zedo.com", "a-ads.com"
)

fun isBlockedReaderHost(host: String): Boolean {
    if (host.isBlank()) return false
    return READER_BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
}

@Composable
fun ReaderScreen(
    target: ReaderTarget,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    var index by remember(target) { mutableIntStateOf(target.startIndex.coerceIn(0, target.chapters.lastIndex)) }
    var jsOn by remember(target.seriesUrl) {
        mutableStateOf(target.seriesUrl !in loadIdSet(prefs, "reader_js_off"))
    }
    var loadKey by remember(target) { mutableIntStateOf(0) } // ⋮ Reload
    // (url, loadKey, jsOn) the WebView last loaded under — any change reloads:
    // Next/Prev changes the url, ⋮ Reload bumps the key, the JS toggle changes
    // the flag (loadUrl on the same URL is still a reload).
    var loadedStamp by remember(target) { mutableStateOf(Triple("", -1, true)) }
    var progress by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val chapter = target.chapters.getOrNull(index) ?: return

    fun toggleJs() {
        jsOn = !jsOn
        val off = loadIdSet(prefs, "reader_js_off")
        val next = if (jsOn) off - target.seriesUrl else off + target.seriesUrl
        prefs.edit { putStringSet("reader_js_off", next) }
        loadKey += 1 // reload under the new setting
    }

    fun openExternally() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapter.url)))
    }

    BackHandler { onBack() }

    // Edge-to-edge: without insets the top bar hides under the status bar and
    // the chapter nav under the gesture bar — taps there feed system UI.
    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        // --- top bar: back · chapter · menu ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            TextButton(onClick = onBack) { Text("‹") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.chapter,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = target.seriesTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { showMenu = true }) { Text("⋮") }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (jsOn) "JavaScript: ON (tap to disable)" else "JavaScript: OFF (tap to enable)") },
                    onClick = { showMenu = false; toggleJs() }
                )
                DropdownMenuItem(
                    text = { Text("Reload") },
                    onClick = { showMenu = false; loadKey += 1 }
                )
                DropdownMenuItem(
                    text = { Text("Open in browser") },
                    onClick = { showMenu = false; openExternally() }
                )
            }
        }
        if (progress in 1..99) LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth()
        )

        // --- the page ---
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.javaScriptEnabled = jsOn
                    settings.domStorageEnabled = jsOn
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // Pop-up ads: no new windows ever; _blank navigates here
                    // where shouldOverrideUrlLoading can veto ad hosts.
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return false
                            return isBlockedReaderHost(host) // swallow ad navigations
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val host = request?.url?.host ?: return null
                            if (!isBlockedReaderHost(host)) return null
                            return WebResourceResponse(
                                "text/plain", "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            loadFailed = false
                            super.onPageStarted(view, url, favicon)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // Only the main frame matters; subframes 404 all the time.
                            if (request?.isForMainFrame == true) loadFailed = true
                            super.onReceivedError(view, request, error)
                        }
                    }
                    loadUrl(chapter.url)
                }
            },
            update = { webView ->
                val stamp = Triple(chapter.url, loadKey, jsOn)
                if (stamp != loadedStamp) {
                    loadedStamp = stamp
                    webView.settings.javaScriptEnabled = jsOn
                    webView.settings.domStorageEnabled = jsOn
                    webView.loadUrl(chapter.url)
                }
            }
        )
        if (loadFailed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    "Page did not load (site down or blocked)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    loadFailed = false
                    loadKey += 1
                }) { Text("Retry") }
            }
        }

        // --- bottom bar: walk the book in reading order ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            TextButton(
                onClick = { if (index > 0) index -= 1 },
                enabled = index > 0
            ) { Text("‹ Prev") }
            Text(
                text = "${index + 1} / ${target.chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = false) {}
            )
            TextButton(
                onClick = { if (index < target.chapters.lastIndex) index += 1 },
                enabled = index < target.chapters.lastIndex
            ) { Text("Next ›") }
        }
    }
}
