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

enum class AudioServiceEvent { CREATED, DESTROYED, RESUMPTION_REQUESTED, RESUMPTION_AVAILABLE }
interface PlaybackHost {
    val playback: AndroidPlayback
    fun onAudioServiceEvent(event: AudioServiceEvent) = Unit
}

/** Main-thread command adapter. The service alone creates/releases the audio engine. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidPlayback(private val context: Context, private val library: LocalLibrary, private val scope: CoroutineScope, private val online: OnlineMusic? = null,
    private val taste: MusicTaste? = null, private val waveApi: MyWaveApi? = null, private val offline: OfflineMusic? = null,
    private val streamQuality: () -> AudioQuality = { AudioQuality.AUTO }) : PlaybackController {
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
    private var logicalQueue = emptyList<Track>()
    private var waitingId: String? = null
    private var waveBatch: WaveBatch? = null
    private val waveItems = linkedMapOf<String, WaveTrack>()
    private var waveJob: Job? = null
    private var waveGeneration = 0L
    private var waveAdvance = false
    private var waveResume = false
    private var waveStarted: String? = null
    private var pendingWaveBatch: WaveBatch? = null
    private val waveRecovery = WaveRecovery()
    private val waveAudio = WaveAudioBuffer(context)
    private var failedWaveTracks = 0
    private var waveTrackErrorJob: Job? = null
    private var wavePausePending = false

    fun connect() { context.startService(Intent(context, AudioService::class.java)) }
    fun audioSessionId(): Int = engine?.audioSessionId ?: 0

    override suspend fun queuePage(offset: Int, limit: Int): CatalogPage<Track> {
        require(offset >= 0 && limit in 1..500 && offset <= Int.MAX_VALUE - limit)
        val indexed = library as? IndexedLocalLibrary ?: return super<PlaybackController>.queuePage(offset, limit)
        if (!followLibrary) return super<PlaybackController>.queuePage(offset, limit)
        val profile = state.value.profileId
        val source = prefs.getString("source:$profile", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        val revision = indexed.indexRevision
        val page = indexed.pageTracks(CatalogFilter(source = source), offset = offset, limit = limit)
        if (!followLibrary || state.value.profileId != profile)
            return super<PlaybackController>.queuePage(offset, limit)
        if (indexed.indexRevision != revision || prefs.getString("source:$profile", null) != source?.name) {
            val currentSource = prefs.getString("source:$profile", null)
                ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            return indexed.pageTracks(CatalogFilter(source = currentSource), offset = offset, limit = limit)
        }
        return page
    }

    /** A media-button receiver may create the service before the local catalog has loaded.
     * Only describe the saved item here; the normal profile-scoped restore resolves its URI. */
    internal fun resumptionItem(): MediaItem? {
        val profile = state.value.profileId
        val saved = runCatching { JSONObject(prefs.getString("queue:$profile", "")!!) }.getOrNull() ?: return null
        val currentId = saved.optString("current")
        val rows = saved.optJSONArray("tracks")
        val item = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == currentId }
        val wave = saved.optBoolean("wave", false)
        if (item == null && !wave) return null
        val title = item?.optString("title")?.takeIf { it.isNotBlank() } ?: "Моя волна"
        val artist = item?.optString("artist")?.takeIf { it.isNotBlank() }
        return MediaItem.Builder().setMediaId(BrowserSources.RESUME)
            .setUri("ymplayer2://browser/${BrowserSources.RESUME}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setIsPlayable(true).build()).build()
    }

    internal fun attach(player: ExoPlayer) {
        check(engine == null)
        engine = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!updating && !restoring && ready) {
                publish()
                if (usesBoundedLocalWindow() && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) syncQueue(logicalQueue)
                checkpoint(); maintainWave()
            } }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && state.value.wave && !updating && !restoring) {
                    val id = player.currentMediaItem?.mediaId
                    if (id != null && id != waveStarted) { waveStarted = id; feedback(id, WaveFeedback.STARTED) }
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val old = oldPosition.mediaItem?.mediaId
                if (!updating && !restoring && old != newPosition.mediaItem?.mediaId && old == waveStarted) {
                    feedback(old, if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) WaveFeedback.FINISHED else WaveFeedback.SKIP,
                        (oldPosition.positionMs / 1000).toInt())
                    waveStarted = null
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Media3 can deliver our internal pause after the surrounding command returns.
                if (!playWhenReady && wavePausePending && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    wavePausePending = false
                    return
                }
                if (!updating && !restoring && state.value.wave && waveAdvance) waveResume = playWhenReady
            }
            override fun onPlayerError(error: PlaybackException) {
                val remote = state.value.current?.source == Source.YANDEX
                val failure = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<MusicException>().firstOrNull()?.failure
                if (remote && state.value.wave && failure !in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS) && failedWaveTracks++ < 3) {
                    recoverWaveTrack()
                    return
                }
                mutable.value = state.value.copy(playing = false, buffering = false, error = if (remote)
                    failure?.message() ?: "Не удалось воспроизвести музыку Яндекса. Проверьте сеть и доступ к треку; нажмите воспроизведение для повтора."
                    else "Не удалось воспроизвести файл. Проверьте носитель или выберите другой трек.")
                if (!remote) scope.launch { library.refresh() }
            }
        })
        job = scope.launch {
            online?.let { music -> launch { music.catalog.collect { reconcileOnline() } } }
            offline?.let { cache -> launch { cache.state.collect { if (ready && !restoring) reconcileOnline() } } }
            taste?.let { preferences -> launch { preferences.state.collect { filterWave() } } }
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
            while (isActive) { delay(500); if (ready) { publish(); maintainWave() }; if (state.value.playing && state.value.positionSeconds % 5 == 0) checkpoint() }
        }
    }

    internal fun detach() {
        if (ready) { publish(); checkpoint() }
        cancelWave()
        job?.cancel(); job = null; engine = null; ready = false
        mutable.value = state.value.copy(playing = false, buffering = false, connected = false)
    }

    private fun command(action: () -> Unit) {
        if (ready && engine != null) action()
        else { pending = action; connect() }
    }

    override fun toggle() = command {
        val player = engine ?: return@command
        if (state.value.wave && waveAdvance && state.value.waveLoading) {
            waveResume = !waveResume
            if (!waveResume) player.pause()
            publish(); return@command
        }
        if (state.value.wave && (state.value.current == null || state.value.waveIssue != null && player.playbackState == Player.STATE_ENDED)) {
            waveResume = true; waveAdvance = true; fetchWave(); return@command
        }
        if (state.value.current?.available != true) return@command
        mutable.value = state.value.copy(error = null)
        if (player.playWhenReady && player.playerError == null) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.prepare(); player.play()
        }
    }
    override fun stop() = command {
        val player = engine ?: return@command
        player.pause()
        if (state.value.wave) {
            // Stop cancels audio/network work, not the selected station. A stopped wave
            // must never become a finite, shuffleable copy of its current batch.
            val upcoming = logicalQueue.getOrNull(state.value.index + 1)?.let { waveItems[it.id] }
            val pending = upcoming?.let { waveBatch?.copy(tracks = listOf(it)) } ?: pendingWaveBatch
            cancelWaveWork()
            pendingWaveBatch = pending
            waveAdvance = state.value.current == null
            // Discard audio, retaining metadata. Resume must prepare the next full file again.
            load(logicalQueue, state.value.index, 0)
        } else {
            cancelWave()
            mutable.value = state.value.copy(positionSeconds = 0)
            player.seekTo(0)
        }
        player.stop(); publish(); checkpoint()
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
            usesBoundedLocalWindow() && skipOutsideWindow(direction, player) -> return@command
            direction > 0 && state.value.wave -> {
                feedback(waveStarted, WaveFeedback.SKIP, state.value.positionSeconds); waveStarted = null
                waveAdvance = true; waveResume = player.playWhenReady
                pauseForWaveWait(player)
                fetchWave(); return@command
            }
            else -> player.pause()
        }
        player.prepare(); publish(); checkpoint()
    }
    override fun select(trackId: String) = command {
        val index = logicalQueue.indexOfFirst { it.id == trackId }
        if (index >= 0 && !logicalQueue[index].available) return@command
        val tracks = if (index < 0) knownTracks(state.value.profileId).filter { it.available } else emptyList()
        if (index < 0 && tracks.none { it.id == trackId }) return@command
        mutable.value = state.value.copy(error = null)
        if (index >= 0) {
            waveAdvance = false
            waveTrackErrorJob?.cancel(); waveTrackErrorJob = null
            val player = engine ?: return@command
            val mediaIndex = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == trackId }
            if (waitingId != null || mediaIndex == null) load(logicalQueue, index, 0)
            else player.seekTo(mediaIndex, 0)
        }
        else {
            cancelWave()
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
        cancelWave()
        restore(profileId)
    }
    override fun chooseSource(source: Source?) = command {
        cancelWave()
        prefs.edit().putString("source:${state.value.profileId}", source?.name).apply()
        followLibrary = true
        val queue = defaultQueue(state.value.profileId)
        load(queue, queue.indexOfFirst { it.available }.coerceAtLeast(0), 0)
    }

    override fun setRepeatMode(mode: RepeatMode) = command { if (!state.value.wave) {
        val bounded = usesBoundedLocalWindow()
        engine?.repeatMode = mode.toPlayerMode()
        if (bounded != usesBoundedLocalWindow()) syncQueue(logicalQueue) else { publish(); checkpoint() }
    } }
    override fun setShuffle(enabled: Boolean) = command { if (!state.value.wave) {
        val bounded = usesBoundedLocalWindow()
        engine?.shuffleModeEnabled = enabled
        if (bounded != usesBoundedLocalWindow()) syncQueue(logicalQueue) else { publish(); checkpoint() }
    } }
    override fun enqueue(trackId: String) = command {
        val track = knownTracks(state.value.profileId).find { it.id == trackId && it.available } ?: return@command
        if (logicalQueue.any { it.id == trackId }) return@command
        cancelWave()
        followLibrary = false
        syncQueue(logicalQueue + track)
    }
    override fun moveInQueue(trackId: String, toIndex: Int) = command {
        val from = logicalQueue.indexOfFirst { it.id == trackId }
        if (from < 0 || toIndex !in logicalQueue.indices || from == toIndex) return@command
        cancelWave()
        followLibrary = false
        syncQueue(logicalQueue.toMutableList().apply { add(toIndex, removeAt(from)) })
    }
    override fun removeFromQueue(trackId: String) = command {
        val index = logicalQueue.indexOfFirst { it.id == trackId }
        if (index < 0) return@command
        cancelWave()
        followLibrary = false
        // Removing the current item must not unexpectedly start its successor.
        if (state.value.current?.id == trackId) { engine?.pause(); mutable.value = state.value.copy(error = null) }
        syncQueue(logicalQueue.filterNot { it.id == trackId })
    }
    override fun clearQueue() = command {
        cancelWave()
        followLibrary = false
        mutable.value = state.value.copy(error = null)
        load(emptyList(), 0, 0)
    }
    override fun playQueue(trackIds: List<String>, startId: String?) = command {
        val known = knownTracks(state.value.profileId).associateBy(Track::id)
        val queue = trackIds.distinct().mapNotNull { known[it]?.takeIf(Track::available) }
        if (queue.isEmpty()) return@command
        cancelWave()
        followLibrary = false
        load(queue, queue.indexOfFirst { it.id == startId }.coerceAtLeast(0), 0)
        engine?.prepare(); engine?.play()
    }

    override fun playMyWave() = command {
        if (waveApi == null || !remoteEnabled(state.value.profileId)) return@command
        cancelWave(); followLibrary = false
        engine?.repeatMode = Player.REPEAT_MODE_OFF; engine?.shuffleModeEnabled = false
        load(emptyList(), 0, 0)
        mutable.value = state.value.copy(wave = true)
        waveAdvance = true; waveResume = true
        fetchWave()
    }
    internal fun playOfflineLikes() = command {
        val profile = state.value.profileId
        val tracks = offline?.tracks(profile)?.filter { it.available && it.offline }.orEmpty()
        if (tracks.isEmpty()) return@command
        cancelWave(); followLibrary = false
        load(tracks, 0, 0)
        engine?.prepare(); engine?.play()
    }
    override fun playRecommendedQueue(trackIds: List<String>, startId: String?) = command {
        if (trackIds.none { id -> knownTracks(state.value.profileId).any { it.id == id && it.available } }) return@command
        playQueue(trackIds, startId)
        mutable.value = state.value.copy(recommendations = true)
        filterWave(); checkpoint()
    }
    override fun retryWave() = command {
        if (!state.value.wave) return@command
        waveRecovery.reset(); failedWaveTracks = 0
        fetchWave()
    }
    internal fun sessionPlay() = command {
        waveResume = true
        if (state.value.wave && (waveAdvance || state.value.current == null)) {
            waveAdvance = true
            if (!state.value.waveLoading) fetchWave()
            publish()
        }
        else { engine?.prepare(); engine?.play() }
    }
    internal fun sessionPause() = command { waveResume = false; engine?.pause(); publish() }
    internal fun sessionRepeatMode(mode: Int) = setRepeatMode(mode.toRepeatMode())
    private fun cancelWaveWork() {
        waveGeneration++; waveJob?.cancel(); waveJob = null
        waveTrackErrorJob?.cancel(); waveTrackErrorJob = null
        pendingWaveBatch = null; waveRecovery.reset(); waveAudio.clear(); failedWaveTracks = 0; wavePausePending = false
        waveAdvance = false; waveResume = false; waveStarted = null
        mutable.value = state.value.copy(waveLoading = false, waveIssue = null)
    }
    private fun cancelWave() {
        cancelWaveWork()
        waveBatch = null; waveItems.clear()
        mutable.value = state.value.copy(wave = false, recommendations = false)
    }
    private fun feedback(id: String?, type: WaveFeedback, seconds: Int = 0) {
        val api = waveApi ?: return
        val item = waveItems[id] ?: return
        val profile = state.value.profileId
        scope.launch { try { api.feedback(profile, item, type, seconds) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Feedback must not stop audio. */ } }
    }
    private fun maintainWave() {
        val player = engine ?: return
        if (!state.value.wave || updating || restoring || !ready || !remoteEnabled(state.value.profileId)) return
        if (waveTrackErrorJob?.isActive == true) return
        if (player.isPlaying && player.currentMediaItem?.mediaId != waveStarted) {
            waveStarted = player.currentMediaItem?.mediaId; feedback(waveStarted, WaveFeedback.STARTED)
        }
        if (player.isPlaying && player.currentPosition >= 1_000) failedWaveTracks = 0
        if (player.playbackState == Player.STATE_ENDED && player.playWhenReady && !waveAdvance) {
            feedback(waveStarted, WaveFeedback.FINISHED, state.value.positionSeconds); waveStarted = null
            waveAdvance = true; waveResume = true
            // Background prefetch failure must not consume the end-of-track recovery budget.
            if (state.value.waveIssue != null && waveRecovery.retryAtMillis == null && !waveRecovery.fatal) {
                waveRecovery.reset()
                mutable.value = state.value.copy(waveIssue = null)
            }
        }
        if (state.value.waveLoading) return
        val needed = waveAdvance && waveResume || player.isPlaying && !player.hasNextMediaItem()
        if (needed && (state.value.waveIssue == null || waveRecovery.due(android.os.SystemClock.elapsedRealtime()))) fetchWave()
    }
    private fun pauseForWaveWait(player: ExoPlayer) {
        if (player.playWhenReady) wavePausePending = true
        updating = true
        try { player.pause() } finally { updating = false }
    }
    private fun recoverWaveTrack() {
        val player = engine ?: return
        val ticket = waveGeneration; val failed = player.currentMediaItem?.mediaId
        feedback(waveStarted, WaveFeedback.SKIP, state.value.positionSeconds); waveStarted = null
        waveAdvance = true; waveResume = player.playWhenReady
        pauseForWaveWait(player)
        mutable.value = state.value.copy(error = null)
        waveTrackErrorJob?.cancel()
        waveTrackErrorJob = scope.launch {
            delay(300) // Same failed-track continuation boundary as 1.x, with cancellation/intent guards.
            if (ticket != waveGeneration || failed != player.currentMediaItem?.mediaId) return@launch
            if (player.hasNextMediaItem()) {
                val resume = waveResume
                waveAdvance = false; player.seekToNextMediaItem(); player.prepare()
                if (resume) player.play()
            } else if (!state.value.waveLoading) fetchWave()
        }
    }
    private fun fetchWave() {
        val api = waveApi ?: return
        if (!state.value.wave || state.value.waveLoading) return
        val ticket = waveGeneration; val profile = state.value.profileId
        waveRecovery.consume()
        mutable.value = state.value.copy(waveLoading = true, waveIssue = null)
        waveJob = scope.launch {
            try {
                taste?.requireRecommendationFilters(profile)
                var prepared: WaveBatch? = null
                for (attempt in 0..3) {
                    val candidate = pendingWaveBatch ?: WaveLoader(api).load(profile, waveBatch, waveItems.values.mapTo(hashSetOf()) { it.track.tasteTarget().key }) {
                        taste?.state?.value?.allows(it) != false
                    }.also { pendingWaveBatch = it }
                    val item = candidate.tracks.single()
                    try {
                        // Fast first track streams immediately. Every following track gets its own
                        // complete audio prefetch before joining Media3's playlist, so a bad future
                        // source cannot fail the loader of the track that is still playing.
                        waveAudio.retain(profile, setOfNotNull(state.value.current?.id, item.track.id))
                        val quality = streamQuality()
                        if (state.value.current != null) waveAudio.prepare(profile, item.track) { online!!.api.stream(profile, item.track.id, quality) }
                        ensureActive()
                        if (taste?.state?.value?.allows(item.track) == false) throw MusicException(MusicFailure.UNAVAILABLE)
                        prepared = candidate
                        break
                    } catch (e: MusicException) {
                        if (e.failure != MusicFailure.UNAVAILABLE) throw e
                        // Reject only this candidate; keep the current audio and advance the cursor.
                        waveItems[item.track.id] = item; waveBatch = candidate; pendingWaveBatch = null
                        waveAudio.retain(profile, setOfNotNull(state.value.current?.id))
                        if (attempt == 3) throw e
                    }
                }
                val batch = checkNotNull(prepared)
                ensureActive()
                if (ticket != waveGeneration || profile != state.value.profileId || !remoteEnabled(profile)) return@launch
                val first = waveBatch == null
                waveBatch = batch
                pendingWaveBatch = null
                batch.tracks.forEach { waveItems[it.track.id] = it }
                if (first) feedback(batch.tracks.first().track.id, WaveFeedback.RADIO_STARTED)
                val before = state.value.current?.id
                val resume = waveResume
                val advance = waveAdvance
                // Keep a small playback history; the dedup history is capped separately below.
                val next = (logicalQueue + batch.tracks.map(WaveTrack::track)).distinctBy(Track::id)
                syncQueue(next)
                if (advance) {
                    val target = if (before == null) 0 else next.indexOfFirst { it.id == before } + 1
                    waveAdvance = false
                    load(next, target.coerceAtMost(next.lastIndex), 0)
                    engine?.prepare(); if (resume) engine?.play()
                }
                if (state.value.index > 30) syncQueue(logicalQueue.drop(state.value.index - 10))
                while (waveItems.size > 4000) waveItems.remove(waveItems.keys.first())
                mutable.value = state.value.copy(waveLoading = false, waveIssue = null)
                waveRecovery.reset()
                checkpoint()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == waveGeneration) {
                    val failure = (e as? MusicException)?.failure ?: if (e is java.io.IOException) MusicFailure.NETWORK else MusicFailure.RESPONSE
                    val retry = waveRecovery.failed(android.os.SystemClock.elapsedRealtime(), failure)
                    mutable.value = state.value.copy(waveLoading = false,
                        waveIssue = "Моя волна: " + failure.message() + if (retry) " Повторим автоматически (${waveRecovery.attempts}/3)." else "")
                }
            }
        }
    }
    private fun filterWave() {
        val preferences = taste?.state?.value ?: return
        if (!ready || restoring || !(state.value.wave || state.value.recommendations) || preferences.profileId != state.value.profileId || !preferences.signedIn) return
        val next = logicalQueue.filter(preferences::allows)
        if (next == logicalQueue) return
        val blockedCurrent = state.value.current?.let { !preferences.allows(it) } == true
        val successor = logicalQueue.drop(state.value.index + 1).firstOrNull(preferences::allows)
        val resume = engine?.playWhenReady == true || waveAdvance && waveResume
        if (blockedCurrent) { feedback(state.value.current?.id, WaveFeedback.DISLIKE); waveStarted = null }
        syncQueue(next)
        if (blockedCurrent) {
            if (successor != null) { load(next, next.indexOf(successor), 0); engine?.prepare(); if (resume) engine?.play() }
            else if (state.value.wave) { waveAdvance = true; waveResume = resume; fetchWave() }
        }
    }

    private fun defaultQueue(profile: String): List<Track> {
        val source = prefs.getString("source:$profile", null)
        return library.tracks(profile).filter { source == null || it.source.name == source }
    }

    private fun PlaybackState.withQueueView(all: List<Track>, absoluteIndex: Int): PlaybackState {
        val indexed = library as? IndexedLocalLibrary
        if (followLibrary && indexed != null) {
            val from = (absoluteIndex - 4).coerceAtLeast(0)
            val until = (absoluteIndex + 25).coerceAtMost(all.size).coerceAtLeast(from)
            val source = prefs.getString("source:$profileId", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            return copy(queue = all.subList(from, until).toList(), index = absoluteIndex,
                queueOffset = from, queueTotal = all.size, currentTrack = all.getOrNull(absoluteIndex),
                automaticLocal = true, automaticSource = source, queueRevision = indexed.indexRevision)
        }
        return copy(queue = all, index = absoluteIndex, queueOffset = 0, queueTotal = null,
            currentTrack = null, automaticLocal = false, automaticSource = null, queueRevision = 0)
    }

    // The public queue keeps its full order for editing. Media3 needs only nearby
    // playable items while sequentially following the local library.
    private fun usesBoundedLocalWindow() = followLibrary && engine?.shuffleModeEnabled == false &&
        engine?.repeatMode != Player.REPEAT_MODE_ALL

    private fun playableQueue(queue: List<Track>, index: Int): List<Track> {
        if (!usesBoundedLocalWindow()) return queue.filterIndexed { i, track ->
            track.available && (!state.value.wave || i <= index || waveAudio.uri(state.value.profileId, track.id) != null)
        }
        val previous = ArrayDeque<Track>()
        for (i in index - 1 downTo 0) if (queue[i].available) {
            previous.addFirst(queue[i]); if (previous.size == 4) break
        }
        val upcoming = ArrayList<Track>(25)
        for (i in index.coerceAtLeast(0) until queue.size) if (queue[i].available) {
            upcoming += queue[i]; if (upcoming.size == 25) break
        }
        return previous + upcoming
    }

    private fun skipOutsideWindow(direction: Int, player: ExoPlayer): Boolean {
        val range = if (direction > 0) state.value.index + 1 until logicalQueue.size
            else state.value.index - 1 downTo 0
        val target = range.firstOrNull { logicalQueue[it].available } ?: return false
        val resume = player.playWhenReady
        load(logicalQueue, target, 0)
        player.prepare()
        if (resume) player.play()
        return true
    }

    private fun remoteEnabled(profile: String) = online?.catalog?.value?.let { it.profileId == profile && it.enabled } == true
    private fun knownTracks(profile: String): List<Track> = library.tracks(profile) + if (remoteEnabled(profile))
        (logicalQueue.filter { it.source == Source.YANDEX } + online!!.tracksForPlayback(profile) + offline?.tracks(profile).orEmpty())
            .associateBy(Track::id).values.map { offline?.decorate(profile, it) ?: it } else emptyList()

    /** Called on the Media3 loader thread, never the main thread. No token or signed URI enters a MediaItem. */
    internal fun resolveStream(profile: String, trackId: String): String {
        val music = online ?: throw java.io.IOException("Online source unavailable")
        if (profile == state.value.profileId && remoteEnabled(profile)) runBlocking { offline?.audio(profile, trackId) }?.let { return it }
        if (state.value.wave && profile == state.value.profileId && remoteEnabled(profile)) waveAudio.uri(profile, trackId)?.let { return it }
        val quality = streamQuality()
        return try { runBlocking { withTimeout(60_000) { music.api.stream(profile, trackId, quality) } } }
        catch (e: Exception) { throw java.io.IOException("Online source unavailable", e) }
    }
    internal fun bufferedWaveAudioIds() = waveAudio.ids()

    private fun restore(profile: String) {
        val validProfile = profile.takeIf { id -> library.profiles.any { it.id == id } } ?: "owner"
        // Main.immediate collectors may run inside StateFlow.value assignment. Read the target
        // checkpoint before notifying them, and do not reconcile an outgoing queue into that profile.
        val json = runCatching { JSONObject(prefs.getString("queue:$validProfile", "")!!) }.getOrNull()
        restoring = true
        try {
        cancelWave()
        logicalQueue = emptyList()
        mutable.value = state.value.copy(profileId = validProfile, queue = emptyList(), index = 0, positionSeconds = 0,
            playing = false, error = null, queueOffset = 0, queueTotal = null, currentTrack = null,
            automaticLocal = false, automaticSource = null, queueRevision = 0)
        online?.accounts?.activate(validProfile)
        val ids = json?.optJSONArray("ids")
        followLibrary = json?.optBoolean("followLibrary", ids == null || ids.length() == 0) ?: true
        val wanted = if (followLibrary) emptySet() else (0 until (ids?.length() ?: 0)).mapTo(hashSetOf()) { ids!!.optString(it) }
        val localById = if (wanted.isEmpty()) emptyMap() else library.tracks(validProfile).asSequence()
            .filter { it.id in wanted }.associateBy(Track::id)
        val references = json?.optJSONArray("tracks")
        val saved = (0 until (references?.length() ?: 0)).mapNotNull { index -> runCatching {
            val item = references!!.getJSONObject(index)
            if (item.getString("source") == Source.YANDEX.name) Track(item.getString("id"), item.getString("title"), item.getString("artist"),
                item.optString("album"), Source.YANDEX, item.getInt("duration"), false, available = remoteEnabled(validProfile),
                genre = "", folder = "Яндекс Музыка", artworkUri = item.optString("artwork").takeIf { it.isNotBlank() },
                artists = item.optJSONArray("artists")?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).let { ArtistRef(it.getString("id"), it.getString("name")) } } }.orEmpty(),
                albumId = item.optString("albumId").takeIf { it.isNotBlank() })
            else SavedTrack(item.getString("id"), item.getString("title"), item.getString("artist"), Source.valueOf(item.getString("source")), item.getInt("duration"), item.getInt("tint")).resolve(localById)
        }.getOrNull() }.associateBy(Track::id)
        val queue = if (followLibrary) defaultQueue(validProfile) else (0 until (ids?.length() ?: 0)).mapNotNull { localById[ids?.optString(it)] ?: saved[ids?.optString(it)] }.distinctBy(Track::id)
        engine?.repeatMode = runCatching { RepeatMode.valueOf(prefs.getString("repeat:$validProfile", "OFF")!!) }.getOrDefault(RepeatMode.OFF).toPlayerMode()
        engine?.shuffleModeEnabled = prefs.getBoolean("shuffle:$validProfile", false)
        val current = json?.optString("current")
        val index = queue.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: queue.indexOfFirst { it.available }.coerceAtLeast(0)
        val position = if (queue.getOrNull(index)?.id == current) json?.optInt("position", 0) ?: 0 else 0
        mutable.value = state.value.copy(recommendations = json?.optBoolean("recommendations", false) == true)
        if (json?.optBoolean("wave", false) == true && waveApi != null) {
            val batches = json.optJSONObject("waveBatches")
            queue.forEach { waveItems[it.id] = WaveTrack(it, batches?.optString(it.id).orEmpty()) }
            waveBatch = WaveBatch(emptyList(), json.optString("waveSession"), json.optString("waveCursor"))
                .takeUnless { queue.isEmpty() && it.sessionId.isBlank() && it.cursor.isBlank() }
            queue.getOrNull(index + 1)?.let { next ->
                pendingWaveBatch = waveBatch!!.copy(tracks = listOf(waveItems.getValue(next.id)), cursor = next.tasteTarget().key)
            }
            mutable.value = state.value.copy(wave = true)
            engine?.repeatMode = Player.REPEAT_MODE_OFF; engine?.shuffleModeEnabled = false
        }
        load(queue, index, position)
        } finally { restoring = false }
        reconcileOnline()
    }

    private fun reconcileOnline() {
        val remote = online?.catalog?.value ?: return
        if (!ready || restoring || remote.profileId != state.value.profileId) return
        if (remote.phase in setOf(AuthPhase.SIGNED_OUT, AuthPhase.GUEST, AuthPhase.UNCONFIGURED, AuthPhase.ERROR)) {
            cancelWave()
            val keep = logicalQueue.filter { it.source != Source.YANDEX }
            if (keep != logicalQueue) syncQueue(keep)
        } else reconcile()
    }

    private fun reconcile() {
        val state = state.value
        val wanted = if (followLibrary) emptySet() else logicalQueue.mapTo(hashSetOf(), Track::id)
        val localById = if (wanted.isEmpty()) emptyMap() else library.tracks(state.profileId).asSequence()
            .filter { it.id in wanted }.associateBy(Track::id)
        val remoteById = if (wanted.isEmpty()) emptyMap() else online?.catalog?.value
            ?.takeIf { it.profileId == state.profileId && it.enabled }?.tracks.orEmpty().asSequence()
            .filter { it.id in wanted }.associateBy(Track::id)
        val tracksById = localById + remoteById
        val next = if (followLibrary) defaultQueue(state.profileId) else logicalQueue.map { track ->
            if (track.source == Source.YANDEX) (tracksById[track.id] ?: track.copy(available = true, offline = false)).let {
                val decorated = offline?.decorate(state.profileId, it) ?: it
                decorated.copy(available = decorated.available && remoteEnabled(state.profileId))
            }
            else tracksById[track.id] ?: track.copy(available = false, uri = null)
        }
        if (next == logicalQueue) return
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
        val playable = playableQueue(next, index)
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
            mutable.value = state.value.withQueueView(queue, index).copy(
                positionSeconds = position.coerceIn(0, queue.getOrNull(index)?.durationSeconds ?: 0),
                playing = false, connected = true, error = null)
            val playable = playableQueue(queue, index)
            if (waitingId != null || playable.isEmpty()) player.clearMediaItems()
            else player.setMediaItems(playable.map { it.mediaItem(state.value.profileId) }, playable.indexOfFirst { it.id == state.value.current?.id }.coerceAtLeast(0), state.value.positionSeconds.toLong() * 1000)
        } finally { updating = false }
        publish(); checkpoint()
    }

    private fun publish() {
        val player = engine ?: return
        val index = logicalQueue.indexOfFirst { it.id == (waitingId ?: player.currentMediaItem?.mediaId) }.coerceAtLeast(0)
        mutable.value = state.value.withQueueView(logicalQueue, index).copy(
            positionSeconds = if (waitingId != null) state.value.positionSeconds else (player.currentPosition.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            playing = if (state.value.wave && waveAdvance && state.value.waveLoading) waveResume else waitingId == null &&
                player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                player.playbackState != Player.STATE_ENDED && player.playerError == null,
            buffering = waitingId == null && player.playbackState == Player.STATE_BUFFERING, repeatMode = player.repeatMode.toRepeatMode(), shuffle = player.shuffleModeEnabled)
    }
    private fun checkpoint() {
        val state = state.value
        val editor = prefs.edit().putString("profile", state.profileId)
            .putString("repeat:${state.profileId}", state.repeatMode.name).putBoolean("shuffle:${state.profileId}", state.shuffle)
        // An automatically followed library is reconstructed from its index. Save only
        // the current item's label for media-button resumption before that index loads.
        val references = if (followLibrary) listOfNotNull(state.current) else state.queue
        val json = JSONObject().put("ids", JSONArray(if (followLibrary) emptyList<String>() else state.queue.map(Track::id)))
            .put("current", state.current?.id).put("position", state.positionSeconds).put("followLibrary", followLibrary)
            .put("tracks", JSONArray(references.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("source", it.source.name).put("duration", it.durationSeconds).put("tint", it.tint).put("album", it.album).put("artwork", it.artworkUri)
                .put("albumId", it.albumId).put("artists", JSONArray(it.artists.map { artist -> JSONObject().put("id", artist.id).put("name", artist.name) })) }))
            .put("wave", state.wave).put("waveSession", waveBatch?.sessionId).put("waveCursor", waveBatch?.cursor)
            .put("recommendations", state.recommendations)
            .put("waveBatches", JSONObject().apply { if (state.wave) state.queue.forEach { track -> put(track.id, waveItems[track.id]?.batchId) } })
        editor.putString("queue:${state.profileId}", json.toString()).apply()
    }
}

private fun Track.mediaItem(profile: String) = MediaItem.Builder().setMediaId(id).setUri(if (source == Source.YANDEX)
    Uri.Builder().scheme("ymplayer2").authority("yandex").appendPath(profile).appendPath(id).build().toString() else uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setArtworkUri(artworkUri?.let(Uri::parse)).build()).build()
private fun RepeatMode.toPlayerMode() = when (this) { RepeatMode.OFF -> Player.REPEAT_MODE_OFF; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.ONE -> Player.REPEAT_MODE_ONE }
private fun Int.toRepeatMode() = when (this) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }
