package dev.petrov.ymplayer2.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Borrows AudioService's engine. Never creates a second player, service, token store or audio queue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidRadio(private val context: Context, private val music: AndroidPlayback, private val api: RadioApi,
    private val scope: CoroutineScope) : RadioAudio {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val launchState = LaunchStateStore(context)
    private var profileLoaded = false
    private val mutable = MutableStateFlow(RadioPlaybackState())
    override val state = mutable.asStateFlow()
    private var engine: ExoPlayer? = null
    private var generation = 0L
    private var work: Job? = null
    private var polling: Job? = null
    private var retry: Job? = null
    private var retryCount = 0
    private var wantsPlay = false
    private var pendingPlay = false
    private var region: String? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!state.value.ownsOutput) return
            mutable.value = state.value.copy(playing = player.isPlaying,
                buffering = wantsPlay && (work?.isActive == true || player.playbackState == Player.STATE_BUFFERING),
                reconnecting = retry?.isActive == true)
            if (player.isPlaying) { retryCount = 0; mutable.value = state.value.copy(issue = null) }
            if (wantsPlay && player.playbackState == Player.STATE_ENDED) reconnect()
        }
        override fun onPlayerError(error: PlaybackException) { if (state.value.ownsOutput && wantsPlay) reconnect() }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (state.value.ownsOutput && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && !playWhenReady) {
                // A permanent focus loss must not be undone by a network retry.
                wantsPlay = false; retry?.cancel(); work?.cancel(); polling?.cancel()
                launchState.write(state.value.profileId, PlaybackOutput.RADIO, false)
                mutable.value = state.value.copy(playing = false, buffering = false, reconnecting = false)
            }
        }
    }
    init { music.radio = this }
    internal fun attach(player: ExoPlayer) {
        engine = player; player.addListener(listener)
        if (pendingPlay) { pendingPlay = false; resolve() }
    }
    internal fun detach() {
        halt(persist = false); mutable.value = state.value.copy(ownsOutput = false)
        engine?.removeListener(listener); engine = null
    }
    override fun switchProfile(profile: String) {
        if (profile == state.value.profileId && profileLoaded) return
        release()
        profileLoaded = true
        val saved = runCatching { JSONObject(prefs.getString("station:$profile", "")!!) }.getOrNull()
        val station = saved?.let { runCatching { RadioStation(it.getString("slug"), it.getString("name"),
            it.optString("logo").takeIf(String::isNotBlank), it.optString("color").takeIf(String::isNotBlank),
            it.optString("stream").takeIf(String::isNotBlank), it.optString("region").takeIf(String::isNotBlank)) }.getOrNull() }
        mutable.value = RadioPlaybackState(profile, station)
    }
    override fun play(station: RadioStation?, region: String?) {
        val selected = station ?: return
        work?.cancel(); polling?.cancel(); retry?.cancel(); generation++
        this.region = region; retryCount = 0; wantsPlay = true
        music.yieldToRadio()
        mutable.value = RadioPlaybackState(state.value.profileId, selected, ownsOutput = true, buffering = true)
        saveStation(selected)
        launchState.write(state.value.profileId, PlaybackOutput.RADIO, true)
        if (engine == null) { pendingPlay = true; music.connect() } else resolve()
    }
    private fun resolve() {
        val selected = state.value.station ?: return
        val player = engine ?: return
        val ticket = generation
        work?.cancel()
        mutable.value = state.value.copy(playing = false, buffering = true, issue = null)
        work = scope.launch {
            try {
                val stream = api.stream(selected, region)
                if (ticket != generation || !wantsPlay || engine !== player) return@launch
                mutable.value = state.value.copy(station = stream.station, onAir = RadioOnAir(), issue = null)
                saveStation(stream.station)
                player.repeatMode = Player.REPEAT_MODE_OFF; player.shuffleModeEnabled = false
                player.setMediaItem(MediaItem.Builder().setMediaId("radio:${stream.station.slug}")
                    .setUri(stream.url).setMediaMetadata(metadata()).build())
                player.prepare(); player.play()
                poll(ticket, stream.station.slug, stream.streamSlug)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation) {
                    mutable.value = state.value.copy(playing = false, buffering = false, issue = e.radioIssue())
                    if (e.radioIssue() == RadioIssue.NETWORK) reconnect()
                    else wantsPlay = false
                }
            }
        }
    }
    private fun poll(ticket: Long, station: String, stream: String) {
        polling?.cancel()
        polling = scope.launch {
            while (isActive && ticket == generation && wantsPlay) {
                var pause = 30_000L
                try {
                    val now = api.onAir(station, stream)
                    if (ticket != generation) return@launch
                    mutable.value = state.value.copy(onAir = now)
                    pause = now.pollAfterMs
                    engine?.let { player -> player.currentMediaItem?.takeIf { it.mediaId == "radio:$station" }?.let { item ->
                        player.replaceMediaItem(0, item.buildUpon().setMediaMetadata(metadata()).build())
                    } }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (ticket == generation) mutable.value = state.value.copy(onAir = RadioOnAir()) }
                delay(pause.coerceIn(5_000, 60_000))
            }
        }
    }
    private fun reconnect() {
        if (!wantsPlay || retry?.isActive == true) return
        if (retryCount >= 5) {
            wantsPlay = false; polling?.cancel()
            mutable.value = state.value.copy(playing = false, buffering = false, reconnecting = false, issue = RadioIssue.NETWORK)
            return
        }
        val ticket = generation
        val wait = (2000L shl retryCount++).coerceAtMost(30_000)
        mutable.value = state.value.copy(playing = false, buffering = false, reconnecting = true, issue = RadioIssue.NETWORK)
        retry = scope.launch { delay(wait); if (ticket == generation && wantsPlay) { retry = null; resolve() } }
    }
    override fun stop() = halt(persist = true)
    private fun halt(persist: Boolean) {
        if (persist && state.value.ownsOutput) launchState.write(state.value.profileId, PlaybackOutput.RADIO, false)
        generation++; wantsPlay = false; pendingPlay = false
        work?.cancel(); polling?.cancel(); retry?.cancel(); work = null; polling = null; retry = null
        if (state.value.ownsOutput) engine?.let { it.stop(); it.clearMediaItems() }
        mutable.value = state.value.copy(playing = false, buffering = false, reconnecting = false, issue = null, onAir = RadioOnAir())
    }
    /** Used by music and clips before taking the output; stopping alone keeps the station resumable. */
    fun release() { stop(); mutable.value = state.value.copy(ownsOutput = false) }
    fun restoreOnLaunch(profile: String, playing: Boolean) {
        switchProfile(profile)
        val station = state.value.station ?: return
        if (playing) play(station, station.regionName) else {
            music.yieldToRadio()
            mutable.value = state.value.copy(ownsOutput = true)
        }
    }
    fun saveForExit() { prefs.edit().commit(); launchState.flush() }
    private fun metadata(): MediaMetadata {
        val station = state.value.station
        val onAir = listOf(state.value.onAir.title, state.value.onAir.artist)
            .filter(String::isNotBlank).joinToString(" · ")
        return MediaMetadata.Builder().setTitle(station?.name).setArtist(onAir).setAlbumTitle(station?.name)
            .setDisplayTitle(station?.name).setSubtitle(onAir).setDescription(station?.regionName)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).setIsPlayable(true)
            .setArtworkUri(station?.logoUri?.let(Uri::parse)).build()
    }
    private fun saveStation(station: RadioStation) {
        val data = JSONObject().put("slug", station.slug).put("name", station.name).put("logo", station.logoUri.orEmpty())
            .put("color", station.logoColor.orEmpty()).put("stream", station.streamSlug.orEmpty()).put("region", station.regionName.orEmpty())
        prefs.edit().putString("station:${state.value.profileId}", data.toString()).apply()
    }
}
