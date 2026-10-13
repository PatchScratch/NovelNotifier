package com.patchscratch.novelnotifier

import android.content.Context
import androidx.core.content.edit
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val coversExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch { backupMessage = BackupManager.exportCovers(context, uri) }
    }
    val coversImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch { backupMessage = BackupManager.importCovers(context, uri) }
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
        // --- Translation (shared: applies to the reader on any site) ---
        var xlateEndpoint by remember { mutableStateOf(prefs.getString("xlate_endpoint", "https://openrouter.ai/api/v1").orEmpty()) }
        var xlateModel by remember { mutableStateOf(prefs.getString("xlate_model", "openai/gpt-4.1-mini").orEmpty()) }
        var xlateKey by remember { mutableStateOf(prefs.getString("xlate_key", "").orEmpty()) }
        var xlatePrompt by remember {
            mutableStateOf(prefs.getString("xlate_prompt", "")?.ifBlank { null } ?: TranslationClient.defaultPrompt())
        }
        var xlateTemp by remember { mutableStateOf(prefs.getString("xlate_temp", "0.3").orEmpty()) }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(t("Translation", "翻訳"), style = MaterialTheme.typography.titleMedium)
        Text(
            t("Chapter translation through any OpenAI-compatible API. In the reader's ⋮ menu, Translate chapter sends the current chapter and shows the English under the original; the translation is saved into the series' offline book and travels with exports. Your key is kept on this device only — never in backups.",
                "OpenAI互換APIによる章翻訳。リーダーの⋮メニュー「この話を翻訳」で現在の話を送信し、原文の下に英語を表示します。翻訳はシリーズのオフラインブックに保存され、エクスポートにも含まれます。APIキーは端末内のみに保存 — バックアップには含まれません。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = xlateEndpoint,
            onValueChange = {
                xlateEndpoint = it
                prefs.edit { putString("xlate_endpoint", it.trim()) }
            },
            label = { Text(t("API endpoint (base URL)", "APIエンドポイント（ベースURL）")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = xlateModel,
            onValueChange = {
                xlateModel = it
                prefs.edit { putString("xlate_model", it.trim()) }
            },
            label = { Text(t("Model", "モデル")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = xlateKey,
            onValueChange = {
                xlateKey = it
                prefs.edit { putString("xlate_key", it.trim()) }
            },
            label = { Text(t("API key", "APIキー")) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = xlateTemp,
            onValueChange = { raw ->
                // numeric-only, clamped 0..2; saved as a string pref
                val cleaned = raw.filter { it.isDigit() || it == '.' }.take(4)
                val parsed = cleaned.toDoubleOrNull()
                if (parsed == null || parsed in 0.0..2.0) {
                    xlateTemp = cleaned
                    prefs.edit { putString("xlate_temp", cleaned.ifBlank { "0.3" }) }
                }
            },
            label = { Text(t("Temperature (0–2)", "温度（0–2）")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = xlatePrompt,
            onValueChange = {
                xlatePrompt = it
                prefs.edit { putString("xlate_prompt", it) }
            },
            label = { Text(t("System prompt (JP → EN)", "システムプロンプト（日→英）")) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            t("Sets the translator's persona and fidelity. Paragraph-alignment instructions are appended automatically so the English lands under each Japanese paragraph. The default follows the app language; Reset restores it.",
                "翻訳者の姿勢と忠実度を決めます。段落整列の指示は自動で追加され、英語が各段落の下に表示されます。既定値はアプリの言語に従います。リセットで既定に戻ります。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        TextButton(onClick = {
            xlatePrompt = TranslationClient.defaultPrompt()
            prefs.edit { putString("xlate_prompt", "") }   // blank = follow app language
        }) { Text(t("Reset to default", "既定に戻す")) }
        var coverModel by remember {
            mutableStateOf(
                prefs.getString("xlate_cover_model", "")?.ifBlank { null } ?: CoverGenClient.DEFAULT_MODEL
            )
        }
        var xlateLayout by remember { mutableStateOf(prefs.getString("xlate_layout", "both") ?: "both") }
        var autoRefine by remember { mutableStateOf(prefs.getBoolean("xlate_auto_refine", true)) }
        var jaDictOn by remember { mutableStateOf(prefs.getBoolean("ja_dict_on", false)) }
        var jaDictBusy by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = coverModel,
            onValueChange = {
                coverModel = it
                prefs.edit { putString("xlate_cover_model", it) }
            },
            label = { Text(t("Cover image model", "カバー画像モデル")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            t("Model used by “Generate cover (AI)” on a series page. Only appears for series without a site cover. Typical OpenRouter image models run about $0.01–0.02 per cover.",
                "シリーズ画面の「カバーを生成（AI）」で使うモデルです。サイトカバーのないシリーズにのみ表示されます。OpenRouterの画像モデルは通常1枚約$0.01–0.02です。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        var coverPrompt by remember {
            mutableStateOf(
                prefs.getString("xlate_cover_prompt", "")?.ifBlank { null } ?: CoverGenClient.defaultPrompt()
            )
        }
        OutlinedTextField(
            value = coverPrompt,
            onValueChange = {
                coverPrompt = it
                prefs.edit { putString("xlate_cover_prompt", it) }
            },
            label = { Text(t("Cover prompt", "カバー用プロンプト")) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            t("{title}, {author}, {genre} and {synopsis} are replaced with the series' data; a prompt without them gets the data appended anyway. By default the title and author are drawn into the art as cover typography. The default follows the app language; Reset restores it.",
                "{title}・{author}・{genre}・{synopsis} はシリーズのデータに置換されます。含まれないプロンプトにはデータが自動で追記されます。既定ではタイトルと作者名が表紙タイポグラフィとして画像内に描き込まれます。既定値はアプリの言語に従います。リセットで既定に戻ります。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        TextButton(onClick = {
            coverPrompt = CoverGenClient.defaultPrompt()
            prefs.edit { putString("xlate_cover_prompt", "") }   // blank = follow app language
        }) { Text(t("Reset to default", "既定に戻す")) }
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Text(
            t("Saved translation layout", "保存時の翻訳レイアウト"),
            style = MaterialTheme.typography.titleSmall
        )
        listOf(
            "both" to t("Japanese with English under each paragraph", "各段落の下に英語"),
            "jp" to t("Japanese only", "日本語のみ"),
            "en" to t("English only (once translated)", "英語のみ（翻訳後）")
        ).forEach { (mode, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { xlateLayout = mode; prefs.edit { putString("xlate_layout", mode) } }
            ) {
                RadioButton(selected = xlateLayout == mode, onClick = { xlateLayout = mode; prefs.edit { putString("xlate_layout", mode) } })
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            t("Applies to the reader now and to ePub exports; stored books keep both languages so you can switch anytime.",
                "リーダーとePub書き出しに適用されます。保存済みの本は両言語を保持するため、いつでも切り替えられます。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { autoRefine = !autoRefine; prefs.edit { putBoolean("xlate_auto_refine", autoRefine) } }
        ) {
            Checkbox(checked = autoRefine, onCheckedChange = { autoRefine = it; prefs.edit { putBoolean("xlate_auto_refine", it) } })
            Text(t("Auto-refine glossary & voices after each chapter", "各話翻訳後に用語集と声を自動精錬"), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            t("novel-mill lock files: prompt.txt / glossary.txt / voices.txt, editable per series from the reader's ⋮ menu. Auto-refine asks the model for new names and voice notes from each finished chapter and appends them to this series' files (a few hundred extra tokens).",
                "novel-millロックファイル：prompt.txt / glossary.txt / voices.txt。リーダーの⋮メニューからシリーズごとに編集できます。自動精錬は翻訳済みの各話から新しい固有名詞・声のメモを模型に尋ね、このシリーズのファイルへ追記します（数百トークンの追加）。"),
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
                if (jaDictOn) {
                    jaDictOn = false
                    prefs.edit { putBoolean("ja_dict_on", false) }
                } else if (!jaDictBusy) {
                    jaDictBusy = true
                    scope.launch {
                        val ok = JaDict.ensureUnpacked(context)
                        jaDictOn = ok
                        jaDictBusy = false
                        prefs.edit { putBoolean("ja_dict_on", ok) }
                    }
                }
            }
        ) {
            Switch(
                checked = jaDictOn,
                enabled = !jaDictBusy,
                onCheckedChange = null   // row handles it; keeps the switch non-cramped
            )
            Text(
                if (jaDictBusy) t("Preparing dictionary…", "辞書を準備中…")
                else t("Japanese dictionary — long-press a word in the reader", "日本語辞書 — リーダーで単語を長押し"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            t("Kuromoji + JMdict, fully offline. First enable unpacks an ~80MB dictionary (one time).",
                "Kuromoji + JMdict、完全オフライン。初回有効化時に約80MBの辞書を一度だけ展開します。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Text(text = t("Read aloud (TTS)", "読み上げ（TTS）"), style = MaterialTheme.typography.titleSmall)
        Text(
            t("Three engines: Android system TTS (instant, reads Japanese natively), Kokoro neural (offline, English, ~140MB), or your AI API (cloud, both languages, best quality).",
                "3エンジン：AndroidシステムTTS（即時・日本語対応）、Kokoroニューラル（オフライン・英語・約140MB）、AI API（クラウド・両言語・最高品質）。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Engine picker
        var ttsEngine by remember { mutableStateOf(prefs.getString("tts_engine", "ANDROID").orEmpty()) }
        listOf(
            "ANDROID" to (t("System TTS", "システムTTS") to t("instant, Japanese native", "即時・日本語ネイティブ")),
            "KOKORO" to (t("Kokoro neural", "Kokoroニューラル") to t("offline, English", "オフライン・英語")),
            "CLOUD" to (t("AI API (cloud)", "AI API（クラウド）") to t("best quality, ~$0.015/ch", "最高品質・約$0.015/話"))
        ).forEach { (value, labelPair) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    ttsEngine = value
                    prefs.edit { putString("tts_engine", value) }
                }
            ) {
                RadioButton(
                    selected = ttsEngine == value,
                    onClick = { ttsEngine = value; prefs.edit { putString("tts_engine", value) } }
                )
                Column {
                    Text(labelPair.first, style = MaterialTheme.typography.bodySmall)
                    Text(labelPair.second, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        // Speed (all engines)
        Text(t("Speed (0.5–3.0)", "速度（0.5–3.0）"), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp))
        var ttsSpeed by remember { mutableStateOf(prefs.getString("tts_speed", "1.0").orEmpty()) }
        OutlinedTextField(
            value = ttsSpeed,
            onValueChange = { raw ->
                val cleaned = raw.replace(Regex("[^0-9.]"), "").take(4)
                ttsSpeed = cleaned
                cleaned.toFloatOrNull()?.let { sp ->
                    if (sp in 0.5f..3.0f) prefs.edit { putString("tts_speed", cleaned) }
                }
            },
            label = { Text("Speed") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        // Kokoro: model download + voice picker
        if (ttsEngine == "KOKORO") {
            var ttsOn by remember { mutableStateOf(TtsEngine.isModelReady(context)) }
            var ttsBusy by remember { mutableStateOf(false) }
            var ttsProgress by remember { mutableStateOf(0) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    if (ttsOn) {
                        TtsEngine.deleteModel(context)
                        ttsOn = false
                    } else if (!ttsBusy) {
                        ttsBusy = true
                        scope.launch {
                            val ok = TtsEngine.ensureModel(context) { pct -> ttsProgress = pct }
                            ttsOn = ok
                            ttsBusy = false
                        }
                    }
                }
            ) {
                Switch(checked = ttsOn, enabled = !ttsBusy, onCheckedChange = null)
                Text(
                    if (ttsBusy) t("Downloading… ", "DL中… ") + ttsProgress + "%"
                    else if (ttsOn) "Model ready (" + TtsEngine.modelSizeMb(context) + "MB)"
                    else t("Download model (~140MB)", "モデルDL（約140MB）"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (ttsOn) {
                Text(t("Voice", "ボイス"), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp))
                val currentVoice = prefs.getInt("tts_voice", 0)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TtsEngine.VOICES.forEach { v ->
                        FilterChip(
                            selected = currentVoice == v.id,
                            onClick = { prefs.edit { putInt("tts_voice", v.id) } },
                            label = { Text(v.name, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
        }

        // Cloud: model + voice
        if (ttsEngine == "CLOUD") {
            var cloudModel by remember { mutableStateOf(prefs.getString("tts_cloud_model", "tts-1").orEmpty()) }
            OutlinedTextField(
                value = cloudModel,
                onValueChange = { cloudModel = it; prefs.edit { putString("tts_cloud_model", it) } },
                label = { Text(t("TTS model", "TTSモデル")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Text(
                t("Uses your existing API key. OpenAI: tts-1 (quality) or tts-1-mini (faster). ~$0.015 per chapter.",
                    "既存のAPIキーを使用。OpenAI: tts-1（品質）または tts-1-mini（高速）。約$0.015/話。"),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Site-info engine: synopses, catchphrases, tags on the Japanese
        // sites — ML Kit on-device is the FREE default; the AI key is an
        // optional quality upgrade at negligible per-synopsis cost.
        var metaEngine by remember {
            mutableStateOf(MetadataTranslator.effectiveEngine(prefs))
        }
        Text(
            t("Translate Japanese site info (synopses, tags)", "日本サイトの情報を翻訳（あらすじ・タグ）"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = metaEngine == MetadataTranslator.ENGINE_OFF,
                onClick = {
                    metaEngine = MetadataTranslator.ENGINE_OFF
                    prefs.edit { putString(MetadataTranslator.PREF_ENGINE, MetadataTranslator.ENGINE_OFF) }
                }
            )
            Text(t("Off", "オフ"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = metaEngine == MetadataTranslator.ENGINE_DEVICE,
                onClick = {
                    metaEngine = MetadataTranslator.ENGINE_DEVICE
                    prefs.edit { putString(MetadataTranslator.PREF_ENGINE, MetadataTranslator.ENGINE_DEVICE) }
                }
            )
            Text(t("On-device (ML Kit) — free, offline", "端末内（ML Kit）— 無料・オフライン"))
        }
        Text(
            t("Free on-device neural translation. A ~30 MB Japanese→English pack downloads on first use; after that it works offline. Gist-quality — fine for synopses and tags.",
                "端末内ニューラル翻訳（無料）。初回使用時に約30MBの日→英パックをダウンロード、以降はオフラインで動作します。あらすじ・タグには十分な品質です。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = metaEngine == MetadataTranslator.ENGINE_API,
                onClick = {
                    metaEngine = MetadataTranslator.ENGINE_API
                    prefs.edit { putString(MetadataTranslator.PREF_ENGINE, MetadataTranslator.ENGINE_API) }
                }
            )
            Text(t("Via my AI key — best quality", "AIキーで — 最高品質"))
        }
        Text(
            if (xlateKey.isBlank()) t(
                "Uses the endpoint, model, prompt and temperature above — set the API key first (falls back to on-device until then).",
                "上記のエンドポイント・モデル・プロンプト・温度を使用 — 先にAPIキーを設定してください（未設定の間は端末内にフォールバック）。"
            ) else t(
                "Uses the endpoint, model, prompt and temperature above. Cost is negligible — one synopsis is well under $0.001.",
                "上記のエンドポイント・モデル・プロンプト・温度を使用。コストはごく僅か — あらすじ1件あたり0.001ドル未満です。"
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 48.dp)
        )
        Text(
            t("Site-info translations are display-only — books, backups, and anything sent to a site stay in the site's native language.",
                "サイト情報の翻訳は表示のみ — ブック・バックアップ・サイトへ送る内容はサイトの言語のままです。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
        )
        Text(
            t("Cost guide is in the README (quality tier ≈ half a cent per chapter).", "コスト目安はREADME参照（品質重視層で1話約0.5セント）。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
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
        Text(
            t("Covers can be moved between your devices on their own: share a small covers-only zip here and import it on the other device (nothing is deleted; both sides merge). Site-fetched covers also re-download automatically via “Prefetch missing covers”.",
                "カバーだけを別端末へ移せます：ここでカバー専用の小さなZIPを書き出し、もう一方の端末で読み込んでください（削除は行わず、両側をマージします）。サイト由来のカバーは「不足カバーを先行取得」で自動再取得もできます。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Button(
            onClick = { coversExportLauncher.launch("novelnotifier-covers.zip") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Share covers (zip)", "カバーを書き出す（ZIP）")) }
        TextButton(
            onClick = {
                coversImportLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Import covers", "カバーを読み込む")) }
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
