package com.patchscratch.novelnotifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot recovery + dismissal bookkeeping for chapter alerts.
 *
 * The notification shade does not survive a reboot. ChapterAlerts keeps every
 * un-dismissed alert in SharedPreferences; BootReceiver re-posts them (silently —
 * they already chimed once) after BOOT_COMPLETED and after the app updates.
 * AlertDismissedReceiver is the delete intent on each notification: it removes
 * the alert from the store so it is not re-posted after the user swiped it away.
 */

class AlertDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        when (intent.action) {
            // One child notification swiped / tapped away.
            ACTION_ALERT_DISMISSED -> {
                val id = intent.getIntExtra(EXTRA_ALERT_ID, Int.MIN_VALUE)
                if (id != Int.MIN_VALUE) removeActiveAlert(prefs, id)
            }
            // The grouped summary dismissed — its children go with it.
            ACTION_ALERTS_CLEARED -> clearActiveAlerts(prefs)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)
        val stored = loadActiveAlerts(prefs)
        if (stored.isEmpty()) return
        DebugLog.log("BOOT", "re-posting ${stored.size} chapter alert(s) after ${intent.action}")
        postChapterNotifications(context, stored, silent = true)
    }
}
