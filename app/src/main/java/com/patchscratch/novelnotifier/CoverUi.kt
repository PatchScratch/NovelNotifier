package com.patchscratch.novelnotifier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Cover image UI: a prefetcher that walks a queue of series, extracting
 * cover URLs from each series page (hidden WebView via SiteClient — Phase 4a
 * migration, previously its own 1dp off-screen WebView), and the CoverThumb
 * tile used everywhere covers render (grids, detail screens).
 *
 * Bulk prefetch (hundreds of covers after you leave the app) lives in
 * SyncEngineService.kt, not here.
 */
@Composable
fun CoverPrefetcher(
    queue: List<Novel>,
    onCoverFound: (String, String) -> Unit,
    onSkip: (String) -> Unit,
    gapSeconds: Int = 8
) {
    val next = queue.firstOrNull() ?: return
    var tries by remember(next.title) { mutableIntStateOf(0) }
    var canRequest by remember(next.title) { mutableStateOf(false) }

    // Local pacing gap (8s, 20s on retry) — sits on top of SiteClient's
    // broader 10s politeness gap to avoid double-tapping the same page.
    LaunchedEffect(next.title, tries) {
        canRequest = false
        delay((if (tries > 0) 20 else gapSeconds).seconds)
        canRequest = true
    }

    // Phase 4a: SiteClient owns the WebView; this composable just enqueues.
    LaunchedEffect(next.title, canRequest, tries) {
        if (!canRequest) return@LaunchedEffect
        SiteClient.enqueue(
            site = SiteClient.Site.NU,
            request = SiteClient.ScrapeRequest(
                url = next.seriesUrl,
                js = coverJs(),
                onComplete = { result ->
                    val cover = when (result) {
                        is SiteClient.ScrapeRequest.Result.Success ->
                            unwrapJsResult(result.html)
                        else -> ""
                    }
                    when {
                        cover.startsWith("http") -> onCoverFound(next.title, cover)
                        cover == "CF" || tries < 2 -> tries += 1
                        else -> onSkip(next.title)
                    }
                }
            )
        )
    }
}

/**
 * Small cover tile. Loads the local CoverStore file for [seriesUrl] when we
 * have one (offline + backup-friendly), otherwise the URL via Coil; no cover
 * at all → the puzzled shiba placeholder with its "No cover available" caption,
 * or — when [placeholderColor] is set (site theme color, e.g. Kakuyomu's
 * baseColor) — a tinted card with [placeholderLabel], matching what the
 * site itself renders for coverless works.
 */
@Composable
fun CoverThumb(
    url: String,
    seriesUrl: String = "",
    modifier: Modifier = Modifier,
    // Bump to re-read CoverStore (e.g. after AI cover generation).
    refreshKey: Int = 0,
    // When set, tapping the thumb opens the image fullscreen (series pages).
    fullscreen: Boolean = false,
    // Site theme color (hex "#RRGGBB") for the no-cover fallback card.
    placeholderColor: String = "",
    // Short label (the title) drawn on the tinted card.
    placeholderLabel: String = ""
) {
    val context = LocalContext.current
    val local = remember(seriesUrl, refreshKey) {
        seriesUrl.takeIf { it.isNotBlank() }?.let { CoverStore.localFile(context, it) }
    }
    var openFull by remember { mutableStateOf(false) }
    val tint = remember(placeholderColor) {
        runCatching { Color(android.graphics.Color.parseColor(placeholderColor)) }
            .getOrDefault(Color.Unspecified)
    }
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(if (fullscreen) Modifier.clickable { openFull = true } else Modifier),
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
        } else if (tint != Color.Unspecified) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tint)
                    .padding(8.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Text(
                    text = placeholderLabel,
                    color = Color.White,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 3
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.Image(
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
    if (openFull) {
        val local2 = local
        val model: Any? = local2 ?: url.takeIf { it.isNotBlank() }
        if (model != null) {
            CoverFullscreenDialog(model = model, onDismiss = { openFull = false })
        }
    }
}

/**
 * Fullscreen cover view — series pages open this when their thumbnail is
 * tapped. Whole image visible (Fit), black backdrop, any tap closes.
 */
@Composable
fun CoverFullscreenDialog(model: Any, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp)
            )
        }
    }
}
