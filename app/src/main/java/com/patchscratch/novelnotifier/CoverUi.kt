package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.patchscratch.novelnotifier.R
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Cover helpers used by the list screen.
 *
 * CoverPrefetcher — hidden WebView that opens one series page at a time and
 *                   copies `.seriesimg img` src. [gapSeconds] spaces hits out
 *                   so Cloudflare is less likely to trip. A "Just a moment"
 *                   page waits 20s, then skips that title to the end of the queue.
 *
 * CoverThumb      — Coil image, or an empty grey box if we have no URL yet.
 *
 * Bulk prefetch (hundreds of covers after you leave the app) lives in
 * SyncEngineService.kt, not here.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CoverPrefetcher(
    queue: List<Novel>,
    onCoverFound: (String, String) -> Unit,
    onSkip: (String) -> Unit,
    gapSeconds: Int = 8
) {
    val next = queue.firstOrNull() ?: return
    var canLoad by remember(next.title) { mutableStateOf(false) }
    var tries by remember(next.title) { mutableIntStateOf(0) }

    LaunchedEffect(next.title, tries) {
        canLoad = false
        delay((if (tries > 0) 20 else gapSeconds).seconds)
        canLoad = true
    }

    if (!canLoad) return

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .offset(x = 4000.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
            }
        },
        update = { webView ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(coverJs()) { result ->
                        val cover = unwrapJsResult(result)
                        when {
                            cover.startsWith("http") -> onCoverFound(next.title, cover)
                            cover == "CF" || tries < 2 -> {
                                tries += 1
                                canLoad = false
                            }
                            else -> onSkip(next.title)
                        }
                    }
                }
            }
            val current = webView.url ?: ""
            if (current != next.seriesUrl) {
                webView.loadUrl(next.seriesUrl)
            }
        }
    )
}

/**
 * Small cover tile. Loads the local CoverStore file for [seriesUrl] when we
 * have one (offline + backup-friendly), otherwise the URL via Coil; no cover
 * at all → the puzzled shiba placeholder with its "No cover available" caption.
 */
@Composable
fun CoverThumb(
    url: String,
    seriesUrl: String = "",
    modifier: Modifier = Modifier,
    // Bump to re-read CoverStore (e.g. after AI cover generation).
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    val local = remember(seriesUrl, refreshKey) {
        seriesUrl.takeIf { it.isNotBlank() }?.let { CoverStore.localFile(context, it) }
    }
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        val model: Any? = local ?: url.takeIf { it.isNotBlank() }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_shiba_no_cover),
                    contentDescription = "No cover available",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = "No cover\navailable",
                    color = Color(0xFF7A5A3B),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xB3F6E3C5))
                        .padding(vertical = 1.dp)
                )
            }
        }
    }
}
