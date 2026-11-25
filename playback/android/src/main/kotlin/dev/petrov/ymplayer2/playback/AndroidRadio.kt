package dev.petrov.ymplayer2.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Borrows AudioService's engine. Never creates a second player, service, token store or audio queue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidRadio(private val context: Context, private val music: AndroidPlayback, private val api: RadioApi,
    private val scope: CoroutineScope, private val diagnostic: (OperationDiagnostic) -> Unit = {}) : RadioAudio {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val launchState = LaunchStateStore(context)
    private var profileLoaded = false
    private val mutable = MutableStateFlow(RadioPlaybackState())
    override val state = mutable.asStateFlow()
    private var engine: ExoPlayer? = null
    private var generation = 0L
    val launchRevision: Long get() = generation
    private var work: Job? = null
    private var polling: Job? = null
    private var retry: Job? = null
    private var pageWork: Job? = null
    private var lineup: RadioLineup? = null
    private var savedLineup: RadioLineup? = null
    private var stationWithoutSavedList = false
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
        override fun onPlayerError(error: PlaybackException) {
            if (state.value.ownsOutput) diagnostic(mediaFailureDiagnostic(DiagnosticOperation.RADIO_PLAYBACK, error))
            if (state.value.ownsOutput && wantsPlay) reconnect()
        }
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
        val station = saved?.let { runCatching { it.station() }.getOrNull() }
        lineup = runCatching {
            val data = JSONObject(prefs.getString("lineup:$profile", null) ?: return@runCatching null)
            val rows = data.getJSONArray("stations")
            val stations = (0 until rows.length()).map { rows.getJSONObject(it).station() }
            val index = saved?.optInt("index", -1) ?: -1
            if (station == null || stations.getOrNull(index)?.slug != station.slug) return@runCatching null
            RadioLineup(RadioListSource(RadioListKind.valueOf(data.getString("kind")), data.optString("value")),
                stations, index, data.optString("cursor").takeIf(String::isNotBlank))
        }.getOrNull()
        savedLineup = lineup
        stationWithoutSavedList = station != null && lineup == null
        region = lineup?.source?.region
        mutable.value = RadioPlaybackState(profile, station, canPrevious = lineup?.canPrevious == true, canNext = lineup?.canNext == true)
    }
    override fun play(station: RadioStation?, region: String?, lineup: RadioLineup?) {
        val selected = station ?: return
        if (lineup != null || selected.slug != state.value.station?.slug) stationWithoutSavedList = false
        pageWork?.cancel(); pageWork = null
        work?.cancel(); polling?.cancel(); retry?.cancel(); generation++
        this.lineup = lineup ?: this.lineup?.takeIf { selected.slug == state.value.station?.slug &&
            (selected.streamSlug == null || selected.streamSlug == state.value.station?.streamSlug) }
        this.region = this.lineup?.source?.region ?: region
        retryCount = 0; wantsPlay = true
        music.yieldToRadio()
        select(selected, buffering = true)
        launchState.write(state.value.profileId, PlaybackOutput.RADIO, true)
        if (engine == null) { pendingPlay = true; music.connect() } else resolve()
    }
    private fun select(station: RadioStation, buffering: Boolean) {
        mutable.value = RadioPlaybackState(state.value.profileId, station, ownsOutput = true, buffering = buffering,
            canPrevious = lineup?.canPrevious == true, canNext = lineup?.canNext == true)
        saveStation(station)
        music.radioCommandsChanged()
    }
    override fun attachRestoredList(lineup: RadioLineup) {
        if (!stationWithoutSavedList || this.lineup != null) return
        val station = state.value.station ?: return
        if (lineup.stations.getOrNull(lineup.index)?.slug != station.slug) return
        this.lineup = lineup
        stationWithoutSavedList = false
        region = lineup.source.region
        mutable.value = state.value.copy(canPrevious = lineup.canPrevious, canNext = lineup.canNext)
        saveStation(station)
        music.radioCommandsChanged()
    }

    override fun skip(direction: Int) {
        val current = lineup ?: return
        if (!state.value.ownsOutput || direction == 0) return
        val index = current.index + direction.compareTo(0)
        if (index in current.stations.indices) {
            pageWork?.cancel(); pageWork = null
            tune(current.copy(index = index))
            return
        }
        val cursor = current.cursor ?: return
        if (direction < 0 || pageWork?.isActive == true) return
        val ticket = generation
        val profile = state.value.profileId
        pageWork = scope.launch {
            try {
                val page = current.source.page(api, profile, cursor)
                if (ticket != generation || lineup !== current) return@launch
                val extended = current.append(page)
                if (index in extended.stations.indices) tune(extended.copy(index = index))
                else {
                    lineup = extended
                    mutable.value = state.value.copy(canNext = false, listIssue = null)
                    state.value.station?.let(::saveStation)
                    music.radioCommandsChanged()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation && lineup === current) {
                    diagnostic(diagnosticFailure(DiagnosticOperation.RADIO_STATIONS, e))
                    mutable.value = state.value.copy(listIssue = e.radioIssue())
                }
            }
        }
    }
    private fun tune(next: RadioLineup) {
        val station = next.stations[next.index]
        if (wantsPlay) play(station, next.source.region, next)
        else {
            halt(persist = true)
            lineup = next
            region = next.source.region
            select(station, buffering = false)
        }
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
                diagnostic(diagnosticFailure(DiagnosticOperation.RADIO_STREAM, e))
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
                catch (e: Exception) { diagnostic(diagnosticFailure(DiagnosticOperation.RADIO_ON_AIR, e)); if (ticket == generation) mutable.value = state.value.copy(onAir = RadioOnAir()) }
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
        pageWork?.cancel(); pageWork = null
        work?.cancel(); polling?.cancel(); retry?.cancel(); work = null; polling = null; retry = null
        if (state.value.ownsOutput) engine?.let { it.stop(); it.clearMediaItems() }
        mutable.value = state.value.copy(playing = false, buffering = false, reconnecting = false, issue = null, onAir = RadioOnAir())
    }
    /** Used by music and clips before taking the output; stopping alone keeps the station resumable. */
    fun release() { stop(); mutable.value = state.value.copy(ownsOutput = false) }
    fun restoreOnLaunch(profile: String, playing: Boolean) {
        switchProfile(profile)
        val station = state.value.station ?: return
        if (playing) play(station) else {
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
        val profile = state.value.profileId
        val list = lineup
        val edit = prefs.edit().putString("station:$profile", station.json().put("index", list?.index).toString())
        if (list?.stations !== savedLineup?.stations || list?.source != savedLineup?.source || list?.cursor != savedLineup?.cursor) {
            if (list == null) edit.remove("lineup:$profile")
            else edit.putString("lineup:$profile", JSONObject().put("kind", list.source.kind.name).put("value", list.source.value)
                .put("cursor", list.cursor).put("stations", JSONArray(list.stations.map { it.json() })).toString())
        }
        edit.apply()
        savedLineup = list
    }
}

private fun RadioStation.json() = JSONObject().put("slug", slug).put("name", name).put("logo", logoUri)
    .put("color", logoColor).put("stream", streamSlug).put("region", regionName)
private fun JSONObject.station() = RadioStation(getString("slug"), getString("name"),
    optString("logo").takeIf(String::isNotBlank), optString("color").takeIf(String::isNotBlank),
    optString("stream").takeIf(String::isNotBlank), optString("region").takeIf(String::isNotBlank))
