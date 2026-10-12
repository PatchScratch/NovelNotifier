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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Kakuyomu login: an in-app WebView over kakuyomu.jp/login — Google / Apple /
 * email all work; the session lands in the app's shared cookie jar and powers
 * official bookmarks (フォロー). Success = redirected away from /login (the
 * same rule that proved reliable for Syosetu); a failed sign-in re-shows the
 * form.
 */
@Composable
fun KakuyomuLoginScreen(
    onLoggedIn: () -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                t("Kakuyomu login — sign in; the app closes this when done.",
                    "カクヨム ログイン — サインインすると自動で閉じます。"),
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onLoggedIn) { Text(t("Done", "完了")) }
            TextButton(onClick = onCancel) { Text(t("Cancel", "キャンセル")) }
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
                            DebugLog.log("KAKUYOMU", "login page finished: $url")
                            // Away from /login = session established. OIDC hops
                            // (google/apple) stay on other hosts until they
                            // bounce back, so only kakuyomu.jp pages count.
                            if (url.contains("kakuyomu.jp") &&
                                !url.contains("/login") && !url.contains("/oidc/")
                            ) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { onLoggedIn() }
                            }
                        }
                    }
                    loadUrl("https://kakuyomu.jp/login")
                }
            }
        )
    }
}
