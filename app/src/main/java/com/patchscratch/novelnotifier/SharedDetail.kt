package com.patchscratch.novelnotifier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets

/**
 * The NU-model series detail, shared by every site (framework B1,
 * Wayne's 2026-10-17 spec): one template so NU / Syosetu / Kakuyomu /
 * AlphaPolis / future sites cannot drift apart. Layout is IDENTICAL by
 * construction — sites differ only in the data and the capability slots:
 *
 * - Header: cover 84×112 · title (+ translated line) · author (+ translated)
 *   · rating row when the site has one · group badge · action row
 *   (change-group button + per-series notification toggle when capable).
 * - INFO tab, NU's exact stack: Next Chapter card (READ CHAPTER /
 *   MARK AS READ) → Description (synopsis + translated line beneath,
 *   expandable) → Type/Status → Genres → Tags → site extras
 *   (translation coverage, AI cover, offline/download rows).
 * - RELEASES tab: order card (Newest/Oldest + hide-read), rows
 *   (ordinal · title · date · read checkbox with site-provided
 *   semantics), optional group-filter and footer slots (NU pagination).
 *
 * Missing data omits rows (rating, type, tags) — never rearranges them.
 */

/** One RELEASES row. [onCheck] null hides the checkbox. */
data class SeriesEpisodeRow(
    val ordinal: Int,            // 1-based episode number (sort + read filter)
    val label: String,           // left column: "c116" / "第116話"
    val title: String,           // second column head (NU: group; JP: episode title)
    val date: String,
    val isRead: Boolean,
    val onOpen: () -> Unit,
    val onCheck: ((checked: Boolean) -> Unit)? = null
)

/** Everything the template renders. Defaults omit optional rows. */
data class SeriesDetailState(
    val seriesUrl: String,
    val onBack: () -> Unit,
    // header
    val coverUrl: String,
    val coverSeriesUrl: String = seriesUrl,
    val title: String,
    val author: String = "",
    val rating: String = "",
    val groupBadge: String = "",
    // action row
    val changeGroupLabel: String = t("CHANGE LIST", "リスト変更"),
    val onChangeGroup: () -> Unit,
    val notifyOn: Boolean? = null,          // null = site has no per-series toggle
    val onToggleNotify: (() -> Unit)? = null,
    val statusLine: String = "",
    // Inline card under the header when the change-group picker is open
    // (NU renders its Change-list card exactly there).
    val belowHeader: @Composable () -> Unit = {},
    // INFO tab
    val nextChapterLabel: String = "",
    val nextChapterEnabled: Boolean = false,
    val onReadNext: () -> Unit = {},
    val onMarkRead: (() -> Unit)? = null,   // null hides MARK AS READ
    val synopsis: String = "",
    val typeLine: String = "",              // "Type: …" / "Status: …" prebuilt
    val genres: String = "",
    val tags: String = "",
    // RELEASES tab
    val episodes: List<SeriesEpisodeRow> = emptyList(),
    val readOrdinal: Int = 0,               // drives hide-read + next-chapter fallback
    // top-right ⋮ menu
    val openOnSiteLabel: String = "",
    val onRefresh: (() -> Unit)? = null,
    // capability slots (never layout changes — additive rows)
    val loading: Boolean = false,
    val headerExtra: @Composable () -> Unit = {},
    val infoExtra: @Composable () -> Unit = {},
    val releasesControls: @Composable () -> Unit = {},
    val releasesFooter: @Composable () -> Unit = {}
)

@Composable
fun SeriesDetailScreen(state: SeriesDetailState) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) } // 0 = INFO, 1 = RELEASES
    var newestFirst by remember { mutableStateOf(true) }
    var hideRead by remember { mutableStateOf(false) }
    var descExpanded by remember { mutableStateOf(false) }
    var showOrder by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }

    val shown = remember(state.episodes, newestFirst, hideRead, state.readOrdinal) {
        val sorted = if (newestFirst) state.episodes.sortedByDescending { it.ordinal }
        else state.episodes.sortedBy { it.ordinal }
        if (hideRead && state.readOrdinal > 0) sorted.filter { it.ordinal > state.readOrdinal } else sorted
    }

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
                    IconButton(onClick = state.onBack, modifier = Modifier.size(64.dp)) {
                        Text("←", fontSize = 36.sp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box {
                        IconButton(
                            onClick = { showPageMenu = true },
                            modifier = Modifier.size(64.dp)
                        ) { Text("⋮", fontSize = 36.sp) }
                        DropdownMenu(
                            expanded = showPageMenu,
                            onDismissRequest = { showPageMenu = false }
                        ) {
                            if (state.openOnSiteLabel.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text(state.openOnSiteLabel) },
                                    onClick = {
                                        showPageMenu = false
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(state.seriesUrl)
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                            state.onRefresh?.let {
                                DropdownMenuItem(
                                    text = { Text(t("Refresh", "更新")) },
                                    onClick = {
                                        showPageMenu = false
                                        it()
                                    }
                                )
                            }
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
                // ---- header: cover · title(+EN) · author(+EN) · rating · badge ----
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    CoverThumb(
                        url = state.coverUrl,
                        seriesUrl = state.coverSeriesUrl,
                        fullscreen = true,
                        modifier = Modifier.width(84.dp).height(112.dp)
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium)
                        // JP titles/synopses get the dimmed machine line for
                        // free; English-native content renders nothing extra.
                        TranslatedHint(text = state.title)
                        if (state.author.isNotBlank()) {
                            Text(state.author, style = MaterialTheme.typography.bodySmall)
                            TranslatedHint(text = state.author)
                        }
                        if (state.rating.isNotBlank()) {
                            Text(prettyRating(state.rating), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.groupBadge.isNotBlank()) {
                            Text(
                                state.groupBadge,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Button(onClick = state.onChangeGroup) { Text(state.changeGroupLabel) }
                            if (state.notifyOn != null && state.onToggleNotify != null) {
                                TextButton(onClick = state.onToggleNotify) {
                                    if (state.notifyOn == true) {
                                        Icon(
                                            Icons.Rounded.Notifications, contentDescription = "Notifications on",
                                            modifier = Modifier.size(32.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.NotificationsOff, contentDescription = "Notifications off",
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (state.statusLine.isNotBlank()) {
                            Text(state.statusLine, style = MaterialTheme.typography.bodySmall)
                        }
                        state.headerExtra()
                    }
                }
                state.belowHeader()
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(t("INFO", "情報")) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(t("RELEASES", "各話")) })
                }
                if (tab == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Next Chapter card — the continue affordance, NU placement
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(t("Next Chapter", "次の各話"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = state.nextChapterLabel.ifBlank { "No chapter link found" },
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Row(modifier = Modifier.padding(top = 8.dp)) {
                                    TextButton(
                                        onClick = state.onReadNext,
                                        enabled = state.nextChapterEnabled
                                    ) { Text(t("READ CHAPTER", "読む")) }
                                    if (state.onMarkRead != null) {
                                        TextButton(
                                            onClick = state.onMarkRead,
                                            enabled = state.nextChapterEnabled
                                        ) { Text(t("MARK AS READ", "既読にする")) }
                                    }
                                }
                            }
                        }
                        // Description: synopsis with the translated line beneath
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .clickable { descExpanded = !descExpanded }
                            ) {
                                Text(t("Description", "あらすじ"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = state.synopsis.ifBlank { "No description yet." },
                                    maxLines = if (descExpanded) Int.MAX_VALUE else 6,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                TranslatedHint(text = state.synopsis, modifier = Modifier.padding(top = 2.dp))
                                Text(if (descExpanded) "▲" else "▼", modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        // Type/Status (omitted when the site lacks the data)
                        if (state.typeLine.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(state.typeLine)
                                }
                            }
                        }
                        // Genres — below the synopsis, NU position; JP
                        // values carry the dimmed translation beneath.
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(t("Genres", "ジャンル"), style = MaterialTheme.typography.titleMedium)
                                if (state.genres.isBlank()) {
                                    Text("None listed", modifier = Modifier.padding(top = 8.dp))
                                } else {
                                    TranslatedInfoText(
                                        text = state.genres,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                        // Tags — below genres, NU position; translated too.
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(t("Tags", "タグ"), style = MaterialTheme.typography.titleMedium)
                                if (state.tags.isBlank()) {
                                    Text("None listed", modifier = Modifier.padding(top = 8.dp))
                                } else {
                                    TranslatedInfoText(
                                        text = state.tags,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                        // site extras (translation coverage, AI cover, offline rows…)
                        state.infoExtra()
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                        }
                    }
                } else {
                    // ---- RELEASES tab ----
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.align(Alignment.End)) {
                            state.releasesControls()
                            TextButton(onClick = { showOrder = true }) {
                                Text(if (newestFirst) "Newest First ▼" else "Oldest First ▼")
                            }
                        }
                        if (showOrder) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(t("Release Ordering", "各話の並び順"), style = MaterialTheme.typography.titleLarge)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { newestFirst = true }
                                            .padding(top = 12.dp)
                                    ) {
                                        RadioButton(selected = newestFirst, onClick = { newestFirst = true })
                                        Column {
                                            Text(t("Newest First", "新しい順"))
                                            Text(
                                                "Show releases in a descending order",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { newestFirst = false }
                                            .padding(top = 8.dp)
                                    ) {
                                        RadioButton(selected = !newestFirst, onClick = { newestFirst = false })
                                        Column {
                                            Text(t("Oldest First", "古い順"))
                                            Text(
                                                t("Show releases in an ascending order", "各話を昇順で表示"),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        Text(t("Hide read releases", "既読の各話を隠す"), modifier = Modifier.weight(1f))
                                        Switch(checked = hideRead, onCheckedChange = { hideRead = it })
                                    }
                                    TextButton(
                                        onClick = { showOrder = false },
                                        modifier = Modifier.align(Alignment.End)
                                    ) { Text(t("SAVE", "保存")) }
                                }
                            }
                        }
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                count = shown.size,
                                key = { i -> shown[i].ordinal.toString() + "|" + shown[i].label + "|" + i }
                            ) { i ->
                                val ep = shown[i]
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ep.label,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.width(88.dp).clickable { ep.onOpen() }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ep.title, maxLines = 2)
                                        Text(ep.date, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (ep.onCheck != null) {
                                        Checkbox(checked = ep.isRead, onCheckedChange = ep.onCheck)
                                    } else if (ep.isRead) {
                                        Checkbox(checked = true, onCheckedChange = null)
                                    }
                                }
                                androidx.compose.material3.HorizontalDivider()
                            }
                            item { state.releasesFooter() }
                        }
                    }
                }
            }
        }
    }
}
