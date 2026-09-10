package dev.petrov.ymplayer2.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

interface PlaybackHost { val playback: AndroidPlayback }

/** Main-thread command adapter. The service alone creates/releases the audio engine. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidPlayback(private val context: Context, private val library: LocalLibrary, private val scope: CoroutineScope) : PlaybackController {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PlaybackState(prefs.getString("profile", "owner") ?: "owner", emptyList(), connected = false))
    override val state = mutable.asStateFlow()
    private var engine: ExoPlayer? = null
    private var job: Job? = null
    private var updating = false
    private var pending: (() -> Unit)? = null
    private var ready = false
    private var followLibrary = true
    private var tracksById = emptyMap<String, Track>()

    fun connect() { context.startService(Intent(context, AudioService::class.java)) }

    internal fun attach(player: ExoPlayer) {
        check(engine == null)
        engine = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!updating && ready) { publish(events.contains(Player.EVENT_TIMELINE_CHANGED)); checkpoint() } }
            override fun onPlayerError(error: PlaybackException) {
                mutable.value = state.value.copy(playing = false, buffering = false, error = "Не удалось воспроизвести файл. Проверьте носитель или выберите другой трек.")
            }
        })
        job = scope.launch {
            launch {
                library.state.collect { catalog ->
                    if (!catalog.ready || catalog.scanning) return@collect
                    if (!ready) {
                        ready = true
                        restore(state.value.profileId)
                        pending?.also { pending = null; it() }
                    } else reconcile()
                }
            }
            while (isActive) { delay(500); if (ready) publish(); if (state.value.playing && state.value.positionSeconds % 5 == 0) checkpoint() }
        }
    }

    internal fun detach() {
        if (ready) { publish(); checkpoint() }
        job?.cancel(); job = null; engine = null; ready = false
        mutable.value = state.value.copy(playing = false, buffering = false, connected = false)
    }

    private fun command(action: () -> Unit) {
        if (ready && engine != null) action()
        else { pending = action; connect() }
    }

    override fun toggle() = command {
        val player = engine ?: return@command
        if (state.value.current?.available != true) return@command
        mutable.value = state.value.copy(error = null)
        if (player.playWhenReady) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.prepare(); player.play()
        }
    }
    override fun stop() = command { engine?.pause(); engine?.seekTo(0); engine?.stop(); publish(); checkpoint() }
    override fun seek(seconds: Int) = command {
        val duration = state.value.current?.durationSeconds ?: 0
        engine?.seekTo(seconds.coerceIn(0, duration).toLong() * 1000); publish(); checkpoint()
    }
    override fun skip(direction: Int) = command {
        val player = engine ?: return@command
        if (direction == 0) return@command
        mutable.value = state.value.copy(error = null)
        when {
            direction > 0 && player.hasNextMediaItem() -> player.seekToNextMediaItem()
            direction < 0 && player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
            else -> player.pause()
        }
        player.prepare(); publish(); checkpoint()
    }
    override fun select(trackId: String) = command {
        val tracks = library.tracks(state.value.profileId).filter { it.available }
        if (tracks.none { it.id == trackId }) return@command
        val index = state.value.queue.indexOfFirst { it.id == trackId }
        mutable.value = state.value.copy(error = null)
        if (index >= 0) engine?.seekTo(index, 0)
        else {
            followLibrary = true
            prefs.edit().remove("source:${state.value.profileId}").apply()
            load(tracks, tracks.indexOfFirst { it.id == trackId }, 0)
        }
        engine?.prepare(); engine?.play()
    }
    override fun switchProfile(profileId: String) = command {
        if (profileId == state.value.profileId || library.profiles.none { it.id == profileId }) return@command
        engine?.pause(); publish(); checkpoint()
        restore(profileId)
    }
    override fun chooseSource(source: Source?) = command {
        prefs.edit().putString("source:${state.value.profileId}", source?.name).apply()
        followLibrary = true
        load(defaultQueue(state.value.profileId), 0, 0)
    }

    override fun setRepeatMode(mode: RepeatMode) = command { engine?.repeatMode = mode.toPlayerMode(); publish(); checkpoint() }
    override fun setShuffle(enabled: Boolean) = command { engine?.shuffleModeEnabled = enabled; publish(); checkpoint() }
    override fun enqueue(trackId: String) = command {
        val track = tracksById[trackId]?.takeIf { it.available } ?: return@command
        if (state.value.queue.any { it.id == trackId }) return@command
        followLibrary = false
        engine?.addMediaItem(track.mediaItem()); publish(true); checkpoint(forceEmpty = true)
    }
    override fun moveInQueue(trackId: String, toIndex: Int) = command {
        val from = state.value.queue.indexOfFirst { it.id == trackId }
        if (from < 0 || toIndex !in state.value.queue.indices || from == toIndex) return@command
        followLibrary = false
        engine?.moveMediaItem(from, toIndex); publish(true); checkpoint()
    }
    override fun removeFromQueue(trackId: String) = command {
        val index = state.value.queue.indexOfFirst { it.id == trackId }
        if (index < 0) return@command
        followLibrary = false
        // Removing the current item must not unexpectedly start its successor.
        if (state.value.current?.id == trackId) { engine?.pause(); mutable.value = state.value.copy(error = null) }
        engine?.removeMediaItem(index); publish(true); checkpoint(forceEmpty = true)
    }
    override fun clearQueue() = command {
        followLibrary = false
        mutable.value = state.value.copy(error = null)
        engine?.pause(); engine?.clearMediaItems(); publish(true); checkpoint(forceEmpty = true)
    }
    override fun playQueue(trackIds: List<String>, startId: String?) = command {
        val queue = trackIds.distinct().mapNotNull { tracksById[it]?.takeIf(Track::available) }
        if (queue.isEmpty()) return@command
        followLibrary = false
        load(queue, queue.indexOfFirst { it.id == startId }.coerceAtLeast(0), 0)
        engine?.prepare(); engine?.play()
    }

    private fun defaultQueue(profile: String): List<Track> {
        val source = prefs.getString("source:$profile", null)
        return library.tracks(profile).filter { it.available && (source == null || it.source.name == source) }
    }

    private fun restore(profile: String) {
        val validProfile = profile.takeIf { id -> library.profiles.any { it.id == id } } ?: "owner"
        mutable.value = state.value.copy(profileId = validProfile, error = null)
        val json = runCatching { JSONObject(prefs.getString("queue:$validProfile", "")!!) }.getOrNull()
        tracksById = library.tracks(validProfile).filter { it.available }.associateBy(Track::id)
        val ids = json?.optJSONArray("ids")
        followLibrary = json?.optBoolean("followLibrary", ids == null || ids.length() == 0) ?: true
        val queue = if (followLibrary) defaultQueue(validProfile) else (0 until (ids?.length() ?: 0)).mapNotNull { tracksById[ids?.optString(it)] }.distinctBy(Track::id)
        engine?.repeatMode = runCatching { RepeatMode.valueOf(prefs.getString("repeat:$validProfile", "OFF")!!) }.getOrDefault(RepeatMode.OFF).toPlayerMode()
        engine?.shuffleModeEnabled = prefs.getBoolean("shuffle:$validProfile", false)
        val current = json?.optString("current")
        val index = queue.indexOfFirst { it.id == current }.coerceAtLeast(0)
        val position = if (queue.getOrNull(index)?.id == current) json?.optInt("position", 0) ?: 0 else 0
        load(queue, index, position)
    }

    private fun reconcile() {
        val state = state.value
        tracksById = library.tracks(state.profileId).filter { it.available }.associateBy(Track::id)
        val next = if (followLibrary) defaultQueue(state.profileId) else state.queue.mapNotNull { tracksById[it.id] }
        if (next == state.queue) return
        val player = engine ?: return
        updating = true
        try {
            if (next.none { it.id == state.current?.id }) player.pause()
            val keep = next.mapTo(hashSetOf(), Track::id)
            for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId !in keep) player.removeMediaItem(i)
            next.forEachIndexed { index, track ->
                val from = (index until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
                if (from == null) player.addMediaItem(index, track.mediaItem())
                else {
                    if (from != index) player.moveMediaItem(from, index)
                    if (player.getMediaItemAt(index) != track.mediaItem()) player.replaceMediaItem(index, track.mediaItem())
                }
            }
        } finally { updating = false }
        publish(true); checkpoint()
    }

    private fun load(queue: List<Track>, index: Int, position: Int) {
        val player = engine ?: return
        updating = true
        player.pause()
        mutable.value = state.value.copy(queue = queue, index = index, positionSeconds = position.coerceIn(0, queue.getOrNull(index)?.durationSeconds ?: 0), playing = false, connected = true, error = null)
        if (queue.isEmpty()) player.clearMediaItems()
        else player.setMediaItems(queue.map { it.mediaItem() }, index, state.value.positionSeconds.toLong() * 1000)
        updating = false
        publish(); checkpoint()
    }

    private fun publish(updateQueue: Boolean = false) {
        val player = engine ?: return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        mutable.value = state.value.copy(queue = if (updateQueue) (0 until player.mediaItemCount).mapNotNull { tracksById[player.getMediaItemAt(it).mediaId] } else state.value.queue,
            index = index, positionSeconds = (player.currentPosition.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
            buffering = player.playbackState == Player.STATE_BUFFERING, repeatMode = player.repeatMode.toRepeatMode(), shuffle = player.shuffleModeEnabled)
    }
    private fun checkpoint(forceEmpty: Boolean = false) {
        val state = state.value
        val editor = prefs.edit().putString("profile", state.profileId)
            .putString("repeat:${state.profileId}", state.repeatMode.name).putBoolean("shuffle:${state.profileId}", state.shuffle)
        if (!forceEmpty && state.queue.isEmpty() && library.state.value.tracks.any { !it.available }) { editor.apply(); return }
        val json = JSONObject().put("ids", JSONArray(state.queue.map(Track::id)))
            .put("current", state.current?.id).put("position", state.positionSeconds).put("followLibrary", followLibrary)
        editor.putString("queue:${state.profileId}", json.toString()).apply()
    }
}

private fun Track.mediaItem() = MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
private fun RepeatMode.toPlayerMode() = when (this) { RepeatMode.OFF -> Player.REPEAT_MODE_OFF; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.ONE -> Player.REPEAT_MODE_ONE }
private fun Int.toRepeatMode() = when (this) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }
