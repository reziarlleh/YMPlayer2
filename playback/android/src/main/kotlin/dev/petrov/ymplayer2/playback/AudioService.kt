package dev.petrov.ymplayer2.playback

import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.net.Uri
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
        val source = onlineDataSourceFactory(this, playback::resolveStream)
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        engine = player
        session = MediaSession.Builder(this, sessionPlayer(player, playback)).setCallback(object : MediaSession.Callback {
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

/** A wave still has a next item when its network request is in flight. The same commands
 * are used by notification/headset controllers and by the on-screen transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun sessionPlayer(player: Player, playback: AndroidPlayback): Player = object : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    override fun seekToNext() { playback.skip(1) }
    override fun seekToNextMediaItem() { playback.skip(1) }
    override fun play() { playback.sessionPlay() }
    override fun pause() { playback.sessionPause() }
    override fun stop() { playback.stop() }
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun onlineDataSourceFactory(context: android.content.Context, resolve: (String, String) -> String): androidx.media3.datasource.DataSource.Factory {
    val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
        .setUserAgent("Yandex-Music-API").setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000))
    return ResolvingDataSource.Factory(upstream) { spec ->
        if (spec.uri.scheme != "ymplayer2") spec else {
            val parts = spec.uri.pathSegments
            if (spec.uri.host != "yandex" || parts.size != 2) throw java.io.IOException("Invalid online media reference")
            spec.withUri(Uri.parse(resolve(parts[0], parts[1])))
        }
    }
}
