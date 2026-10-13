package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * Editor for the novel-mill-style translation lock files
 * (filesDir/xlate/…/prompt.txt, glossary.txt, voices.txt). Opened with a
 * series from the reader (edits that series' layer plus the global one) or
 * without one from Settings (global only). Saves are immediate file writes,
 * so the next chapter translation picks them up.
 */
@Composable
fun XlateFilesScreen(
    context: android.content.Context,
    seriesUrl: String,
    seriesTitle: String,
    onBack: () -> Unit
) {
    val global = XlateStack.dir(context)
    val series = if (seriesUrl.isBlank()) null else XlateStack.seriesDir(context, seriesUrl)

    var gPrompt by remember { mutableStateOf(XlateStack.read(global.resolve(XlateStack.PROMPT))) }
    var gGlossary by remember { mutableStateOf(XlateStack.read(global.resolve(XlateStack.GLOSSARY))) }
    var gVoices by remember { mutableStateOf(XlateStack.read(global.resolve(XlateStack.VOICES))) }
    var sPrompt by remember { mutableStateOf(series?.let { XlateStack.read(it.resolve(XlateStack.PROMPT)) } ?: "") }
    var sGlossary by remember { mutableStateOf(series?.let { XlateStack.read(it.resolve(XlateStack.GLOSSARY)) } ?: "") }
    var sVoices by remember { mutableStateOf(series?.let { XlateStack.read(it.resolve(XlateStack.VOICES)) } ?: "") }

    fun saveGlobal() {
        XlateStack.write(global.resolve(XlateStack.PROMPT), gPrompt)
        XlateStack.write(global.resolve(XlateStack.GLOSSARY), gGlossary)
        XlateStack.write(global.resolve(XlateStack.VOICES), gVoices)
    }

    fun saveSeries() {
        val s = series ?: return
        XlateStack.write(s.resolve(XlateStack.PROMPT), sPrompt)
        XlateStack.write(s.resolve(XlateStack.GLOSSARY), sGlossary)
        XlateStack.write(s.resolve(XlateStack.VOICES), sVoices)
    }

    Column(modifier = Modifier.fillMaxSize().screenInsets().verticalScroll(rememberScrollState())) {
        Row {
            TextButton(onClick = { saveGlobal(); saveSeries(); onBack() }) {
                Text("‹ " + t("Save & back", "保存して戻る"))
            }
        }
        Text(
            t("Translation lock files (novel-mill style). The prompt sets the translator's style; a series prompt replaces the global one. Glossary and voice lines stack: global first, series after. Auto-refine appends new lines to the series files after each translated chapter.",
                "翻訳ロックファイル（novel-mill方式）。プロンプトは翻訳者のスタイルを決めます。シリーズのプロンプトは全体設定を置き換えます。用語集と声のメモは全局→シリーズの順に積み重なります。自動精錬は翻訳済みの各話のあと、シリーズファイルに新しい行を追記します。"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Text(
            t("Global (all series)", "全体（全シリーズ）"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LockField(t("prompt.txt — translator style", "prompt.txt — 翻訳スタイル"), gPrompt, { gPrompt = it; saveGlobal() })
        LockField(t("glossary.txt — names & terms", "glossary.txt — 人名・用語"), gGlossary, { gGlossary = it; saveGlobal() })
        LockField(t("voices.txt — character voices", "voices.txt — キャラの声"), gVoices, { gVoices = it; saveGlobal() })

        if (series != null) {
            Text(
                t("This series", "このシリーズ") + " — " + seriesTitle.take(40),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LockField(t("prompt.txt (replaces global)", "prompt.txt（全体を置換）"), sPrompt, { sPrompt = it; saveSeries() })
            LockField(t("glossary.txt (appends)", "glossary.txt（追記）"), sGlossary, { sGlossary = it; saveSeries() })
            LockField(t("voices.txt (appends)", "voices.txt（追記）"), sVoices, { sVoices = it; saveSeries() })
        }
    }
}

@Composable
private fun LockField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
