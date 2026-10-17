package com.patchscratch.novelnotifier

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * AlphaPolis login (B4): the one place a browser is needed — a visible
 * WebView on the site's login page; every headless fetch elsewhere shares
 * the cookie jar this fills (premium chapters the account paid for flow
 * through the same episode_body POST).
 *
 * Login detection: AlphaPolis sets its session via a `remember_*` token
 * cookie family; when it appears, the session is live. The positive rule
 * (a cookie that exists ONLY when logged in) follows the Kakuyomu lesson —
 * never URL-shape detection.
 */
@Composable
fun AlphaPolisLoginScreen(
    onLoggedIn: () -> Unit,
    onCancel: () -> Unit,
    // The page to open; defaults to the login page. Pointing it at a
    // logged-in-only page doubles as a session viewer (already logged in
    // → the cookie rule fires immediately).
    startUrl: String = "https://www.alphapolis.co.jp/login"
) {
    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = t("Log in to AlphaPolis", "アルファポリスにログイン"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text(t("Cancel", "キャンセル")) }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Positive rule: a logged-in-only cookie. The
                            // remember_* family is the site's persistent
                            // login token (shaped like other Laravel apps);
                            // verify live against Wayne's session.
                            val cookies = CookieManager.getInstance()
                                .getCookie("https://www.alphapolis.co.jp") ?: ""
                            if (cookies.contains("remember_") || cookies.contains("session")) {
                                onLoggedIn()
                            }
                        }
                    }
                    loadUrl(startUrl)
                }
            }
        )
    }
}
