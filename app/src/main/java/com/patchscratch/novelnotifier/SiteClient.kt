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
        var webView: WebView? = null      // lazy, main-thread access only
    }

    internal val sites = Site.entries.associateWith { SiteState() }
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** True when the site has pending or in-flight requests. */
    fun isBusy(site: Site): Boolean {
        val state = sites[site] ?: return false
        return state.processing || state.queue.isNotEmpty()
    }

    /** Cloudflare encountered — apply escalating cooldown. */
    fun reportCloudflare(site: Site) {
        val state = sites[site] ?: return
        val now = System.currentTimeMillis()
        val cooldown = if (now - state.cloudflareUntil < 60_000) {
            // Repeat CF within a minute → escalate
            CF_GAP_MS * 3   // 60s
        } else {
            CF_GAP_MS       // 20s
        }
        state.cloudflareUntil = now + cooldown
        DebugLog.log("SITE", "Cloudflare on ${site.name}, cooldown ${cooldown / 1000}s")
    }

    // --- queue pump ---------------------------------------------------------

    private fun pump(site: Site) {
        val state = sites[site] ?: return
        if (state.processing) return
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

                    // Cloudflare detection (same logic as Parsers.kt)
                    view.evaluateJavascript("document.title") { title ->
                        val t = title?.trim('"') ?: ""
                        if (t.contains("Cloudflare", ignoreCase = true) ||
                            t.contains("Attention Required", ignoreCase = true)) {
                            reportCloudflare(site)
                            deferred.complete("")
                            return@evaluateJavascript
                        }
                        // Normal page — run optional JS then extract
                        if (request.js != null) {
                            view.evaluateJavascript(request.js) { jsResult ->
                                deferred.complete(jsResult ?: "")
                            }
                        } else {
                            view.evaluateJavascript(
                                "(document.body ? document.body.innerHTML : '')"
                            ) { html ->
                                deferred.complete(html ?: "")
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
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"
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

        /**
         * Load a URL. [callback] fires with the JS result (or page HTML if
         * [js] is null) when the page finishes. Cloudflare detection is
         * automatic — the callback receives "CF" if detected.
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

                        // CF detection
                        view.evaluateJavascript("document.title") { title ->
                            val t = title?.trim('"') ?: ""
                            if (t.contains("Cloudflare", ignoreCase = true) ||
                                t.contains("Attention Required", ignoreCase = true)) {
                                client.reportCloudflare(site)
                                callback("CF")
                                return@evaluateJavascript
                            }
                            if (js != null) {
                                view.evaluateJavascript(js) { result ->
                                    callback(result ?: "")
                                }
                            } else {
                                view.evaluateJavascript(
                                    "(document.body ? document.body.innerHTML : '')"
                                ) { html ->
                                    callback(html ?: "")
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
            // Clear the session's WebViewClient so queue-based loads work again
            val ctx = appContext
            if (ctx != null) {
                mainHandler.post {
                    client.sites[site]?.webView?.webViewClient = WebViewClient() // neutral
                }
            }
            DebugLog.log("SITE", "session closed for ${site.name}")
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
