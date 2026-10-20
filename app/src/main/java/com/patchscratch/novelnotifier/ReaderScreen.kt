package com.patchscratch.novelnotifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.collectAsState
import kotlin.math.abs
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
import coil.compose.AsyncImage
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

/**
 * True when the device has a network with internet capability. The reader's
 * offline fast-path uses this: with no network the extraction ladder cannot
 * succeed, so a stored ePub copy is served immediately instead.
 */
fun isNetworkOnline(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    return cm.getNetworkCapabilities(network)
        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

// Reader page palettes (reading surface, independent of the app theme).
private data class ReaderPalette(val background: Color, val text: Color)
private val ReaderLight = ReaderPalette(Color(0xFFFFFFFF), Color(0xFF1A1C1E))
private val ReaderSepia = ReaderPalette(Color(0xFFF4E8D0), Color(0xFF4A3B28))
private val ReaderDark = ReaderPalette(Color(0xFF101418), Color(0xFFD8DBE2))

@Composable
fun ReaderScreen(
    target: ReaderTarget,
    onBack: () -> Unit,
    onOpenXlateFiles: () -> Unit = {},
    // Syosetu しおり (reading position). -1 = not a bookmarked Syosetu
    // serial (hooks idle). Auto fires when the current chapter's number is
    // HIGHER than the saved position — the site's own open-an-episode
    // rule, forward only; manual is the reader-menu set (may go backward).
    shioriPos: Int = -1,
    onShioriAuto: (Int) -> Unit = {},
    onShioriManual: (Int) -> Unit = {},
    // Kakuyomu reading history (site-verified {episodeId, position} on
    // episode view). True → record on open; the reader derives the episode
    // id from the chapter URL itself.
    kakuyomuHistory: Boolean = false,
    onKakuyomuHistory: (episodeId: String, ordinal: Int, manual: Boolean) -> Unit = { _, _, _ -> }
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
    // AlphaPolis logged-in pages fill #novelBody only in a VISIBLE WebView
    // (the site's paginated viewer ignores hidden views). When the headless
    // path comes up empty we hop to WEB for the load, extract, then return
    // the user to the text view automatically.
    var alpWebHop by remember(target) { mutableStateOf(false) }
    // DOM extracts captured during a hop, by chapter URL. The flip back to
    // TEXT restarts the extraction ladder, whose AlphaPolis branch would
    // otherwise retry the (failing) headless path and re-hop forever; the
    // cache short-circuits that restart to the text we already hold.
    val alpDomExtracts = remember(target) { mutableMapOf<String, ChapterExtract>() }
    // Hops that ran their full ladder and found nothing (paywalled chapter
    // the session doesn't own), keyed by chapter URL → the retryKey cycle
    // they failed in. Prevents hop→fail→flip→re-hop reload loops; a manual
    // retry (new retryKey) is always allowed another hop.
    val alpHopExhausted = remember(target) { mutableMapOf<String, Int>() }
    // True while the reader sits on a PAYWALL page in web view, kept there
    // deliberately (Wayne, 2026-10-18): the user can unlock the chapter or
    // buy credits inside the app, then tap Retry to extract + auto-save.
    var paywallNotice by remember(target) { mutableStateOf(false) }
    // Qidian/Webnovel (Wayne's rule, 2026-10-20): never scraped, never even
    // loaded — they actively seek to ban apps that access their sites.
    val prohibited = remember(target, index) {
        SitePolicy.prohibitedHostOf(target.chapters.getOrNull(index)?.url.orEmpty()) != null
    }
    var prohibitedNotice by remember(target) { mutableStateOf(false) }
    var extractFailed by remember(target) { mutableStateOf(false) }
    var extracting by remember(target) { mutableStateOf(false) }
    /** True while the chapter host is showing a Cloudflare challenge. */
    var cfChecking by remember(target) { mutableStateOf(false) }
    /**
     * CF first-touch per chapter (Wayne's foxteller case): subresource
     * challenges can never render UI, so an SPA whose app.js gets challenged
     * stalls on its splash forever. One detour through the HOST ROOT forces
     * a main-frame challenge that CAN auto-clear and set cf_clearance; the
     * chapter then reloads into a session that passes.
     */
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
    // Stored chapters carrying an AI translation — the reader menu's
    // "N/M translated" coverage label. -1 = not loaded yet.
    var translatedCount by remember(target.seriesUrl) {
        mutableIntStateOf(EpubStore.translatedCount(context, target.seriesUrl))
    }
    var savedOffline by remember(target) { mutableStateOf(false) } // this chapter is in the book
    var offlineCopy by remember(target) { mutableStateOf(false) } // rendered FROM the book
    val savedUrls = remember(target) { mutableSetOf<String>() } // already appended this session
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val textScroll = rememberScrollState()

    var progress by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // --- AI translation state (per chapter) ---
    // Loaded from the stored book when the chapter changes; replaced by a
    // fresh ⋮ translation. Renders paragraph-under-paragraph below the
    // original, toggleable.
    var translation by remember(target) { mutableStateOf<List<String>?>(null) }
    var translating by remember(target) { mutableStateOf(false) }
    var translateError by remember(target) { mutableStateOf("") }
    var showTranslation by remember { mutableStateOf(true) }
    // Long-press Japanese dictionary (Settings toggle; first enable unpacks
    // the JMdict asset — the toggle blocks until that finishes).
    var jaDictOn by remember(target) { mutableStateOf(prefs.getBoolean("ja_dict_on", false)) }
    // TTS: which paragraph is being spoken (-1 = idle); the spoken-text
    // list follows the reader's layout choice (JP+EN / JP / EN).
    val ttsState by TtsController.state.collectAsState()
    val speakingPara = ttsState.paragraph

    // Layout as reactive state — the ⋮ menu AND paragraph rendering both
    // observe this (reading SharedPreferences directly never triggers
    // recomposition, so the layout appeared to "do nothing").
    var xlateLayout by remember(target) {
        mutableStateOf(prefs.getString("xlate_layout", "both") ?: "both")
    }


    fun startSpeaking() {
        val layout = prefs.getString("xlate_layout", "both") ?: "both"
        val spoken = mutableListOf<String>()
        extractResult?.paragraphs?.forEachIndexed { i, jp ->
            val en = translation?.getOrNull(i)
            when (layout) {
                "jp" -> spoken.add(jp)
                "en" -> if (!en.isNullOrBlank()) spoken.add(en)
                else -> {
                    spoken.add(jp)
                    if (!en.isNullOrBlank()) spoken.add(en)
                }
            }
        }
        if (spoken.isNotEmpty()) {
            // Chapter label read directly — `chapter` is declared below
            // (local functions must respect declaration order here).
            val label = target.chapters.getOrNull(index)?.chapter.orEmpty()
            TtsController.setContext(context)
            TtsController.start(
                context, spoken, 0,
                title = "${target.seriesTitle} · ${label.ifBlank { "c${index + 1}" }}"
            )
        }
    }
    var wordHit by remember(target) { mutableStateOf<JaDict.WordHit?>(null) }
    val chapter = target.chapters.getOrNull(index) ?: return

    /**
     * Foxteller /rss/<id> resolution stage per chapter: 0 = not needed,
     * 1 = resolving (extraction paused — the overview page must not win),
     * 2 = resolved (final navigation fired; the ladder owns it now).
     */
    var foxRssStage by remember(chapter.url) { mutableStateOf(0) }
    /** The WebView's actual URL — chapter.url can be NU's /extnu redirect. */
    var loadedUrl by remember(target) { mutableStateOf("") }

    val xlateKey = prefs.getString("xlate_key", "").orEmpty()
    // Shared by first translation and re-translate: runs the
    // chapter through the endpoint, overwrites the stored one,
    // then auto-refines the series lock files.
    fun startTranslate() {
        val extract = extractResult ?: return
        if (extract.paragraphs.isEmpty() || xlateKey.isBlank()) return
        translating = true
        translateError = ""
        scope.launch {
            // Novel-Mill lock files: series prompt.txt overrides the
            // global one, which overrides the Settings prompt; glossary
            // and voices ride along as ## sections.
            val locks = XlateStack.load(context, target.seriesUrl)
            val result = try {
                TranslationClient.translate(
                    endpointBase = prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                    apiKey = xlateKey,
                    model = prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty(),
                    paragraphs = extract.paragraphs,
                    customPrompt = locks.prompt.ifBlank {
                        prefs.getString("xlate_prompt", "").orEmpty()
                    },
                    glossary = locks.glossary,
                    voices = locks.voices,
                    temperature = prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
                )
            } catch (e: Exception) {
                DebugLog.log("XLATE", "chapter translate failed", e)
                translateError = (e.message ?: e.javaClass.simpleName)
                    .replace(Regex("\\s+"), " ").take(160)
                    .ifBlank { "Translation request failed" }
                null
            }
            translating = false
            if (result != null) {
                DebugLog.log("XLATE", "chapter translated OK: ${result.size} paragraphs")
                translation = result
                translateError = ""
                showTranslation = true
                // Persist alongside the original — store the
                // original first if it isn't in the book yet.
                withContext(Dispatchers.IO) {
                    val stored = EpubStore.setTranslation(
                        context, target.seriesTitle, target.seriesUrl, chapter.url, result
                    )
                    if (!stored) {
                        EpubStore.appendChapter(
                            context, target.seriesTitle, target.seriesUrl,
                            chapter.url, chapter.chapter, extract, target.meta
                        )
                        EpubStore.setTranslation(
                            context, target.seriesTitle, target.seriesUrl, chapter.url, result
                        )
                    }
                    epubCount = EpubStore.storedCount(context, target.seriesUrl)
                    translatedCount = EpubStore.translatedCount(context, target.seriesUrl)
                }
                // Auto-refine the series lock files with this
                // chapter's context (novel-mill's missing piece).
                if (prefs.getBoolean("xlate_auto_refine", true)) {
                    val refined = XlateStack.refineAfterChapter(
                        context, target.seriesUrl,
                        extract.paragraphs, result,
                        prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty(),
                        xlateKey,
                        prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty(),
                        prefs.getString("xlate_temp", "0.3")?.toDoubleOrNull() ?: 0.3
                    )
                    DebugLog.log("XLATE", "auto-refine ${if (refined) "saved" else "skipped/failed"}")
                }
            }
        }
    }
    LaunchedEffect(chapter.url, extractTick) {
        translation = withContext(Dispatchers.IO) {
            EpubStore.readAllStoredDocs(context, target.seriesUrl)
                .firstOrNull { it.url == chapter.url }?.translation
        }
    }

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
            val written = withContext(Dispatchers.IO) {
                EpubStore.appendChapter(
                    context, target.seriesTitle, target.seriesUrl,
                    chapter.url, chapter.chapter, extract, target.meta
                )
            }
            if (written > 0) {
                epubCount = withContext(Dispatchers.IO) {
                    EpubStore.storedCount(context, target.seriesUrl)
                }
                savedOffline = true
                DebugLog.log("EPUB", "saved ${chapter.chapter} (${extract.paragraphs.size} paragraphs)")
            }
        }
    }

    // Offline library: export the series book via the file picker (Drive,
    // USB, Downloads…), or hand it to another e-reader via the share sheet.
    var exportMessage by remember { mutableStateOf("") }

    fun startBackgroundBulk() {
        EpubBulkService.start(
            context,
            EpubBulkService.BulkJob(
                seriesTitle = target.seriesTitle,
                seriesUrl = target.seriesUrl,
                chapters = target.chapters.map { EpubBulkService.BulkChapter(it.url, it.chapter) },
                meta = target.meta
            )
        )
    }

    val bulkPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        startBackgroundBulk() // runs either way; without the grant there's just no progress toast
    }
    val epubExportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                EpubStore.exportTo(
                    context, target.seriesUrl,
                    EpubStore.chapterBucket(chapter.chapter), target.seriesTitle, uri
                )
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
            if (extractResult != null && alpWebHop && viewMode == READER_VIEW_WEB) {
                alpDomExtracts[chapter.url] = extractResult!!
                alpWebHop = false
                viewMode = READER_VIEW_TEXT
            }
            if (extractResult != null) extractFailed = false
            if (extractResult != null) {
                DebugLog.log("READER", "extracted ${extractResult!!.paragraphs.size} paragraphs")
            } else {
                DebugLog.log("READER", "extract miss — js said: " + (res ?: "").take(120))
                // Which extractor stage fails? Mirror nodeVisible's checks.
                webViewRef.value?.evaluateJavascript(
                    """(function(){
                        var c = document.querySelector('.chapter-content');
                        if (!c) return 'NOCONTAINER';
                        var ps = c.querySelectorAll('p'), vis = 0, chars = 0, h0 = [];
                        for (var i = 0; i < ps.length; i++) {
                          var p = ps[i], st = window.getComputedStyle(p);
                          var r = p.getBoundingClientRect();
                          if (i < 3) h0.push((r.height|0) + '/' + st.display + '/' + st.visibility + '/' + st.opacity);
                          if (st.display !== 'none' && st.visibility !== 'hidden' && parseFloat(st.opacity) > 0.01 && r.height > 0) {
                            var t = (p.innerText || '').trim();
                            if (t.length > 1) { vis++; chars += t.length; }
                          }
                        }
                        return 'cc=' + ps.length + ' vis=' + vis + ' chars=' + chars + ' first=' + h0.join(',');
                    })()"""
                ) { d -> DebugLog.log("READER", "dom: " + unwrapJsResult(d).take(140)) }
            }
            extracting = false
            onDone?.invoke()
        }
    }

    // NU /extnu chapter loader (Wayne's foxteller case, 2026-10-15): the
    // entry link is NU's redirector, the destination is the translator's
    // /rss/<id> URL (an SPA overview, never a chapter), subresource
    // Cloudflare stalls the SPA on its splash, and NU sometimes bounces
    // the redirect back to its own homepage. One state machine, keyed per
    // chapter (never restarted by page finishes), walks the whole chain:
    // bounce-retry -> rss resolve (DIRECT/BASE) -> splash first-touch ->
    // final chapter URL. foxRssStage==1 keeps the extraction ladder from
    // crowning an intermediate overview page; ==2 hands over to it.
    LaunchedEffect(chapter.url, viewMode) {
        val entry = android.net.Uri.parse(chapter.url)
        val isExtNu = (entry.host ?: "").endsWith("novelupdates.com") &&
            (entry.path ?: "").startsWith("/extnu/")
        if (!isExtNu || viewMode != READER_VIEW_TEXT) return@LaunchedEffect
        val ordinal = chapterNum(chapter.chapter)
        if (ordinal <= 0) return@LaunchedEffect
        var rssUrl = ""
        var rootTouched = false
        var stalledSteps = 0
        for (step in 0 until 44) {          // ~2.5s each -> ~110s ceiling
            delay(2500)
            val wv = webViewRef.value ?: return@LaunchedEffect
            val cur = loadedUrl
            val curHost = android.net.Uri.parse(cur.ifBlank { chapter.url }).host ?: ""
            val onNu = curHost.endsWith("novelupdates.com")
            when {
                // NU bounced the redirect to its own pages -> retry the entry
                onNu && cur != chapter.url -> {
                    DebugLog.log("READER", "extnu bounced to $curHost — retrying entry")
                    wv.loadUrl(chapter.url)
                }
                // The rss overview: resolve the real chapter via site links
                cur.contains("/rss/") -> {
                    if (rssUrl.isBlank()) {
                        rssUrl = cur
                        foxRssStage = 1
                    }
                    stalledSteps++
                    wv.evaluateJavascript(foxtellerRssResolveJs(ordinal)) { r ->
                        val v = unwrapJsResult(r)
                        when {
                            v.startsWith("DIRECT:") -> {
                                foxRssStage = 2
                                DebugLog.log("READER", "rss -> chapter " + v.take(100))
                                wv.loadUrl(v.removePrefix("DIRECT:"))
                            }
                            v.startsWith("BASE:") -> {
                                DebugLog.log("READER", "rss -> series base (older chapter)")
                                wv.loadUrl(v.removePrefix("BASE:"))
                            }
                        }
                    }
                    // Splash stall (subresource CF): detour through the host
                    // root ONCE so a main-frame challenge can clear, then
                    // return straight to the rss URL (not the flaky entry).
                    if (!rootTouched && stalledSteps >= 5) {
                        rootTouched = true
                        DebugLog.log("READER", "splash stall — CF first-touch via root")
                        wv.loadUrl("https://$curHost/")
                        delay(7000)
                        if (foxRssStage == 1) wv.loadUrl(rssUrl)
                    }
                }
                // A /novel/<id>/<n>/ page: the chapter itself — done here;
                // the extraction ladder (re-armed by its page finish) owns it.
                curHost.endsWith("foxteller.com") && Regex("/novel/\\d+/\\d+/").containsMatchIn(cur) -> {
                    if (foxRssStage == 1) foxRssStage = 2
                    return@LaunchedEffect
                }
            }
        }
        if (foxRssStage == 1) {
            DebugLog.log("READER", "extnu loader gave up; letting the ladder try")
            foxRssStage = 2
        }
    }

    // Extraction pipeline: after each finished load (and manual retries) try
    // at ~1.2 s, again ~2.5 s later, then keep a patient ladder going for
    // ~24 s more. Fast server-rendered hosts still succeed on the first two
    // shots; SPA hosts behind Cloudflare (verified: foxteller.com — challenge
    // page first, then a client-rendered #chapter-content) need the ladder:
    // their content arrives long after onPageFinished, and CF clearance
    // re-fires page finishes that restart this effect with a fresh window.
    // Only in text mode — the web view needs no extraction. A successful
    // extraction appends the chapter to the series' offline ePub; total
    // failure falls back to the stored copy when one exists (offline reading).
    LaunchedEffect(extractTick, retryKey, viewMode, chapter.url) {
        // During an AlphaPolis web-hop the ladder keeps running in WEB mode —
        // the hop exists precisely so the VISIBLE DOM fills #novelBody for
        // tryExtract to read; success flips the reader back to TEXT.
        val alpHop = alpWebHop && viewMode == READER_VIEW_WEB
        if ((viewMode != READER_VIEW_TEXT && !alpHop) || extractTick == 0) return@LaunchedEffect
        if (foxRssStage == 1) return@LaunchedEffect
        extractResult = null
        extractFailed = false
        imageChapter = false
        offlineCopy = false
        cfChecking = false
        paywallNotice = false
        prohibitedNotice = false
        // Qidian/Webnovel: no extraction attempt, no WebView traffic (the
        // factory/update blocks above already skip the load). Warn + stop.
        if (prohibited) {
            prohibitedNotice = true
            return@LaunchedEffect
        }
        // AlphaPolis reads fully headless (B4): page → CSRF+episode tokens →
        // POST /novel/episode_body — no WebView in the reading path, so the
        // site's premium chapters (session-authorized) work without any
        // scraping surface. Stored copies serve when offline.
        val isAlpChapter = (android.net.Uri.parse(chapter.url).host ?: "").endsWith("alphapolis.co.jp")
        val stored = if (isAlpChapter) {
            withContext(Dispatchers.IO) {
                EpubStore.readChapter(context, target.seriesUrl, chapter.url)
            }
        } else null
        if (isAlpChapter) {
            // Text we already DOM-extracted this session (the flip back to
            // TEXT restarts this ladder) — serve it, never re-hop.
            alpDomExtracts[chapter.url]?.let {
                extractResult = it
                return@LaunchedEffect
            }
        }
        if (isAlpChapter && !alpHop) {
            // !alpHop is load-bearing: without it this branch re-enters on
            // every page-finish and reload()s forever — the 'pages are just
            // cycling' live catch. Fully headless via the shared fetch (the
            // anonymous loader POST is the proven truth path; a guarded
            // sessioned attempt covers owned premium). Only when it yields
            // nothing (locked premium / site error) do the stored copy and
            // the visible-WebView hop take over — the hop is also the
            // paywall + in-app unlock surface.
            if (isNetworkOnline(context)) {
                val headless = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { fetchAlpChapterHeadless(chapter.url) }.getOrNull()
                }
                if (headless != null) {
                    extractResult =
                        if (headless.title.isBlank()) headless.copy(title = chapter.chapter) else headless
                    return@LaunchedEffect
                }
                if (stored != null) {
                    extractResult = stored
                    offlineCopy = true
                    return@LaunchedEffect
                }
                if (alpHopExhausted[chapter.url] == retryKey) {
                    extractFailed = true
                    return@LaunchedEffect
                }
                alpWebHop = true
                viewMode = READER_VIEW_WEB
                webViewRef.value?.reload()
                return@LaunchedEffect
            }
            if (stored != null) {
                extractResult = stored
                offlineCopy = true
                return@LaunchedEffect
            }
            extractFailed = true
            return@LaunchedEffect
        }
        // No network at all: the WebView cannot load anything, so skip the
        // ~30 s patience ladder and serve the stored ePub copy NOW. Without
        // a stored copy the ladder still runs (it fails gracefully, and the
        // WebView may hold a client-side cache of the page).
        if (!isNetworkOnline(context)) {
            val storedNow = withContext(Dispatchers.IO) {
                EpubStore.readChapter(context, target.seriesUrl, chapter.url)
            }
            if (storedNow != null) {
                extractResult = storedNow
                offlineCopy = true
                return@LaunchedEffect
            }
        }
        delay(1200)
        tryExtract()
        delay(2600)
        var shots = 0
        while (extractResult == null && !extractFailed && shots < 10) {
            // A challenge page up? Say so instead of spinning silently —
            // the visible WebView may need the user's eyes for a moment.
            webViewRef.value?.evaluateJavascript("(document.title || '')") { r ->
                val title = unwrapJsResult(r).lowercase()
                cfChecking = title.contains("just a moment") ||
                    title.contains("attention required") || title.contains("challenge")
            }
            delay(3000)
            tryExtract()
            shots++
        }
        // A hop that found nothing: either the chapter is paywalled — keep
        // the user IN the web view so they can unlock it or buy credits
        // without leaving the app (Wayne, 2026-10-18), with a notice whose
        // Retry re-runs extraction once they have — or it's a plain failure:
        // return to TEXT so the failure banner and its controls are visible.
        // Mark the cycle exhausted so the restart can't re-hop in a loop.
        if (alpHop && extractResult == null) {
            alpHopExhausted[chapter.url] = retryKey
            val storedHop = withContext(Dispatchers.IO) {
                EpubStore.readChapter(context, target.seriesUrl, chapter.url)
            }
            if (storedHop != null) {
                extractResult = storedHop
                offlineCopy = true
                alpWebHop = false
                return@LaunchedEffect
            }
            extractFailed = true
            webViewRef.value?.evaluateJavascript(
                // Precise call-to-action phrases only: bare 購入/クレジット
                // appear in the logged-in header (credit balance) and site
                // chrome on EVERY page and would false-positive free
                // chapters into the paywall path (caught live: "1-1").
                """(function(){var t=document.body?document.body.innerText:'';
                   return /このエピソードを購入|エピソードをレンタル|クレジットを購入|購入して読む|レンタルして読む/.test(t)?'PAYWALL':''})()"""
                    .trimIndent()
            ) { r ->
                if (unwrapJsResult(r) == "PAYWALL") {
                    alpWebHop = false          // hop over; STAY in the web view
                    paywallNotice = true
                } else {
                    alpWebHop = false
                    viewMode = READER_VIEW_TEXT
                }
            }
            return@LaunchedEffect
        }
        if (extractResult == null && !extractFailed) {
            // Offline fallback first: if this chapter was read before, serve
            // the stored ePub copy instead of declaring failure. (Plain
            // callback — hop into the composable scope for IO.)
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

    // Per-chapter saved flag, refreshed from the book on chapter change.
    LaunchedEffect(chapter.url) {
        savedOffline = withContext(Dispatchers.IO) {
            EpubStore.readChapter(context, target.seriesUrl, chapter.url) != null
        }
        // しおり auto-advance — mirrors the site: opening an episode past
        // your saved position moves the marker there.
        val ep = chapterNum(chapter.chapter)
        if (shioriPos >= 0 && ep > shioriPos) onShioriAuto(ep)
        // Kakuyomu: the site records history on episode view — same on open.
        if (kakuyomuHistory) {
            kakuyomuEpisodeIdFromUrl(chapter.url)?.let { onKakuyomuHistory(it, ep, false) }
        }
    }

    // Auto-save — EVERY successfully displayed chapter becomes its offline
    // copy (Wayne's rule, 2026-10-15: "downloaded chapters should be visible
    // offline" — reading IS the opt-in; the per-series toggle now governs
    // bulk downloads only). Dedup by chapter URL; never re-store the very
    // copy we're currently serving from the book, and never overwrite a
    // chapter already in the store (it may carry an AI translation).
    LaunchedEffect(extractResult, chapter.url) {
        val extract = extractResult ?: return@LaunchedEffect
        if (offlineCopy || chapter.url in savedUrls) return@LaunchedEffect
        val alreadyStored = withContext(Dispatchers.IO) {
            EpubStore.readChapter(context, target.seriesUrl, chapter.url) != null
        }
        if (alreadyStored) {
            savedUrls.add(chapter.url)   // counted, but not re-written
            return@LaunchedEffect
        }
        savedUrls.add(chapter.url)
        val count = withContext(Dispatchers.IO) {
            EpubStore.appendChapter(
                context, target.seriesTitle, target.seriesUrl,
                chapter.url, chapter.chapter, extract, target.meta
            )
            // appendChapter returns the BUCKET book's chapter count — the
            // reader's counter means the series total across all books.
            EpubStore.storedCount(context, target.seriesUrl)
        }
        if (count > 0) {
            epubCount = count
            savedOffline = true
        }
    }

    // Bulk translation finished for this series → refresh the coverage
    // counters so the menu's "N/M translated" reflects the completed run
    // without reopening the chapter.
    LaunchedEffect(Unit) {
        TranslationQueue.state.collect { st ->
            if (st != null && !st.running && st.seriesUrl == target.seriesUrl) {
                epubCount = withContext(Dispatchers.IO) { EpubStore.storedCount(context, target.seriesUrl) }
                translatedCount = withContext(Dispatchers.IO) { EpubStore.translatedCount(context, target.seriesUrl) }
            }
        }
    }

    BackHandler { onBack() }

    // Edge-to-edge: without insets the top bar hides under the status bar and
    // the chapter nav under the gesture bar — taps there feed system UI.
    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        wordHit?.let { hit -> WordSheet(hit) { wordHit = null } }
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
                // ---- Display ----------------------------------------------------
                DropdownMenuItem(
                    text = {
                        Text(
                            if (viewMode == READER_VIEW_TEXT) "✓ " + t("Reader view (extracted text)", "リーダー表示（抽出テキスト）")
                            else t("Reader view (extracted text)", "リーダー表示（抽出テキスト）")
                        )
                    },
                    onClick = { showMenu = false; pickView(READER_VIEW_TEXT) }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (viewMode == READER_VIEW_WEB) "✓ " + t("Web view (full page)", "Web表示（フルページ）")
                            else t("Web view (full page)", "Web表示（フルページ）")
                        )
                    },
                    onClick = { showMenu = false; pickView(READER_VIEW_WEB) }
                )
                DropdownMenuItem(
                    text = { Text(t("Page theme", "ページテーマ") + ": " + t(readerTheme.replaceFirstChar { it.uppercase() }, readerTheme)) },
                    onClick = {
                        readerTheme = when (readerTheme) {
                            "sepia" -> "dark"; "dark" -> "light"; else -> "sepia"
                        }
                        saveReaderTheme(prefs, readerTheme)
                    }
                )
                DropdownMenuItem(
                    text = { Text(t("Font", "フォント") + ": " + if (serif) t("Serif", "明朝") else t("Sans", "ゴシック")) },
                    onClick = {
                        serif = !serif
                        saveReaderSerif(prefs, serif)
                    }
                )
                // One row: smaller / current size / larger (was two items).
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = {
                                fontSize = (fontSize - 1).coerceIn(12, 28)
                                saveReaderFontSize(prefs, fontSize)
                            }) { Text("A−") }
                            Text("${fontSize}sp", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                fontSize = (fontSize + 1).coerceIn(12, 28)
                                saveReaderFontSize(prefs, fontSize)
                            }) { Text("A+") }
                        }
                    },
                    onClick = {
                        fontSize = (fontSize + 1).coerceIn(12, 28)
                        saveReaderFontSize(prefs, fontSize)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (jsOn) t("JavaScript: ON (tap to disable)", "JavaScript: ON（タップで無効）") else t("JavaScript: OFF (tap to enable)", "JavaScript: OFF（タップで有効）")) },
                    onClick = { showMenu = false; toggleJs() }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

                // ---- Translation -------------------------------------------------
                // AI translation: current chapter through the configured
                // OpenAI-compatible endpoint. Needs the API key from Settings.
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                translating -> t("Translating…", "翻訳中…")
                                translation != null -> (if (showTranslation) "✓ " else "") +
                                    t("Translation (toggle)", "翻訳（切替）")
                                translateError.isNotBlank() -> t("Translation failed — tap to retry", "翻訳失敗 — タップで再試行")
                                xlateKey.isBlank() -> t("Translate chapter (set API key in Settings)", "この話を翻訳（設定でAPIキー）")
                                else -> t("Translate chapter", "この話を翻訳")
                            }
                        )
                    },
                    onClick = {
                        showMenu = false
                        if (translating) return@DropdownMenuItem
                        if (translation != null) {
                            // Already translated — the item toggles display.
                            showTranslation = !showTranslation
                            return@DropdownMenuItem
                        }
                        startTranslate()
                    }
                )
                if (translation != null && !translating && xlateKey.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(t("Re-translate chapter", "この話を再翻訳")) },
                        onClick = {
                            showMenu = false
                            // Drop the stored/loaded translation so the next
                            // render can't show stale text mid-run, then redo.
                            translation = null
                            startTranslate()
                        }
                    )
                }
                // Paragraph layout, switchable right here (Settings holds the
                // saved default): both / Japanese only / English only.
                DropdownMenuItem(
                    text = {
                        Text(
                            t("Layout: ", "レイアウト：") + when (xlateLayout) {
                                "jp" -> t("Japanese only", "日本語のみ")
                                "en" -> t("English only", "英語のみ")
                                else -> t("JP + EN", "日英両方")
                            }
                        )
                    },
                    onClick = {
                        xlateLayout = when (xlateLayout) {
                            "both" -> "jp"; "jp" -> "en"; else -> "both"
                        }
                        prefs.edit { putString("xlate_layout", xlateLayout) }
                    }
                )
                // Bulk twin of Download all: every STORED chapter through the
                // translator, one at a time, auto-refining lock files as it
                // goes. Unstored chapters need Download all first. The label
                // carries coverage (translated/stored) so a finished bulk
                // run is visible as "all N translated".
                if (xlateKey.isNotBlank()) {
                    val bulk by TranslationQueue.state.collectAsState()
                    val mine = bulk?.takeIf { it.seriesUrl == target.seriesUrl }
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    mine?.running == true ->
                                        t("Translating all… ", "一括翻訳中… ") + mine.done + "/" + mine.total +
                                            t(" (tap to stop)", "（タップで停止）")
                                    else -> {
                                        val n = translatedCount
                                        val m = epubCount
                                        when {
                                            n < 0 -> t("Translate all stored", "保存済みを一括翻訳")
                                            m > 0 && n >= m -> t("✓ All ", "✓ 全") + "$m" + t(" chapters translated", "話 翻訳済み")
                                            m > 0 -> t("Translate all stored (", "保存済みを一括翻訳（") + "$n/$m" + t(" translated)", " 翻訳済み）")
                                            else -> t("Translate all stored", "保存済みを一括翻訳")
                                        }
                                    }
                                }
                            )
                        },
                        onClick = {
                            showMenu = false
                            if (mine?.running == true) TranslationQueue.stop()
                            else TranslationQueue.start(context, target.seriesTitle, target.seriesUrl)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(t("Translation files (prompt/glossary/voices)…", "翻訳ファイル（プロンプト/用語集/声）…")) },
                    onClick = { showMenu = false; onOpenXlateFiles() }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

                // ---- Read aloud --------------------------------------------------
                DropdownMenuItem(
                    text = {
                        Text(
                            when (ttsState.state) {
                                TtsController.State.PLAYING -> t("Stop speaking", "読み上げ停止")
                                TtsController.State.PAUSED -> t("Resume speaking", "読み上げ再開")
                                else -> t("Speak chapter", "この話を読み上げる")
                            }
                        )
                    },
                    onClick = {
                        showMenu = false
                        when (ttsState.state) {
                            TtsController.State.PLAYING -> {
                                TtsController.stop()
                            }
                            TtsController.State.PAUSED -> {
                                TtsController.resume(context)
                            }
                            else -> {
                                startSpeaking()
                            }
                        }
                    }
                )

                // ---- Reading position (per-site markers; group hidden when
                // neither site applies to this series) ---------------------------
                val showShiori = shioriPos >= 0
                val showKakuyomuMark = kakuyomuHistory && kakuyomuEpisodeIdFromUrl(chapter.url) != null
                if (showShiori || showKakuyomuMark) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    if (showShiori) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    t("Set しおり to this chapter (", "しおりをこの話に設定（") +
                                        chapterNum(chapter.chapter) + t(")", "）")
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShioriManual(chapterNum(chapter.chapter))
                            }
                        )
                    }
                    if (showKakuyomuMark) {
                        DropdownMenuItem(
                            text = { Text(t("Set reading position here", "ここまで読んだことにする")) },
                            onClick = {
                                showMenu = false
                                kakuyomuEpisodeIdFromUrl(chapter.url)?.let {
                                    onKakuyomuHistory(it, chapterNum(chapter.chapter), true)
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

                // ---- Offline library ----------------------------------------------
                // Chapters you read are stored automatically; this toggle
                // decides whether BULK downloads include the series.
                DropdownMenuItem(
                    text = {
                        Text(
                            if (offlineEnabled) t("Include in bulk download: ON", "一括ダウンロードに含める: ON")
                            else t("Include in bulk download: OFF", "一括ダウンロードに含める: OFF")
                        )
                    },
                    onClick = { showMenu = false; toggleOfflineForSeries() }
                )
                if (savedOffline) {
                    DropdownMenuItem(
                        text = { Text("✓ " + t("Saved offline", "保存済み") + " ($epubCount)") },
                        onClick = { showMenu = false }
                    )
                } else if (extractResult != null) {
                    DropdownMenuItem(
                        text = { Text(t("Save this chapter offline", "この章をオフライン保存")) },
                        onClick = { showMenu = false; saveChapterOffline() }
                    )
                }
                if (offlineEnabled && target.chapters.size > 0) {
                    if (EpubBulkService.running(context)) {
                        DropdownMenuItem(
                            text = { Text(t("Stop background download", "バックグラウンド取得を停止")) },
                            onClick = { showMenu = false; EpubBulkService.stop(context) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(t("Download all", "全話ダウンロード") + " (${target.chapters.size})") },
                            onClick = {
                                showMenu = false
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.POST_NOTIFICATIONS
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    // Fresh installs lose the grant (uninstall wipes it);
                                    // the progress notification needs it. Download starts
                                    // on grant.
                                    bulkPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    startBackgroundBulk()
                                }
                            }
                        )
                    }
                }
                if (epubCount > 0) {
                    DropdownMenuItem(
                        text = {
                            val bucket = EpubStore.chapterBucket(chapter.chapter)
                            val label = EpubStore.bookDisplayTitle("", bucket, emptySet())
                            Text(t("Export ePub", "ePubを書き出す") + " — " + label.replace(" — ", "") + " ($epubCount)…")
                        },
                        onClick = {
                            showMenu = false
                            epubExportLauncher.launch(
                                EpubStore.exportName(
                                    target.seriesTitle,
                                    EpubStore.chapterBucket(chapter.chapter),
                                    EpubStore.bucketsOf(context, target.seriesUrl)
                                )
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("Share ePub to another app", "ePubを他のアプリに共有")) },
                        onClick = {
                            showMenu = false
                            EpubStore.shareIntent(context, target.seriesUrl)?.let {
                                context.startActivity(Intent.createChooser(it, "Share ${target.seriesTitle}"))
                            }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

                // ---- Page ---------------------------------------------------------
                DropdownMenuItem(
                    text = { Text(t("Reload", "再読み込み")) },
                    onClick = { showMenu = false; loadKey += 1 }
                )
                DropdownMenuItem(
                    text = { Text(t("Open in browser", "ブラウザで開く")) },
                    onClick = { showMenu = false; openExternally() }
                )
            }
        }
        if (progress in 1..99 && !showText) LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        if (EpubBulkService.running(context)) {
            Text(
                "Background download running — " + EpubBulkService.statusText(context),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (offlineCopy) {
            Text(
                t("Offline copy — saved earlier", "オフラインコピー — 保存済み"),
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
                t("Extracting chapter text…", "章テキストを抽出中…"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (cfChecking && !extractFailed) {
            Text(
                t(
                    "Cloudflare check on this site — it usually clears in a few seconds; the page will pick up when it does.",
                    "このサイトはCloudflare確認中です — 通常数秒で解除され、その後本文が読み込まれます。"
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (viewMode == READER_VIEW_TEXT && extractFailed) {
            Text(
                text = if (imageChapter) {
                    t("This chapter is images — showing the website", "この章は画像形式です — Web版を表示")
                } else {
                    t("Couldn't extract — showing the website", "抽出できません — Web版を表示")
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
                                // AlphaPolis protection (Wayne's suggestion
                                // 2026-10-18): serve a stub for
                                // content-viewer.js — the site's anti-copy
                                // layer that clears #novelBody on blur and
                                // blocks copy/right-click/selection. Replacing
                                // the script at the network layer has no
                                // timing race (the hasFocus injection from
                                // onPageStarted can lose to its ready
                                // handler). Nothing else depends on the
                                // script's exports; the stub is a comment.
                                if (host.endsWith("alphapolis.co.jp") &&
                                    request.url.path?.endsWith("content-viewer.js") == true
                                ) {
                                    return WebResourceResponse(
                                        "application/javascript", "utf-8",
                                        java.io.ByteArrayInputStream(
                                            ";/* content-viewer removed by reader */".toByteArray()
                                        )
                                    )
                                }
                                if (!isBlockedReaderHost(host)) return null
                                return WebResourceResponse(
                                    "text/plain", "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                loadFailed = false
                                // Syosetu's R18 age check redirects through
                                // nl.syosetu.com/redirect/ageauth — confirm
                                // once and the cookie persists.
                                if (url != null && url.contains("/ageauth/")) {
                                    DebugLog.log("READER", "Syosetu age check shown — confirm once")
                                }
                                // AlphaPolis anti-copy (content-viewer.js, verified from
                                // a logged-in page save 2026-10-18): on window blur it
                                // CLEARS #novelBody behind a gray cover ("コンテンツ保護…"),
                                // restores on focus — a hidden/text-mode WebView never has
                                // focus, so the body would be empty the moment our
                                // extraction reads it. hasFocus()=>true makes both blur
                                // guards short-circuit; the copy/right-click blocks
                                // don't affect DOM reading.
                                if (url != null && url.contains("alphapolis.co.jp")) {
                                    view?.evaluateJavascript(
                                        "(function(){try{document.hasFocus=function(){return true};}catch(e){}})()"
                                    ) { }
                                }
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Main frame only matters; subframe finishes would
                                // trigger re-extraction storms.
                                if (url != null && url == view?.url) {
                                    DebugLog.log("READER", "loaded " + url.take(90) + " title=" + (view?.title ?: "").take(40))
                                    loadedUrl = url
                                    // Redirect committed on a spoof host (e.g.
                                    // extnu → literaturecity): adopt the right UA
                                    // so the ladder's bounce-retry passes the
                                    // site's browser check.
                                    view?.settings?.userAgentString =
                                        SitePolicy.uaFor(url, view.settings.userAgentString)
                                    extractTick += 1
                                }
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
                        // Per-host UA (SitePolicy): desktop-Chrome spoof for
                        // browser-checking hosts; cleaned mobile elsewhere.
                        settings.userAgentString =
                            SitePolicy.uaFor(chapter.url, settings.userAgentString)
                        if (!prohibited) loadUrl(chapter.url)
                    }
                },
                update = { webView ->
                    val stamp = Triple(chapter.url, loadKey, jsOn)
                    if (stamp != loadedStamp) {
                        loadedStamp = stamp
                        webView.settings.javaScriptEnabled = jsOn
                        webView.settings.domStorageEnabled = jsOn
                        if (!prohibited) webView.loadUrl(chapter.url)
                    }
                }
            )

            // Qidian/Webnovel warning (Wayne's rule, 2026-10-20): these
            // sites are never scraped or loaded by the app — they actively
            // seek to ban apps that access them. Reading stays possible
            // only outside the app.
            if (prohibitedNotice) {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            t(
                                "Not scraped by design: Qidian and Webnovel actively seek to ban apps that access their sites, so NovelNotifier never loads them. Open the chapter in your browser to read.",
                                "Qidian・Webnovelはアプリによるアクセスを積極的にブロック・通報するため、本アプリでは読み込み自体を行いません。お使いのブラウザで開いてください。"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.foundation.layout.Row {
                            androidx.compose.material3.TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(chapter.url)
                                        )
                                    )
                                }
                            }) { Text(t("Open in browser ↗", "ブラウザで開く ↗")) }
                            androidx.compose.material3.TextButton(onClick = { prohibitedNotice = false }) {
                                Text(t("DISMISS", "閉じる"))
                            }
                        }
                    }
                }
            }

            // Paywall interstitial (AlphaPolis): the web view stays up so the
            // user can unlock the chapter / buy credits in place; Retry
            // re-runs the whole extraction (and auto-saves once entitled).
            if (paywallNotice && viewMode == READER_VIEW_WEB) {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            t(
                                "Paywalled chapter — unlock it or buy credits here, then Retry to read & save it offline.",
                                "有料章節 — ここで購入・レンタル後、再試行すると読書・オフライン保存できます。"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.foundation.layout.Row {
                            androidx.compose.material3.TextButton(onClick = {
                                paywallNotice = false
                                viewMode = READER_VIEW_TEXT
                                retryKey += 1
                            }) { Text(t("RETRY", "再試行")) }
                            androidx.compose.material3.TextButton(onClick = { paywallNotice = false }) {
                                Text(t("DISMISS", "閉じる"))
                            }
                        }
                    }
                }
            }

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
                    extract.paragraphs.forEachIndexed { i, paragraph ->
                        // Inline illustrations anchored to this paragraph
                        // (site-embedded images — illustrations in the
                        // original text, Wayne's 2026-10-17 ask).
                        extract.images.filter { it.first == i }.forEach { (_, url) ->
                            AsyncImage(
                                // Offline-first: prefer the cached epub media
                                // bytes; the URL serves when never stored.
                                model = EpubMediaStore.localFor(context, url) ?: url,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                        // Saved layout choice: both (JP with EN under it),
                        // jp only, or en only once a translation exists.
                        val en = translation?.getOrNull(i)
                        val showEn = en != null && en.isNotBlank() && showTranslation
                        val isSpeaking = speakingPara == i
                        if (xlateLayout != "en") {
                            JpParagraph(
                                text = paragraph,
                                color = if (isSpeaking) palette.text.copy(alpha = 0.5f) else palette.text,
                                fontSize = fontSize,
                                serif = serif,
                                dictOn = jaDictOn,
                                onWord = { wordHit = it },
                                onTap = if (ttsState.state in listOf(
                                    TtsController.State.PLAYING, TtsController.State.PAUSED
                                )) {
                                    { _ -> TtsController.seekTo(context, i) }
                                } else null
                            )
                        }
                        // AI translation renders directly under its source
                        // paragraph (same order enforced by the JSON-array
                        // contract); slightly dimmed to keep the original
                        // visually primary.
                        if (showEn && xlateLayout != "jp") {
                            Text(
                                text = en,
                                color = palette.text.copy(alpha = if (xlateLayout == "en") 1f else 0.72f),
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.6).sp,
                                fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
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

            // TTS control bar — Voice Aloud / Moon+ Reader style.
            if (ttsState.state in listOf(TtsController.State.PLAYING, TtsController.State.PAUSED)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        // Main control row — @Voice pattern: play/pause center-dominant
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        ) {
                            IconButton(onClick = { TtsController.prev(context) }) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous paragraph")
                            }
                            // Play/Pause — FilledIconButton for visual dominance
                            // Dominant play/pause — filled circle (@Voice pattern)
                            Surface(
                                onClick = { TtsController.togglePlayPause(context) },
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp).size(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (ttsState.state == TtsController.State.PLAYING) {
                                        Icon(Icons.Rounded.Pause, contentDescription = "Pause",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(32.dp))
                                    } else {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(32.dp))
                                    }
                                }
                            }
                            IconButton(onClick = { TtsController.next(context) }) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Next paragraph")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            // Progress + speed + sleep in a compact row
                            Text(
                                "${ttsState.paragraph + 1} / ${ttsState.total}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            // Speed: tap the label, drag the slider, applies on
                            // release (setSpeed restarts the paragraph, so live
                            // drag would stutter).
                            var showSpeed by remember { mutableStateOf(false) }
                            var speedDraft by remember(ttsState.engine) { mutableStateOf(ttsState.speed) }
                            Box {
                                TextButton(onClick = {
                                    speedDraft = ttsState.speed
                                    showSpeed = true
                                }) {
                                    Text("${ttsState.speed}x", style = MaterialTheme.typography.labelMedium)
                                }
                                DropdownMenu(
                                    expanded = showSpeed,
                                    onDismissRequest = { showSpeed = false }
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text(
                                            t("Speed", "速度") + "  ${"%.2f".format(speedDraft)}x",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Slider(
                                            value = speedDraft,
                                            onValueChange = { speedDraft = it },
                                            onValueChangeFinished = {
                                                TtsController.setSpeed(context, (speedDraft * 20).toInt() / 20f)
                                            },
                                            valueRange = 0.5f..2.5f,
                                            modifier = Modifier.width(220.dp)
                                        )
                                    }
                                }
                            }
                            // Sleep timer: pauses playback when it runs out.
                            val sleepLeft by TtsController.sleepRemainingMs.collectAsState()
                            var showSleep by remember { mutableStateOf(false) }
                            Box {
                                // Material moon, not a unicode glyph — Samsung's
                                // font has no U+23FE (rendered as a tofu box).
                                TextButton(onClick = { showSleep = true }) {
                                    Icon(
                                        Icons.Rounded.Bedtime,
                                        contentDescription = "Sleep timer",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (sleepLeft != null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (sleepLeft != null) {
                                        Text(
                                            "  " + TtsController.formatSleep(sleepLeft ?: 0),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showSleep,
                                    onDismissRequest = { showSleep = false }
                                ) {
                                    listOf(0, 5, 15, 30, 60).forEach { min ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (min == 0) t("Off", "オフ")
                                                    else t("${min} min", "${min}分")
                                                )
                                            },
                                            onClick = {
                                                TtsController.setSleepTimer(min)
                                                showSleep = false
                                            }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { TtsController.stop() }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Stop",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        // Thin progress bar
                        LinearProgressIndicator(
                            progress = { if (ttsState.total > 0) (ttsState.paragraph + 1f) / ttsState.total else 0f },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    }
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
                    t("Page did not load (site down or blocked)", "ページを読み込めません（サイトダウンまたはブロック）"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    loadFailed = false
                    loadKey += 1
                }) { Text(t("Retry", "再試行")) }
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
            ) { Text("‹ " + t("Prev", "前へ")) }
            Text(
                text = "${index + 1} / ${target.chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            TextButton(
                onClick = { if (index < target.chapters.lastIndex) index += 1 },
                enabled = index < target.chapters.lastIndex
            ) { Text(t("Next", "次へ") + " ›") }
        }
    }

    // Translation failures surface as a dialog (popup over inline text —
    // keeps the reading layout clean); Retry re-runs the chapter.
    if (translateError.isNotBlank()) {
        ErrorDialog(
            title = t("Translation failed", "翻訳に失敗しました"),
            message = translateError,
            onRetry = { startTranslate() },
            onDismiss = { translateError = "" }
        )
    }
}
