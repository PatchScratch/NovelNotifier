package com.patchscratch.novelnotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Media-style notification + MediaSession for read-aloud (2026-10-14).
 * Gives the shade / lockscreen / Android-13 system media controls
 * play-pause, paragraph skip and stop while a chapter is speaking, and a
 * tap target back into the reader.
 *
 * Deliberately NOT a foreground service: the engines keep running in the
 * app's process, and this notification just exposes control of them.
 * TtsController drives everything — attach() is called on start(), the
 * state collector posts/updates/cancels, release() tears it down.
 */
object TtsMedia {

    private const val CHANNEL = "tts_playback"
    private const val NOTIF_ID = 46

    private var appContext: Context? = null
    private var session: MediaSessionCompat? = null
    private var scope: CoroutineScope? = null
    private var collector: Job? = null

    /** Called from TtsController.start(); re-attaches per chapter. */
    fun attach(context: Context, title: String) {
        val ctx = context.applicationContext
        appContext = ctx
        ensureChannel(ctx)
        if (session == null) {
            session = MediaSessionCompat(ctx, "NovelNotifierTTS").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { appContext?.let { TtsController.resume(it) } }
                    override fun onPause() { TtsController.pause() }
                    override fun onSkipToNext() { appContext?.let { TtsController.next(it) } }
                    override fun onSkipToPrevious() { appContext?.let { TtsController.prev(it) } }
                    override fun onStop() { TtsController.stop() }
                })
                isActive = true
            }
        }
        if (scope == null) scope = CoroutineScope(Dispatchers.Main.immediate)
        // One collector for the process lifetime; it watches TtsController
        // state and mirrors it into the notification + PlaybackState.
        if (collector == null) {
            collector = scope?.launch {
                TtsController.state.collect { st -> reflect(st) }
            }
        }
        // Title may have changed with the new chapter — refresh immediately.
        reflect(TtsController.state.value)
    }

    private fun reflect(st: TtsController.TtsState) {
        val ctx = appContext ?: return
        val sess = session ?: return
        val playing = st.state == TtsController.State.PLAYING
        val paused = st.state == TtsController.State.PAUSED

        val pb = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING
                else if (paused) PlaybackStateCompat.STATE_PAUSED
                else PlaybackStateCompat.STATE_STOPPED,
                st.paragraph.coerceAtLeast(0).toLong(),
                st.speed
            )
            .build()
        sess.setPlaybackState(pb)
        sess.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, TtsController.nowPlayingTitle.ifBlank { "Read aloud" })
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, st.total.toLong().coerceAtLeast(1))
                .build()
        )

        if (!playing && !paused) {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
            sess.isActive = false
            return
        }
        sess.isActive = true

        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = action(ctx, ACTION_TOGGLE, 1)
        val next = action(ctx, ACTION_NEXT, 2)
        val stop = action(ctx, ACTION_STOP, 3)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(TtsController.nowPlayingTitle.ifBlank { "Read aloud" })
            .setContentText(
                (if (playing) "Playing " else "Paused ") +
                    (if (st.total > 0) "${st.paragraph + 1} / ${st.total}" else "")
            )
            .setOngoing(playing)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Prev", action(ctx, ACTION_PREV, 4))
            .addAction(0, if (playing) "Pause" else "Play", toggle)
            .addAction(0, "Next", next)
            .addAction(0, "Stop", stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sess.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
    }

    private fun action(ctx: Context, act: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, TtsMediaReceiver::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Media controls while a chapter is being read aloud"
                setShowBadge(false)
            }
        )
    }

    fun release() {
        collector?.cancel()
        collector = null
        appContext?.let { NotificationManagerCompat.from(it).cancel(NOTIF_ID) }
        try { session?.release() } catch (_: Exception) {}
        session = null
    }
}

private const val ACTION_TOGGLE = "com.patchscratch.novelnotifier.TTS_TOGGLE"
private const val ACTION_NEXT = "com.patchscratch.novelnotifier.TTS_NEXT"
private const val ACTION_PREV = "com.patchscratch.novelnotifier.TTS_PREV"
private const val ACTION_STOP = "com.patchscratch.novelnotifier.TTS_STOP"

/** Shade/lockscreen/media-control button taps land here (explicit intents). */
class TtsMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            ACTION_TOGGLE -> TtsController.togglePlayPause(ctx)
            ACTION_NEXT -> TtsController.next(ctx)
            ACTION_PREV -> TtsController.prev(ctx)
            ACTION_STOP -> TtsController.stop()
        }
    }
}
