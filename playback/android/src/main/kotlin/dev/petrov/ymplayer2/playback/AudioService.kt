package dev.petrov.ymplayer2.playback

import dev.petrov.ymplayer2.localization.*

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.FlagSet
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.cancel
import android.net.Uri
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import dev.petrov.ymplayer2.core.SessionCommandOrigin
import dev.petrov.ymplayer2.core.SessionTransportCommand
import dev.petrov.ymplayer2.core.SessionMediaButton
import dev.petrov.ymplayer2.core.SessionKeyAction
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioService : MediaLibraryService() {
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private var engine: ExoPlayer? = null
    private val activityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var activityRepublished = false
    private var carPromotionActive = false
    private val host get() = application as PlaybackHost
    private val playback get() = host.playback

    override fun onCreate() {
        super.onCreate()
        // STOP leaves a resumable checkpoint, not an active TV notification card.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setMediaNotificationProvider(TvMediaNotificationProvider(this, explicitStop = {
            playback.carClips?.stop() ?: if (playback.radioMode) host.radio?.stop() else playback.notificationStop()
        }, notificationCreated = { current ->
            if (carPromotionActive) {
                activityHandler.removeCallbacks(expireCarPromotion)
                activityHandler.postDelayed(expireCarPromotion, 1000)
            }
            if (android.os.Build.VERSION.SDK_INT >= 34 && !activityRepublished) {
                activityRepublished = true
                // Media3 queues the actual notification write after createNotification returns.
                // Two queue turns put the session activity update after that write, once per service.
                activityHandler.post { activityHandler.post {
                    if (session === current) {
                        current.setSessionActivity(mediaPlayerActivity(this))
                        android.util.Log.i("YMPlayerSession", "sessionActivity republished after notification")
                    }
                } }
            }
        }))
        host.onAudioServiceEvent(AudioServiceEvent.CREATED)
        val source = onlineDataSourceFactory(this, playback::resolveStream)
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(source))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                250, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build()).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        engine = player
        var preparedCarItem: MediaItem? = null
        val callback = browserCallback(host, serviceScope, playback, host::onAudioServiceEvent,
            preparedCarItem = { preparedCarItem }, beforeCarPlay = ::promoteCarPlayback)
        session = MediaLibrarySession.Builder(this, sessionPlayer(player, playback, onCarPrepared = { preparedCarItem = it }, resumeCar = {
            session?.let(callback::resumeActiveOutput) == true
        }) { command ->
            if (command == SessionTransportCommand.PAUSE || command == SessionTransportCommand.STOP ||
                command == SessionTransportCommand.SET_PLAY_WHEN_READY_FALSE) callback.cancelPendingSelection()
            // Read while Player handles the request: Media3 clears this context after dispatch.
            val current = session
            val controller = current?.controllerForCurrentRequest
            val origin = when {
                controller == null -> SessionCommandOrigin.NO_CONTROLLER
                current != null && current.isMediaNotificationController(controller) -> SessionCommandOrigin.MEDIA_NOTIFICATION_CONTROLLER
                else -> SessionCommandOrigin.MEDIA_CONTROLLER
            }
            playback.recordControllerCommand(command, origin, controller?.packageName)
        }, callback).apply {
            setSessionActivity(mediaPlayerActivity(this@AudioService))
        }.build()
        // Our UI uses an in-process adapter; register for notification/foreground ownership.
        addSession(requireNotNull(session))
        playback.attach(player)
        host.radio?.attach(player)
    }

    private fun promoteCarPlayback() {
        if (android.os.Build.VERSION.SDK_INT < 35 || isPlaybackOngoing) return
        val now = session ?: return
        // Android 15 requires media playback foreground status before ExoPlayer asks for audio
        // focus. An explicit car command can arrive before Media3 has published its first card.
        val channelId = "ymp_car_start"
        val notifications = getSystemService(android.app.NotificationManager::class.java)
        notifications.createNotificationChannel(android.app.NotificationChannel(
            channelId, tr(Msg.msg_dff16f6f70a1), android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentIntent(now.sessionActivity)
            .setOnlyAlertOnce(true).setOngoing(true).build()
        androidx.core.app.ServiceCompat.startForeground(this, 1002, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        carPromotionActive = true
        activityHandler.removeCallbacks(expireCarPromotion)
        activityHandler.postDelayed(expireCarPromotion, 15000)
    }

    private val expireCarPromotion = Runnable {
        carPromotionActive = false
        getSystemService(android.app.NotificationManager::class.java).cancel(1002)
        if (!isPlaybackOngoing) androidx.core.app.ServiceCompat.stopForeground(this,
            androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Retain Media3's foreground/service lifecycle, but do not persist its cleanup
        // pause over a saved Play waiting for the next foreground launch.
        playback.onServiceTaskRemoved { super.onTaskRemoved(rootIntent) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        activityHandler.removeCallbacksAndMessages(null)
        host.radio?.detach()
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
private fun browserCallback(host: PlaybackHost, scope: kotlinx.coroutines.CoroutineScope,
    playback: AndroidPlayback, diagnostic: (AudioServiceEvent) -> Unit, preparedCarItem: () -> MediaItem?, beforeCarPlay: () -> Unit) =
    object : CarBrowserCallback(host, scope, beforeCarPlay) {
    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
        observeMediaButtonEvent(intent) { key ->
            val button = when (key.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> SessionMediaButton.PLAY_PAUSE
                KeyEvent.KEYCODE_HEADSETHOOK -> SessionMediaButton.HEADSET_HOOK
                KeyEvent.KEYCODE_MEDIA_PLAY -> SessionMediaButton.PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> SessionMediaButton.PAUSE
                KeyEvent.KEYCODE_MEDIA_STOP -> SessionMediaButton.STOP
                KeyEvent.KEYCODE_MEDIA_NEXT -> SessionMediaButton.NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> SessionMediaButton.PREVIOUS
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SessionMediaButton.FAST_FORWARD
                KeyEvent.KEYCODE_MEDIA_REWIND -> SessionMediaButton.REWIND
                else -> SessionMediaButton.OTHER
            }
            val action = when (key.action) {
                KeyEvent.ACTION_DOWN -> SessionKeyAction.DOWN
                KeyEvent.ACTION_UP -> SessionKeyAction.UP
                KeyEvent.ACTION_MULTIPLE -> SessionKeyAction.MULTIPLE
                else -> SessionKeyAction.OTHER
            }
            val origin = if (session.isMediaNotificationController(controllerInfo))
                SessionCommandOrigin.MEDIA_NOTIFICATION_CONTROLLER else SessionCommandOrigin.MEDIA_CONTROLLER
            playback.recordMediaButton(button, key.keyCode, action, key.repeatCount, key.eventTime, key.downTime,
                origin, controllerInfo.packageName)
        }

    override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
        android.util.Log.i("YMPlayerSession", "controller=${controller.packageName} trusted=${controller.isTrusted} playPause=${result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE)}")
        return Futures.immediateFuture(result)
    }
    override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        diagnostic(AudioServiceEvent.RESUMPTION_REQUESTED)
        val item = playback.resumptionItem()
        if (car(session, controller) && isForPlayback) {
            // A cold engine still has an empty timeline between PrepareFromId and Play.
            // Media3 asks for resumption here even though the driver has chosen a specific item.
            preparedCarItem()?.let { return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(it), 0, C.TIME_UNSET)) }
            return carResumption(item, session, controller)
        }
        if (item != null) diagnostic(AudioServiceEvent.RESUMPTION_AVAILABLE)
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
            listOfNotNull(item), if (item == null) C.INDEX_UNSET else 0, C.TIME_UNSET))
    }


}

/** Observe only: false always leaves Media3 responsible for mapping/handling the key. */
internal fun observeMediaButtonEvent(intent: Intent, observe: (KeyEvent) -> Unit): Boolean {
    try {
        if (intent.action == Intent.ACTION_MEDIA_BUTTON)
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)?.let(observe)
    } catch (_: Exception) { /* Diagnostics must never consume an input event. */ }
    return false
}

/** A wave still has a next item when its network request is in flight. The same commands
 * are used by notification/headset controllers and by the on-screen transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun sessionPlayer(player: Player, playback: AndroidPlayback, resumeCar: () -> Boolean = { false },
    onCarPrepared: (MediaItem?) -> Unit = {},
    commandObserved: (SessionTransportCommand) -> Unit = {}): Player = object : ForwardingPlayer(player) {
    private fun observe(command: SessionTransportCommand) {
        // A diagnostics/storage failure must never swallow a transport command.
        try { commandObserved(command) } catch (_: Exception) { }
    }
    private val modeCallbacks = mutableMapOf<Player.Listener, () -> Unit>()
    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        if (listener in modeCallbacks) return
        val callback = {
            listener.onRepeatModeChanged(repeatMode)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onAvailableCommandsChanged(availableCommands)
            listener.onEvents(this, Player.Events(FlagSet.Builder().add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED).add(Player.EVENT_REPEAT_MODE_CHANGED)
                .add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED).build()))
        }
        modeCallbacks[listener] = callback
        playback.addModeListener(callback)
    }
    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        modeCallbacks.remove(listener)?.let(playback::removeModeListener)
    }
    override fun getRepeatMode() = if (playback.radioMode || playback.carClips != null) Player.REPEAT_MODE_OFF else playback.sessionRepeatMode()
    override fun getShuffleModeEnabled() = playback.carClips == null && !playback.radioMode && playback.sessionShuffle()
    private var selectedCar: CarSelection? = null
    private var selectedSource: String? = null
    private fun select(items: List<MediaItem>) {
        selectedCar = items.singleOrNull()?.localConfiguration?.tag as? CarSelection
        onCarPrepared(items.singleOrNull()?.takeIf { selectedCar != null })
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
    override fun prepare() { if (selectedSource == null && selectedCar == null) super.prepare() }
    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        val clips = playback.carClips
        val radio = playback.radio?.state?.value.takeIf { playback.radioMode }
        if (!(clips?.canNext ?: radio?.canNext ?: playback.state.value.canSkipNext)) commands.remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (!(clips?.canPrevious ?: radio?.canPrevious ?: playback.state.value.canSkipPrevious)) commands.remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (clips != null || playback.radioMode || !playback.state.value.supportsQueueOrdering) commands.remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE)
        if (playback.radioMode) commands.remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM).remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION).remove(Player.COMMAND_SEEK_BACK).remove(Player.COMMAND_SEEK_FORWARD)
        return commands.build()
    }
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    private fun skip(direction: Int) { playback.carClips?.let { it.skip(direction); return }; if (playback.radioMode) playback.radio?.skip(direction) else playback.skip(direction) }
    override fun seekToNext() = skip(1)
    override fun seekToNextMediaItem() = skip(1)
    override fun seekToPrevious() = skip(-1)
    override fun seekToPreviousMediaItem() = skip(-1)
    override fun play() { observe(SessionTransportCommand.PLAY); playRequested() }
    private fun playRequested() {
        selectedCar?.let { selectedCar = null; onCarPrepared(null); it.play(); return }
        if (selectedSource == null) playback.carClips?.let { it.play(); return }
        if (selectedSource == null && playback.radioMode) {
            playback.radio?.let { if (it.state.value.let { state -> !state.playing && !state.buffering }) it.play() }
            return
        }
        if (selectedSource == null && resumeCar()) return
        when (selectedSource.also { selectedSource = null }) {
            BrowserSources.WAVE -> playback.playMyWave()
            BrowserSources.LIKED_CACHE -> playback.playOfflineLikes()
            else -> playback.sessionPlay()
        }
    }
    private fun pauseRequested() { playback.carClips?.let { it.pause(); return }; if (playback.radioMode) playback.radio?.stop() else playback.sessionPause() }
    override fun pause() { observe(SessionTransportCommand.PAUSE); pauseRequested() }
    override fun stop() {
        observe(SessionTransportCommand.STOP)
        selectedSource = null; selectedCar = null; onCarPrepared(null)
        playback.carClips?.let { it.stop(); return }
        if (playback.radioMode) playback.radio?.stop() else playback.sessionStop()
    }
    override fun setRepeatMode(repeatMode: Int) { if (playback.carClips == null && !playback.radioMode) playback.sessionRepeatMode(repeatMode) }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { if (playback.carClips == null && !playback.radioMode) playback.setShuffle(shuffleModeEnabled) }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        observe(if (playWhenReady) SessionTransportCommand.SET_PLAY_WHEN_READY_TRUE else SessionTransportCommand.SET_PLAY_WHEN_READY_FALSE)
        if (playWhenReady) playRequested() else pauseRequested()
    }
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
