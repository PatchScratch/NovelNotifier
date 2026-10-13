package com.patchscratch.novelnotifier

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Long-press Japanese dictionary for the reader (user blueprint 2026-10-13):
 * Kuromoji (IPADIC) finds the word under the finger — surface, base form,
 * reading, part of speech — and a pre-built JMdict_e SQLite (built by
 * scripts/build_jmdict_db.py, shipped gzipped in assets, unpacked to
 * filesDir once) supplies the senses. All offline; the toggle lives in
 * Settings (ja_dict_on).
 *
 * Cost profile: the jar embeds IPADIC (~13MB in the APK), the asset is
 * ~19MB gzipped and expands to ~80MB in filesDir on first use — the
 * toggle's copy says so.
 */
object JaDict {

    data class WordHit(
        val surface: String,
        val baseForm: String,
        val reading: String,      // kuromoji katakana, converted for display
        val pos: String,
        val senses: List<String>  // JMdict sense lines
    )

    @Volatile private var tokenizer: com.atilika.kuromoji.ipadic.Tokenizer? = null

    /** Tokenizer build loads IPADIC (~1s, ~100MB heap spike) — first use only. */
    private fun tokenizer(): com.atilika.kuromoji.ipadic.Tokenizer =
        tokenizer ?: synchronized(this) {
            tokenizer ?: com.atilika.kuromoji.ipadic.Tokenizer().also { tokenizer = it }
        }

    private fun dbFile(context: Context): File = context.filesDir.resolve("jmdict.db")

    /** True once the dictionary is ready (asset gunzipped to filesDir). */
    fun isReady(context: Context): Boolean = dbFile(context).exists()

    /**
     * Copy assets/databases/jmdict.db → filesDir/jmdict.db. The source is
     * stored gzipped in the repo, but AGP decompresses .gz assets at merge
     * time, so the APK stream is plain SQLite. Idempotent; call off the UI
     * thread. Returns false when the asset is missing.
     */
    suspend fun ensureUnpacked(context: Context): Boolean = withContext(Dispatchers.IO) {
        val out = dbFile(context)
        if (out.exists()) return@withContext true
        try {
            val tmp = File(out.parentFile, "jmdict.db.tmp")
            context.assets.open("databases/jmdict.db").use { input ->
                FileOutputStream(tmp).use { fo -> input.copyTo(fo) }
            }
            if (tmp.length() < 1_000_000) {
                tmp.delete(); return@withContext false
            }
            if (!tmp.renameTo(out)) {
                tmp.delete(); return@withContext false
            }
            DebugLog.log("JADICT", "unpacked ${out.length() / 1_000_000}MB")
            true
        } catch (e: Exception) {
            DebugLog.log("JADICT", "unpack failed", e)
            false
        }
    }

    /** Katakana → hiragana for friendlier display. */
    fun toHiragana(katakana: String): String = buildString {
        katakana.forEach { c ->
            val code = c.code
            append(
                when {
                    code in 0x30A1..0x30F6 -> (code - 0x60).toChar()
                    c == 'ー' -> 'ー'
                    else -> c
                }
            )
        }
    }

    /**
     * Tokenize [paragraph], find the token containing [charOffset] and look
     * it up (base form first, then surface). Null = nothing usable under
     * the finger (punctuation, whitespace, unknown).
     */
    suspend fun lookupAt(context: Context, paragraph: String, charOffset: Int): WordHit? =
        withContext(Dispatchers.Default) {
            val tokens = tokenizer().tokenize(paragraph)
            val token = tokens.firstOrNull { charOffset >= it.position && charOffset < it.position + it.surface.length }
                ?: return@withContext null
            val surface = token.surface
            val base = token.baseForm.ifBlank { surface }
            val reading = toHiragana(token.reading.ifBlank { surface })
            val pos = token.partOfSpeechLevel1
            val db = openDb(context)
            val senses = lookupSenses(db, base).ifEmpty { lookupSenses(db, surface) }
            WordHit(surface, base, reading, pos, senses)
        }

    @Volatile private var openHandle: SQLiteDatabase? = null

    private fun openDb(context: Context): SQLiteDatabase {
        openHandle?.let { return it }
        synchronized(this) {
            openHandle?.let { return it }
            val f = dbFile(context)
            val h = if (f.exists()) {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            } else {
                SQLiteDatabase.create(null)   // in-memory: lookups simply miss
            }
            openHandle = h
            return h
        }
    }

    private fun lookupSenses(db: SQLiteDatabase, form: String): List<String> {
        return try {
            db.rawQuery(
                "SELECT senses FROM dict WHERE form = ? ORDER BY length(senses) LIMIT 3",
                arrayOf(form)
            ).use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) out.add(c.getString(0))
                out
            }
        } catch (e: Exception) {
            DebugLog.log("JADICT", "lookup failed '$form'", e)
            emptyList()
        }
    }
}

/**
 * A reader paragraph that supports long-press word lookup: captures its
 * TextLayoutResult, maps the press position to a character offset, and
 * hands the looked-up word to [onWord]. Without the dictionary on it is a
 * plain styled Text.
 */
@Composable
fun JpParagraph(
    text: String,
    color: Color,
    fontSize: Int,
    serif: Boolean,
    dictOn: Boolean,
    onWord: (JaDict.WordHit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var layout: TextLayoutResult? by remember(text) { mutableStateOf(null) }
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.65).sp,
        fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
        onTextLayout = { layout = it },
        modifier = Modifier
            .padding(bottom = 10.dp)
            .then(
                if (dictOn) Modifier.pointerInput(text) {
                    detectTapGestures(
                        onLongPress = { pos ->
                            val l = layout ?: return@detectTapGestures
                            val offset = l.getOffsetForPosition(pos)
                            scope.launch {
                                JaDict.lookupAt(context, text, offset)?.let(onWord)
                            }
                        }
                    )
                } else Modifier
            )
    )
}

/** Popup with the looked-up word: reading, base form, POS, JMdict senses. */
@Composable
fun WordSheet(hit: JaDict.WordHit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "閉じる")) }
        },
        title = { Text(hit.surface) },
        text = {
            Column {
                if (hit.reading != hit.surface) {
                    Text(hit.reading, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    listOfNotNull(
                        hit.baseForm.takeIf { it != hit.surface },
                        hit.pos.takeIf { it.isNotBlank() && it != "*" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
                if (hit.senses.isEmpty()) {
                    Text(
                        t("No dictionary entry — token only", "辞書に項目なし — トークンのみ"),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    hit.senses.forEach { s ->
                        Text(
                            s,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    )
}
