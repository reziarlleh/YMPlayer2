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

    fun connect() { context.startService(Intent(context, AudioService::class.java)) }

    internal fun attach(player: ExoPlayer) {
        check(engine == null)
        engine = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!updating && ready) { publish(); checkpoint() } }
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
        val next = state.value.index + direction.compareTo(0)
        if (next in state.value.queue.indices) {
            mutable.value = state.value.copy(error = null)
            engine?.seekTo(next, 0); engine?.prepare()
        } else engine?.pause()
    }
    override fun select(trackId: String) = command {
        val tracks = library.tracks(state.value.profileId).filter { it.available }
        if (tracks.none { it.id == trackId }) return@command
        val queue = state.value.queue.takeIf { it.any { track -> track.id == trackId } } ?: tracks
        load(queue, queue.indexOfFirst { it.id == trackId }, 0)
        engine?.prepare(); engine?.play()
    }
    override fun switchProfile(profileId: String) = command {
        if (profileId == state.value.profileId || library.profiles.none { it.id == profileId }) return@command
        engine?.pause(); publish(); checkpoint()
        restore(profileId)
    }
    override fun chooseSource(source: Source?) = command {
        prefs.edit().putString("source:${state.value.profileId}", source?.name).apply()
        load(defaultQueue(state.value.profileId), 0, 0)
    }

    private fun defaultQueue(profile: String): List<Track> {
        val source = prefs.getString("source:$profile", null)
        return library.tracks(profile).filter { it.available && (source == null || it.source.name == source) }
    }

    private fun restore(profile: String) {
        val validProfile = profile.takeIf { id -> library.profiles.any { it.id == id } } ?: "owner"
        mutable.value = state.value.copy(profileId = validProfile, error = null)
        val json = runCatching { JSONObject(prefs.getString("queue:$validProfile", "")!!) }.getOrNull()
        val allowed = library.tracks(validProfile).filter { it.available }.associateBy(Track::id)
        val ids = json?.optJSONArray("ids")
        val restored = if (ids == null) defaultQueue(validProfile) else (0 until ids.length()).mapNotNull { allowed[ids.optString(it)] }.distinctBy(Track::id)
        val queue = if (restored.isEmpty()) defaultQueue(validProfile) else restored
        val current = json?.optString("current")
        val index = queue.indexOfFirst { it.id == current }.coerceAtLeast(0)
        val position = if (queue.getOrNull(index)?.id == current) json?.optInt("position", 0) ?: 0 else 0
        load(queue, index, position)
    }

    private fun reconcile() {
        val state = state.value
        val allowed = library.tracks(state.profileId).filter { it.available }.associateBy(Track::id)
        val next = state.queue.mapNotNull { allowed[it.id] }.ifEmpty { defaultQueue(state.profileId) }
        if (next == state.queue) return
        // A rescan may change titles; preserve audio only when its file is still available.
        val resume = state.playing && next.any { it.id == state.current?.id }
        val index = next.indexOfFirst { it.id == state.current?.id }.coerceAtLeast(0)
        val position = if (next.getOrNull(index)?.id == state.current?.id) state.positionSeconds else 0
        load(next, index, position)
        if (resume) { engine?.prepare(); engine?.play() }
    }

    private fun load(queue: List<Track>, index: Int, position: Int) {
        val player = engine ?: return
        updating = true
        player.pause()
        mutable.value = PlaybackState(state.value.profileId, queue, index, position.coerceIn(0, queue.getOrNull(index)?.durationSeconds ?: 0))
        if (queue.isEmpty()) player.clearMediaItems()
        else player.setMediaItems(queue.map { track ->
            MediaItem.Builder().setMediaId(track.id).setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()).build()
        }, index, state.value.positionSeconds.toLong() * 1000)
        updating = false
        publish(); checkpoint()
    }

    private fun publish() {
        val player = engine ?: return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        mutable.value = state.value.copy(index = index, positionSeconds = (player.currentPosition.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
            buffering = player.playbackState == Player.STATE_BUFFERING)
    }
    private fun checkpoint() {
        val state = state.value
        if (state.queue.isEmpty() && library.state.value.tracks.any { !it.available }) return
        val json = JSONObject().put("ids", JSONArray(state.queue.map(Track::id)))
            .put("current", state.current?.id).put("position", state.positionSeconds)
        prefs.edit().putString("profile", state.profileId).putString("queue:${state.profileId}", json.toString()).apply()
    }
}
