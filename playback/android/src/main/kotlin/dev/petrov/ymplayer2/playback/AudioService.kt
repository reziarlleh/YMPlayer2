package dev.petrov.ymplayer2.playback

import dev.petrov.ymplayer2.localization.*

import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.FlagSet
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.net.Uri
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var engine: ExoPlayer? = null
    private val host get() = application as PlaybackHost
    private val playback get() = host.playback

    override fun onCreate() {
        super.onCreate()
        host.onAudioServiceEvent(AudioServiceEvent.CREATED)
        val source = onlineDataSourceFactory(this, playback::resolveStream)
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        engine = player
        session = MediaLibrarySession.Builder(this, sessionPlayer(player, playback), browserCallback(playback, host::onAudioServiceEvent)).apply {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                setSessionActivity(PendingIntent.getActivity(this@AudioService, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }.build()
        // Our UI uses an in-process adapter; register for notification/foreground ownership.
        addSession(requireNotNull(session))
        playback.attach(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        playback.detach()
        session?.release(); session = null
        engine?.release(); engine = null
        host.onAudioServiceEvent(AudioServiceEvent.DESTROYED)
        super.onDestroy()
    }
}

/** The two launch targets published by 1.x. Browse never reads another profile's catalog. */
internal object BrowserSources {
    const val ROOT = "ymp_root"
    const val WAVE = "ymp_my_wave"
    const val LIKED_CACHE = "ymp_liked_cache"
    const val RESUME = "ymp_resume"

    fun item(id: String): MediaItem? = when (id) {
        ROOT -> MediaItem.Builder().setMediaId(ROOT).setMediaMetadata(MediaMetadata.Builder()
            .setTitle("YMPlayer 2").setIsBrowsable(true).setIsPlayable(false).build()).build()
        WAVE -> playable(WAVE, tr(Msg.msg_de1ea8c09caa), tr(Msg.msg_1298b5e04346))
        LIKED_CACHE -> playable(LIKED_CACHE, tr(Msg.msg_8491940b48e2), tr(Msg.msg_22324e61bdfe))
        else -> null
    }

    private fun playable(id: String, title: String, subtitle: String) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
            .setIsBrowsable(false).setIsPlayable(true).build()).build()

    fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        if (parentId != ROOT || page < 0 || pageSize < 1) return emptyList()
        val offset = page.toLong() * pageSize
        return if (offset >= 2) emptyList() else listOfNotNull(item(WAVE), item(LIKED_CACHE)).drop(offset.toInt()).take(pageSize)
    }

    fun launchItem(id: String): MediaItem? = item(id)?.takeIf { id != ROOT }?.buildUpon()
        ?.setUri("ymplayer2://browser/$id")?.build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun browserCallback(playback: AndroidPlayback, diagnostic: (AudioServiceEvent) -> Unit) = object : MediaLibraryService.MediaLibrarySession.Callback {
    override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        diagnostic(AudioServiceEvent.RESUMPTION_REQUESTED)
        val item = playback.resumptionItem()
        if (item != null) diagnostic(AudioServiceEvent.RESUMPTION_AVAILABLE)
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
            listOfNotNull(item), if (item == null) C.INDEX_UNSET else 0, C.TIME_UNSET))
    }

    override fun onGetLibraryRoot(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(requireNotNull(BrowserSources.item(BrowserSources.ROOT)), params))

    override fun onGetChildren(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo,
        parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(LibraryResult.ofItemList(BrowserSources.children(parentId, page, pageSize), params))

    override fun onGetItem(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo,
        mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(BrowserSources.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
            ?: LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE))

    // A browser can select only these source IDs. Ignore all caller-supplied URIs/metadata.
    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
        mediaItems.singleOrNull()?.mediaId?.let(BrowserSources::launchItem)?.let(::listOf).orEmpty())
}

/** A wave still has a next item when its network request is in flight. The same commands
 * are used by notification/headset controllers and by the on-screen transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun sessionPlayer(player: Player, playback: AndroidPlayback): Player = object : ForwardingPlayer(player) {
    private val modeCallbacks = mutableMapOf<Player.Listener, () -> Unit>()
    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        if (listener in modeCallbacks) return
        val callback = {
            listener.onRepeatModeChanged(repeatMode)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onEvents(this, Player.Events(FlagSet.Builder().add(Player.EVENT_REPEAT_MODE_CHANGED)
                .add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED).build()))
        }
        modeCallbacks[listener] = callback
        playback.addModeListener(callback)
    }
    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        modeCallbacks.remove(listener)?.let(playback::removeModeListener)
    }
    override fun getRepeatMode() = playback.sessionRepeatMode()
    override fun getShuffleModeEnabled() = playback.sessionShuffle()
    private var selectedSource: String? = null
    private fun select(items: List<MediaItem>) {
        selectedSource = items.singleOrNull()?.mediaId?.takeIf { it == BrowserSources.RESUME || BrowserSources.launchItem(it) != null }
    }
    override fun setMediaItem(mediaItem: MediaItem) = select(listOf(mediaItem))
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = select(listOf(mediaItem))
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = select(listOf(mediaItem))
    override fun setMediaItems(mediaItems: List<MediaItem>) = select(mediaItems)
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = select(mediaItems)
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = select(mediaItems)
    override fun addMediaItem(mediaItem: MediaItem) = Unit
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun addMediaItems(mediaItems: List<MediaItem>) = Unit
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = Unit
    override fun prepare() { if (selectedSource == null) super.prepare() }
    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (!playback.state.value.supportsQueueOrdering) commands.remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE)
        return commands.build()
    }
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    override fun seekToNext() { playback.skip(1) }
    override fun seekToNextMediaItem() { playback.skip(1) }
    override fun seekToPrevious() { playback.skip(-1) }
    override fun seekToPreviousMediaItem() { playback.skip(-1) }
    override fun play() {
        when (selectedSource.also { selectedSource = null }) {
            BrowserSources.WAVE -> playback.playMyWave()
            BrowserSources.LIKED_CACHE -> playback.playOfflineLikes()
            else -> playback.sessionPlay()
        }
    }
    override fun pause() { playback.sessionPause() }
    override fun stop() { selectedSource = null; playback.stop() }
    override fun setRepeatMode(repeatMode: Int) { playback.sessionRepeatMode(repeatMode) }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { playback.setShuffle(shuffleModeEnabled) }
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
