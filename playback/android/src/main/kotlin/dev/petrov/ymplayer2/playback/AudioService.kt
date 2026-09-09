package dev.petrov.ymplayer2.playback

import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioService : MediaSessionService() {
    private var session: MediaSession? = null
    private var engine: ExoPlayer? = null
    private val playback get() = (application as PlaybackHost).playback

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        engine = player
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            // The catalog is loaded by our in-process adapter. External controllers may
            // operate the current queue, but cannot inject file paths or network URIs.
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                Futures.immediateFuture(emptyList())
        }).apply {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                setSessionActivity(PendingIntent.getActivity(this@AudioService, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }.build()
        // Our UI uses an in-process adapter, so no controller bind invokes onGetSession.
        // Register explicitly for MediaSessionService notification/foreground ownership.
        addSession(requireNotNull(session))
        playback.attach(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        playback.detach()
        session?.release(); session = null
        engine?.release(); engine = null
        super.onDestroy()
    }
}
