package com.patchscratch.novelnotifier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local series page (v3.0 B3): the multi-volume grouping behind a home-grid
 * series row. Volumes in volume-number order — cover thumb, "Vol N" label,
 * chapter count and the saved read position per volume. Tapping a volume
 * opens its book detail; reading itself is untouched (each volume keeps
 * its own position and chapters).
 */
@Composable
fun LocalSeriesScreen(
    libraryId: String,
    seriesKey: String,
    onBack: () -> Unit,
    onOpenBook: (LocalLibraryStore.LocalBook) -> Unit
) {
    val context = LocalContext.current
    var volumes by remember(seriesKey) { mutableStateOf<List<LocalLibraryStore.LocalBook>>(emptyList()) }
    var coverPaths by remember(seriesKey) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var readPos by remember(seriesKey) { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(libraryId, seriesKey) {
        withContext(Dispatchers.IO) {
            val vols = LocalLibraryStore.seriesVolumes(context, libraryId, seriesKey)
            volumes = vols
            // Lazy cover extraction rides this load (legacy imports).
            coverPaths = vols.associate {
                it.id to (LocalLibraryStore.coverFile(context, it.id)?.let { f -> "file://${f.absolutePath}" } ?: "")
            }
            val prefs = context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE)
            readPos = vols.associate { it.id to prefs.getInt("local_pos_${it.id}", 0) }
        }
    }

    val name = volumes.firstOrNull()
        ?.let { LocalLibraryStore.strippedTitle(it.title).ifBlank { it.title } } ?: ""

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onBack) { Text("←", fontSize = 36.sp) }
            Text(
                name.ifBlank { t("Series", "シリーズ") },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        Text(
            "${volumes.size} ${t("volumes", "巻")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(volumes, key = { it.id }) { v ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBook(v) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val vol = LocalLibraryStore.volumeNumber(v.title)
                        CoverThumb(
                            url = coverPaths[v.id].orEmpty(),
                            seriesUrl = "locallib://${v.id}",
                            modifier = Modifier.size(width = 54.dp, height = 74.dp)
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            if (vol != Int.MAX_VALUE) {
                                Text(
                                    "Vol $vol",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                                )
                            }
                            Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            val pos = readPos[v.id] ?: 0
                            Text(
                                buildString {
                                    if (v.chapterCount > 0) append("${v.chapterCount} ${t("ch", "話")}")
                                    if (pos > 0) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append(
                                            if (pos >= v.chapterCount) t("read", "読了")
                                            else t("read $pos/${v.chapterCount}", "読了 $pos/${v.chapterCount}")
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
