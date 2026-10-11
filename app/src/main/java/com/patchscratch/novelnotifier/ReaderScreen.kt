package com.patchscratch.novelnotifier

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phase 3 — the reader.
 *
 * 3.1 Browser mode: a WebView that follows the chapter URL wherever it goes
 * (NU redirects, translator hosts, Cloudflare gates — same shared cookie
 * session). Chapter rows and READ CHAPTER hand over the releases in READING
 * ORDER so ‹ Prev / Next › walk the book.
 *
 * 3.2 Reader mode: chapter text extracted in the WebView (chapterExtractJs,
 * computed-style decoy filtering) and rendered natively — sepia/dark/light
 * page themes, serif/sans, font size, all persisted. Falls back to the web
 * view per-page when extraction fails; the ⋮ menu switches views on demand.
 *
 * Per-novel JavaScript toggle (reader_js_off): some hosts only render with
 * JS; others spam pop-ups unless it's off. Ad hosts always blocked at both
 * request and navigation level.
 */

/** One open reading session. [chapters] must be in reading order (ascending). */
data class ReaderTarget(
    val seriesTitle: String,
    val seriesUrl: String,
    val chapters: List<Release>,
    val startIndex: Int,
    /** Series-page metadata for the book's title page; null keeps stored meta. */
    val meta: EpubStore.BookMeta? = null
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

// Reader page palettes (reading surface, independent of the app theme).
private data class ReaderPalette(val background: Color, val text: Color)
private val ReaderLight = ReaderPalette(Color(0xFFFFFFFF), Color(0xFF1A1C1E))
private val ReaderSepia = ReaderPalette(Color(0xFFF4E8D0), Color(0xFF4A3B28))
private val ReaderDark = ReaderPalette(Color(0xFF101418), Color(0xFFD8DBE2))

@Composable
fun ReaderScreen(
    target: ReaderTarget,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    var index by remember(target) { mutableIntStateOf(target.startIndex.coerceIn(0, target.chapters.lastIndex)) }
    var jsOn by remember(target.seriesUrl) {
        mutableStateOf(target.seriesUrl !in loadIdSet(prefs, "reader_js_off"))
    }
    var loadKey by remember(target) { mutableIntStateOf(0) } // ⋮ Reload
    // (url, loadKey, jsOn) the WebView last loaded under — any change reloads:
    // Next/Prev changes the url, ⋮ Reload bumps the key, the JS toggle changes
    // the flag (loadUrl on the same URL is still a reload).
    var loadedStamp by remember(target) { mutableStateOf(Triple("", -1, true)) }

    // --- reader-mode state (3.2) ---
    var viewMode by remember { mutableStateOf(loadReaderView(prefs)) } // text | web
    var readerTheme by remember { mutableStateOf(loadReaderTheme(prefs)) } // light|sepia|dark
    var serif by remember { mutableStateOf(loadReaderSerif(prefs)) }
    var fontSize by remember { mutableIntStateOf(loadReaderFontSize(prefs)) }
    var extractTick by remember(target) { mutableIntStateOf(0) } // finished loads
    var retryKey by remember(target) { mutableIntStateOf(0) } // manual re-extract
    var extractResult by remember(target) { mutableStateOf<ChapterExtract?>(null) }
    var extractFailed by remember(target) { mutableStateOf(false) }
    var extracting by remember(target) { mutableStateOf(false) }
    var imageChapter by remember(target) { mutableStateOf(false) } // chapter is image slices, not text
    var webDecodeStamp by remember(target) { mutableStateOf("") } // pages the web decode ran on
    // --- offline library state (3.3) ---
    // Saving is PER-SERIES opt-in (user decision 2026-10-11): a 1400-series
    // library must never silently fill device storage. An enabled series
    // auto-saves chapters as they are read; bulk download is offered for
    // enabled series; explicit single-chapter saves are always allowed.
    var offlineEnabled by remember(target.seriesUrl) {
        mutableStateOf(target.seriesUrl in loadIdSet(prefs, "epub_series_on"))
    }
    var epubCount by remember(target.seriesUrl) { mutableIntStateOf(EpubStore.storedCount(context, target.seriesUrl)) }
    var savedOffline by remember(target) { mutableStateOf(false) } // this chapter is in the book
    var offlineCopy by remember(target) { mutableStateOf(false) } // rendered FROM the book
    var bulkActive by remember(target) { mutableStateOf(false) }
    var bulkStatus by remember(target) { mutableStateOf("") }
    val savedUrls = remember(target) { mutableSetOf<String>() } // already appended this session
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val textScroll = rememberScrollState()

    var progress by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val chapter = target.chapters.getOrNull(index) ?: return

    val showText = viewMode == READER_VIEW_TEXT && extractResult != null
    val palette = when (readerTheme) {
        "light" -> ReaderLight
        "dark" -> ReaderDark
        else -> ReaderSepia
    }

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

    fun toggleOfflineForSeries() {
        offlineEnabled = !offlineEnabled
        val on = loadIdSet(prefs, "epub_series_on")
        val next = if (offlineEnabled) on + target.seriesUrl else on - target.seriesUrl
        prefs.edit { putStringSet("epub_series_on", next) }
    }

    /** User-selected save of the current chapter into the series' ePub. */
    fun saveChapterOffline() {
        val extract = extractResult ?: return
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                EpubStore.appendChapter(
                    context, target.seriesTitle, target.seriesUrl,
                    chapter.url, chapter.chapter, extract, target.meta
                )
            }
            if (count > 0) {
                epubCount = count
                savedOffline = true
                DebugLog.log("EPUB", "saved ${chapter.chapter} (${extract.paragraphs.size} paragraphs) → $count chapters in book")
            }
        }
    }

    // Offline library: export the series book via the file picker (Drive,
    // USB, Downloads…), or hand it to another e-reader via the share sheet.
    var exportMessage by remember { mutableStateOf("") }
    val epubExportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                EpubStore.exportTo(context, target.seriesUrl, target.seriesTitle, uri)
            }
            exportMessage = msg
        }
    }

    fun pickView(mode: String) {
        viewMode = mode
        saveReaderView(prefs, mode)
        if (mode == READER_VIEW_TEXT) {
            // A previous failure should not stop a fresh attempt.
            retryKey += 1
        }
    }

    /** Run the extractor once; [onDone] fires in the JS callback (async). */
    fun tryExtract(onDone: (() -> Unit)? = null) {
        val wv = webViewRef.value ?: return
        extracting = true
        wv.evaluateJavascript(chapterExtractJs()) { res ->
            extractResult = parseChapterExtract(res)
            if (extractResult != null) extractFailed = false
            extracting = false
            onDone?.invoke()
        }
    }

    // Extraction pipeline: after each finished load (and manual retries) try
    // at ~1.2 s; JS-rendering hosts get one more shot ~2.5 s later. Only in
    // text mode — the web view needs no extraction. A successful extraction
    // appends the chapter to the series' offline ePub; total failure falls
    // back to the stored copy when one exists (offline reading).
    LaunchedEffect(extractTick, retryKey, viewMode, chapter.url) {
        if (viewMode != READER_VIEW_TEXT || extractTick == 0) return@LaunchedEffect
        extractResult = null
        extractFailed = false
        imageChapter = false
        offlineCopy = false
        delay(1200)
        tryExtract()
        delay(2600)
        if (extractResult == null && !extractFailed) {
            // Second try; verdict inside the callback — evaluateJavascript is
            // asynchronous, so checking extractResult synchronously raced.
            tryExtract {
                if (extractResult == null) {
                    // Offline fallback first: if this chapter was read before,
                    // serve the stored ePub copy instead of declaring failure.
                    // (Plain callback — hop into the composable scope for IO.)
                    scope.launch {
                        val stored = withContext(Dispatchers.IO) {
                            EpubStore.readChapter(context, target.seriesUrl, chapter.url)
                        }
                        if (stored != null && extractResult == null) {
                            extractResult = stored
                            offlineCopy = true
                            return@launch
                        }
                        if (stored == null && extractResult == null) {
                            extractFailed = true
                            webViewRef.value?.evaluateJavascript(imageChapterJs()) { r ->
                                if (unwrapJsResult(r).startsWith("IMAGES")) imageChapter = true
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-chapter saved flag, refreshed from the book on chapter change.
    LaunchedEffect(chapter.url) {
        savedOffline = withContext(Dispatchers.IO) {
            EpubStore.readChapter(context, target.seriesUrl, chapter.url) != null
        }
    }

    // Auto-save — ONLY for series the user opted in, dedup by chapter URL.
    LaunchedEffect(extractResult, chapter.url, offlineEnabled) {
        val extract = extractResult ?: return@LaunchedEffect
        if (!offlineEnabled || offlineCopy || chapter.url in savedUrls) return@LaunchedEffect
        savedUrls.add(chapter.url)
        val count = withContext(Dispatchers.IO) {
            EpubStore.appendChapter(
                context, target.seriesTitle, target.seriesUrl,
                chapter.url, chapter.chapter, extract, target.meta
            )
        }
        if (count > 0) {
            epubCount = count
            savedOffline = true
        }
    }

    // Bulk downloader (enabled series only): walks the chapters the RELEASES
    // table has loaded, one WebView load at a time with a courtesy gap —
    // already-saved chapters are skipped without touching the network.
    //
    // NU-HOSTED LINKS ARE SKIPPED: many RELEASES rows point at NU's extnu
    // redirect or NU blog posts, and machine-gunning novelupdates.com at
    // bulk speed escalates Cloudflare and poisons the shared session for
    // EVERYTHING (list checks and auto-updates started randomly failing —
    // user report 2026-10-11). Only links that go DIRECT to the translator
    // host are bulk-downloaded: one hit per chapter to the site that actually
    // serves it, 4 s apart. NU-mediated chapters stay available via manual
    // save (single, user-paced hits are fine) — or 3.3.1's engine-paced
    // downloader, which is planned to walk the translator's own ToC with a
    // single NU hit per series.
    LaunchedEffect(bulkActive) {
        if (!bulkActive) return@LaunchedEffect
        val nuHosted = { url: String ->
            url.startsWith("https://www.novelupdates.com/") || url.startsWith("http://www.novelupdates.com/")
        }
        val known = withContext(Dispatchers.IO) {
            EpubStore.readBookIndex(EpubStore.bookFile(context, target.seriesUrl)).map { it.url }.toSet()
        }
        var saved = 0
        var failed = 0
        var skipped = 0
        var viaToc = 0
        var viaNu = 0

        // ---- pass 1: DIRECT links only, 4 s apart ---------------------------
        // Each loaded page is also harvested for same-host chapter links
        // (tocHarvestJs) — most translator sites embed their chapter list on
        // the page, so NU-routed chapters can often resolve to direct URLs
        // without touching novelupdates.com at all.
        val toc = HashMap<Pair<Int, Int>, String>()
        suspend fun waitForExtraction(): ChapterExtract? {
            val deadline = System.currentTimeMillis() + 45_000
            while (extractResult == null && !extractFailed && bulkActive &&
                System.currentTimeMillis() < deadline
            ) {
                delay(500)
            }
            return extractResult
        }
        suspend fun harvestFromPage() {
            val wv = webViewRef.value ?: return
            wv.evaluateJavascript(tocHarvestJs()) { res ->
                scope.launch {
                    val links = parseTocHarvest(res)
                    links.forEach { link -> link.key?.let { k -> toc.putIfAbsent(k, link.url) } }
                }
            }
        }

        val pendingNu = mutableListOf<Int>() // indices needing NU resolution
        for (i in target.chapters.indices) {
            if (!bulkActive) break
            val doc = target.chapters[i]
            if (doc.url in known) { skipped++; continue }
            if (nuHosted(doc.url)) { pendingNu.add(i); continue }
            index = i
            bulkStatus = "Direct ${i + 1}/${target.chapters.size} · saved $saved · failed $failed"
            val extract = waitForExtraction()
            if (extract != null && !offlineCopy) {
                val count = withContext(Dispatchers.IO) {
                    EpubStore.appendChapter(
                        context, target.seriesTitle, target.seriesUrl,
                        doc.url, doc.chapter, extract, target.meta
                    )
                }
                if (count > 0) { epubCount = count; saved++ }
                harvestFromPage()
            } else {
                failed++
            }
            delay(4000) // courtesy gap to the translator host between chapters
        }

        // ---- pass 2: NU-routed chapters — ToC resolve first, then NU ------- 
        // Failover pacing is the engine's 10 s per NU hit (bulk-speed extnu
        // traffic escalates Cloudflare for the whole session).
        for (i in pendingNu) {
            if (!bulkActive) break
            val doc = target.chapters[i]
            if (doc.url in known) { skipped++; continue }
            val key = chapterKey(doc.chapter)
            val directUrl = key?.let { toc[it] }
            if (directUrl != null && !nuHosted(directUrl)) {
                // ToC resolved it — a direct load, no NU involved.
                index = i
                // Substitute the direct URL for this load. The stamp must be
                // aligned with it FIRST — the update block reloads whenever
                // the (url, loadKey, jsOn) stamp changes, and it watches the
                // NU chapter.url that `index` just selected.
                loadedStamp = Triple(directUrl, loadKey, jsOn)
                webViewRef.value?.loadUrl(directUrl)
                bulkStatus = "ToC-resolved ${doc.chapter} · saved $saved"
                val extract = waitForExtraction()
                if (extract != null && !offlineCopy) {
                    val count = withContext(Dispatchers.IO) {
                        EpubStore.appendChapter(
                            context, target.seriesTitle, target.seriesUrl,
                            doc.url, doc.chapter, extract, target.meta
                        )
                    }
                    if (count > 0) { epubCount = count; saved++; viaToc++ }
                    harvestFromPage()
                    delay(4000)
                    continue
                }
                failed++
                delay(4000)
                continue
            }
            // No ToC entry — fail over to the NU link itself, slowly.
            index = i
            bulkStatus = "NU failover ${doc.chapter} · saved $saved · NU $viaNu"
            val extract = waitForExtraction()
            if (extract != null && !offlineCopy) {
                val count = withContext(Dispatchers.IO) {
                    EpubStore.appendChapter(
                        context, target.seriesTitle, target.seriesUrl,
                        doc.url, doc.chapter, extract, target.meta
                    )
                }
                if (count > 0) { epubCount = count; saved++; viaNu++ }
            } else {
                failed++
            }
            delay(10_000) // engine-grade courtesy gap between NU hits
        }
        bulkActive = false
        bulkStatus = "Bulk done · saved $saved (ToC $viaToc · NU $viaNu) · skipped $skipped · failed $failed"
        DebugLog.log("EPUB", "bulk ${target.seriesTitle}: $bulkStatus")
    }

    // Web-view decode pass: on font-substituted hosts, rewrite the page's own
    // text nodes so select/copy/share from the web view gives real text. Runs
    // once per page load, silently skips clean pages.
    LaunchedEffect(extractTick, viewMode) {
        if (viewMode != READER_VIEW_WEB || extractTick == 0) return@LaunchedEffect
        delay(1500)
        val stamp = "${chapter.url}|$loadKey"
        if (webDecodeStamp == stamp) return@LaunchedEffect
        webDecodeStamp = stamp
        webViewRef.value?.evaluateJavascript(decodeDomJs()) { r ->
            val res = unwrapJsResult(r)
            if (res.startsWith("DECODED")) {
                DebugLog.log("READER", "web view decoded ${res.substringAfter(':')} text nodes (${chapter.chapter})")
            }
        }
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
                    text = if (showText && extractResult?.title?.isNotBlank() == true) {
                        extractResult?.title ?: chapter.chapter
                    } else chapter.chapter,
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
                    text = {
                        Text(
                            if (viewMode == READER_VIEW_TEXT) "✓ Reader view (extracted text)"
                            else "Reader view (extracted text)"
                        )
                    },
                    onClick = { showMenu = false; pickView(READER_VIEW_TEXT) }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (viewMode == READER_VIEW_WEB) "✓ Web view (full page)"
                            else "Web view (full page)"
                        )
                    },
                    onClick = { showMenu = false; pickView(READER_VIEW_WEB) }
                )
                DropdownMenuItem(
                    text = { Text("Page theme: ${readerTheme.replaceFirstChar { it.uppercase() }}") },
                    onClick = {
                        readerTheme = when (readerTheme) {
                            "sepia" -> "dark"; "dark" -> "light"; else -> "sepia"
                        }
                        saveReaderTheme(prefs, readerTheme)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Font: ${if (serif) "Serif" else "Sans"}") },
                    onClick = {
                        serif = !serif
                        saveReaderSerif(prefs, serif)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Text smaller (A−)") },
                    onClick = {
                        fontSize = (fontSize - 1).coerceIn(12, 28)
                        saveReaderFontSize(prefs, fontSize)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Text larger (A+)") },
                    onClick = {
                        fontSize = (fontSize + 1).coerceIn(12, 28)
                        saveReaderFontSize(prefs, fontSize)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(if (offlineEnabled) "Offline for this series: ON" else "Offline for this series: OFF")
                    },
                    onClick = { showMenu = false; toggleOfflineForSeries() }
                )
                if (offlineEnabled && !bulkActive && target.chapters.size > 0) {
                    DropdownMenuItem(
                        text = { Text("Bulk download loaded chapters (direct links only)") },
                        onClick = {
                            showMenu = false
                            // Extraction only runs in text mode — switch first.
                            pickView(READER_VIEW_TEXT)
                            bulkActive = true
                        }
                    )
                }
                if (bulkActive) {
                    DropdownMenuItem(
                        text = { Text("Stop bulk download") },
                        onClick = { showMenu = false; bulkActive = false }
                    )
                }
                if (savedOffline) {
                    DropdownMenuItem(
                        text = { Text("✓ Chapter saved offline ($epubCount in book)") },
                        onClick = { showMenu = false }
                    )
                } else if (extractResult != null) {
                    DropdownMenuItem(
                        text = { Text("Save this chapter offline") },
                        onClick = { showMenu = false; saveChapterOffline() }
                    )
                }
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
                if (epubCount > 0) {
                    DropdownMenuItem(
                        text = { Text("Export ePub ($epubCount chapters)…") },
                        onClick = {
                            showMenu = false
                            epubExportLauncher.launch("${target.seriesTitle}.epub")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share ePub to another app") },
                        onClick = {
                            showMenu = false
                            EpubStore.shareIntent(context, target.seriesUrl)?.let {
                                context.startActivity(Intent.createChooser(it, "Share ${target.seriesTitle}"))
                            }
                        }
                    )
                }
            }
        }
        if (progress in 1..99 && !showText) LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        if (bulkStatus.isNotBlank()) {
            Text(
                bulkStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (offlineCopy) {
            Text(
                "Offline copy — saved earlier, shown without the site",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (exportMessage.isNotBlank()) {
            Text(
                exportMessage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (viewMode == READER_VIEW_TEXT && extractResult == null && !extractFailed) {
            // Covers the extracting flag AND page loads that keep restarting
            // (a spinning challenge re-fires onPageFinished constantly).
            Text(
                "Extracting chapter text…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (viewMode == READER_VIEW_TEXT && extractFailed) {
            Text(
                text = if (imageChapter) {
                    "This chapter is rendered as images — showing the website, which displays them"
                } else {
                    "Couldn't extract this page — showing the website instead"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // --- the page: extracted text surface + (hidden) WebView ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (showText) palette.background else Color.White)
        ) {
            // WebView stays composed (keeps its page + session) but moves
            // off-screen while the extracted text is on top.
            AndroidView(
                modifier = if (showText) {
                    Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .offset(x = 4000.dp, y = 4000.dp)
                } else {
                    Modifier.fillMaxSize()
                },
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef.value = this
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

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Main frame only matters; subframe finishes would
                                // trigger re-extraction storms.
                                if (url != null && url == view?.url) extractTick += 1
                                super.onPageFinished(view, url)
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

            // The extracted chapter, rendered natively.
            if (showText) {
                val extract = extractResult!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(textScroll)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    if (extract.title.isNotBlank() && extract.title != chapter.chapter) {
                        Text(
                            text = extract.title,
                            color = palette.text,
                            fontSize = (fontSize + 4).sp,
                            lineHeight = ((fontSize + 4) * 1.4).sp,
                            fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    extract.paragraphs.forEach { paragraph ->
                        Text(
                            text = paragraph,
                            color = palette.text,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.65).sp,
                            fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                    Text(
                        text = "· ${index + 1} of ${target.chapters.size} ·",
                        color = palette.text.copy(alpha = 0.55f),
                        fontSize = (fontSize - 3).sp,
                        fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
                        modifier = Modifier
                            .padding(vertical = 20.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
        if (loadFailed && !showText) {
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
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            TextButton(
                onClick = { if (index < target.chapters.lastIndex) index += 1 },
                enabled = index < target.chapters.lastIndex
            ) { Text("Next ›") }
        }
    }
}
