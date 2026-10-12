package com.patchscratch.novelnotifier

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * Kakuyomu login: an in-app WebView over kakuyomu.jp/login — Google / Apple /
 * email all work; the session lands in the app's shared cookie jar and powers
 * official bookmarks (フォロー).
 *
 * Close rule is POSITIVE-ONLY: the screen closes when the open GraphQL
 * endpoint confirms a signed-in visitor. URL heuristics alone proved wrong —
 * the OIDC flow (and even the login page's own logo link) touches plain
 * kakuyomu.jp URLs mid-flow, which once closed the screen before the user
 * finished signing in (2026-10-13). Every qualifying page finish re-checks
 * the session, cheap and idempotent; "Done" runs the same check and explains
 * itself instead of closing when no session exists.
 */
@Composable
fun KakuyomuLoginScreen(
    onLoggedIn: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    fun checkSession() {
        if (checking) return
        checking = true
        scope.launch {
            try {
                CookieManager.getInstance().flush()
                val visitor = fetchKakuyomuVisitor()
                if (visitor != null) {
                    DebugLog.log("KAKUYOMU", "login confirmed: @${visitor.screenName}")
                    onLoggedIn()
                    return@launch
                }
                hint = t("No session yet — finish signing in, then tap Done.", "まだログインしていません — サインインを完了してから完了をタップしてください。")
            } catch (e: Exception) {
                DebugLog.log("KAKUYOMU", "login session check failed", e)
                hint = t("Check failed — tap Done to retry.", "確認に失敗しました — 完了をタップして再試行。")
            }
            checking = false
        }
    }

    BackHandler { onCancel() }

    Column(modifier = Modifier.fillMaxSize().screenInsets()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                t("Kakuyomu login — the app closes itself once you're signed in.",
                    "カクヨム ログイン — サインインすると自動で閉じます。"),
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { checkSession() }) { Text(t("Done", "完了")) }
            TextButton(onClick = onCancel) { Text(t("Cancel", "キャンセル")) }
        }
        if (hint.isNotBlank()) {
            Text(hint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
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
                            // Auth pages and provider hops never prove anything;
                            // anything else on kakuyomu.jp MIGHT be the post-login
                            // landing — ask the API whether a session exists.
                            if (url.contains("kakuyomu.jp") &&
                                !url.contains("/login") && !url.contains("/oidc/") &&
                                !url.contains("accounts.")
                            ) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { checkSession() }
                            }
                        }
                    }
                    loadUrl("https://kakuyomu.jp/login")
                }
            }
        )
    }
}
