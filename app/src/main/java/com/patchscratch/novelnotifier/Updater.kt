package com.patchscratch.novelnotifier

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app self-updater (Wayne's 2026-10-16 spec): a silent GitHub check once
 * a day on launch, a prompt with IGNORE / DOWNLOAD AND INSTALL, a
 * foreground download that streams the APK straight into a PackageInstaller
 * session (UpdateDownloadService), and — after an in-place update — a
 * changelog popup with OK on first start (updates only; fresh installs stay
 * silent).
 *
 * Source of truth is GitHub Releases (the app's only distribution):
 * releases/latest excludes drafts/prereleases, which matches the "release
 * it" gate, and the APK asset naming NovelNotifier-v<version>.apk is the
 * documented convention (AGENTS.md).
 */

/** One published release, reduced to what the prompt needs. */
data class UpdateInfo(
    val tag: String,          // "v2.3"
    val version: String,      // "2.3" (tag without v)
    val title: String,        // release name
    val body: String,         // release notes (= the fastlane changelog)
    val apkUrl: String,
    val apkBytes: Long
)

/**
 * Shared UI state for the updater flow: written by the check (in
 * AppViewModel) and the download service, read by the root dialogs.
 * `phase`: "" idle · "prompt" · "downloading" · "installing" · error text.
 */
object UpdateState {
    var info by mutableStateOf<UpdateInfo?>(null)
    var phase by mutableStateOf("")
    var progress by mutableIntStateOf(-1)   // -1 indeterminate, 0..100 definite
    fun reset() {
        info = null; phase = ""; progress = -1
    }
}

/** The installed version, e.g. "2.2". */
fun installedVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

/**
 * True when [tag] names a strictly newer version than [current]. Numeric
 * per component, tolerant of the early 3-component tags (v1.0.0) and of
 * any "v" prefix. Non-numeric components compare 0 (never crash on odd
 * tags — an unparseable tag simply isn't newer).
 */
fun isNewer(tag: String, current: String): Boolean {
    fun parts(s: String) = s.trim().removePrefix("v")
        .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(tag); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * The latest published release, or null (no release / no APK asset /
 * network failure — callers treat null as "nothing to say").
 */
suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val conn = URL("https://api.github.com/repos/PatchScratch/NovelNotifier/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "NovelNotifier/1.0 (github.com/PatchScratch/NovelNotifier)")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        parseLatestRelease(raw)
    } catch (e: Exception) {
        DebugLog.log("UPDATE", "release check failed", e)
        null
    }
}

/** Pure parser (unit-tested): {tag_name, name, body, assets[]} → UpdateInfo. */
internal fun parseLatestRelease(json: String): UpdateInfo? = try {
    val o = JSONObject(json)
    val tag = o.optString("tag_name")
    if (tag.isBlank()) null else {
        val apkName = "NovelNotifier-v${tag.removePrefix("v")}.apk"
        val asset = o.optJSONArray("assets")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("name") == apkName }
        }
        if (asset == null) {
            DebugLog.log("UPDATE", "release $tag has no asset $apkName")
            null
        } else UpdateInfo(
            tag = tag,
            version = tag.removePrefix("v"),
            title = o.optString("name").ifBlank { tag },
            body = o.optString("body"),
            apkUrl = asset.optString("browser_download_url"),
            apkBytes = asset.optLong("size", 0L)
        )
    }
} catch (e: Exception) {
    DebugLog.log("UPDATE", "release parse failed", e)
    null
}

// --- first-start-after-update changelog --------------------------------------

/**
 * The changelog text for the installed versionCode, bundled at build time
 * from the fastlane changelogs (build.gradle copyChangelogs task). Empty
 * when the file is missing — the popup then shows just the version line.
 */
fun bundledChangelog(context: Context): String = try {
    val code = context.packageManager.getPackageInfo(context.packageName, 0).versionCodeCompat()
    context.assets.open("changelogs/$code.txt").bufferedReader().use { it.readText() }.trim()
} catch (e: Exception) {
    ""
}

private fun android.content.pm.PackageInfo.versionCodeCompat(): Int =
    if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else @Suppress("DEPRECATION") versionCode

/**
 * "Updated to vX" popup with OK (Wayne's spec): shown on the first start
 * after an in-place update — the stored last-run version differs from the
 * installed one. Fresh installs record the version silently and stay quiet.
 * [onSawIt] persists the current version as seen.
 */
@Composable
fun ChangelogDialog(currentVersion: String, onSawIt: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onSawIt,
        title = { Text(t("Updated to v", "アップデート済み: v") + currentVersion) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val log = bundledChangelog(context)
                if (log.isNotBlank()) {
                    Text(log, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        t("(No changelog was bundled with this build.)", "（このビルドに変更履歴が同梱されていません。）"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSawIt) { Text("OK") }
        }
    )
}

/**
 * The update prompt: release notes with IGNORE / DOWNLOAD AND INSTALL.
 * While the service downloads, the same dialog becomes a progress card
 * (system install sheet takes over at 100%).
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    onIgnore: () -> Unit,
    onDownload: (Context) -> Unit
) {
    val context = LocalContext.current
    val info = UpdateState.info ?: return
    val downloading = UpdateState.phase == "downloading" || UpdateState.phase == "installing"
    AlertDialog(
        onDismissRequest = { if (!downloading) onIgnore() },
        title = {
            Text(
                if (downloading) t("Downloading update…", "アップデートをダウンロード中…")
                else t("Update available — v", "アップデートあり — v") + info.version +
                    t(" (you have v", "（現在 v") + currentVersion + t(")", "）")
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (downloading) {
                    val pct = UpdateState.progress
                    if (pct in 0..100) {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "$pct%  ·  ${info.apkBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        t(
                            "The system will ask you to confirm the installation when the download finishes.",
                            "ダウンロード完了後、システムがインストールの確認を求めます。"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    if (info.body.isNotBlank()) {
                        Text(info.body, style = MaterialTheme.typography.bodySmall)
                    } else if (info.title.isNotBlank()) {
                        Text(info.title, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (!downloading) {
                TextButton(onClick = { onDownload(context) }) {
                    Text(t("Download and install", "ダウンロードしてインストール"))
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onIgnore) { Text(t("Ignore", "無視")) }
            }
        }
    )
}

/**
 * Install-permission gate: Android requires the user to allow "install
 * unknown apps" for this app once before PackageInstaller works from a
 * non-Play source. Shown by the caller when canRequestPackageInstalls()
 * is false at download time.
 */
@Composable
fun InstallPermissionDialog(
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(t("Allow installations", "インストールを許可")) },
        text = {
            Text(
                t(
                    "To update itself, the app needs Android's permission to install packages. " +
                        "The next screen has one switch — turn it on, press back, and the update continues.",
                    "アプリが自己更新するには、Androidの「このソースからのインストール」許可が必要です。" +
                        "次の画面のスイッチをオンにして戻ると、更新が続行されます。"
                ),
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(t("Open settings", "設定を開く")) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(t("Cancel", "キャンセル")) }
        }
    )
}

/** Deep link to this app's "install unknown apps" switch. */
fun unknownSourcesIntent(context: Context): Intent =
    Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

/** True when Android lets this app install packages right now. */
fun canRequestInstalls(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 26 ||
        context.packageManager.canRequestPackageInstalls()

/** Cheap connectivity gate for the once-a-day background check. */
fun updateCheckDue(prefs: android.content.SharedPreferences): Boolean =
    System.currentTimeMillis() - prefs.getLong("update_last_check_at", 0L) > 24 * 3600_000L
