package com.patchscratch.novelnotifier

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * SFW/NSFW mode (2026-10-14): one biometric-gated master switch that hides
 * user-flagged lists/categories and all R18 surfaces until unlocked.
 *
 * The rule is deliberately dumb — a series is hidden iff any list/category
 * it belongs to is flagged. Hidden units are the site-native groupings
 * (NU reading lists, Syosetu 分類) plus client-side categories for flat-
 * follow sites (Kakuyomu; see CategoryStore). Unlock state lives only in
 * AppViewModel memory: fresh processes and ON_STOP re-lock, so the app
 * always wakes up in SFW mode.
 */

/**
 * Drawer row for every site home. Always visible in both modes — the row
 * itself must not leak what is hidden, only that hiding exists.
 */
@Composable
fun NsfwToggleRow(unlocked: Boolean, onToggle: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Text(
                if (unlocked) t("Hide hidden (18+)  🙈", "非表示にする（18禁）  🙈")
                else t("Reveal hidden (18+)  🔒", "非表示を表示（18禁）  🔒")
            )
        },
        selected = false,
        onClick = onToggle
    )
}

/**
 * Shown when the unlock attempt finds no lockscreen credential at all:
 * hiding content behind nothing would be a lie, so offer the way out.
 */
@Composable
fun NeedsDeviceLockDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("No screen lock set", "画面ロックが未設定です")) },
        text = {
            Text(
                t(
                    "Hidden (18+) content is locked with your fingerprint or screen lock. Set one up in Android security settings to use it.",
                    "非表示（18禁）コンテンツは指紋または画面ロックで保護されます。使用するにはAndroidのセキュリティ設定でロックを設定してください。"
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }) { Text(t("Open security settings", "セキュリティ設定を開く")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "閉じる")) }
        }
    )
}
