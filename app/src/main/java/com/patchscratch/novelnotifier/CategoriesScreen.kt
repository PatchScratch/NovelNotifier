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
 * Per-site category management (Wayne's silo rule: each site's drawer
 * manages its own settings). "kakuyomu" shows the client-side categories
 * (full CRUD + hidden switch); "syosetu" shows the site-native 分類 with
 * hidden switches. NU lists are managed in the NU Lists window instead
 * (ListOptionsScreen), not here. NU EditLists aesthetic: plain sections,
 * inline dialogs, no chrome.
 */
@Composable
fun CategoriesScreen(
    site: String,
    syosetuCategories: List<SyosetuCategory>,
    onBack: () -> Unit,
    onEditCategories: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = viewModel<AppViewModel>()
    val kaku = viewModel<KakuyomuViewModel>()
    val alp = viewModel<AlphaPolisViewModel>()
    // Live state from AppViewModel — flips here re-filter every surface.
    val hiddenSyosetu = app.hiddenSyosetuCats

    var newCatName by remember { mutableStateOf("") }
    var renameCat by remember { mutableStateOf<LocalCategory?>(null) }
    var confirmDelete by remember { mutableStateOf<LocalCategory?>(null) }
    // Local-library shelves (v3.0): same CategoryStore key the home drawer
    // and book detail use. Hoisted so the rename/delete dialogs below can
    // mutate it too — they previously fell through to the Kakuyomu VM.
    var localShelves by remember(site) { mutableStateOf(CategoryStore.load(context, site)) }
    fun mutateLocal(next: CategoryState) {
        localShelves = next
        CategoryStore.save(context, site, next)
    }

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
                "Flagged categories vanish from every screen until \"Reveal hidden (18+)\" is unlocked with your fingerprint or screen lock. Hidden series never notify.",
                "フラグを付けたカテゴリは、「非表示を表示（18禁）」を指紋・画面ロックでロック解除するまで全画面から消えます。非表示の作品は通知されません。"
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (site.startsWith("local:")) {
            // --- local library: shelves = categories, full CRUD ------------
            SectionHeader(t("Shelves", "棚"))
            if (localShelves.cats.isEmpty()) {
                EmptyNote(t("No shelves yet — add one below.", "棚はまだありません — 下で追加できます。"))
            }
            localShelves.cats.forEach { cat ->
                CategoryRow(
                    name = cat.name,
                    hidden = cat.hidden,
                    onHidden = { mutateLocal(CategoryStore.setHidden(localShelves, cat.id, it)) },
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
                    label = { Text(t("New shelf", "新しい棚")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = newCatName.isNotBlank(),
                    onClick = {
                        mutateLocal(CategoryStore.add(localShelves, newCatName))
                        newCatName = ""
                    }
                ) { Text(t("Add", "追加")) }
            }
        }

        if (site == "kakuyomu" || site == "alphapolis") {
            // --- flat-follow site: client-side categories ------------------
            // (Kakuyomu + AlphaPolis share the CategoryStore layer; the
            //  mutators route to whichever site's drawer opened this.)
            val isAlp = site == "alphapolis"
            val state = if (isAlp) alp.categories else kaku.categories
            fun addCat(name: String) = if (isAlp) alp.addCategory(name) else kaku.addCategory(name)
            fun setHidden(id: String, h: Boolean) =
                if (isAlp) alp.setCategoryHidden(id, h) else kaku.setCategoryHidden(id, h)
            fun rename(id: String, name: String) =
                if (isAlp) alp.renameCategory(id, name) else kaku.renameCategory(id, name)
            SectionHeader(
                t(
                    if (isAlp) "AlphaPolis categories" else "Kakuyomu categories",
                    if (isAlp) "アルファポリス カテゴリ" else "カクヨム カテゴリ"
                )
            )
            state.cats.forEach { cat ->
                CategoryRow(
                    name = cat.name,
                    hidden = cat.hidden,
                    onHidden = { setHidden(cat.id, it) },
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
                        addCat(newCatName)
                        newCatName = ""
                    }
                ) { Text(t("Add", "追加")) }
            }
        }

        if (site == "syosetu") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Syosetu 分類 (site-native) ---------------------------------
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
            // Same affordance as NU's Lists window: edit on the site itself.
            // There is no standalone editor page — Syosetu's category-name
            // editing lives behind the 「カテゴリ名編集」 control at the top
            // of the bookmark list (helpcenter 118: 10 categories, 15 chars).
            TextButton(onClick = onEditCategories) {
                Text(t("Edit categories on syosetu.com  ↗", "syosetu.comでカテゴリを編集  ↗"))
            }
            Text(
                t(
                    "Opens your bookmark list — tap 「カテゴリ名編集」 at the top to rename 分類 (up to 10, 15 characters each). The next bookmark sync picks up changes.",
                    "ブックマーク一覧を開きます — 上部の「カテゴリ名編集」から分類名を変更できます（最大10個、各15文字まで）。次回のブックマーク同期で反映されます。"
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
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
                    when {
                        site.startsWith("local:") ->
                            mutateLocal(CategoryStore.rename(localShelves, cat.id, name))
                        site == "alphapolis" -> alp.renameCategory(cat.id, name)
                        else -> kaku.renameCategory(cat.id, name)
                    }
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
                    when {
                        site.startsWith("local:") ->
                            mutateLocal(CategoryStore.delete(localShelves, cat.id))
                        site == "alphapolis" -> alp.deleteCategory(cat.id)
                        else -> kaku.deleteCategory(cat.id)
                    }
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
