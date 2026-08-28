package com.patchscratch.novelnotifier

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

/**
 * One series: INFO + RELEASES, CHANGE LIST, mark as read, ⋮ Refresh.
 *
 * How Compose state works here:
 *   `remember { mutableStateOf(...) }` keeps a value across redraws.
 *   `by` is a Kotlin *property delegate*. That is why we import getValue and
 *   setValue even though they never appear as function calls — do not delete them.
 *
 * Paint order:
 *   1. Load series_cache/<hash>.json if present so INFO/RELEASES appear immediately.
 *   2. Opening a series does NOT scrape. Cache is enough until the user asks.
 *   3. ⋮ Refresh / LOAD MORE / CHANGE LIST / MARK AS READ start a hidden WebView.
 *
 * Hidden WebView is 1dp off-screen unless Cloudflare shows "Just a moment".
 * Then it expands so the user can complete the check. There is no bypass.
 *
 * CHANGE LIST: JS finds select#taskOption and fires a jQuery change event
 * so the site actually saves the move (a plain selectedIndex change is not enough).
 *
 * MARK AS READ: JS hits /readinglist_update.php?rid=&sid=&checked=yes
 *   rid = checkbox id without the "chklist" prefix
 *   sid = #mypostid on the series page (cached on SeriesPage.sid)
 *
 * READ CHAPTER opens the chapter URL in the default browser (v1).
 */
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
    // page = INFO + RELEASES we currently show. Starts empty, then cache, then scrape.
    var page by remember { mutableStateOf(SeriesPage()) }
    var seriesSid by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) } // 0 = INFO, 1 = RELEASES
    var newestFirst by remember { mutableStateOf(true) }
    var hideRead by remember { mutableStateOf(false) }
    var descExpanded by remember { mutableStateOf(false) }
    var showOrder by remember { mutableStateOf(false) }
    var showChangeList by remember { mutableStateOf(false) }
    var pendingMoveId by remember { mutableStateOf<String?>(null) } // "listId:::listName" for CHANGE LIST JS
    var pendingReadRid by remember { mutableStateOf<String?>(null) } // checkbox id for mark-as-read JS
    var pendingReadChapter by remember { mutableStateOf<String?>(null) } // resolve rid after scrape if cache had none
    var listStatus by remember { mutableStateOf("") }
    var loadedPage by remember { mutableIntStateOf(0) }
    var wantPage by remember { mutableIntStateOf(1) }
    var loadKey by remember { mutableIntStateOf(0) } // bump to force the WebView to reload
    var maxPage by remember { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(true) }
    var pageLoadFailed by remember { mutableStateOf(false) }
    var allowNetwork by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }
    var showChallenge by remember { mutableStateOf(false) } // true = expand WebView so user can pass CF
    val listState = rememberLazyListState()
    val changeLists = availableLists.ifEmpty { page.lists }

    // Cache-first: never scrape just because the user opened the series.
    LaunchedEffect(novel.seriesUrl, novel.latestChapter) {
        val cached = loadSeriesCache(context, novel.seriesUrl)
        if (cached != null) {
            page = cached.first
            seriesSid = cached.first.sid.ifBlank { seriesSid }
            maxPage = cached.first.maxPage.coerceAtLeast(1)
            loadedPage = cached.second
            wantPage = cached.second.coerceAtLeast(1)
            loadingMore = false
            pageLoadFailed = false
            if (cached.first.coverUrl.startsWith("http")) onCoverFound(cached.first.coverUrl)
        }
        val stale = seriesNeedsRefresh(context, novel)
        allowNetwork = cached == null
        if (cached == null) {
            loadingMore = true
            wantPage = 1
            loadedPage = 0
        } else if (stale) {
            listStatus = "Newer chapter on the list — use ⋮ Refresh when you want it"
        }
    }

    val cover = page.coverUrl.ifBlank { novel.coverUrl }
    val lastReadNum = lastRead?.first?.let(::chapterNum) ?: -1
    val expectedNextNum = if (lastReadNum > 0) lastReadNum + 1 else -1
    // Next unread = last-read c-number + 1 if that row is loaded, else the lowest c we have.
    val nextUnread = if (expectedNextNum > 0) {
        page.releases.firstOrNull { chapterNum(it.chapter) == expectedNextNum }
    } else {
        page.releases.minByOrNull { num ->
            val n = chapterNum(num.chapter)
            if (n < 0) Int.MAX_VALUE else n
        }
    }
    val shownReleases = page.releases.filter { release ->
        !hideRead || lastRead == null || chapterNum(release.chapter) >= lastReadNum
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Turn on the hidden WebView and ask it for [pageNumber] of the RELEASES table. */
    fun requestPage(pageNumber: Int) {
        if (pageNumber < 1) return
        allowNetwork = true
        wantPage = pageNumber
        pageLoadFailed = false
        loadingMore = true
        loadKey += 1
    }

    fun requestNextPage() {
        if (loadingMore || pageLoadFailed || loadedPage == 0) return
        if (newestFirst && loadedPage < maxPage) requestPage(loadedPage + 1)
        else if (!newestFirst && loadedPage > 1) requestPage(loadedPage - 1)
    }

    fun retryPage() {
        requestPage(wantPage)
    }

    fun applyOrder(newest: Boolean) {
        newestFirst = newest
        page = page.copy(releases = emptyList())
        loadedPage = 0
        requestPage(if (newest) 1 else maxPage.coerceAtLeast(1))
        showOrder = false
    }

    LaunchedEffect(loadingMore, loadKey) {
        if (!loadingMore) return@LaunchedEffect
        delay(8.seconds)
        if (loadingMore) {
            pageLoadFailed = true
            loadingMore = false
            listStatus = "Refresh timed out — tap RETRY or ⋮ Refresh later"
        }
    }

    // Pages load only from LOAD MORE / RETRY / ⋮ Refresh. Auto-scroll used to skip pages.

    Box(modifier = Modifier.fillMaxSize()) {
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
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(64.dp)
                ) {
                    Text("←", fontSize = 36.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { showPageMenu = true },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Text("⋮", fontSize = 36.sp)
                    }
                    DropdownMenu(
                        expanded = showPageMenu,
                        onDismissRequest = { showPageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open on Novel Updates") },
                            onClick = {
                                showPageMenu = false
                                openUrl(novel.seriesUrl)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            onClick = {
                                showPageMenu = false
                                listStatus = "Refreshing..."
                                requestPage(1)
                            }
                        )
                    }
                }
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                CoverThumb(
                    url = cover,
                    modifier = Modifier
                        .width(84.dp)
                        .height(112.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = page.title.ifBlank { novel.title },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (page.author.isNotBlank()) {
                        Text(page.author, style = MaterialTheme.typography.bodySmall)
                    }
                    if (page.rating.isNotBlank()) {
                        Text(prettyRating(page.rating), style = MaterialTheme.typography.bodySmall)
                    }
                    if (isRealListName(page.currentList)) {
                        Text(
                            text = page.currentList,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Button(onClick = { showChangeList = true }) { Text("CHANGE LIST") }
                        TextButton(onClick = onToggleNotify) {
                            Text(if (notifyOn) "🔔" else "🔕", fontSize = 32.sp)
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
                                        allowNetwork = true
                                        loadKey += 1
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
                                text = when {
                                    nextUnread != null ->
                                        "${nextUnread.chapter}  by ${nextUnread.group}"
                                    expectedNextNum > 0 ->
                                        "c$expectedNextNum is not in the loaded pages yet. Use LOAD MORE."
                                    else -> "No chapter link found"
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                TextButton(
                                    onClick = { nextUnread?.url?.let(::openUrl) },
                                    enabled = nextUnread?.url?.isNotBlank() == true
                                ) { Text("READ CHAPTER") }
                                TextButton(
                                    onClick = {
                                        nextUnread?.let {
                                            onSetLastRead(it.chapter, it.url, true)
                                            if (it.rid.isNotBlank()) {
                                                pendingReadRid = it.rid
                                            } else {
                                                pendingReadChapter = it.chapter
                                            }
                                            allowNetwork = true
                                            loadKey += 1
                                        }
                                        onMarkRead()
                                    },
                                    enabled = nextUnread != null
                                ) { Text("MARK AS READ") }
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
                                            if (checked) {
                                                if (release.rid.isNotBlank()) {
                                                    pendingReadRid = release.rid
                                                } else {
                                                    pendingReadChapter = release.chapter
                                                }
                                                allowNetwork = true
                                                loadKey += 1
                                            }
                                        }
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        val moreLeft = if (newestFirst) loadedPage < maxPage else loadedPage > 1
                        item {
                            Column(modifier = Modifier.padding(16.dp)) {
                                when {
                                    pageLoadFailed -> {
                                        Text(
                                            text = "Could not load page $wantPage. The site may be rate-limiting.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(onClick = { retryPage() }) { Text("RETRY") }
                                    }
                                    loadingMore -> {
                                        Text(
                                            text = "Loading page $wantPage...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(onClick = { retryPage() }) { Text("RETRY") }
                                    }
                                    moreLeft -> {
                                        TextButton(onClick = { requestNextPage() }) { Text("LOAD MORE") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Hidden (or CF-visible) series WebView. allowNetwork is false until the user
    // taps Refresh / LOAD MORE / CHANGE LIST / MARK AS READ.
    if (allowNetwork && novel.seriesUrl.isNotBlank()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            if (showChallenge) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Complete the check if you see one",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { showChallenge = false }) { Text("Close") }
                }
            }
            AndroidView(
            modifier = if (showChallenge) {
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .padding(horizontal = 8.dp)
            } else {
                Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .offset(x = 4000.dp)
            },
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
                              var task = document.querySelector('select#taskOption, select[name="taskOption"], select.taskOption');
                              var currentList = '';
                              var lists = [];
                              if (task) {
                                for (var i = 0; i < task.options.length; i++) {
                                  var t = (task.options[i].textContent || '').replace(/\s+/g, ' ').trim();
                                  var v = (task.options[i].value || '').trim();
                                  if (!t || t === '---' || t === '--') continue;
                                  if (/^select/i.test(t) || /choose list|move to/i.test(t)) continue;
                                  lists.push({ id: v, name: t });
                                  if (task.options[i].selected) currentList = t;
                                }
                              }
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
                                currentList: currentList,
                                sid: (document.querySelector('#mypostid') || {}).value || '',
                                lists: lists,
                                releases: releases
                              });
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script) { result ->
                            val rawJson = unwrapJsResult(result)
                            if (rawJson == "CF") {
                                pageLoadFailed = true
                                loadingMore = false
                                showChallenge = true
                                listStatus = "Site check needed — complete it below"
                                return@evaluateJavascript
                            }
                            val parsed = parseSeriesPage(rawJson)
                            if (parsed.maxPage > maxPage) maxPage = parsed.maxPage
                            if (parsed.coverUrl.startsWith("http")) onCoverFound(parsed.coverUrl)
                            val incoming = if (newestFirst) parsed.releases else parsed.releases.asReversed()
                            val existingNums = page.releases.map { chapterNum(it.chapter) }.filter { it > 0 }
                            val incomingNums = incoming.map { chapterNum(it.chapter) }.filter { it > 0 }
                            val existingMin = existingNums.minOrNull()
                            val incomingMax = incomingNums.maxOrNull()
                            // If LOAD MORE jumped more than ~8 chapters, we skipped a table page.
                            val skippedPage = newestFirst &&
                                wantPage == loadedPage + 1 &&
                                existingMin != null &&
                                incomingMax != null &&
                                existingMin - incomingMax > 8
                            if (skippedPage) {
                                pageLoadFailed = true
                                loadingMore = false
                                listStatus = "Skipped a page — tap RETRY"
                                return@evaluateJavascript
                            }
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
                            val wantChapter = pendingReadChapter
                            if (pendingReadRid.isNullOrBlank() && !wantChapter.isNullOrBlank()) {
                                val match = mergedReleases.find { release ->
                                    chapterNum(release.chapter) == chapterNum(wantChapter) ||
                                        release.chapter.contains(wantChapter)
                                }
                                if (match != null && match.rid.isNotBlank()) pendingReadRid = match.rid
                                pendingReadChapter = null
                            }
                            if (incoming.isNotEmpty()) {
                                loadedPage = wantPage
                                pageLoadFailed = false
                                showChallenge = false
                                if (listStatus.startsWith("Refresh") || listStatus.startsWith("Site check")) {
                                    listStatus = "Updated from Novel Updates"
                                }
                                saveSeriesCache(context, novel.seriesUrl, page.copy(releases = mergedReleases, maxPage = maxOf(page.maxPage, parsed.maxPage), sid = parsed.sid.ifBlank { page.sid }), wantPage)
                            } else if (wantPage > 1 || page.releases.isNotEmpty()) {
                                pageLoadFailed = true
                            }
                            loadingMore = false
                        }
                    }
                }
                val base = novel.seriesUrl.trimEnd('/')
                val target = if (wantPage <= 1) "$base/" else "$base/?pg=$wantPage"
                val moveId = pendingMoveId
                // CHANGE LIST: pick the matching <select> option and fire jQuery('change').
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
                    val moveName = moveId.substringAfter(":::", "")
                    webView.evaluateJavascript(moveScript) { jsResult ->
                        val raw = unwrapJsResult(jsResult)
                        if (raw.startsWith("picked=")) {
                            if (moveName.isNotBlank()) {
                                page = page.copy(currentList = moveName)
                            }
                            listStatus = "Moved on Novel Updates"
                        } else {
                            listStatus = raw.ifBlank { "Could not change list on the site" }
                        }
                    }
                } else if (pendingReadRid != null && (webView.url ?: "").contains("/series/")) {
                    // MARK AS READ: tick chklist{rid} and GET readinglist_update.php.
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
                } else {
                    val loadedKey = webView.tag as? Int
                    if ((webView.url ?: "") != target || loadedKey != loadKey) {
                        webView.tag = loadKey
                        webView.loadUrl(target)
                    }
                }
            }
        )
        }
    }
    }
}
