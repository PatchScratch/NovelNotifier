package com.patchscratch.novelnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground downloader for the in-app updater: streams the release APK
 * STRAIGHT into a PackageInstaller session (no intermediate file on disk),
 * with a progress notification (survives screen-off, like EpubBulkService)
 * and progress mirrored into [UpdateState] for the in-app dialog. On
 * completion the session commits and Android shows its install
 * confirmation sheet; [UpdateInstallReceiver] logs the verdict.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL = "app_update"
        const val NOTIF_ID = 48
        const val EXTRA_URL = "url"
        @Volatile var running = false
            private set

        fun start(context: Context, url: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UpdateDownloadService::class.java).putExtra(EXTRA_URL, url)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UpdateDownloadService::class.java).setAction("STOP")
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            UpdateState.phase = ""
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || running) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(NOTIF_ID, buildNotification(0, true))
        scope.launch {
            try {
                downloadAndInstall(url)
            } catch (e: Exception) {
                DebugLog.log("UPDATE", "download/install failed", e)
                UpdateState.phase = t("Update failed — try again", "更新に失敗しました — 再試行してください")
                note(t("Update failed", "更新に失敗しました"), 0, false)
            } finally {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(pct: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(t("Downloading update", "アップデートをダウンロード中"))
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0, t("Stop", "停止"),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, UpdateDownloadService::class.java).setAction("STOP"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun note(text: String, pct: Int, indeterminate: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /**
     * The one-shot pipeline: HttpURLConnection → session stream. The
     * PackageInstaller commit hands off to the system confirmation sheet;
     * UpdateState.phase "installing" keeps the dialog from offering
     * buttons while that sheet is up.
     */
    private suspend fun downloadAndInstall(url: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val out: OutputStream = session.openWrite("novelnotifier-update", 0, -1)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "NovelNotifier/1.0 (github.com/PatchScratch/NovelNotifier)")
            val total = conn.contentLengthLong
            var done = 0L
            val buf = ByteArray(64 * 1024)
            UpdateState.phase = "downloading"
            conn.inputStream.use { input ->
                out.use { o ->
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        UpdateState.progress = pct
                        if (done % (8L * 1024 * 1024) < buf.size) {  // every ~8MB
                            note(t("Downloading update", "アップデートをダウンロード中"), maxOf(pct, 0), pct < 0)
                        }
                    }
                    o.flush()
                    // fsync INSIDE the use-block: fsync on the closed
                    // stream (after use{}) throws EBADF — caught live on
                    // the rig (full download lost at the last step).
                    session.fsync(o)
                }
            }
            conn.disconnect()

            UpdateState.phase = "installing"
            note(t("Ready to install", "インストール準備完了"), 100, false)
            // The status IntentSender MUST be MUTABLE: the platform attaches
            // EXTRA_STATUS at send time (an immutable one arrives empty —
            // verified live: receiver saw -999 and no confirm sheet).
            val pi = PendingIntent.getBroadcast(
                this@UpdateDownloadService, sessionId,
                Intent(this@UpdateDownloadService, UpdateInstallReceiver::class.java)
                    .putExtra("sid", sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
            DebugLog.log("UPDATE", "session $sessionId committed (${done / (1024 * 1024)}MB)")
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** Verdicts from the PackageInstaller commit (system sheet outcome). */
class UpdateInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            // The system hands us the confirm sheet as an Intent we must
            // START ourselves (status 1, not an error — mishandling this
            // was why the sheet never appeared).
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                DebugLog.log("UPDATE", "pending user action — launching confirm sheet")
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                DebugLog.log("UPDATE", "install SUCCESS — app will restart into the new version")
                UpdateState.reset()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                DebugLog.log("UPDATE", "install cancelled by user")
                UpdateState.reset()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                DebugLog.log("UPDATE", "install failed: $status $msg")
                android.widget.Toast.makeText(
                    context,
                    t("Install failed — see Logs", "インストール失敗 — ログを確認"),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                UpdateState.reset()
            }
        }
        // The dialog either reset (success) or shows its error phase; the
        // notification can go away.
        context.getSystemService(NotificationManager::class.java)?.cancel(UpdateDownloadService.NOTIF_ID)
    }
}
