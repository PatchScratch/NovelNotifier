package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import com.patchscratch.novelnotifier.ui.theme.NovelNotifierTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URL

data class Novel(
    val title: String,
    val latestChapter: String
)

data class ReadingList(
    val name: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovelNotifierTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovelApp()
                }
            }
        }
    }
}

@Composable
fun NovelApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    var novels by remember { mutableStateOf(emptyList<Novel>()) }
    var statusMessage by remember { mutableStateOf("Set your RSS URL in Settings") }
    var selectedNovel by remember { mutableStateOf<Novel?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLoginWeb by remember { mutableStateOf(false) }
    var readTitles by remember {
        mutableStateOf(prefs.getStringSet("read_titles", emptySet()) ?: emptySet())
    }
    var rssUrl by remember { mutableStateOf(prefs.getString("rss_url", "") ?: "") }
    var checkHours by remember { mutableIntStateOf(prefs.getInt("check_hours", 12)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var loginState by remember {
        mutableStateOf(
            if (prefs.getString("nu_cookies", "").isNullOrBlank()) "Not logged in"
            else "Logged in to Novel Updates"
        )
    }

    LaunchedEffect(rssUrl, refreshKey) {
        if (rssUrl.isBlank()) {
            statusMessage = "Set your RSS URL in Settings"
            novels = emptyList()
            isRefreshing = false
            return@LaunchedEffect
        }
        isRefreshing = true
        statusMessage = "Loading Novel Updates feed..."
        try {
            val xml = withContext(Dispatchers.IO) {
                val connection = URL(rssUrl).openConnection()
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) NovelNotifier/1.0"
                )
                connection.getInputStream().bufferedReader().use { it.readText() }
            }
            val titles = parseRssTitles(xml)
            if (titles.isNotEmpty()) {
                novels = titles.take(20).map { rawTitle ->
                    val chapterMatch = Regex("""\s+c\d+\s*$""", RegexOption.IGNORE_CASE).find(rawTitle)
                    val chapter = chapterMatch?.value?.trim() ?: "New release"
                    val title = rawTitle.removeSuffix(chapterMatch?.value ?: "").trim()
                    Novel(title = title, latestChapter = chapter)
                }
                statusMessage = "Loaded Novel Updates feed (${titles.size} items)"
            } else {
                statusMessage = "Feed loaded, but no titles found"
            }
        } catch (e: Exception) {
            statusMessage = "Could not load feed: ${e.message}"
        }
        isRefreshing = false
    }

    fun markAsRead(title: String) {
        val updated = readTitles + title
        readTitles = updated
        prefs.edit { putStringSet("read_titles", updated) }
    }

    fun saveSettings(newUrl: String, newHours: Int) {
        rssUrl = newUrl.trim()
        checkHours = newHours
        prefs.edit {
            putString("rss_url", rssUrl)
            putInt("check_hours", checkHours)
        }
        showSettings = false
    }

    fun onLoggedIn(cookieHeader: String) {
        loginState = "Logged in to Novel Updates"
        prefs.edit { putString("nu_cookies", cookieHeader) }
        showLoginWeb = false
        showSettings = true
    }

    fun onLoggedOut() {
        loginState = "Not logged in"
        prefs.edit { remove("nu_cookies") }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    when {
        showLoginWeb -> LoginWebScreen(
            onLoggedIn = { cookies -> onLoggedIn(cookies) },
            onCancel = {
                showLoginWeb = false
                showSettings = true
            }
        )
        showSettings -> SettingsScreen(
            currentUrl = rssUrl,
            currentHours = checkHours,
            loginState = loginState,
            onSave = { url, hours -> saveSettings(url, hours) },
            onStartLogin = {
                showSettings = false
                showLoginWeb = true
            },
            onLoggedOut = { onLoggedOut() },
            onCancel = { showSettings = false }
        )
        selectedNovel == null -> NovelListScreen(
            statusMessage = statusMessage,
            checkHours = checkHours,
            loginState = loginState,
            novels = novels,
            readTitles = readTitles,
            isRefreshing = isRefreshing,
            onRefresh = { refreshKey += 1 },
            onNovelClick = { novel -> selectedNovel = novel },
            onOpenSettings = { showSettings = true }
        )
        else -> NovelDetailScreen(
            novel = selectedNovel!!,
            isRead = selectedNovel!!.title in readTitles,
            onMarkRead = { markAsRead(selectedNovel!!.title) },
            onBackClick = { selectedNovel = null }
        )
    }
}

fun parseRssTitles(xml: String): List<String> {
    val titles = mutableListOf<String>()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(xml.reader())
    var insideItem = false
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "item") insideItem = true
                if (insideItem && parser.name == "title") titles.add(parser.nextText())
            }
            XmlPullParser.END_TAG -> {
                if (parser.name == "item") insideItem = false
            }
        }
        event = parser.next()
    }
    return titles
}

fun unwrapJsResult(result: String?): String {
    if (result.isNullOrBlank() || result == "null") return ""
    return result
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelListScreen(
    statusMessage: String,
    checkHours: Int,
    loginState: String,
    novels: List<Novel>,
    readTitles: Set<String>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onNovelClick: (Novel) -> Unit,
    onOpenSettings: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf(emptyList<ReadingList>()) }
    var selectedList by remember { mutableStateOf("My Reading List") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Reading List",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(lists.size) { index ->
                        val list = lists[index]
                        NavigationDrawerItem(
                            label = { Text(list.name) },
                            selected = selectedList == list.name,
                            onClick = {
                                selectedList = list.name
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selectedList) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "$statusMessage\nCheck interval: every $checkHours hours\n$loginState",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(novels.size) { index ->
                            val novel = novels[index]
                            val isRead = novel.title in readTitles
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable { onNovelClick(novel) }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = novel.title, style = MaterialTheme.typography.titleMedium)
                                    Text(text = novel.latestChapter, style = MaterialTheme.typography.bodyMedium)
                                    if (isRead) {
                                        Text(
                                            text = "Read",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .offset(x = 4000.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val script = """
                            (function() {
                              var names = [];
                              var moveSelect = null;
                              document.querySelectorAll('select').forEach(function(sel) {
                                var texts = [];
                                for (var i = 0; i < sel.options.length; i++) {
                                  texts.push((sel.options[i].textContent || '').trim());
                                }
                                if (texts.indexOf('---') !== -1) {
                                  moveSelect = sel;
                                }
                              });
                              if (moveSelect) {
                                for (var j = 0; j < moveSelect.options.length; j++) {
                                  var t = (moveSelect.options[j].textContent || '').trim();
                                  if (t && t !== '---') names.push(t);
                                }
                              }
                              return names.join('|||');
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { result ->
                            val raw = unwrapJsResult(result)
                            val parsed = raw.split("|||")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                            if (parsed.isNotEmpty()) {
                                lists = parsed.map { ReadingList(it) }
                            }
                        }
                    }
                }
                loadUrl("https://www.novelupdates.com/reading-list/")
            }
        }
    )
}

@Composable
fun NovelDetailScreen(
    novel: Novel,
    isRead: Boolean,
    onMarkRead: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = novel.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(text = "Latest: ${novel.latestChapter}", modifier = Modifier.padding(bottom = 16.dp))
        Text(
            text = if (isRead) "Status: Read" else "Status: Unread",
            modifier = Modifier.padding(bottom = 24.dp)
        )
        if (!isRead) {
            Button(onClick = onMarkRead, modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Mark as read")
            }
        }
        Button(onClick = onBackClick) { Text("Back") }
    }
}

@Composable
fun SettingsScreen(
    currentUrl: String,
    currentHours: Int,
    loginState: String,
    onSave: (String, Int) -> Unit,
    onStartLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    onCancel: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var hours by remember { mutableIntStateOf(currentHours) }
    val options = listOf(1, 6, 12, 24)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(text = "Novel Updates account", style = MaterialTheme.typography.titleMedium)
        Text(
            text = loginState,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = onStartLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Log in on Novel Updates")
        }
        TextButton(onClick = onLoggedOut, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Novel Updates RSS URL") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp)
        )
        Text(text = "Check for updates every", style = MaterialTheme.typography.titleMedium)
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { hours = option }
            ) {
                RadioButton(selected = hours == option, onClick = { hours = option })
                Text("$option hour" + if (option == 1) "" else "s")
            }
        }
        Button(
            onClick = { onSave(url, hours) },
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) { Text("Save") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebScreen(
    onLoggedIn: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
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

@Preview(showBackground = true)
@Composable
fun NovelAppPreview() {
    NovelNotifierTheme {
        NovelApp()
    }
}