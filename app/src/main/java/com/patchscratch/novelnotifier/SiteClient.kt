package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Singleton owner of one headless WebView per site — the Phase 4a debt
 * cleanup. Previously 13 separate WebView instances across screens and
 * services each managed their own cookie jar access and pacing; this
 * centralises them behind a request queue so:
 *
 * - Pacing is enforced globally per site (10s standard, 20s post-CF),
 *   not per-screen — two screens hitting NU can no longer double-tap.
 * - Cloudflare state is tracked per site: once CF fires, every pending
 *   and future request waits until the cooldown clears.
 * - The session cookies are shared automatically (Android's global
 *   CookieManager), but lifecycle is now owned here instead of leaked
 *   by whichever screen happened to create a WebView.
 *
 * Migration path: SyncEngineService first, then screens one at a time.
 * Each screen's existing `WebView(context).apply { ... }` block becomes
 * a `SiteClient.enqueue(site, ScrapeRequest(...))` call.
 */
object SiteClient {

    enum class Site { NU, SYOSETU, KAKUYOMU }

    /** What a screen wants done and how to tell it the result. */
    data class ScrapeRequest(
        val url: String,
        val js: String? = null,          // optional JS to evaluate after load
        val timeoutMs: Long = 30_000,
        val onComplete: (Result) -> Unit
    ) {
        sealed class Result {
            data class Success(val html: String) : Result()
            data class Cloudflare(val url: String) : Result()
            data class Error(val message: String) : Result()
        }
    }

    // --- per-site state ---------------------------------------------------

    internal class SiteState {
        val queue = ConcurrentLinkedQueue<ScrapeRequest>()
        @Volatile var processing = false
        @Volatile var lastRequestAt = 0L
        @Volatile var cloudflareUntil = 0L
        @Volatile var sessionActive = false
        var webView: WebView? = null      // lazy, main-thread access only
    }

    internal val sites = Site.entries.associateWith { SiteState() }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Non-null when a site's Cloudflare block is persistent (repeated within
     * the cooldown window) and needs the USER to solve the challenge. The
     * root-level ChallengeOverlay collects this; it clears via
     * [clearCloudflare] once the user passes the check.
     */
    data class CfChallenge(val site: Site, val url: String)

    private val _cfNeeded = MutableStateFlow<CfChallenge?>(null)
    val cfNeeded: StateFlow<CfChallenge?> = _cfNeeded

    // pacing (from the house rules in AGENTS.md)
    private const val STANDARD_GAP_MS = 10_000L
    private const val CF_GAP_MS = 20_000L

    // --- public API --------------------------------------------------------

    /**
     * Enqueue a scrape. The caller's [ScrapeRequest.onComplete] fires on
     * a background thread when the page is loaded (and JS evaluated if
     * provided). Pacing and Cloudflare cooldowns are applied automatically.
     */
    fun enqueue(site: Site, request: ScrapeRequest) {
        val state = sites[site] ?: return
        state.queue.add(request)
        DebugLog.log("SITE", "enqueued ${site.name} ${request.url.take(60)} (queue=${state.queue.size})")
        pump(site)
    }

    /** True when the site has pending or in-flight requests or an open session. */
    fun isBusy(site: Site): Boolean {
        val state = sites[site] ?: return false
        return state.processing || state.sessionActive || state.queue.isNotEmpty()
    }

    /** Cloudflare encountered — apply escalating cooldown. */
    fun reportCloudflare(site: Site, blockedUrl: String? = null) {
        val state = sites[site] ?: return
        val now = System.currentTimeMillis()
        val repeat = now - state.cloudflareUntil < 60_000
        val cooldown = if (repeat) {
            // Repeat CF within a minute → escalate
            CF_GAP_MS * 3   // 60s
        } else {
            CF_GAP_MS       // 20s
        }
        state.cloudflareUntil = now + cooldown
        // A repeat means pacing alone won't clear it — surface the challenge.
        if (repeat && _cfNeeded.value == null) {
            _cfNeeded.value = CfChallenge(site, blockedUrl ?: site.homeUrl())
            DebugLog.log("SITE", "persistent Cloudflare on ${site.name} — challenge needed")
        }
        DebugLog.log("SITE", "Cloudflare on ${site.name}, cooldown ${cooldown / 1000}s")
    }

    private fun Site.homeUrl(): String = when (this) {
        Site.NU -> "https://www.novelupdates.com/"
        Site.SYOSETU -> "https://syosetu.com/"
        Site.KAKUYOMU -> "https://kakuyomu.jp/"
    }

    /**
     * The user passed the Cloudflare challenge (detected by the root
     * ChallengeOverlay) — drop the cooldown so the queue resumes immediately
     * and clear the challenge flag.
     */
    fun clearCloudflare(site: Site) {
        val state = sites[site] ?: return
        state.cloudflareUntil = 0L
        if (_cfNeeded.value?.site == site) _cfNeeded.value = null
        DebugLog.log("SITE", "Cloudflare cleared for ${site.name}")
    }

    // --- queue pump ---------------------------------------------------------

    private fun pump(site: Site) {
        val state = sites[site] ?: return
        if (state.processing) return
        // A Session owns the WebView exclusively — leave the queued requests
        // untouched; close() pumps again to resume.
        if (state.sessionActive) return
        val next = state.queue.poll() ?: return

        val now = System.currentTimeMillis()
        val waitMs = when {
            now < state.cloudflareUntil -> state.cloudflareUntil - now
            now - state.lastRequestAt < STANDARD_GAP_MS ->
                STANDARD_GAP_MS - (now - state.lastRequestAt)
            else -> 0L
        }

        if (waitMs > 0) {
            DebugLog.log("SITE", "${site.name} pacing wait ${waitMs / 1000}s")
            // Re-queue and retry after the wait
            state.queue.add(next)
            mainHandler.postDelayed({ pump(site) }, waitMs)
            return
        }

        state.processing = true
        state.lastRequestAt = now
        execute(site, next) { result ->
            state.processing = false
            // Fire the callback on a background thread (callers may do I/O)
            Thread { next.onComplete(result) }.start()
            // Continue with the next request
            if (state.queue.isNotEmpty()) pump(site)
        }
    }

    // --- WebView execution -----------------------------------------------------

    // Title heuristics: the shared helper plus the literal "cloudflare"
    // the older inline check used to catch.
    private fun isCfTitle(t: String) =
        isCloudflareTitle(t) || t.contains("cloudflare", ignoreCase = true)

    // One round-trip when no custom JS is requested: CF probe, then body
    // HTML. CF_DETECT_JS ends with a top-level `return 'CF'`, so it must
    // sit inside this wrapper's function scope to be valid JS.
    internal val PROBE_AND_EXTRACT_JS =
        "(function(){\n$CF_DETECT_JS\nreturn (document.body ? document.body.innerHTML : '');\n})()"

    private fun execute(site: Site, request: ScrapeRequest, done: (ScrapeRequest.Result) -> Unit) {
        mainHandler.post {
            val context = appContext ?: run {
                done(ScrapeRequest.Result.Error("no app context"))
                return@post
            }
            val webView = getOrCreateWebView(site, context)
            val deferred = CompletableDeferred<String>()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null || view == null) return
                    DebugLog.log("SITE", "page finished: $url")

                    // Cloudflare detection: title/URL heuristics first, then
                    // the content probe (custom payloads embed CF_DETECT_JS
                    // and return 'CF' themselves).
                    view.evaluateJavascript("document.title") { title ->
                        val t = title?.trim('"') ?: ""
                        if (isCfTitle(t) || isCloudflareUrl(url)) {
                            reportCloudflare(site, request.url)
                            deferred.complete("")
                            return@evaluateJavascript
                        }
                        val runJs = request.js ?: PROBE_AND_EXTRACT_JS
                        view.evaluateJavascript(runJs) { jsResult ->
                            if (unwrapJsResult(jsResult) == "CF") {
                                reportCloudflare(site, request.url)
                                deferred.complete("")
                            } else {
                                deferred.complete(jsResult ?: "")
                            }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    DebugLog.log("SITE", "WebView error $errorCode: $description")
                    deferred.completeExceptionally(Exception("HTTP $errorCode: $description"))
                }
            }

            webView.loadUrl(request.url)

            // Timeout
            Thread {
                try {
                    val html = kotlinx.coroutines.runBlocking {
                        withTimeout(request.timeoutMs) { deferred.await() }
                    }
                    if (html.isBlank()) {
                        done(ScrapeRequest.Result.Cloudflare(request.url))
                    } else {
                        done(ScrapeRequest.Result.Success(html))
                    }
                } catch (e: Exception) {
                    done(ScrapeRequest.Result.Error(e.message ?: "timeout"))
                }
            }.start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(site: Site, context: Context): WebView {
        val state = sites[site] ?: error("unknown site $site")
        state.webView?.let { return it }

        val webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Same UA the screens use: Cloudflare fingerprints the UA, so
            // the shared scraper must not differ from what the user solves
            // the challenge with.
            settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        state.webView = webView
        DebugLog.log("SITE", "created WebView for ${site.name}")
        return webView
    }

    // --- session API (for multi-page scrapers) --------------------------------
    // EpubBulkService, NovelDetailScreen, NovelListScreen, and SiteSearch-
    // Screen need MULTIPLE loadUrl calls on the SAME WebView with a
    // persistent WebViewClient (URL interception, ToC harvesting across
    // page loads, interactive JS evaluation). The Session wraps the shared
    // WebView and fires the caller's callbacks on every page load. While a
    // session is active, the queue-based enqueue() waits.

    /**
     * Open a scraping session on [site]. While active, the site's WebView
     * is exclusively yours; the request queue is paused. Call [Session.close]
     * when done to release the WebView back to the queue.
     */
    fun openSession(site: Site): Session {
        val state = sites[site] ?: error("unknown site $site")
        state.sessionActive = true
        if (state.processing) {
            // An in-flight queue request may still complete; its client gets
            // replaced by the session's first load. Its own timeout still
            // settles its callback — log it so the overlap is visible.
            DebugLog.log("SITE", "session opened on ${site.name} while a request is in flight")
        }
        return Session(site, this)
    }

    /**
     * A multi-load scraping session on one site's WebView. The caller
     * controls pacing between loads (typically the same 8–10s gap they
     * use today); SiteClient provides the WebView, cookie jar, and CF state.
     */
    class Session internal constructor(
        private val site: Site,
        private val client: SiteClient
    ) {
        private var closed = false

        /** Optional: called on EVERY page finish with the loaded URL. Use for ToC harvesting. */
        var onPageLoaded: ((url: String) -> Unit)? = null

        /** Optional: intercept a URL before load; return the replacement URL or null to allow. */
        var urlInterceptor: ((url: String) -> String?)? = null

        /** The WebView's current URL, or null if none yet (same read the old in-screen scrapers did). */
        fun currentUrl(): String? = client.sites[site]?.webView?.url

        /**
         * Load a URL. [callback] fires with the JS result (or page HTML if
         * [js] is null) when the page finishes. Cloudflare detection is
         * automatic — the callback receives "CF" if detected (title/URL
         * heuristics plus the CF_DETECT_JS content probe).
         */
        fun loadUrl(url: String, js: String? = null, callback: (String) -> Unit) {
            if (closed) return
            val ctx = appContext ?: return
            mainHandler.post {
                if (closed) return@post
                val webView = client.getOrCreateWebView(site, ctx)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (view == null || url == null) return
                        DebugLog.log("SITE", "session page finished: $url")

                        // Fire the ToC-harvest observer first
                        onPageLoaded?.invoke(url)

                        // CF detection: title/URL heuristics, then the
                        // content probe (custom payloads return 'CF' themselves).
                        view.evaluateJavascript("document.title") { title ->
                            val t = title?.trim('"') ?: ""
                            if (client.isCfTitle(t) || isCloudflareUrl(url)) {
                                client.reportCloudflare(site, url)
                                callback("CF")
                                return@evaluateJavascript
                            }
                            val runJs = js ?: client.PROBE_AND_EXTRACT_JS
                            view.evaluateJavascript(runJs) { result ->
                                if (unwrapJsResult(result) == "CF") {
                                    client.reportCloudflare(site, url)
                                    callback("CF")
                                } else {
                                    callback(result ?: "")
                                }
                            }
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val target = request?.url?.toString() ?: return false
                        val replacement = urlInterceptor?.invoke(target)
                        if (replacement != null && replacement != target) {
                            view?.loadUrl(replacement)
                            return true
                        }
                        return false // let the WebView load normally
                    }
                }
                webView.loadUrl(url)
            }
        }

        /**
         * Evaluate JS on the currently-loaded page (no navigation). Use
         * for AJAX calls through the same-origin policy (SiteSearchScreen).
         */
        fun evaluateJavascript(script: String, callback: (String) -> Unit) {
            if (closed) return
            val ctx = appContext ?: return
            mainHandler.post {
                if (closed) return@post
                val webView = client.getOrCreateWebView(site, ctx)
                webView.evaluateJavascript(script) { result ->
                    callback(result ?: "")
                }
            }
        }

        /** Release the WebView back to SiteClient. The queue resumes. */
        fun close() {
            if (closed) return
            closed = true
            onPageLoaded = null
            urlInterceptor = null
            val state = client.sites[site]
            state?.sessionActive = false
            // Clear the session's WebViewClient so queue-based loads work again
            val ctx = appContext
            if (ctx != null) {
                mainHandler.post {
                    client.sites[site]?.webView?.webViewClient = WebViewClient() // neutral
                }
            }
            DebugLog.log("SITE", "session closed for ${site.name}")
            if (state != null && state.queue.isNotEmpty()) client.pump(site)
        }
    }

    // App context for WebView creation (set from MainActivity's Application)
    @Volatile private var appContext: Context? = null
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Clean shutdown — release all WebViews. */
    fun shutdown() {
        mainHandler.post {
            sites.values.forEach { state ->
                state.webView?.destroy()
                state.webView = null
            }
        }
    }
}
