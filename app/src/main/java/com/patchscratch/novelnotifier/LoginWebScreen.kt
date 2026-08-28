package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
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
 * Visible WebView of novelupdates.com/login/.
 * When the wordpress_logged_in cookie appears, we save it and close this screen.
 * Cookies are process-wide, so later hidden WebViews are already logged in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebScreen(
    onLoggedIn: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Log in",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
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
                            val cookies = CookieManager.getInstance()
                                .getCookie("https://www.novelupdates.com") ?: ""
                            if (cookies.contains("wordpress_logged_in")) {
                                onLoggedIn(cookies)
                            }
                        }
                    }
                    loadUrl("https://www.novelupdates.com/login/")
                }
            }
        )
    }
}
