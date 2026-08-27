package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import org.json.JSONObject
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

data class Release(
    val chapter: String,
    val group: String,
    val date: String,
    val url: String,
    val rid: String = ""
)

data class SeriesPage(
    val title: String = "",
    val author: String = "",
    val rating: String = "",
    val description: String = "",
    val genres: String = "",
    val tags: String = "",
    val type: String = "",
    val status: String = "",
    val coverUrl: String = "",
    val releases: List<Release> = emptyList(),
    val maxPage: Int = 1,
    val currentList: String = "",
    val lists: List<ReadingList> = emptyList(),
    val sid: String = ""
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

fun loadLastRead(prefs: android.content.SharedPreferences): Map<String, Pair<String, String>> {
    return loadIdSet(prefs, "last_read").mapNotNull { row ->
        val parts = row.split("|||", limit = 3)
        if (parts.size == 3) parts[0] to (parts[1] to parts[2]) else null
    }.toMap()
}

fun chapterNum(text: String): Int {
    return Regex("""c(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
}

fun unwrapJsResult(result: String?): String {
    if (result.isNullOrBlank() || result == "null") return ""
    return result
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}

fun parseSeriesPage(raw: String): SeriesPage {
    if (raw.isBlank()) return SeriesPage()
    return try {
        val json = JSONObject(raw)
        val releasesJson = json.optJSONArray("releases")
        val releases = mutableListOf<Release>()
        if (releasesJson != null) {
            for (i in 0 until releasesJson.length()) {
                val item = releasesJson.optJSONObject(i) ?: continue
                releases.add(
                    Release(
                        chapter = item.optString("chapter"),
                        group = item.optString("group"),
                        date = item.optString("date"),
                        url = item.optString("url"),
                        rid = item.optString("rid")
                    )
                )
            }
        }
        val listsJson = json.optJSONArray("lists")
        val lists = mutableListOf<ReadingList>()
        if (listsJson != null) {
            for (i in 0 until listsJson.length()) {
                val item = listsJson.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isNotBlank()) lists.add(ReadingList(item.optString("id"), name))
            }
        }
        SeriesPage(
            title = json.optString("title"),
            author = json.optString("author"),
            rating = json.optString("rating"),
            description = json.optString("description"),
            genres = json.optString("genres"),
            tags = json.optString("tags"),
            type = json.optString("type"),
            status = json.optString("status"),
            coverUrl = json.optString("cover"),
            releases = releases,
            maxPage = json.optInt("maxPage", 1).coerceAtLeast(1),
            currentList = json.optString("currentList"),
            lists = lists,
            sid = json.optString("sid")
        )
    } catch (_: Exception) {
        SeriesPage()
    }
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
    var readingLists by remember { mutableStateOf(emptyList<ReadingList>()) }
    var readTitles by remember {
        mutableStateOf(prefs.getStringSet("read_titles", emptySet()) ?: emptySet())
    }
    var lastRead by remember { mutableStateOf(loadLastRead(prefs)) }
    var coverCache by remember { mutableStateOf(loadCoverCache(prefs)) }
    var notifyOff by remember { mutableStateOf(loadIdSet(prefs, "list_notify_off")) }
    var updateOff by remember { mutableStateOf(loadIdSet(prefs, "list_update_off")) }
    var seriesNotifyOff by remember { mutableStateOf(loadIdSet(prefs, "series_notify_off")) }
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

    fun setLastRead(title: String, chapter: String, url: String, read: Boolean) {
        lastRead = if (read) lastRead + (title to (chapter to url)) else lastRead - title
        prefs.edit {
            putStringSet(
                "last_read",
                lastRead.map { "${it.key}|||${it.value.first}|||${it.value.second}" }.toSet()
            )
        }
    }

    fun toggleSeriesNotify(title: String) {
        seriesNotifyOff = if (title in seriesNotifyOff) seriesNotifyOff - title else seriesNotifyOff + title
        prefs.edit { putStringSet("series_notify_off", seriesNotifyOff) }
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
            onListsLoaded = { parsed -> if (parsed.isNotEmpty()) readingLists = parsed },
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
            lastRead = lastRead[selectedWithCover.title],
            notifyOn = selectedWithCover.title !in seriesNotifyOff,
            availableLists = readingLists,
            onMarkRead = { markAsRead(selectedWithCover.title) },
            onSetLastRead = { chapter, url, read ->
                setLastRead(selectedWithCover.title, chapter, url, read)
            },
            onToggleNotify = { toggleSeriesNotify(selectedWithCover.title) },
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
    onListsLoaded: (List<ReadingList>) -> Unit,
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
                                if (parsedLists.isNotEmpty()) {
                                    lists = parsedLists
                                    onListsLoaded(parsedLists)
                                }
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

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelDetailScreen(
    novel: Novel,
    lastRead: Pair<String, String>?,
    notifyOn: Boolean,
    availableLists: List<ReadingList>,
    onMarkRead: () -> Unit,
    onSetLastRead: (String, String, Boolean) -> Unit,
    onToggleNotify: () -> Unit,
    onCoverFound: (String) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(SeriesPage()) }
    var seriesSid by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var newestFirst by remember { mutableStateOf(true) }
    var hideRead by remember { mutableStateOf(false) }
    var descExpanded by remember { mutableStateOf(false) }
    var showOrder by remember { mutableStateOf(false) }
    var showChangeList by remember { mutableStateOf(false) }
    var pendingMoveId by remember { mutableStateOf<String?>(null) }
    var pendingReadRid by remember { mutableStateOf<String?>(null) }
    var listStatus by remember { mutableStateOf("") }
    var loadedPage by remember { mutableIntStateOf(1) }
    var maxPage by remember { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val changeLists = availableLists.ifEmpty { page.lists }

    val cover = page.coverUrl.ifBlank { novel.coverUrl }
    val lastReadNum = lastRead?.first?.let(::chapterNum) ?: -1
    val nextUnread = page.releases
        .filter { lastReadNum < 0 || chapterNum(it.chapter) > lastReadNum }
        .minByOrNull { chapterNum(it.chapter) }
        ?: page.releases.firstOrNull()
    val shownReleases = page.releases.filter { release ->
        !hideRead || lastRead == null || chapterNum(release.chapter) >= lastReadNum
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun applyOrder(newest: Boolean) {
        newestFirst = newest
        page = page.copy(releases = emptyList())
        loadedPage = if (newest) 1 else maxPage.coerceAtLeast(1)
        loadingMore = true
        showOrder = false
    }

    LaunchedEffect(listState, shownReleases.size, loadedPage, maxPage, loadingMore, tab, newestFirst) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (tab != 1 || last == null || loadingMore) return@collect
                if (last < shownReleases.size - 3) return@collect
                if (newestFirst && loadedPage < maxPage) {
                    loadingMore = true
                    loadedPage += 1
                } else if (!newestFirst && loadedPage > 1) {
                    loadingMore = true
                    loadedPage -= 1
                }
            }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBackClick) { Text("←") }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                CoverThumb(
                    url = cover,
                    modifier = Modifier
                        .width(96.dp)
                        .height(128.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = page.title.ifBlank { novel.title },
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (page.author.isNotBlank()) {
                        Text(page.author, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (page.rating.isNotBlank()) {
                        Text(page.rating, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (page.currentList.isNotBlank()) {
                        Text(
                            text = page.currentList,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Button(onClick = { showChangeList = true }) { Text("CHANGE LIST") }
                        TextButton(onClick = onToggleNotify) {
                            Text(
                                text = if (notifyOn) "🔔" else "🔕",
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    }
                    if (listStatus.isNotBlank()) {
                        Text(listStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (showChangeList) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Change list", style = MaterialTheme.typography.titleMedium)
                        if (changeLists.isEmpty()) {
                            Text(
                                "Open the hamburger menu once so lists can load, then try again.",
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            changeLists.forEach { list ->
                                TextButton(
                                    onClick = {
                                        pendingMoveId = list.id + ":::" + list.name
                                        listStatus = "Moving to ${list.name}..."
                                        showChangeList = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (list.name == page.currentList) "✓  ${list.name}" else list.name)
                                }
                            }
                        }
                        TextButton(
                            onClick = { showChangeList = false },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Cancel") }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("INFO") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("RELEASES") })
            }
            if (tab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Next Chapter", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (nextUnread == null) {
                                    "No chapter link found"
                                } else {
                                    "${nextUnread.chapter}  by ${nextUnread.group}"
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                TextButton(
                                    onClick = { nextUnread?.url?.let(::openUrl) },
                                    enabled = nextUnread?.url?.isNotBlank() == true
                                ) { Text("READ CHAPTER") }
                                TextButton(onClick = {
                                    nextUnread?.let {
                                        onSetLastRead(it.chapter, it.url, true)
                                        if (it.rid.isNotBlank()) pendingReadRid = it.rid
                                    }
                                    onMarkRead()
                                }) { Text("MARK AS READ") }
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .clickable { descExpanded = !descExpanded }
                        ) {
                            Text("Description", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = page.description.ifBlank { "No description yet." },
                                maxLines = if (descExpanded) Int.MAX_VALUE else 6,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(if (descExpanded) "▲" else "▼", modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (page.type.isNotBlank() || page.status.isNotBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (page.type.isNotBlank()) Text("Type: ${page.type}")
                                if (page.status.isNotBlank()) Text("Status: ${page.status}")
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Genres", style = MaterialTheme.typography.titleMedium)
                            Text(page.genres.ifBlank { "None listed" }, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Tags", style = MaterialTheme.typography.titleMedium)
                            Text(page.tags.ifBlank { "None listed" }, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    TextButton(
                        onClick = { showOrder = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(if (newestFirst) "Newest First ▼" else "Oldest First ▼")
                    }
                    if (showOrder) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Release Ordering", style = MaterialTheme.typography.titleLarge)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { newestFirst = true }
                                        .padding(top = 12.dp)
                                ) {
                                    RadioButton(selected = newestFirst, onClick = { newestFirst = true })
                                    Column {
                                        Text("Newest First")
                                        Text(
                                            "Show releases in a descending order",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { newestFirst = false }
                                        .padding(top = 8.dp)
                                ) {
                                    RadioButton(selected = !newestFirst, onClick = { newestFirst = false })
                                    Column {
                                        Text("Oldest First")
                                        Text(
                                            "Show releases in an ascending order",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text("Hide read releases", modifier = Modifier.weight(1f))
                                    Switch(checked = hideRead, onCheckedChange = { hideRead = it })
                                }
                                TextButton(
                                    onClick = { applyOrder(newestFirst) },
                                    modifier = Modifier.align(Alignment.End)
                                ) { Text("SAVE") }
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            count = shownReleases.size,
                            key = { index -> shownReleases[index].chapter + shownReleases[index].url + index }
                        ) { index ->
                            val release = shownReleases[index]
                            val thisNum = chapterNum(release.chapter)
                            val isLastRead = lastReadNum > 0 && thisNum == lastReadNum
                            key("${release.chapter}|${release.url}|$index") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = release.chapter,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier
                                            .width(88.dp)
                                            .clickable { openUrl(release.url) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(release.group)
                                        Text(release.date, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Checkbox(
                                        checked = isLastRead,
                                        onCheckedChange = { checked ->
                                            onSetLastRead(release.chapter, release.url, checked)
                                            if (checked && release.rid.isNotBlank()) {
                                                pendingReadRid = release.rid
                                            }
                                        }
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        val moreLeft = if (newestFirst) loadedPage < maxPage else loadedPage > 1
                        if (loadingMore || moreLeft) {
                            item {
                                Text(
                                    text = if (loadingMore) "Loading more releases..." else "Scroll for more",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
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
                }
            },
            update = { webView ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val script = """
                            (function() {
                              function text(sel) {
                                var el = document.querySelector(sel);
                                return el ? (el.innerText || el.textContent || '').trim() : '';
                              }
                              function texts(sel) {
                                var out = [];
                                document.querySelectorAll(sel).forEach(function(a) {
                                  var t = (a.textContent || '').trim();
                                  if (t) out.push(t);
                                });
                                return out.join(', ');
                              }
                              var img = document.querySelector('.seriesimg img, .serieseditimg img');
                              var releases = [];
                              document.querySelectorAll('#myTable tbody tr').forEach(function(tr) {
                                var tds = tr.querySelectorAll('td');
                                if (!tds.length) return;
                                var link = tr.querySelector('a.chp-release');
                                if (!link) {
                                  var links = Array.from(tr.querySelectorAll('a[href]'));
                                  link = links.find(function(a) {
                                    return /c\d+|v\d+/i.test((a.textContent || ''));
                                  }) || links[links.length - 1];
                                }
                                if (!link) return;
                                var chapter = (link.textContent || '').replace(/\s+/g, ' ').trim();
                                if (!chapter) return;
                                if (/^[\\-–—._\\s]+$/.test(chapter)) return;
                                var cb = tr.querySelector('input[type=checkbox]');
                                var rid = '';
                                if (cb && cb.id && cb.id.indexOf('chklist') === 0) {
                                  rid = cb.id.replace('chklist', '');
                                } else if (cb && cb.value && cb.value !== 'notset') {
                                  rid = cb.value;
                                }
                                releases.push({
                                  chapter: chapter,
                                  group: tds[1] ? tds[1].textContent.replace(/\s+/g, ' ').trim() : '',
                                  date: tds[0] ? tds[0].textContent.replace(/\s+/g, ' ').trim() : '',
                                  url: link.href || '',
                                  rid: rid
                                });
                              });
                              var max = 1;
                              document.querySelectorAll('.digg_pagination a').forEach(function(a) {
                                var m = ((a.getAttribute('href') || '') + (a.search || '')).match(/[?&]pg=(\d+)/);
                                if (m) {
                                  var n = parseInt(m[1], 10);
                                  if (n > max) max = n;
                                }
                              });
                              return JSON.stringify({
                                title: text('#editsn, .seriestitle, h4.seriestitle') || document.title.replace(' - Novel Updates', ''),
                                author: texts('#showauthors a, #editaid a'),
                                rating: text('.uvotes, #rateit, span.uvotes'),
                                description: text('#editdescription, #editdesc'),
                                genres: texts('#seriesgenre a, .seriesgenre a'),
                                tags: texts('#showtags a, #edittags a'),
                                type: text('#showtype, #edittype'),
                                status: text('#editstatus, #showstatus'),
                                cover: img ? (img.src || '') : '',
                                maxPage: max,
                                currentList: '',
                                sid: (document.querySelector('#mypostid') || {}).value || '',
                                lists: [],
                                releases: releases
                              });
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { result ->
                            val parsed = parseSeriesPage(unwrapJsResult(result))
                            if (parsed.maxPage > maxPage) maxPage = parsed.maxPage
                            if (parsed.coverUrl.startsWith("http")) onCoverFound(parsed.coverUrl)
                            val incoming = if (newestFirst) parsed.releases else parsed.releases.asReversed()
                            val mergedReleases = if (page.releases.isEmpty()) {
                                incoming
                            } else {
                                val existing = page.releases.map { it.chapter + "|" + it.url }.toSet()
                                page.releases + incoming.filter { it.chapter + "|" + it.url !in existing }
                            }
                            page = page.copy(
                                title = parsed.title.ifBlank { page.title },
                                author = parsed.author.ifBlank { page.author },
                                rating = parsed.rating.ifBlank { page.rating },
                                description = parsed.description.ifBlank { page.description },
                                genres = parsed.genres.ifBlank { page.genres },
                                tags = parsed.tags.ifBlank { page.tags },
                                type = parsed.type.ifBlank { page.type },
                                status = parsed.status.ifBlank { page.status },
                                coverUrl = parsed.coverUrl.ifBlank { page.coverUrl },
                                currentList = parsed.currentList.ifBlank { page.currentList },
                                lists = parsed.lists.ifEmpty { page.lists },
                                releases = mergedReleases,
                                maxPage = maxOf(page.maxPage, parsed.maxPage),
                                sid = parsed.sid.ifBlank { page.sid }
                            )
                            if (parsed.sid.isNotBlank()) seriesSid = parsed.sid
                            loadingMore = false
                        }
                    }
                }
                val base = novel.seriesUrl.trimEnd('/')
                val target = if (loadedPage <= 1) "$base/" else "$base/?pg=$loadedPage"
                val moveId = pendingMoveId
                if (moveId != null && (webView.url ?: "").contains("/series/")) {
                    pendingMoveId = null
                    val moveScript = """
                        (function() {
                          var raw = ${JSONObject.quote(moveId)};
                          var parts = raw.split(':::');
                          var wantId = parts[0] || '';
                          var wantName = (parts[1] || '').replace(/\s+/g, ' ').trim().toLowerCase();
                          var sel = document.querySelector('select#taskOption, select[name="taskOption"], select.taskOption');
                          if (!sel) {
                            document.querySelectorAll('select').forEach(function(s) {
                              var blob = '';
                              for (var i = 0; i < s.options.length; i++) blob += (s.options[i].textContent || '');
                              if (/ongoing|ecchi|completed/i.test(blob)) sel = s;
                            });
                          }
                          if (!sel) return 'no-taskOption';
                          var picked = '';
                          for (var i = 0; i < sel.options.length; i++) {
                            var t = (sel.options[i].textContent || '').replace(/\s+/g, ' ').trim();
                            var v = (sel.options[i].value || '').trim();
                            if (v === wantId || t.toLowerCase() === wantName) {
                              sel.selectedIndex = i;
                              sel.value = v;
                              picked = v + '/' + t;
                              break;
                            }
                          }
                          if (!picked) return 'no-match';
                          var jq = (typeof jQuery === 'function');
                          if (jq) jQuery(sel).val(sel.value).trigger('change').trigger('input');
                          if (sel.onchange) sel.onchange();
                          sel.dispatchEvent(new Event('change', { bubbles: true }));
                          sel.dispatchEvent(new Event('input', { bubbles: true }));
                          return 'picked=' + picked;
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(moveScript) { jsResult ->
                        val raw = unwrapJsResult(jsResult)
                        listStatus = if (raw.startsWith("picked=")) {
                            "Moved on Novel Updates"
                        } else {
                            raw.ifBlank { "Could not change list on the site" }
                        }
                    }
                } else if (pendingReadRid != null && (webView.url ?: "").contains("/series/")) {
                    val readRid = pendingReadRid
                    pendingReadRid = null
                    val readScript = """
                        (function() {
                          var rid = ${JSONObject.quote(readRid ?: "")};
                          var sid = (document.querySelector('#mypostid') || {}).value || ${JSONObject.quote(seriesSid)};
                          if (!rid || !sid) return 'missing rid=' + rid + ' sid=' + sid;
                          var box = document.getElementById('chklist' + rid);
                          if (box) { box.checked = true; box.click(); }
                          try {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', '/readinglist_update.php?rid=' + encodeURIComponent(rid) + '&sid=' + encodeURIComponent(sid) + '&checked=yes', false);
                            xhr.send(null);
                            return 'read ' + xhr.status + ' rid=' + rid;
                          } catch (e) {
                            return 'read-err';
                          }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(readScript) { jsResult ->
                        val raw = unwrapJsResult(jsResult)
                        listStatus = if (raw.startsWith("read 200")) {
                            "Marked as read on Novel Updates"
                        } else {
                            raw.ifBlank { "Could not mark as read on the site" }
                        }
                    }
                } else if ((webView.url ?: "") != target) {
                    webView.loadUrl(target)
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
