package com.patchscratch.novelnotifier

import android.content.Context
import androidx.core.content.edit
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * RSS URL, login, library sync, backup/restore, check interval, display mode.
 * Uses screenInsets() so Cancel sits above the Android nav bar.
 *
 * getValue / setValue are used by the `by` keyword. Do not delete those imports.
 */
@Composable
fun SettingsScreen(
    site: String = "nu",
    kakuyomuR18: Boolean = false,
    onKakuyomuR18: (Boolean) -> Unit = {},
    currentUrl: String,
    currentHours: Int,
    loginState: String,
    syncStatus: String,
    onThemeChange: (String) -> Unit = {},
    onCustomThemeReady: (CustomTheme?) -> Unit = {},
    onStartCoverSync: () -> Unit,
    onStopCoverSync: () -> Unit,
    onSyosetuLogin: () -> Unit = {},
    onSyosetuLogout: () -> Unit = {},
    onSyosetuSync: () -> Unit = {},
    syosetuR18: Boolean = false,
    onSyosetuR18: (Boolean) -> Unit = {},
    syosetuSubsites: Set<Int> = setOf(1, 2, 3),
    onSyosetuSubsites: (Set<Int>) -> Unit = {},
    syosetuLoggedIn: Boolean = false,
    syosetuStatus: String = "",
    kakuyomuLoggedIn: Boolean = false,
    kakuyomuStatus: String = "",
    onKakuyomuLogin: () -> Unit = {},
    onKakuyomuLogout: () -> Unit = {},
    onKakuyomuSync: () -> Unit = {},
    onCheckNow: () -> Unit,
    onSave: (String, Int) -> Unit,
    onStartLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
    var url by remember { mutableStateOf(currentUrl) }
    var hours by remember { mutableIntStateOf(currentHours) }
    var layoutMode by remember { mutableStateOf(loadLayoutMode(prefs)) }
    var stackAlerts by remember { mutableStateOf(loadStackAlerts(prefs)) }
    var themeMode by remember { mutableStateOf(loadThemeMode(prefs)) }
    // Mirrors filesDir/custom_theme.json; the radio only exists while set.
    var customThemeName by remember {
        mutableStateOf(prefs.getString("custom_theme_name", "").orEmpty())
    }
    var themeMessage by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    val options = listOf(1, 6, 12, 24)

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch { backupMessage = BackupManager.export(context, uri) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch { backupMessage = BackupManager.restore(context, uri) }
    }

    // Persist + tell MainActivity so the new palette applies immediately.
    fun pickTheme(mode: String) {
        themeMode = mode
        saveThemeMode(prefs, mode)
        onThemeChange(mode)
    }

    // Import a user theme file: validate, store, switch to it — or show why not.
    val themeImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    importCustomTheme(context, uri)
                }
                customThemeName = parsed.name
                themeMessage = "Imported \"${parsed.name}\""
                onCustomThemeReady(parsed)
                pickTheme(THEME_CUSTOM)
            } catch (e: ThemeParseException) {
                themeMessage = "Theme not imported: ${e.message}"
            } catch (e: Exception) {
                themeMessage = "Theme not imported: ${e.message}"
            }
        }
    }
    val themeTemplateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            themeMessage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(templateThemeJson())
                    } ?: return@withContext "Could not open the destination"
                    "Template saved — edit it and import it back"
                } catch (e: Exception) {
                    "Could not save template: ${e.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenInsets()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (site == "kakuyomu") {
            Text(text = "Kakuyomu カクヨム", style = MaterialTheme.typography.titleMedium)
            if (kakuyomuStatus.isNotBlank()) {
                Text(kakuyomuStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (kakuyomuLoggedIn) {
                Button(
                    onClick = onKakuyomuSync,
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text(t("Sync bookmarks (follows)", "ブックマーク（フォロー）を同期")) }
                TextButton(onClick = onKakuyomuLogout) {
                    Text(t("Log out of Kakuyomu", "カクヨムからログアウト"))
                }
            } else {
                Button(onClick = onKakuyomuLogin) { Text(t("Log in on Kakuyomu", "カクヨムにログイン")) }
            }
            Text(
                t("Logged in, bookmarks follow the site: adding ⚑ follows the work on Kakuyomu, removing it unfollows, and syncing makes the site's list the master (in-app bookmarks made offline are pushed on sync).",
                    "ログインするとブックマークはサイトと連動します：⚑でフォロー、解除でフォロー解除、同期ではサイトのリストがマスターです（オフライン中に追加したブックマークは同期時にサイトへ反映）。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                t("Kakuyomu needs no account — search, rankings and reading work right away. Discovery goes through the site's own open GraphQL endpoint; episode text flows through the shared reader. Bookmarks (⚑ on a work) are stored on this device and carried in backups.",
                    "カクヨムはアカウント不要です — 検索・ランキング・閲覧がすぐ使えます。Discoverはカクヨム公式のGraphQLエンドポイント経由、本文は共有リーダーで表示します。ブックマーク（作品の⚑）は端末に保存され、バックアップに含まれます。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                t("Offline ePubs and bulk download work the same as the other sites (per-series, 100-episode part files).",
                    "オフラインePubと一括ダウンロードは他のサイトと同様（作品ごと、100話パート分割）です。"),
                style = MaterialTheme.typography.bodySmall
            )
            var showAgeGate by remember { mutableStateOf(false) }
            if (showAgeGate) {
                AlertDialog(
                    onDismissRequest = { showAgeGate = false },
                    title = { Text(t("Adult content (18+)", "成人コンテンツ（18禁）")) },
                    text = {
                        Text(t(
                            "Unlike Syosetu's sister site, Kakuyomu mixes R18 works into the same rankings and search. They are hidden here by default; enabling adds an R18 chip in Discover that switches to R18-only browsing, and R18 rows are marked. You confirm you are 18 or older.",
                            "カクヨムはSyosetuのシスターサイトと異なり、R18作品が同じランキング・検索に混在しています。アプリでは既定で非表示にします。有効にするとDiscoverにR18チップが表示され、R18作品のみの閲覧に切り替えられ、R18行には印が付きます。あなたは18歳以上であることを確認してください。"))
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onKakuyomuR18(true)
                            showAgeGate = false
                        }) { Text(t("I am 18 or older — enable", "18歳以上です — 有効にする")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAgeGate = false }) { Text(t("Cancel", "キャンセル")) }
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(t("Show R18 works (18+)", "R18作品を表示（18禁）"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        t("Hidden by default; the Discover R18 chip appears after enabling.", "既定では非表示。有効にするとDiscoverにR18チップが表示されます。"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = kakuyomuR18,
                    onCheckedChange = { checked ->
                        if (checked) showAgeGate = true else onKakuyomuR18(false)
                    }
                )
            }
        } else if (site == "syosetu") {
            Text(text = "Syosetu なろう", style = MaterialTheme.typography.titleMedium)
            if (syosetuStatus.isNotBlank()) {
                Text(syosetuStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            var showAgeGate by remember { mutableStateOf(false) }
            if (showAgeGate) {
                AlertDialog(
                    onDismissRequest = { showAgeGate = false },
                    title = { Text(t("Adult content (18+)", "成人コンテンツ（18禁）")) },
                    text = {
                        Text(t(
                            "novel18.syosetu.com hosts R18 works (Nocturne, Moonlight, Moonlight Ladies). Enabling shows an R18 chip in Discover and routes those novels to the sister site, which shows Syosetu's own age check on first open. You confirm you are 18 or older.",
                            "novel18.syosetu.com はR18作品（ノクターン・ムーンライト・ムーンライトレディース）を配信しています。有効にするとDiscoverにR18チップが表示され、該当作品はシスターサイト経由で開きます（初回はSyosetu独自の年齢確認が表示されます）。あなたは18歳以上であることを確認してください。"))
                    },
                    confirmButton = {
                        TextButton(onClick = { showAgeGate = false; onSyosetuR18(true) }) {
                            Text(t("I am 18 or older — enable", "18歳以上 — 有効にする"))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAgeGate = false }) { Text(t("Cancel", "キャンセル")) }
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!syosetuR18) showAgeGate = true else onSyosetuR18(false)
                    }
                    .padding(vertical = 4.dp)
            ) {
                Switch(
                    checked = syosetuR18,
                    onCheckedChange = { on -> if (on) showAgeGate = true else onSyosetuR18(false) }
                )
                Text(t("R18 titles (novel18)", "R18作品（novel18）"),
                    modifier = Modifier.padding(start = 8.dp))
            }
            if (syosetuR18) {
                Text(
                    t("Sister sites — disable any you don't read to cut the noise:",
                       "シスターサイト — 読まないサイトはオフにしてください："),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                listOf(
                    1 to t("Nocturne (ノクターン)", "ノクターンノベルズ"),
                    2 to t("Moonlight (ムーンライト)", "ムーンライトノベルズ"),
                    3 to t("Moonlight Ladies (レディース)", "ムーンライトレディース")
                ).forEach { (code, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (code in syosetuSubsites) syosetuSubsites - code else syosetuSubsites + code
                                onSyosetuSubsites(next)
                            }
                            .padding(start = 12.dp)
                    ) {
                        Switch(
                            checked = code in syosetuSubsites,
                            onCheckedChange = { on ->
                                val next = if (on) syosetuSubsites + code else syosetuSubsites - code
                                onSyosetuSubsites(next)
                            }
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (syosetuLoggedIn) {
                Button(
                    onClick = onSyosetuSync,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(t("Sync bookmark library", "ブックマークを同期")) }
                TextButton(onClick = onSyosetuLogout, modifier = Modifier.fillMaxWidth()) {
                    Text(t("Log out of Syosetu", "Syosetuからログアウト"))
                }
            } else {
                Text(
                    t("Sign in to pull your bookmark categories into the app library. Credentials stay in the app's own session — nothing goes to the external browser.", "ログインするとブックマークの分類をアプリのライブラリに取り込みます。認証情報はアプリ内セッションのみに保存され、外部ブラウザには送られません。"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(onClick = onSyosetuLogin, modifier = Modifier.fillMaxWidth()) {
                    Text(t("Log in on Syosetu", "Syosetuにログイン"))
                }
            }
        } else {
        Text(text = t("Account", "アカウント"), style = MaterialTheme.typography.titleMedium)
        Text(
            text = loginState,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = onStartLogin, modifier = Modifier.fillMaxWidth()) {
            // When a session exists the login screen auto-closes on sight of
            // the cookie — label it for what it does in that state.
            Text(if (loginState.startsWith("Logged in")) t("Switch / re-login on Novel Updates", "再ログイン / アカウント切替") else t("Log in on Novel Updates", "ノベルアップデーツにログイン"))
        }
        TextButton(onClick = onLoggedOut, modifier = Modifier.fillMaxWidth()) {
            Text(t("Log out", "ログアウト"))
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(t("RSS feed URL (optional)", "RSSフィードURL（任意）")) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
        )
        Text(
            text = t("Optional. With a feed URL chapter checks are one cheap request. Without one, the app checks your reading lists directly in the background — nothing to configure. Existing feed URLs travel with backups.", "任意です。フィードURLがあれば更新チェックは1リクエストで済みます。なければアプリがバックグラウンドでリストを直接確認します — 設定不要。フィードURLはバックアップに含まれます。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = t("Display", "表示"), style = MaterialTheme.typography.titleMedium)
        Text(
            text = t("Home layout. Grid columns follow screen width (3 on a phone, more on a tablet).", "ホームレイアウト。グリッドの列数は画面幅に応じます（スマホで3列、タブレットでは更多）。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                layoutMode = LAYOUT_LIST
                saveLayoutMode(prefs, LAYOUT_LIST)
            }
        ) {
            RadioButton(selected = layoutMode == LAYOUT_LIST, onClick = {
                layoutMode = LAYOUT_LIST
                saveLayoutMode(prefs, LAYOUT_LIST)
            })
            Text(t("Single column", "リスト表示"))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                layoutMode = LAYOUT_GRID
                saveLayoutMode(prefs, LAYOUT_GRID)
            }
        ) {
            RadioButton(selected = layoutMode == LAYOUT_GRID, onClick = {
                layoutMode = LAYOUT_GRID
                saveLayoutMode(prefs, LAYOUT_GRID)
            })
            Text(t("Dynamic grid", "グリッド表示"))
        }

        Text(
            text = t("Theme. Green is the Novel Updates house style; AMOLED black saves battery on OLED screens; wallpaper colors follow Material You.", "テーマ。グリーンはノベルアップデーツの house style、AMOLED black は有機ELでバッテリーを節約、wallpaper colors は Material You に従います。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )
        val themeOptions = buildList {
            add(THEME_SYSTEM to "Follow system (light / dark)")
            add(THEME_LIGHT to "Light")
            add(THEME_DARK to "Dark")
            add(THEME_BLACK to "AMOLED black")
            if (android.os.Build.VERSION.SDK_INT >= 31) add(THEME_DYNAMIC to "Wallpaper colors")
            if (customThemeName.isNotBlank()) add(THEME_CUSTOM to "Custom: $customThemeName")
        }
        themeOptions.forEach { (mode, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickTheme(mode) }
            ) {
                RadioButton(selected = themeMode == mode, onClick = { pickTheme(mode) })
                Text(label)
            }
        }
        TextButton(
            onClick = {
                themeImportLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Import theme file (JSON)…", "テーマファイルを読み込む (JSON)…")) }
        TextButton(
            onClick = { themeTemplateLauncher.launch("novelnotifier-theme.json") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Save a theme template to edit", "テーマテンプレートを保存して編集")) }
        if (themeMessage.isNotBlank()) {
            Text(
                text = themeMessage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = t("Library sync", "ライブラリ同期"), style = MaterialTheme.typography.titleMedium)
        Text(
            text = syncStatus,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = t("Downloads missing covers for every list, paced against the site. Progress shows in a notification.", "全リストの不足カバーをサイトに負担しないペースで取得します。進捗は通知に表示されます。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = onStartCoverSync, modifier = Modifier.fillMaxWidth()) {
            Text(t("Prefetch missing covers", "不足カバーを先行取得"))
        }
        TextButton(onClick = onStopCoverSync, modifier = Modifier.fillMaxWidth()) {
            Text(t("Stop sync", "同期を停止"))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = t("Backup & restore", "バックアップ"), style = MaterialTheme.typography.titleMedium)
        Text(
            text = t("One zip with your lists, chapter caches and cover images. Save it anywhere your file picker reaches — Google Drive, OneDrive, USB. Backups never contain your password, login, or feed links — a restored device asks for a fresh login and picks the rest up automatically.", "リスト・章キャッシュ・カバー画像を1つのZIPに。ファイルピッカーが届く場所ならどこでも保存できます — Google Drive、OneDrive、USB。バックアップにパスワード・ログイン・フィードリンクは決して含まれません — 復元端末は再ログインだけで残りを自動で取得します。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = { backupLauncher.launch("novelnotifier-library.zip") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Back up library", "ライブラリをバックアップ")) }
        TextButton(
            onClick = {
                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Restore from backup", "バックアップから復元")) }
        if (backupMessage.isNotBlank()) {
            Text(
                text = backupMessage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // UI language toggle — applies to app chrome immediately.
        Text(
            text = t("App language", "アプリの言語"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(UiLang.EN to "English", UiLang.JP to "日本語").forEach { (code, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            UiLang.current = code
                            prefs.edit { putString("ui_lang", code) }
                        }
                ) {
                    RadioButton(
                        selected = UiLang.current == code,
                        onClick = {
                            UiLang.current = code
                            prefs.edit { putString("ui_lang", code) }
                        }
                    )
                    Text(label)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = t("Notifications", "通知"), style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    stackAlerts = !stackAlerts
                    saveStackAlerts(prefs, stackAlerts)
                }
                .padding(vertical = 4.dp)
        ) {
            Switch(
                checked = stackAlerts,
                onCheckedChange = {
                    stackAlerts = it
                    saveStackAlerts(prefs, it)
                }
            )
            Text(
                t("Stack chapter alerts into one banner", "章通知を1つのバナーにまとめる"),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            text = t("Off posts one notification per series. Un-read alerts survive reboots either way.", "オフは各シリーズ個別に通知。未読通知はどちらでも再起動後も保持されます。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = t("Checks", "更新チェック"), style = MaterialTheme.typography.titleMedium)
        Text(
            text = t("Check for updates every", "更新チェックの間隔"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { hours = option }
            ) {
                RadioButton(selected = hours == option, onClick = { hours = option })
                Text(t("${option} hr", "${option}時間"))
            }
        }
        Button(
            onClick = onCheckNow,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) { Text(t("Check now", "今すぐチェック")) }
        Button(
            onClick = { onSave(url, hours) },
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) { Text(t("Save", "保存")) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(t("Cancel", "キャンセル")) }
    }
}
