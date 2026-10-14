package com.patchscratch.novelnotifier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Site search UI (Phase 4b): all scraping lives in [SearchViewModel] on a
 * SiteClient Session — this screen is pure UI. Suggestions fire as you type
 * (debounced); "Search all" pages through /series-finder, human-paced by
 * explicit load-more and a 1.5 s scroll gap.
 */
@Composable
fun SiteSearchScreen(
    onOpenSeries: (Novel) -> Unit,
    onBack: () -> Unit
) {
    val vm = viewModel<SearchViewModel>()
    val query = vm.query
    val status = vm.status
    val ready = vm.ready
    val fullMode = vm.fullMode
    val finderLoading = vm.finderLoading
    val finderPage = vm.finderPage
    val finderMax = vm.finderMax
    val suggestions = vm.suggestions
    val fullResults = vm.fullResults

    // The Session holds the shared NU WebView exclusively while we're
    // composed; release the instant we leave.
    DisposableEffect(Unit) {
        vm.enter()
        onDispose { vm.leave() }
    }

    val listState = rememberLazyListState()
    var lastAutoLoadAt by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenInsets()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("←") }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.onQueryChange(it) },
                label = { Text("Search Novel Updates") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.startFullSearch() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
            )
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val shown = if (fullMode) fullResults else suggestions
        if (!fullMode && ready && query.trim().length >= 2) {
            TextButton(
                onClick = { vm.startFullSearch() },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Search all results →") }
        }
        val nearEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                info.totalItemsCount > 4 && last >= info.totalItemsCount - 4
            }
        }
        LaunchedEffect(nearEnd, fullMode, finderLoading, finderPage, finderMax) {
            if (!fullMode || !nearEnd || finderLoading || finderPage >= finderMax) return@LaunchedEffect
            if (System.currentTimeMillis() - lastAutoLoadAt < 1_500L) return@LaunchedEffect
            lastAutoLoadAt = System.currentTimeMillis()
            vm.loadFinderPage(finderPage + 1)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(shown) { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSeries(result.toSearchNovel()) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverThumb(
                        url = result.coverUrl,
                        seriesUrl = result.seriesUrl,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        result.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            item {
                Text(
                    when {
                        !fullMode -> " "
                        finderLoading -> "Loading more…"
                        fullResults.isNotEmpty() && finderPage >= finderMax -> "End of results (page $finderPage of $finderMax)"
                        else -> "Scroll for more…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

private fun SearchResult.toSearchNovel(): Novel = Novel(
    title = title,
    latestChapter = "",
    seriesUrl = seriesUrl,
    coverUrl = coverUrl
)
