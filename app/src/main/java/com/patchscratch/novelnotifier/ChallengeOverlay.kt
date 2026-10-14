package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Root-level Cloudflare challenge pane (Phase 4b, replaces the per-screen
 * dual-purpose panes as screens migrate to SiteClient). Shows when
 * SiteClient.cfNeeded reports a PERSISTENT block — one CF hit is handled by
 * pacing; a repeat within the cooldown window means a human has to pass the
 * check.
 *
 * A dedicated visible WebView at real layout size from the start (no
 * rescale-and-reload dance). Cookies are app-global via CookieManager, so
 * once the check passes here, the headless scraper's next load clears.
 * The pane auto-detects the solve (page finishes non-CF) and calls
 * [onSolved]; "Continue" does the same by hand.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChallengeOverlay(
    challenge: SiteClient.CfChallenge,
    onSolved: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val currentOnSolved by androidx.compose.runtime.rememberUpdatedState(onSolved)
        var solvedNotified = remember { arrayOf(false) }
        val challengeView = remember(challenge) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = chromeMobileUserAgent(settings.userAgentString)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Turnstile redirects to the real page on success →
                        // this finishes non-CF → solved.
                        view?.evaluateJavascript(SiteClient.PROBE_AND_EXTRACT_JS) { res ->
                            if (unwrapJsResult(res) != "CF" && !solvedNotified[0]) {
                                solvedNotified[0] = true
                                DebugLog.log("CF", "overlay detected challenge solved")
                                currentOnSolved()
                            }
                        }
                    }
                }
                loadUrl(challenge.url)
            }
        }
        DisposableEffect(challenge) {
            onDispose {
                challengeView.destroy()
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(460.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Complete the site check, then Continue",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onSolved) { Text(t("Continue", "続ける")) }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { challengeView }
                )
            }
        }
    }
}
