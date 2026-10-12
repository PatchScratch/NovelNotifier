package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Syosetu login: an in-app WebView over syosetu.com/login — credentials land
 * in the app's shared cookie jar (NOT the external browser), powering
 * bookmark-category sync. Detects success by the page itself showing the
 * logged-in header (a ログアウト link / redirect away from the login form),
 * so we never depend on cookie names.
 */
@Composable
fun SyosetuLoginScreen(
    onLoggedIn: () -> Unit,
    onCancel: () -> Unit
) {
    var checking by remember { mutableStateOf(false) }

    fun isLoggedInNow(view: WebView?): Boolean {
        val html = view?.url ?: return false
        // after login Narou bounces off /login/; a logout link anywhere confirms
        val body = try {
            view.evaluateJavascript("document.body.innerText") { }
            null
        } catch (e: Exception) { null }
        return !html.contains("/login/")
    }

    BackHandler { onCancel() }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                "Syosetu ログイン — sign in; the app closes this when done.",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onLoggedIn) { Text("Done") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url == null) return
                            DebugLog.log("SYOSETU", "login page finished: $url")
                            // Success = redirected AWAY from /login/. A failed
                            // sign-in re-shows the form at /login/input/; only a
                            // valid POST lands elsewhere (redirectlink target,
                            // mypage, top). Requiring a literal ログアウト link
                            // here was too strict — the landing page's header
                            // doesn't always carry it, and the screen sat open.
                            if (url.contains("syosetu.com") && !url.contains("/login")) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { onLoggedIn() }
                            }
                        }
                    }
                    loadUrl("https://syosetu.com/login/input/")
                }
            }
        )
    }
}
