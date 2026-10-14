package com.patchscratch.novelnotifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One screen manages all hiding (SFW/NSFW feature). Three sections:
 * - Kakuyomu: full CRUD for the client-side categories + hidden switch.
 * - NU reading lists and Syosetu 分類: the site-native groupings, read-only
 *   names with a hidden switch (their hide flags live in the
 *   hidden_lists / syosetu_hidden_cats pref sets, so they ride backups).
 *
 * NU EditLists aesthetic: plain sections, inline dialogs, no chrome.
 */
@Composable
fun CategoriesScreen(
    nuLists: List<ReadingList>,
    syosetuCategories: List<SyosetuCategory>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = viewModel<AppViewModel>()
    val kaku = viewModel<KakuyomuViewModel>()
    // Live state from AppViewModel — flips here re-filter every surface.
    val hiddenLists = app.hiddenLists
    val hiddenSyosetu = app.hiddenSyosetuCats

    var newCatName by remember { mutableStateOf("") }
    var renameCat by remember { mutableStateOf<LocalCategory?>(null) }
    var confirmDelete by remember { mutableStateOf<LocalCategory?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .screenInsets()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + t("Back", "戻る")) }
            Text(
                t("Categories & hidden", "カテゴリと非表示"),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            t(
                "Flagged lists and categories vanish from every screen until \"Reveal hidden (18+)\" is unlocked with your fingerprint or screen lock. Hidden series never notify.",
                "フラグを付けたリスト・カテゴリは、「非表示を表示（18禁）」を指紋・画面ロックでロック解除するまで全画面から消えます。非表示の作品は通知されません。"
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // --- Kakuyomu: client-side categories ------------------------------
        SectionHeader(t("Kakuyomu categories", "カクヨム カテゴリ"))
        kaku.categories.cats.forEach { cat ->
            CategoryRow(
                name = cat.name,
                hidden = cat.hidden,
                onHidden = { kaku.setCategoryHidden(cat.id, it) },
                onRename = { renameCat = cat },
                onDelete = { confirmDelete = cat }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = newCatName,
                onValueChange = { newCatName = it },
                label = { Text(t("New category", "新しいカテゴリ")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = newCatName.isNotBlank(),
                onClick = {
                    kaku.addCategory(newCatName)
                    newCatName = ""
                }
            ) { Text(t("Add", "追加")) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- NU reading lists (site-native) ----------------------------------
        SectionHeader("Novel Updates")
        if (nuLists.isEmpty()) {
            EmptyNote(t("Lists appear after the first library sync.", "ライブラリ同期後に表示されます。"))
        }
        nuLists.forEach { list ->
            CategoryRow(
                name = list.name,
                hidden = list.id in hiddenLists,
                onHidden = {
                    app.updateHiddenLists(
                        if (it) hiddenLists + list.id else hiddenLists - list.id
                    )
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Syosetu 分類 (site-native) ---------------------------------------
        SectionHeader(t("Syosetu 分類", "Syosetu 分類"))
        if (syosetuCategories.isEmpty()) {
            EmptyNote(t("Categories appear after the first bookmark sync.", "ブックマーク同期後に表示されます。"))
        }
        syosetuCategories.forEach { cat ->
            CategoryRow(
                name = cat.name,
                hidden = cat.name in hiddenSyosetu,
                onHidden = {
                    // Names, not ids: bookmarks carry only the category name,
                    // so a site-side rename orphans the flag (accepted).
                    app.updateHiddenSyosetuCats(
                        if (it) hiddenSyosetu + cat.name else hiddenSyosetu - cat.name
                    )
                }
            )
        }
    }

    renameCat?.let { cat ->
        var name by remember(cat.id) { mutableStateOf(cat.name) }
        AlertDialog(
            onDismissRequest = { renameCat = null },
            title = { Text(t("Rename category", "カテゴリ名の変更")) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    kaku.renameCategory(cat.id, name)
                    renameCat = null
                }) { Text(t("Save", "保存")) }
            },
            dismissButton = {
                TextButton(onClick = { renameCat = null }) { Text(t("Cancel", "キャンセル")) }
            }
        )
    }

    confirmDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("Delete category?", "カテゴリを削除しますか？")) },
            text = {
                Text(
                    t(
                        "\"${cat.name}\" disappears; its works fall back to All. Works are not unfollowed.",
                        "「${cat.name}」を削除します。作品は「すべて」に戻ります（フォロー解除はされません）。"
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    kaku.deleteCategory(cat.id)
                    confirmDelete = null
                }) { Text(t("Delete", "削除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(t("Cancel", "キャンセル")) }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/** One list/category row: name + hidden switch (+ rename/delete for local cats). */
@Composable
private fun CategoryRow(
    name: String,
    hidden: Boolean,
    onHidden: (Boolean) -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onRename != null) {
                TextButton(onClick = onRename) { Text(t("Rename", "名前変更")) }
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(t("Delete", "削除"), color = MaterialTheme.colorScheme.error)
                }
            }
            Switch(checked = hidden, onCheckedChange = onHidden)
        }
    }
}

/**
 * Move-one-work picker (single membership, move-only). Used from the
 * Kakuyomu library's long-press and the work-detail header. "New category"
 * creates one and moves in a single tap.
 */
@Composable
fun CategoryMoveDialog(
    workId: String,
    current: LocalCategory?,
    cats: List<LocalCategory>,
    onMove: (catId: String?) -> Unit,
    onCreateAndMove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newCat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Category", "カテゴリ")) },
        text = {
            Column {
                TextButton(onClick = {
                    onMove(null)
                    onDismiss()
                }) {
                    Text(
                        if (current == null) "✓ " else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(t("All (no category)", "すべて（カテゴリなし）"))
                }
                cats.forEach { cat ->
                    TextButton(onClick = {
                        onMove(cat.id)
                        onDismiss()
                    }) {
                        Text(
                            (if (current?.id == cat.id) "✓ " else "") + cat.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCat,
                        onValueChange = { newCat = it },
                        label = { Text(t("New category", "新しいカテゴリ")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = newCat.isNotBlank(),
                        onClick = {
                            onCreateAndMove(newCat)
                            onDismiss()
                        }
                    ) { Text(t("Add", "追加")) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "閉じる")) }
        }
    )
}
