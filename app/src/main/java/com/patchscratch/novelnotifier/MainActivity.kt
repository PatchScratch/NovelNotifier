package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import coil.compose.AsyncImage
import com.patchscratch.novelnotifier.ui.theme.NovelNotifierTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URL
import kotlin.time.Duration.Companion.seconds

data class Novel(
    val title: String,
    val latestChapter: String,
    val seriesUrl: String = "",
    val coverUrl: String = ""
)

data class ReadingList(
    val id: String,
    val name: String
)

fun loadCoverCache(prefs: android.content.SharedPreferences): Map<String, String> {
    val stored = prefs.getStringSet("cover_cache", emptySet()) ?: emptySet()
    return stored.mapNotNull { row ->
        val parts = row.split("|||", limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
}

fun saveCoverCache(prefs: android.content.SharedPreferences, cache: Map<String, String>) {
    prefs.edit {
        putStringSet("cover_cache", cache.map { "${it.key}|||${it.value}" }.toSet())
    }
}

fun loadIdSet(prefs: android.content.SharedPreferences, key: String): Set<String> {
    return prefs.getStringSet(key, emptySet()) ?: emptySet()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    var showEditLists by remember { mutableStateOf(false) }
    var readTitles by remember {
        mutableStateOf(prefs.getStringSet("read_titles", emptySet()) ?: emptySet())
    }
    var coverCache by remember { mutableStateOf(loadCoverCache(prefs)) }
    var notifyOff by remember { mutableStateOf(loadIdSet(prefs, "list_notify_off")) }
    var updateOff by remember { mutableStateOf(loadIdSet(prefs, "list_update_off")) }
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

    fun rememberCover(title: String, url: String) {
        if (url.isBlank()) return
        val updated = coverCache + (title to url)
        coverCache = updated
        saveCoverCache(prefs, updated)
        selectedNovel = selectedNovel?.let {
            if (it.title == title) it.copy(coverUrl = url) else it
        }
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
                    Novel(
                        title = title,
                        latestChapter = chapter,
                        coverUrl = coverCache[title].orEmpty()
                    )
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

    val selectedWithCover = selectedNovel?.let { novel ->
        val cached = coverCache[novel.title].orEmpty()
        if (cached.isNotBlank() && novel.coverUrl.isBlank()) novel.copy(coverUrl = cached) else novel
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
        showEditLists -> EditListsScreen(onBack = { showEditLists = false })
        selectedWithCover == null -> NovelListScreen(
            rssNovels = novels,
            rssStatus = statusMessage,
            checkHours = checkHours,
            loginState = loginState,
            readTitles = readTitles,
            coverCache = coverCache,
            notifyOff = notifyOff,
            updateOff = updateOff,
            isRefreshing = isRefreshing,
            onRefresh = { refreshKey += 1 },
            onNovelClick = { novel -> selectedNovel = novel },
            onCoverFound = { title, url -> rememberCover(title, url) },
            onOpenSettings = { showSettings = true },
            onEditLists = { showEditLists = true },
            onSaveListOptions = { newNotifyOff, newUpdateOff ->
                notifyOff = newNotifyOff
                updateOff = newUpdateOff
                prefs.edit {
                    putStringSet("list_notify_off", newNotifyOff)
                    putStringSet("list_update_off", newUpdateOff)
                }
            }
        )
        else -> NovelDetailScreen(
            novel = selectedWithCover,
            isRead = selectedWithCover.title in readTitles,
            onMarkRead = { markAsRead(selectedWithCover.title) },
            onCoverFound = { url -> rememberCover(selectedWithCover.title, url) },
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
    rssNovels: List<Novel>,
    rssStatus: String,
    checkHours: Int,
    loginState: String,
    readTitles: Set<String>,
    coverCache: Map<String, String>,
    notifyOff: Set<String>,
    updateOff: Set<String>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onNovelClick: (Novel) -> Unit,
    onCoverFound: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onEditLists: () -> Unit,
    onSaveListOptions: (Set<String>, Set<String>) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf(emptyList<ReadingList>()) }
    var selectedList by remember { mutableStateOf("My Reading List") }
    var selectedListId by remember { mutableStateOf("") }
    var listNovels by remember { mutableStateOf(emptyList<Novel>()) }
    var listStatus by remember { mutableStateOf("") }
    var listReloadKey by remember { mutableIntStateOf(0) }
    var coverQueue by remember { mutableStateOf(emptyList<Novel>()) }
    var showListOptions by remember { mutableStateOf(false) }

    if (showListOptions) {
        ListOptionsScreen(
            lists = lists,
            notifyOff = notifyOff,
            updateOff = updateOff,
            onSave = { n, u ->
                onSaveListOptions(n, u)
                showListOptions = false
            },
            onCancel = { showListOptions = false }
        )
        return
    }

    val shownNovels = (if (selectedListId.isBlank()) rssNovels else listNovels).map { novel ->
        val cached = coverCache[novel.title].orEmpty()
        if (cached.isNotBlank() && novel.coverUrl.isBlank()) novel.copy(coverUrl = cached) else novel
    }
    val shownStatus = if (selectedListId.isBlank()) {
        rssStatus
    } else {
        val missing = shownNovels.count { it.coverUrl.isBlank() && it.seriesUrl.isNotBlank() }
        val archived = selectedListId in updateOff
        val base = listStatus.ifBlank { "Loading $selectedList..." }
        when {
            archived -> "$base\nUpdates off for this list"
            missing > 0 -> "$base\nFetching covers ($missing left)"
            else -> base
        }
    }

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
                NavigationDrawerItem(
                    label = { Text("Edit Lists  ⚙") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onEditLists()
                    }
                )
                NavigationDrawerItem(
                    label = { Text("List options  ⚙") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showListOptions = true
                    }
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(lists.size) { index ->
                        val list = lists[index]
                        NavigationDrawerItem(
                            label = { Text(list.name) },
                            selected = selectedListId == list.id,
                            onClick = {
                                selectedList = list.name
                                selectedListId = list.id
                                listNovels = emptyList()
                                coverQueue = emptyList()
                                listStatus = "Loading ${list.name}..."
                                listReloadKey += 1
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
                    text = "$shownStatus\nCheck interval: every $checkHours hours\n$loginState",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        if (selectedListId.isBlank()) onRefresh()
                        else if (selectedListId !in updateOff) listReloadKey += 1
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(shownNovels.size) { index ->
                            val novel = shownNovels[index]
                            val isRead = novel.title in readTitles
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable { onNovelClick(novel) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CoverThumb(url = novel.coverUrl, modifier = Modifier.size(56.dp))
                                    Column(modifier = Modifier.padding(start = 12.dp)) {
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
                              var found = {};
                              var order = [];
                              function isJunk(name) {
                                name = (name || '').replace(/\s+/g, ' ').trim();
                                if (!name || name.length > 50) return true;
                                if (/^\d+$/.test(name)) return true;
                                if (/^(OR|AND|YES|NO|---|--|–|Default|Dark|Dark Crisp|Alphabetical|Unread|Latest|Reading Lists|Reading List|Edit Lists)$/i.test(name)) return true;
                                return false;
                              }
                              function addList(id, name) {
                                name = (name || '').replace(/\s+/g, ' ').trim();
                                id = (id || '').trim();
                                if (isJunk(name)) return;
                                if (found[name]) return;
                                found[name] = id || name;
                                order.push(name);
                              }
                              var moveSelect = null;
                              document.querySelectorAll('select').forEach(function(sel) {
                                var texts = [];
                                for (var i = 0; i < sel.options.length; i++) {
                                  texts.push((sel.options[i].textContent || '').trim());
                                }
                                if (texts.indexOf('---') !== -1 || texts.indexOf('--') !== -1) {
                                  moveSelect = sel;
                                }
                              });
                              if (moveSelect) {
                                for (var j = 0; j < moveSelect.options.length; j++) {
                                  addList(moveSelect.options[j].value, moveSelect.options[j].textContent);
                                }
                              }
                              document.querySelectorAll('a[href*="/reading-list"]').forEach(function(a) {
                                var href = a.href || '';
                                var m = href.match(/[?&]list=([^&]+)/);
                                addList(m ? decodeURIComponent(m[1]) : '', a.textContent);
                              });
                              var lists = [];
                              for (var n = 0; n < order.length; n++) {
                                lists.push(found[order[n]] + '===' + order[n]);
                              }
                              var novels = [];
                              document.querySelectorAll('a[href*="/series/"]').forEach(function(a) {
                                var title = (a.textContent || '').trim();
                                if (!title) return;
                                var href = a.href || '';
                                var img = '';
                                var chapter = '';
                                var row = a.parentElement;
                                for (var k = 0; k < 6 && row; k++) {
                                  var seriesLinks = row.querySelectorAll('a[href*="/series/"]');
                                  if (seriesLinks.length > 1) break;
                                  var text = row.textContent || '';
                                  var m = text.match(/\[\s*([^\]]+)\]/);
                                  if (!chapter && m && /c\d+/i.test(m[1])) chapter = m[1].trim();
                                  var image = row.querySelector('img');
                                  if (image && image.src && image.src.indexOf('data:') !== 0 && image.src.indexOf('icon') === -1) {
                                    img = image.src;
                                  }
                                  row = row.parentElement;
                                }
                                if (title && href) {
                                  novels.push(title + '===' + (chapter || 'No chapter info') + '===' + href + '===' + img);
                                }
                              });
                              return lists.join('|||') + ':::NOVELS:::' + novels.join('|||');
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { result ->
                            val raw = unwrapJsResult(result)
                            val parts = raw.split(":::NOVELS:::")
                            val listPart = parts.getOrNull(0).orEmpty()
                            val novelPart = parts.getOrNull(1).orEmpty()
                            if (listPart.isNotBlank()) {
                                val parsedLists = listPart.split("|||").mapNotNull { item ->
                                    val bits = item.split("===", limit = 2)
                                    if (bits.size == 2) ReadingList(bits[0], bits[1]) else null
                                }.distinctBy { it.name }
                                if (parsedLists.isNotEmpty()) lists = parsedLists
                            }
                            if (selectedListId.isNotBlank()) {
                                val parsedNovels = novelPart.split("|||").mapNotNull { item ->
                                    val bits = item.split("===", limit = 4)
                                    if (bits.size >= 2) {
                                        val title = bits[0]
                                        Novel(
                                            title = title,
                                            latestChapter = bits[1],
                                            seriesUrl = bits.getOrNull(2).orEmpty(),
                                            coverUrl = coverCache[title].orEmpty().ifBlank {
                                                bits.getOrNull(3).orEmpty()
                                            }
                                        )
                                    } else null
                                }.distinctBy { it.title }
                                listNovels = parsedNovels
                                coverQueue = if (selectedListId in updateOff) {
                                    emptyList()
                                } else {
                                    parsedNovels.filter {
                                        it.coverUrl.isBlank() &&
                                                it.seriesUrl.isNotBlank() &&
                                                coverCache[it.title].isNullOrBlank()
                                    }
                                }
                                listStatus = if (parsedNovels.isEmpty()) {
                                    "No series found in $selectedList"
                                } else {
                                    "Loaded ${parsedNovels.size} series from $selectedList"
                                }
                            }
                        }
                    }
                }
                loadUrl("https://www.novelupdates.com/reading-list/")
            }
        },
        update = { webView ->
            val target = if (selectedListId.isBlank()) {
                "https://www.novelupdates.com/reading-list/"
            } else {
                "https://www.novelupdates.com/reading-list/?list=$selectedListId"
            }
            val current = webView.url ?: ""
            if (listReloadKey > 0 || selectedListId.isNotBlank()) {
                if (!current.startsWith(target)) webView.loadUrl(target)
            }
        }
    )

    CoverPrefetcher(
        queue = coverQueue,
        onCoverFound = { title, url ->
            onCoverFound(title, url)
            coverQueue = coverQueue.filterNot { it.title == title }
            listNovels = listNovels.map {
                if (it.title == title) it.copy(coverUrl = url) else it
            }
        },
        onSkip = { title ->
            val skipped = coverQueue.find { it.title == title }
            coverQueue = coverQueue.filterNot { it.title == title } + listOfNotNull(skipped)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListOptionsScreen(
    lists: List<ReadingList>,
    notifyOff: Set<String>,
    updateOff: Set<String>,
    onSave: (Set<String>, Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    var draftNotifyOff by remember { mutableStateOf(notifyOff) }
    var draftUpdateOff by remember { mutableStateOf(updateOff) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                TextButton(
                    onClick = { onSave(draftNotifyOff, draftUpdateOff) },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "List options",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "Enable list notifications",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(lists.size) { index ->
                    val list = lists[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(list.name, modifier = Modifier.weight(1f))
                        Switch(
                            checked = list.id !in draftNotifyOff,
                            onCheckedChange = { enabled ->
                                draftNotifyOff = if (enabled) draftNotifyOff - list.id else draftNotifyOff + list.id
                            }
                        )
                    }
                }
                item {
                    Text(
                        text = "Check for updates",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Text(
                        text = "Turn this off for archived lists to reduce requests",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(lists.size) { index ->
                    val list = lists[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(list.name, modifier = Modifier.weight(1f))
                        Switch(
                            checked = list.id !in draftUpdateOff,
                            onCheckedChange = { enabled ->
                                draftUpdateOff = if (enabled) draftUpdateOff - list.id else draftUpdateOff + list.id
                            }
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CoverPrefetcher(
    queue: List<Novel>,
    onCoverFound: (String, String) -> Unit,
    onSkip: (String) -> Unit
) {
    val next = queue.firstOrNull() ?: return
    var canLoad by remember(next.title) { mutableStateOf(false) }
    var tries by remember(next.title) { mutableIntStateOf(0) }

    LaunchedEffect(next.title) {
        canLoad = false
        delay(4.seconds)
        canLoad = true
    }

    if (!canLoad) return

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
            }
        },
        update = { webView ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val script = """
                        (function() {
                          if (document.body && document.body.innerText.indexOf('Just a moment') !== -1) {
                            return 'CF';
                          }
                          var img = document.querySelector('.seriesimg img, .serieseditimg img');
                          return img ? (img.src || '') : '';
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script) { result ->
                        val cover = unwrapJsResult(result)
                        when {
                            cover.startsWith("http") -> onCoverFound(next.title, cover)
                            cover == "CF" || tries < 2 -> {
                                tries += 1
                                canLoad = false
                            }
                            else -> onSkip(next.title)
                        }
                    }
                }
            }
            val current = webView.url ?: ""
            if (current != next.seriesUrl) {
                webView.loadUrl(next.seriesUrl)
            }
        }
    )
}

@Composable
fun CoverThumb(url: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelDetailScreen(
    novel: Novel,
    isRead: Boolean,
    onMarkRead: () -> Unit,
    onCoverFound: (String) -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CoverThumb(
                url = novel.coverUrl,
                modifier = Modifier
                    .width(96.dp)
                    .height(128.dp)
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = novel.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Latest: ${novel.latestChapter}",
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = if (isRead) "Status: Read" else "Status: Unread",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        if (!isRead) {
            Button(onClick = onMarkRead, modifier = Modifier.padding(top = 24.dp)) {
                Text("Mark as read")
            }
        }
        Button(onClick = onBackClick, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
    }

    if (novel.seriesUrl.isNotBlank()) {
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
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val script = """
                                (function() {
                                  var img = document.querySelector('.seriesimg img, .serieseditimg img');
                                  return img ? (img.src || '') : '';
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(script) { result ->
                                val cover = unwrapJsResult(result)
                                if (cover.startsWith("http")) onCoverFound(cover)
                            }
                        }
                    }
                    loadUrl(novel.seriesUrl)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditListsScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit Lists",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl("https://www.novelupdates.com/reading-list/")
                }
            }
        )
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
