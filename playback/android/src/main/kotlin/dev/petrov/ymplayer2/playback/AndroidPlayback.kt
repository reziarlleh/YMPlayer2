package dev.petrov.ymplayer2.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
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
class AndroidPlayback(private val context: Context, private val library: LocalLibrary, private val scope: CoroutineScope, private val online: OnlineMusic? = null) : PlaybackController {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PlaybackState(prefs.getString("profile", "owner") ?: "owner", emptyList(), connected = false))
    override val state = mutable.asStateFlow()
    private var engine: ExoPlayer? = null
    private var job: Job? = null
    private var updating = false
    private var restoring = false
    private var pending: (() -> Unit)? = null
    private var ready = false
    private var followLibrary = true
    private var tracksById = emptyMap<String, Track>()
    private var logicalQueue = emptyList<Track>()
    private var waitingId: String? = null

    fun connect() { context.startService(Intent(context, AudioService::class.java)) }

    internal fun attach(player: ExoPlayer) {
        check(engine == null)
        engine = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!updating && !restoring && ready) { publish(); checkpoint() } }
            override fun onPlayerError(error: PlaybackException) {
                val remote = state.value.current?.source == Source.YANDEX
                val failure = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<MusicException>().firstOrNull()?.failure
                mutable.value = state.value.copy(playing = false, buffering = false, error = if (remote)
                    failure?.message() ?: "Не удалось воспроизвести музыку Яндекса. Проверьте сеть и доступ к треку; нажмите воспроизведение для повтора."
                    else "Не удалось воспроизвести файл. Проверьте носитель или выберите другой трек.")
                if (!remote) scope.launch { library.refresh() }
            }
        })
        job = scope.launch {
            online?.let { music -> launch { music.catalog.collect { reconcileOnline() } } }
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
        if (player.playWhenReady && player.playerError == null) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.prepare(); player.play()
        }
    }
    override fun stop() = command {
        mutable.value = state.value.copy(positionSeconds = 0)
        engine?.pause(); engine?.seekTo(0); engine?.stop(); publish(); checkpoint()
    }
    override fun seek(seconds: Int) = command {
        val duration = state.value.current?.durationSeconds ?: 0
        val position = seconds.coerceIn(0, duration)
        if (waitingId != null) mutable.value = state.value.copy(positionSeconds = position)
        else engine?.seekTo(position.toLong() * 1000)
        publish(); checkpoint()
    }
    override fun skip(direction: Int) = command {
        val player = engine ?: return@command
        if (direction == 0) return@command
        mutable.value = state.value.copy(error = null)
        if (waitingId != null) {
            val range = if (direction > 0) (state.value.index + 1 until logicalQueue.size).toList() else (state.value.index - 1 downTo 0).toList()
            val index = range.firstOrNull { logicalQueue[it].available }
                ?: if (state.value.repeatMode == RepeatMode.ALL) logicalQueue.indices.firstOrNull { logicalQueue[it].available } else null
            if (index != null) load(logicalQueue, index, 0)
            return@command
        }
        when {
            direction > 0 && player.hasNextMediaItem() -> player.seekToNextMediaItem()
            direction < 0 && player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
            else -> player.pause()
        }
        player.prepare(); publish(); checkpoint()
    }
    override fun select(trackId: String) = command {
        val tracks = knownTracks(state.value.profileId).filter { it.available }
        if (tracks.none { it.id == trackId }) return@command
        val index = state.value.queue.indexOfFirst { it.id == trackId }
        mutable.value = state.value.copy(error = null)
        if (index >= 0) {
            if (waitingId != null) load(logicalQueue, index, 0)
            else engine?.seekTo(logicalQueue.filter { it.available }.indexOfFirst { it.id == trackId }, 0)
        }
        else {
            followLibrary = tracks.first { it.id == trackId }.source != Source.YANDEX
            prefs.edit().remove("source:${state.value.profileId}").apply()
            val next = if (followLibrary) tracks.filter { it.source != Source.YANDEX } else tracks.filter { it.source == Source.YANDEX }
            load(next, next.indexOfFirst { it.id == trackId }, 0)
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
        val queue = defaultQueue(state.value.profileId)
        load(queue, queue.indexOfFirst { it.available }.coerceAtLeast(0), 0)
    }

    override fun setRepeatMode(mode: RepeatMode) = command { engine?.repeatMode = mode.toPlayerMode(); publish(); checkpoint() }
    override fun setShuffle(enabled: Boolean) = command { engine?.shuffleModeEnabled = enabled; publish(); checkpoint() }
    override fun enqueue(trackId: String) = command {
        val track = knownTracks(state.value.profileId).find { it.id == trackId && it.available } ?: return@command
        if (state.value.queue.any { it.id == trackId }) return@command
        followLibrary = false
        syncQueue(logicalQueue + track)
    }
    override fun moveInQueue(trackId: String, toIndex: Int) = command {
        val from = state.value.queue.indexOfFirst { it.id == trackId }
        if (from < 0 || toIndex !in state.value.queue.indices || from == toIndex) return@command
        followLibrary = false
        syncQueue(logicalQueue.toMutableList().apply { add(toIndex, removeAt(from)) })
    }
    override fun removeFromQueue(trackId: String) = command {
        val index = state.value.queue.indexOfFirst { it.id == trackId }
        if (index < 0) return@command
        followLibrary = false
        // Removing the current item must not unexpectedly start its successor.
        if (state.value.current?.id == trackId) { engine?.pause(); mutable.value = state.value.copy(error = null) }
        syncQueue(logicalQueue.filterNot { it.id == trackId })
    }
    override fun clearQueue() = command {
        followLibrary = false
        mutable.value = state.value.copy(error = null)
        load(emptyList(), 0, 0)
    }
    override fun playQueue(trackIds: List<String>, startId: String?) = command {
        val known = knownTracks(state.value.profileId).associateBy(Track::id)
        val queue = trackIds.distinct().mapNotNull { known[it]?.takeIf(Track::available) }
        if (queue.isEmpty()) return@command
        followLibrary = false
        load(queue, queue.indexOfFirst { it.id == startId }.coerceAtLeast(0), 0)
        engine?.prepare(); engine?.play()
    }

    private fun defaultQueue(profile: String): List<Track> {
        val source = prefs.getString("source:$profile", null)
        return library.tracks(profile).filter { source == null || it.source.name == source }
    }

    private fun remoteEnabled(profile: String) = online?.catalog?.value?.let { it.profileId == profile && it.enabled } == true
    private fun knownTracks(profile: String): List<Track> = library.tracks(profile) + if (remoteEnabled(profile))
        (logicalQueue.filter { it.source == Source.YANDEX } + online!!.tracksForPlayback(profile)).associateBy(Track::id).values else emptyList()

    /** Called on the Media3 loader thread, never the main thread. No token or signed URI enters a MediaItem. */
    internal fun resolveStream(profile: String, trackId: String): String {
        val music = online ?: throw java.io.IOException("Online source unavailable")
        return try { runBlocking { withTimeout(60_000) { music.api.stream(profile, trackId) } } }
        catch (e: Exception) { throw java.io.IOException("Online source unavailable", e) }
    }

    private fun restore(profile: String) {
        val validProfile = profile.takeIf { id -> library.profiles.any { it.id == id } } ?: "owner"
        // Main.immediate collectors may run inside StateFlow.value assignment. Read the target
        // checkpoint before notifying them, and do not reconcile an outgoing queue into that profile.
        val json = runCatching { JSONObject(prefs.getString("queue:$validProfile", "")!!) }.getOrNull()
        restoring = true
        try {
        logicalQueue = emptyList()
        mutable.value = state.value.copy(profileId = validProfile, queue = emptyList(), index = 0, positionSeconds = 0, playing = false, error = null)
        online?.accounts?.activate(validProfile)
        tracksById = library.tracks(validProfile).associateBy(Track::id)
        val ids = json?.optJSONArray("ids")
        followLibrary = json?.optBoolean("followLibrary", ids == null || ids.length() == 0) ?: true
        val references = json?.optJSONArray("tracks")
        val saved = (0 until (references?.length() ?: 0)).mapNotNull { index -> runCatching {
            val item = references!!.getJSONObject(index)
            if (item.getString("source") == Source.YANDEX.name) Track(item.getString("id"), item.getString("title"), item.getString("artist"),
                item.optString("album"), Source.YANDEX, item.getInt("duration"), false, available = remoteEnabled(validProfile),
                genre = "", folder = "Яндекс Музыка", artworkUri = item.optString("artwork").takeIf { it.isNotBlank() })
            else SavedTrack(item.getString("id"), item.getString("title"), item.getString("artist"), Source.valueOf(item.getString("source")), item.getInt("duration"), item.getInt("tint")).resolve(tracksById)
        }.getOrNull() }.associateBy(Track::id)
        val queue = if (followLibrary) defaultQueue(validProfile) else (0 until (ids?.length() ?: 0)).mapNotNull { tracksById[ids?.optString(it)] ?: saved[ids?.optString(it)] }.distinctBy(Track::id)
        engine?.repeatMode = runCatching { RepeatMode.valueOf(prefs.getString("repeat:$validProfile", "OFF")!!) }.getOrDefault(RepeatMode.OFF).toPlayerMode()
        engine?.shuffleModeEnabled = prefs.getBoolean("shuffle:$validProfile", false)
        val current = json?.optString("current")
        val index = queue.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: queue.indexOfFirst { it.available }.coerceAtLeast(0)
        val position = if (queue.getOrNull(index)?.id == current) json?.optInt("position", 0) ?: 0 else 0
        load(queue, index, position)
        } finally { restoring = false }
        reconcileOnline()
    }

    private fun reconcileOnline() {
        val remote = online?.catalog?.value ?: return
        if (!ready || restoring || remote.profileId != state.value.profileId) return
        if (remote.phase in setOf(AuthPhase.SIGNED_OUT, AuthPhase.GUEST, AuthPhase.UNCONFIGURED, AuthPhase.ERROR)) {
            val keep = logicalQueue.filter { it.source != Source.YANDEX }
            if (keep != logicalQueue) syncQueue(keep)
        } else reconcile()
    }

    private fun reconcile() {
        val state = state.value
        tracksById = (library.tracks(state.profileId) + online?.catalog?.value?.takeIf { it.profileId == state.profileId && it.enabled }?.tracks.orEmpty()).associateBy(Track::id)
        val next = if (followLibrary) defaultQueue(state.profileId) else logicalQueue.map { track ->
            if (track.source == Source.YANDEX) (tracksById[track.id] ?: track.copy(available = true)).let { it.copy(available = it.available && remoteEnabled(state.profileId)) }
            else tracksById[track.id] ?: track.copy(available = false, uri = null)
        }
        if (next == state.queue) return
        syncQueue(next)
    }

    /** Logical references survive disconnects; the engine only receives playable items. */
    private fun syncQueue(next: List<Track>) {
        val before = state.value
        val preferred = next.indexOfFirst { it.id == before.current?.id }
        val index = if (preferred >= 0) preferred else before.index.coerceAtMost((next.size - 1).coerceAtLeast(0))
        if (waitingId != null || next.getOrNull(index)?.available == false || preferred < 0) {
            load(next, index, if (preferred >= 0) before.positionSeconds else 0)
            return
        }
        val player = engine ?: return
        logicalQueue = next
        val playable = next.filter { it.available }
        updating = true
        try {
            val keep = playable.mapTo(hashSetOf(), Track::id)
            for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId !in keep) player.removeMediaItem(i)
            playable.forEachIndexed { index, track ->
                val from = (index until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
                if (from == null) player.addMediaItem(index, track.mediaItem(state.value.profileId))
                else {
                    if (from != index) player.moveMediaItem(from, index)
                    if (player.getMediaItemAt(index) != track.mediaItem(state.value.profileId)) player.replaceMediaItem(index, track.mediaItem(state.value.profileId))
                }
            }
        } finally { updating = false }
        publish(); checkpoint()
    }

    private fun load(queue: List<Track>, index: Int, position: Int) {
        val player = engine ?: return
        updating = true
        try {
            player.pause()
            logicalQueue = queue
            waitingId = queue.getOrNull(index)?.takeIf { !it.available }?.id
            mutable.value = state.value.copy(queue = queue, index = index, positionSeconds = position.coerceIn(0, queue.getOrNull(index)?.durationSeconds ?: 0), playing = false, connected = true, error = null)
            val playable = queue.filter { it.available }
            if (waitingId != null || playable.isEmpty()) player.clearMediaItems()
            else player.setMediaItems(playable.map { it.mediaItem(state.value.profileId) }, playable.indexOfFirst { it.id == state.value.current?.id }.coerceAtLeast(0), state.value.positionSeconds.toLong() * 1000)
        } finally { updating = false }
        publish(); checkpoint()
    }

    private fun publish() {
        val player = engine ?: return
        val index = logicalQueue.indexOfFirst { it.id == (waitingId ?: player.currentMediaItem?.mediaId) }.coerceAtLeast(0)
        mutable.value = state.value.copy(queue = logicalQueue, index = index,
            positionSeconds = if (waitingId != null) state.value.positionSeconds else (player.currentPosition.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            playing = waitingId == null && player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
            buffering = waitingId == null && player.playbackState == Player.STATE_BUFFERING, repeatMode = player.repeatMode.toRepeatMode(), shuffle = player.shuffleModeEnabled)
    }
    private fun checkpoint() {
        val state = state.value
        val editor = prefs.edit().putString("profile", state.profileId)
            .putString("repeat:${state.profileId}", state.repeatMode.name).putBoolean("shuffle:${state.profileId}", state.shuffle)
        val json = JSONObject().put("ids", JSONArray(state.queue.map(Track::id)))
            .put("current", state.current?.id).put("position", state.positionSeconds).put("followLibrary", followLibrary)
            .put("tracks", JSONArray(state.queue.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("source", it.source.name).put("duration", it.durationSeconds).put("tint", it.tint).put("album", it.album).put("artwork", it.artworkUri) }))
        editor.putString("queue:${state.profileId}", json.toString()).apply()
    }
}

private fun Track.mediaItem(profile: String) = MediaItem.Builder().setMediaId(id).setUri(if (source == Source.YANDEX)
    Uri.Builder().scheme("ymplayer2").authority("yandex").appendPath(profile).appendPath(id).build().toString() else uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setArtworkUri(artworkUri?.let(Uri::parse)).build()).build()
private fun RepeatMode.toPlayerMode() = when (this) { RepeatMode.OFF -> Player.REPEAT_MODE_OFF; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.ONE -> Player.REPEAT_MODE_ONE }
private fun Int.toRepeatMode() = when (this) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }
