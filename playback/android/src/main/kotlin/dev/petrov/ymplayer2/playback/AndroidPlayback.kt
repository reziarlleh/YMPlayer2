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
    val radio: AndroidRadio? get() = null
    fun onAudioServiceEvent(event: AudioServiceEvent) = Unit
}

/** Main-thread command adapter. The service alone creates/releases the audio engine. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidPlayback(private val context: Context, private val library: LocalLibrary, private val scope: CoroutineScope, private val online: OnlineMusic? = null,
    private val taste: MusicTaste? = null, private val waveApi: MyWaveApi? = null, private val offline: OfflineMusic? = null,
    private val streamQuality: () -> AudioQuality = { AudioQuality.AUTO },
    private val listened: (String, Track) -> Unit = { _, _ -> }) : PlaybackController {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val launchState = LaunchStateStore(context)
    private var userCommanded = false
    private val mutable = MutableStateFlow(PlaybackState(prefs.getString("profile", "owner") ?: "owner", emptyList(), connected = false))
    override val state = mutable.asStateFlow()
    override val waveSettings = waveApi?.let { api -> online?.let { music -> WaveSettings(music.accounts, api, scope,
        read = { key -> runCatching { JSONObject(prefs.getString("waveSettings:$key", "{}")!!).let { json ->
            json.keys().asSequence().associateWith { json.getString(it) }
        } }.getOrDefault(emptyMap()) },
        write = { key, choices -> prefs.edit().putString("waveSettings:$key", JSONObject(choices).toString()).apply() }) } }
    internal var radio: AndroidRadio? = null
    internal var radioMode = false
        private set
    /** Preserve the Music checkpoint before lending the service engine to a live station. */
    internal fun yieldToRadio() {
        if (!radioMode && ready && !restoring) { publish(); checkpoint() }
        radioMode = true
        indexedSelectionGeneration++; indexedWindowJob?.cancel(); indexedWindowJob = null
        restoring = false; pending.clear(); deferredReconcile = false
        cancelWaveWork()
        engine?.let { it.pause(); it.stop(); it.clearMediaItems() }
        mutable.value = state.value.copy(playing = false, buffering = false)
        modeListeners.toList().forEach { it() }
    }
    private var engine: ExoPlayer? = null
    private var job: Job? = null
    private var updating = false
    private var restoring = false
    private val pending = ArrayDeque<() -> Unit>()
    private var ready = false
    private var followLibrary = true
    private var logicalQueue = emptyList<Track>()
    private var referenceOrder: ReferencePlaybackOrder? = null
    private var persistedReferenceOrder: ReferencePlaybackOrder? = null
    private var persistedReferenceRevision = -1L
    private var localMediaEntries = emptyMap<String, Pair<Int, Track>>()
    private var indexedWindowJob: Job? = null
    private var indexedSelectionGeneration = 0L
    private var deferredReconcile = false
    private val indexedOrder = (library as? IndexedLocalLibrary)?.let(::IndexedPlaybackOrder)
    private val modeListeners = mutableSetOf<() -> Unit>()
    private var notifiedRepeat: RepeatMode? = null
    private var notifiedShuffle: Boolean? = null
    private var waitingId: String? = null
    private var waveBatch: WaveBatch? = null
    private var activeWaveRequest = WaveRequest()
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
    private var lastAudible: Pair<String, String>? = null

    fun connect() { context.startService(Intent(context, AudioService::class.java)) }
    /** Only an explicit application launch requests automatic playback; service creation stays silent. */
    fun resumeOnLaunch() = sessionPlay()
    fun pauseForClips() = sessionPause()
    fun saveForExit() {
        if (!radioMode && ready && !restoring) { publish(); checkpoint() }
        prefs.edit().commit()
        launchState.flush()
    }
    fun audioSessionId(): Int = engine?.audioSessionId ?: 0

    override suspend fun queuePage(offset: Int, limit: Int): CatalogPage<Track> {
        require(offset >= 0 && limit in 1..500 && offset <= Int.MAX_VALUE - limit)
        referenceOrder?.let { order ->
            val snapshot = order.references
            val from = offset.coerceAtMost(snapshot.size)
            val selected = snapshot.subList(from, (from + limit).coerceAtMost(snapshot.size))
            return CatalogPage(resolveReferences(selected), snapshot.size, offset)
        }
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
        val album = item?.optString("album")?.takeIf { it.isNotBlank() }
        return MediaItem.Builder().setMediaId(BrowserSources.RESUME)
            .setUri("ymplayer2://browser/${BrowserSources.RESUME}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setAlbumTitle(album).setDisplayTitle(title).setSubtitle(artist).setDescription(album)
                .setIsPlayable(true).build()).build()
    }

    internal fun attach(player: ExoPlayer) {
        check(engine == null)
        engine = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!radioMode && !updating && !restoring && ready) {
                publish()
                recordAudible(player)
                if (referenceOrder != null && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    startDiskWork { refreshReferenceWindow(preserveCurrent = true) }
                }
                if (usesBoundedLocalWindow() && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    if (library is IndexedLocalLibrary) engine?.let(::refreshIndexedWindow)
                    else syncQueue(activeQueue())
                }
                checkpoint(); maintainWave(); maintainIndexedOrder()
            } }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (radioMode) return
                if (!isPlaying) lastAudible = null
                if (isPlaying && state.value.wave && !updating && !restoring) {
                    val id = player.currentMediaItem?.mediaId
                    if (id != null && id != waveStarted) { waveStarted = id; feedback(id, WaveFeedback.STARTED) }
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (radioMode) return
                val old = oldPosition.mediaItem?.mediaId
                if (!updating && !restoring && old != newPosition.mediaItem?.mediaId && old == waveStarted) {
                    feedback(old, if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) WaveFeedback.FINISHED else WaveFeedback.SKIP,
                        (oldPosition.positionMs / 1000).toInt())
                    waveStarted = null
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (radioMode) return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) lastAudible = null
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (radioMode) return
                // Media3 can deliver our internal pause after the surrounding command returns.
                if (!playWhenReady && wavePausePending && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    wavePausePending = false
                    return
                }
                if (!updating && !restoring && state.value.wave && waveAdvance) waveResume = playWhenReady
            }
            override fun onPlayerError(error: PlaybackException) {
                if (radioMode) return
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
                    if (radioMode || !catalog.ready || catalog.scanning) return@collect
                    if (!ready) {
                        ready = true
                        restore(state.value.profileId)
                        drainPending()
                    } else reconcile()
                }
            }
            while (isActive) {
                delay(500)
                // Until metadata arrives the engine may still describe the outgoing profile.
                if (!radioMode && ready && !restoring) {
                    publish(); engine?.let(::recordAudible); maintainWave(); maintainIndexedOrder()
                    if (state.value.playing && lastSavedSecond != state.value.positionSeconds) checkpoint()
                }
            }
        }
    }

    private fun recordAudible(player: Player) {
        val current = state.value.current ?: return
        val key = state.value.profileId to current.id
        if (player.isPlaying && current.id == player.currentMediaItem?.mediaId && key != lastAudible) {
            lastAudible = key
            listened(state.value.profileId, current)
        }
    }

    internal fun detach() {
        lastAudible = null
        if (ready && !restoring) { publish(); checkpoint() }
        indexedSelectionGeneration++
        indexedWindowJob?.cancel(); indexedWindowJob = null; restoring = false
        cancelWave()
        job?.cancel(); job = null; engine = null; ready = false
        mutable.value = state.value.copy(playing = false, buffering = false, connected = false)
    }

    private fun command(action: () -> Unit) {
        userCommanded = true
        launchState.write(state.value.profileId, PlaybackOutput.MUSIC, false)
        if (radioMode) {
            radio?.release(); radioMode = false
            if (!ready && library.state.value.ready && !library.state.value.scanning) ready = true
            if (ready && engine != null) restore(state.value.profileId)
            modeListeners.toList().forEach { it() }
        }
        if (ready && engine != null && !restoring) action()
        else { pending.addLast(action); if (!ready || engine == null) connect() }
    }

    private fun drainPending() {
        while (ready && engine != null && !restoring && pending.isNotEmpty()) pending.removeFirst().invoke()
    }

    private fun diskCommand(action: suspend () -> Unit) = command { startDiskWork(action = action) }

    /** Reuse the selection gate so commands issued during metadata IO keep their order. */
    private fun startDiskWork(completed: (() -> Unit)? = null, action: suspend () -> Unit) {
        val player = engine ?: return
        val profile = state.value.profileId
        val generation = ++indexedSelectionGeneration
        indexedWindowJob?.cancel()
        restoring = true
        indexedWindowJob = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (engine === player && state.value.profileId == profile) {
                    player.pause()
                    mutable.value = state.value.copy(playing = false, buffering = false,
                        error = "Не удалось прочитать медиатеку. Обновите папки с музыкой и повторите выбор.")
                }
            } finally {
                finishDiskWork(player, generation, completed)
            }
        }
    }

    private fun finishDiskWork(player: ExoPlayer, generation: Long, completed: (() -> Unit)? = null) {
        if (generation != indexedSelectionGeneration) return
        restoring = false
        if (!ready || engine !== player) return
        if (deferredReconcile) {
            deferredReconcile = false
            reconcileOnline()
            if (!restoring) reconcile()
        } else completed?.invoke()
        if (!restoring) filterWave()
        drainPending()
    }

    private suspend fun localTracksByIds(profile: String, ids: Collection<String>): Map<String, Track> {
        if (ids.isEmpty()) return emptyMap()
        val indexed = library as? IndexedLocalLibrary
            ?: return library.tracks(profile).filter { it.id in ids }.associateBy(Track::id)
        var revision: Long
        var tracks: Map<String, Track>
        do {
            revision = indexed.indexRevision
            tracks = indexed.tracksByIds(ids)
        } while (indexed.indexRevision != revision)
        return tracks
    }

    private suspend fun editableQueue(): List<Track> {
        return activeQueue()
    }

    private suspend fun referencesFor(ids: Collection<String>): List<PlaybackReference> {
        val indexed = library as? IndexedLocalLibrary ?: return ids.distinct().mapNotNull { id ->
            knownTracks(state.value.profileId).find { it.id == id }?.let(PlaybackReference::from)
        }
        val local = indexed.referencesByIds(ids)
        val remote = knownTracks(state.value.profileId).associateBy(Track::id)
        return ids.distinct().mapNotNull { id -> local[id]?.let(::PlaybackReference)
            ?: remote[id]?.let(PlaybackReference::from) }
    }

    private suspend fun editableReferences(): List<PlaybackReference> {
        referenceOrder?.let { return it.references }
        val indexed = library as? IndexedLocalLibrary
        if (!followLibrary || indexed == null) return logicalQueue.map(PlaybackReference::from)
        val source = prefs.getString("source:${state.value.profileId}", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        return referencesFor(indexed.catalogTrackIds(source))
    }

    private suspend fun resolveReferences(refs: List<PlaybackReference>): List<Track> {
        val profile = state.value.profileId
        val local = localTracksByIds(profile, refs.filter { it.saved.source != Source.YANDEX }.map(PlaybackReference::id))
        val remote = knownTracks(profile).associateBy(Track::id)
        return refs.map { ref -> if (ref.saved.source != Source.YANDEX) ref.saved.resolve(local)
            else (remote[ref.id] ?: ref.remoteTrack(remoteEnabled(profile))).let {
                (offline?.decorate(profile, it) ?: it).let { track -> track.copy(available = track.available && remoteEnabled(profile)) }
            }
        }
    }

    private suspend fun resolveReference(ref: PlaybackReference) = resolveReferences(listOf(ref)).single()

    private suspend fun setReferences(refs: List<PlaybackReference>, preferred: String? = state.value.current?.id,
        position: Int = state.value.positionSeconds, resume: Boolean = state.value.playing, preserveCurrent: Boolean = true) {
        val order = referenceOrder ?: ReferencePlaybackOrder()
        order.replace(refs)
        referenceOrder = order
        followLibrary = false
        logicalQueue = emptyList()
        refreshReferenceWindow(preferred, position, resume, preserveCurrent)
    }

    private suspend fun refreshReferenceWindow(id: String? = engine?.currentMediaItem?.mediaId ?: state.value.current?.id,
        position: Int = state.value.positionSeconds, resume: Boolean = state.value.playing, preserveCurrent: Boolean = true) {
        val order = referenceOrder ?: return
        val player = engine ?: return
        val selected = order.reference(id) ?: order.references.getOrNull(state.value.index.coerceAtMost((order.references.size - 1).coerceAtLeast(0)))
        val current = selected?.let { resolveReference(it) }
        val index = order.index(current?.id).coerceAtLeast(0)
        val from = (index - 1).coerceIn(0, (order.references.size - 3).coerceAtLeast(0))
        val visible = resolveReferences(order.references.drop(from).take(3))
        val previous = current?.takeIf(Track::available)?.let { order.adjacent(it.id, -1, state.value.shuffle, state.value.repeatMode, ::resolveReference) }
        val next = current?.takeIf(Track::available)?.let { order.adjacent(it.id, 1, state.value.shuffle, state.value.repeatMode, ::resolveReference) }
        val media = (if (previous?.id == next?.id) listOfNotNull(current, next) else listOfNotNull(previous, current, next))
            .filter(Track::available).distinctBy(Track::id).map { order.index(it.id) to it }
        val keepAudio = preserveCurrent && current?.available == true && player.currentMediaItem?.mediaId == current.id
        updating = true
        try {
            applyOrderingModes()
            localMediaEntries = media.associate { it.second.id to it }
            waitingId = current?.takeIf { !it.available }?.id
            mutable.value = state.value.copy(queue = visible, index = index, queueOffset = from,
                queueTotal = order.references.size, currentTrack = current, automaticLocal = false,
                automaticSource = null, referenceQueue = true, explicitQueueIds = order.ids,
                queueRevision = order.revision + ((library as? IndexedLocalLibrary)?.indexRevision ?: 0),
                positionSeconds = if (keepAudio) (player.currentPosition / 1000).toInt() else position.coerceIn(0, current?.durationSeconds ?: 0),
                playing = resume && current?.available == true, connected = true, error = null)
            if (keepAudio) syncPlayerItems(player, media)
            else {
                player.pause()
                if (waitingId != null || media.isEmpty()) player.clearMediaItems()
                else player.setMediaItems(media.map { it.second.mediaItem(state.value.profileId) },
                    media.indexOfFirst { it.second.id == current?.id }.coerceAtLeast(0), state.value.positionSeconds.toLong() * 1000)
                if (current?.available == true) { player.prepare(); if (resume) player.play() }
            }
        } finally { updating = false }
        publish(); checkpoint()
    }

    private suspend fun skipReference(direction: Int, resume: Boolean = engine?.playWhenReady == true) {
        val order = referenceOrder ?: return
        val next = order.adjacent(state.value.current?.id, direction, state.value.shuffle, state.value.repeatMode, ::resolveReference)
        if (next == null) { engine?.pause(); publish(); checkpoint(); return }
        refreshReferenceWindow(next.id, 0, resume, preserveCurrent = false)
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
            cancelWaveWork()
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
        if (referenceOrder != null) {
            startDiskWork { skipReference(if (direction > 0) 1 else -1) }
            return@command
        }
        if (waitingId != null) {
            if (library is IndexedLocalLibrary && usesBoundedLocalWindow()) {
                loadIndexedSelection(waitingId, 0, resume = false, direction = if (direction > 0) 1 else -1)
                return@command
            }
            val queue = activeQueue()
            val range = if (direction > 0) (state.value.index + 1 until queue.size).toList() else (state.value.index - 1 downTo 0).toList()
            val index = range.firstOrNull { queue[it].available }
                ?: if (state.value.repeatMode == RepeatMode.ALL) queue.indices.firstOrNull { queue[it].available } else null
            if (index != null) load(queue, index, 0)
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
        if (referenceOrder?.ids?.contains(trackId) == true) {
            startDiskWork {
                val ref = referenceOrder?.reference(trackId) ?: return@startDiskWork
                if (resolveReference(ref).available) refreshReferenceWindow(trackId, 0, resume = true, preserveCurrent = false)
            }
            return@command
        }
        if (library is IndexedLocalLibrary && trackId.startsWith("local:") &&
            (followLibrary || logicalQueue.none { it.id == trackId })) {
            cancelWave()
            followLibrary = true
            // Selecting from search may cross the currently selected source filter.
            loadIndexedSelection(trackId, 0, resume = true, allowSourceChange = true, rejectUnavailable = true)
            return@command
        }
        val queue = if (followLibrary && library is IndexedLocalLibrary) emptyList() else activeQueue()
        val index = queue.indexOfFirst { it.id == trackId }
        if (index >= 0 && !queue[index].available) return@command
        val tracks = if (index < 0) knownTracks(state.value.profileId).filter { it.available } else emptyList()
        if (index < 0 && tracks.none { it.id == trackId }) return@command
        mutable.value = state.value.copy(error = null)
        if (index >= 0) {
            waveAdvance = false
            waveTrackErrorJob?.cancel(); waveTrackErrorJob = null
            val player = engine ?: return@command
            val mediaIndex = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == trackId }
            if (waitingId != null || mediaIndex == null) load(queue, index, 0)
            else player.seekTo(mediaIndex, 0)
        }
        else {
            cancelWave()
            followLibrary = tracks.first { it.id == trackId }.source != Source.YANDEX
            prefs.edit().remove("source:${state.value.profileId}").apply()
            val next = if (followLibrary) tracks.filter { it.source != Source.YANDEX } else tracks.filter { it.source == Source.YANDEX }
            if (library is IndexedLocalLibrary) {
                startDiskWork { setReferences(next.map(PlaybackReference::from), trackId, 0, resume = true, preserveCurrent = false) }
                return@command
            }
            load(next, next.indexOfFirst { it.id == trackId }, 0)
        }
        engine?.prepare(); engine?.play()
    }
    override fun switchProfile(profileId: String) = command {
        if (profileId == state.value.profileId || library.profiles.none { it.id == profileId }) return@command
        engine?.pause(); publish(); checkpoint()
        cancelWave()
        launchState.write(profileId, PlaybackOutput.MUSIC, false)
        restore(profileId)
    }
    override fun chooseSource(source: Source?) = command {
        cancelWave()
        referenceOrder = null
        prefs.edit().putString("source:${state.value.profileId}", source?.name).apply()
        followLibrary = true
        indexedOrder?.reset()
        applyOrderingModes()
        if (library is IndexedLocalLibrary && usesBoundedLocalWindow()) {
            loadIndexedSelection(null, 0, resume = false)
            return@command
        }
        val queue = defaultQueue(state.value.profileId)
        load(queue, queue.indexOfFirst { it.available }.coerceAtLeast(0), 0)
    }

    override fun setRepeatMode(mode: RepeatMode) = command { if (!state.value.wave) changeOrdering(state.value.withRepeat(mode)) }
    override fun setShuffle(enabled: Boolean) = command { if (!state.value.wave) changeOrdering(state.value.withShuffle(enabled)) }
    override fun setContinueWave(enabled: Boolean) = command {
        if (!state.value.wave && (!enabled || state.value.origin.wave != null)) changeOrdering(state.value.withContinuation(enabled))
    }
    private fun changeOrdering(next: PlaybackState) {
        val before = state.value
        if (before.shuffle != next.shuffle) { referenceOrder?.resetShuffle(); indexedOrder?.reset() }
        val bounded = usesBoundedLocalWindow()
        val queue = if (referenceOrder == null && !(followLibrary && indexedOrder != null)) activeQueue() else emptyList()
        // Engine listeners must see a complete logical mode transition, never half of it.
        updating = true
        try { mutable.value = next; applyOrderingModes() } finally { updating = false }
        when {
            referenceOrder != null -> { checkpoint(); startDiskWork { refreshReferenceWindow() } }
            followLibrary && indexedOrder != null -> {
                checkpoint()
                loadIndexedSelection(next.current?.id, next.positionSeconds, next.playing, preserveCurrent = true)
            }
            bounded != usesBoundedLocalWindow() -> syncQueue(queue)
            else -> { publish(); checkpoint() }
        }
    }
    override fun enqueue(trackId: String) = enqueueMany(listOf(trackId))
    override fun enqueueMany(trackIds: List<String>) = diskCommand {
        val ids = trackIds.distinct()
        val known = knownTracks(state.value.profileId).associateBy(Track::id) + localTracksByIds(state.value.profileId, ids)
        val tracks = ids.mapNotNull { known[it]?.takeIf(Track::available) }
        if (tracks.isEmpty()) return@diskCommand
        if (library is IndexedLocalLibrary) {
            val refs = editableReferences()
            val present = refs.mapTo(hashSetOf(), PlaybackReference::id)
            val additions = tracks.filter { it.id !in present }.map(PlaybackReference::from)
            if (additions.isEmpty()) return@diskCommand
            cancelWave()
            setReferences(refs + additions)
            return@diskCommand
        }
        val queue = editableQueue()
        val present = queue.mapTo(hashSetOf(), Track::id)
        val additions = tracks.filter { it.id !in present }
        if (additions.isEmpty()) return@diskCommand
        cancelWave()
        followLibrary = false
        syncQueue(queue + additions)
    }
    override fun moveInQueue(trackId: String, toIndex: Int) = diskCommand {
        if (library is IndexedLocalLibrary) {
            val refs = editableReferences()
            val from = refs.indexOfFirst { it.id == trackId }
            if (from < 0 || toIndex !in refs.indices || from == toIndex) return@diskCommand
            cancelWave()
            setReferences(refs.toMutableList().apply { add(toIndex, removeAt(from)) })
            return@diskCommand
        }
        val queue = editableQueue()
        val from = queue.indexOfFirst { it.id == trackId }
        if (from < 0 || toIndex !in queue.indices || from == toIndex) return@diskCommand
        cancelWave()
        followLibrary = false
        syncQueue(queue.toMutableList().apply { add(toIndex, removeAt(from)) })
    }
    override fun removeFromQueue(trackId: String) = diskCommand {
        if (library is IndexedLocalLibrary) {
            val refs = editableReferences()
            if (refs.none { it.id == trackId }) return@diskCommand
            val before = state.value
            val removingCurrent = before.current?.id == trackId
            cancelWave()
            if (removingCurrent) engine?.pause()
            setReferences(refs.filterNot { it.id == trackId }, if (removingCurrent) null else before.current?.id,
                if (removingCurrent) 0 else before.positionSeconds, before.playing && !removingCurrent)
            return@diskCommand
        }
        val queue = editableQueue()
        val index = queue.indexOfFirst { it.id == trackId }
        if (index < 0) return@diskCommand
        cancelWave()
        followLibrary = false
        // Removing the current item must not unexpectedly start its successor.
        if (state.value.current?.id == trackId) { engine?.pause(); mutable.value = state.value.copy(error = null) }
        syncQueue(queue.filterNot { it.id == trackId })
    }
    override fun clearQueue() = command {
        cancelWave()
        followLibrary = false
        mutable.value = state.value.copy(error = null)
        load(emptyList(), 0, 0)
    }
    override fun playQueue(trackIds: List<String>, startId: String?) = diskCommand {
        playResolvedQueue(trackIds, startId)
    }
    override fun playList(trackIds: List<String>, startId: String?, origin: PlaybackOrigin) = diskCommand {
        playResolvedQueue(trackIds, startId, origin)
    }
    private suspend fun playResolvedQueue(trackIds: List<String>, startId: String?, origin: PlaybackOrigin = PlaybackOrigin(PlaybackSource.LIST)): Boolean {
        if (library is IndexedLocalLibrary) {
            val refs = referencesFor(trackIds)
            if (refs.isEmpty()) return false
            val order = ReferencePlaybackOrder().apply { replace(refs) }
            val selected = order.reference(startId)?.let { resolveReference(it).takeIf(Track::available) }
                ?: order.adjacent(null, 1, state.value.shuffle, RepeatMode.OFF, ::resolveReference) ?: return false
            cancelWave()
            mutable.value = state.value.copy(origin = origin)
            referenceOrder = order
            setReferences(refs, selected.id, 0, resume = true, preserveCurrent = false)
            return true
        }
        val known = knownTracks(state.value.profileId).associateBy(Track::id) + localTracksByIds(state.value.profileId, trackIds)
        val queue = trackIds.distinct().mapNotNull { known[it]?.takeIf(Track::available) }
        if (queue.isEmpty()) return false
        cancelWave()
        mutable.value = state.value.copy(origin = origin)
        followLibrary = false
        load(queue, queue.indexOfFirst { it.id == startId }.coerceAtLeast(0), 0)
        engine?.prepare(); engine?.play()
        return true
    }

    override fun playMyWave() = command {
        startWave(waveSettings?.request(state.value.profileId) ?: WaveRequest(), PlaybackSource.MY_WAVE)
    }
    override fun playWave(request: WaveRequest) = command { startWave(request, PlaybackSource.OBJECT_WAVE) }
    private fun startWave(request: WaveRequest, source: PlaybackSource) {
        if (waveApi == null || !remoteEnabled(state.value.profileId)) return
        cancelWave(); followLibrary = false; referenceOrder = null; activeWaveRequest = request
        mutable.value = state.value.copy(repeatMode = RepeatMode.OFF, shuffle = false, continueWave = false,
            origin = PlaybackOrigin(source, request.title, request))
        engine?.repeatMode = Player.REPEAT_MODE_OFF; engine?.shuffleModeEnabled = false
        load(emptyList(), 0, 0)
        mutable.value = state.value.copy(wave = true)
        waveAdvance = true; waveResume = true
        fetchWave()
    }
    override fun playOffline() = playOfflineLikes()
    internal fun playOfflineLikes() = diskCommand {
        val profile = state.value.profileId
        cancelWave(); followLibrary = false
        mutable.value = state.value.copy(origin = PlaybackOrigin(PlaybackSource.OFFLINE))
        load(emptyList(), 0, 0)
        val tracks = offline?.readyTracks(profile)?.filter { it.available && it.offline }.orEmpty()
        if (tracks.isEmpty()) { checkpoint(); return@diskCommand }
        if (library is IndexedLocalLibrary) {
            setReferences(tracks.map(PlaybackReference::from), tracks.first().id, 0, resume = true, preserveCurrent = false)
            return@diskCommand
        }
        load(tracks, 0, 0)
        engine?.prepare(); engine?.play()
    }
    override fun playRecommendedQueue(trackIds: List<String>, startId: String?, origin: PlaybackOrigin) = diskCommand {
        if (!playResolvedQueue(trackIds, startId, origin)) return@diskCommand
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
    internal fun sessionPause() = command { waveResume = false; engine?.pause(); publish(); checkpoint() }
    internal fun sessionRepeatMode(mode: Int) = setRepeatMode(mode.toRepeatMode())
    internal fun sessionRepeatMode(): Int = state.value.repeatMode.toPlayerMode()
    internal fun sessionShuffle(): Boolean = state.value.shuffle
    internal fun addModeListener(listener: () -> Unit) { modeListeners += listener }
    internal fun removeModeListener(listener: () -> Unit) { modeListeners -= listener }

    private fun applyOrderingModes() {
        val player = engine ?: return
        if (referenceOrder != null || followLibrary && indexedOrder != null) {
            // ALL follows the catalog boundary rather than looping this small engine window.
            player.repeatMode = if (state.value.repeatMode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = false
        } else {
            player.repeatMode = if (state.value.shuffle) Player.REPEAT_MODE_ALL else state.value.repeatMode.toPlayerMode()
            player.shuffleModeEnabled = state.value.shuffle
        }
        if (notifiedRepeat != state.value.repeatMode || notifiedShuffle != state.value.shuffle) {
            notifiedRepeat = state.value.repeatMode; notifiedShuffle = state.value.shuffle
            modeListeners.toList().forEach { it() }
        }
    }

    private fun maintainIndexedOrder() {
        val player = engine ?: return
        if (ready && !restoring && !updating && !state.value.wave && state.value.continueWave &&
            player.playbackState == Player.STATE_ENDED && player.playWhenReady) {
            state.value.origin.wave?.let { startWave(it, PlaybackSource.OBJECT_WAVE) }
            return
        }
        if (referenceOrder != null) {
            if (ready && !restoring && !updating && player.playbackState == Player.STATE_ENDED &&
                player.playWhenReady && (state.value.repeatMode == RepeatMode.ALL || state.value.shuffle))
                startDiskWork { skipReference(1, resume = true) }
            return
        }
        if (!ready || restoring || updating || indexedOrder == null || !followLibrary || indexedWindowJob?.isActive == true) return
        if (player.playbackState == Player.STATE_ENDED && player.playWhenReady &&
            (state.value.repeatMode == RepeatMode.ALL || state.value.shuffle))
            loadIndexedSelection(player.currentMediaItem?.mediaId, 0, resume = true, direction = 1)
    }
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
        activeWaveRequest = WaveRequest()
        mutable.value = state.value.copy(wave = false, recommendations = false, origin = PlaybackOrigin(), continueWave = false)
    }
    private fun feedback(id: String?, type: WaveFeedback, seconds: Int = 0) {
        val api = waveApi ?: return
        val item = waveItems[id] ?: return
        val profile = state.value.profileId
        scope.launch { try { api.feedback(profile, item, type, seconds) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Feedback must not stop audio. */ } }
    }
    private fun maintainWave() {
        if (radioMode) return
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
                    val candidate = pendingWaveBatch ?: WaveLoader(api).load(profile, waveBatch, waveItems.values.mapTo(hashSetOf()) { it.track.tasteTarget().key }, activeWaveRequest) {
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
                // Keep only what Media3 can use now: one previous, current, prepared next.
                val keepFrom = (state.value.index - if (advance) 0 else 1).coerceAtLeast(0)
                val next = (logicalQueue.drop(keepFrom) + batch.tracks.map(WaveTrack::track)).distinctBy(Track::id)
                syncQueue(next)
                if (advance) {
                    val target = if (before == null) 0 else next.indexOfFirst { it.id == before } + 1
                    waveAdvance = false
                    load(next, target.coerceAtMost(next.lastIndex), 0)
                    engine?.prepare(); if (resume) engine?.play()
                }
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
        if (radioMode) return
        val preferences = taste?.state?.value ?: return
        if (!ready || restoring || !(state.value.wave || state.value.recommendations) || preferences.profileId != state.value.profileId || !preferences.signedIn) return
        referenceOrder?.let { order ->
            val next = order.references.filter { preferences.allows(if (it.saved.source == Source.YANDEX) it.remoteTrack(true) else it.saved.resolve(emptyMap())) }
            if (next == order.references) return
            val before = state.value
            val blocked = before.current?.let { !preferences.allows(it) } == true
            val successor = order.references.drop(before.index + 1).firstOrNull { it in next }
            startDiskWork { setReferences(next, if (blocked) successor?.id else before.current?.id,
                if (blocked) 0 else before.positionSeconds, before.playing && (!blocked || successor != null), preserveCurrent = !blocked) }
            return
        }
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
        val tracks = library.tracks(profile)
        return if (source == null) tracks else tracks.filter { it.source.name == source }
    }

    // Indexed commands use editableReferences(); this fallback serves in-memory libraries.
    private fun activeQueue(): List<Track> = if (followLibrary && library is IndexedLocalLibrary && usesBoundedLocalWindow())
        defaultQueue(state.value.profileId) else logicalQueue

    private fun PlaybackState.withQueueView(all: List<Track>, absoluteIndex: Int): PlaybackState {
        val indexed = library as? IndexedLocalLibrary
        if (followLibrary && indexed != null) {
            val from = (absoluteIndex - 4).coerceAtLeast(0)
            val until = (absoluteIndex + 25).coerceAtMost(all.size).coerceAtLeast(from)
            val source = prefs.getString("source:$profileId", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            return copy(queue = all.subList(from, until).toList(), index = absoluteIndex,
                queueOffset = from, queueTotal = all.size, currentTrack = all.getOrNull(absoluteIndex),
                automaticLocal = true, automaticSource = source, queueRevision = indexed.indexRevision,
                referenceQueue = false, explicitQueueIds = null)
        }
        return copy(queue = all, index = absoluteIndex, queueOffset = 0, queueTotal = null,
            currentTrack = null, automaticLocal = false, automaticSource = null, queueRevision = 0,
            referenceQueue = false, explicitQueueIds = null)
    }

    // Indexed automatic playback gives Media3 nearby items, independently of ordering modes.
    private fun usesBoundedLocalWindow() = followLibrary && (indexedOrder != null ||
        engine?.shuffleModeEnabled == false && engine?.repeatMode != Player.REPEAT_MODE_ALL)

    private fun playableQueue(queue: List<Track>, index: Int): List<Pair<Int, Track>> {
        if (!usesBoundedLocalWindow()) return queue.mapIndexedNotNull { i, track ->
            if (track.available && (!state.value.wave || i <= index || waveAudio.uri(state.value.profileId, track.id) != null))
                i to track else null
        }
        val previous = ArrayDeque<Pair<Int, Track>>()
        for (i in index - 1 downTo 0) if (queue[i].available) {
            previous.addFirst(i to queue[i]); if (previous.size == 4) break
        }
        val upcoming = ArrayList<Pair<Int, Track>>(25)
        for (i in index.coerceAtLeast(0) until queue.size) if (queue[i].available) {
            upcoming += i to queue[i]; if (upcoming.size == 25) break
        }
        return previous + upcoming
    }

    private fun refreshIndexedWindow(player: ExoPlayer) {
        val indexed = library as? IndexedLocalLibrary ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val profile = state.value.profileId
        val source = prefs.getString("source:$profile", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        val revision = indexed.indexRevision
        indexedWindowJob?.cancel()
        indexedWindowJob = scope.launch {
            val window = indexedOrder!!.window(id, source, state.value.shuffle, state.value.repeatMode) ?: return@launch
            if (engine !== player || !ready || !usesBoundedLocalWindow() || state.value.profileId != profile ||
                indexed.indexRevision != revision || player.currentMediaItem?.mediaId != id ||
                prefs.getString("source:$profile", null) != source?.name || !window.current.available) return@launch
            val positions = window.visible.items.mapIndexed { offset, track ->
                track.id to window.visible.offset + offset
            }.toMap()
            val playable = window.media.map { (positions[it.id] ?: -1) to it }
            val before = state.value
            updating = true
            try {
                localMediaEntries = playable.associate { it.second.id to it }
                mutable.value = before.copy(queue = window.visible.items, index = window.index,
                    queueOffset = window.visible.offset, queueTotal = window.visible.total,
                    currentTrack = window.current, automaticLocal = true, automaticSource = source,
                    queueRevision = revision)
                syncPlayerItems(player, playable)
            } finally { updating = false }
            publish(); checkpoint()
        }
    }

    private fun syncPlayerItems(player: ExoPlayer, playable: List<Pair<Int, Track>>) {
        val keep = playable.mapTo(hashSetOf()) { it.second.id }
        for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId !in keep) player.removeMediaItem(i)
        playable.forEachIndexed { index, (_, track) ->
            val from = (index until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
            if (from == null) player.addMediaItem(index, track.mediaItem(state.value.profileId))
            else {
                if (from != index) player.moveMediaItem(from, index)
                if (player.getMediaItemAt(index) != track.mediaItem(state.value.profileId)) player.replaceMediaItem(index, track.mediaItem(state.value.profileId))
            }
        }
    }

    /** Resolve an automatic local selection from the index, never a catalog snapshot.
     * Commands arriving during IO retain their order and run as soon as the selection is ready. */
    private fun loadIndexedSelection(id: String?, position: Int, resume: Boolean,
        fallbackFirst: Boolean = false, allowSourceChange: Boolean = false, direction: Int? = null,
        preserveCurrent: Boolean = false, rejectUnavailable: Boolean = false) {
        val indexed = library as? IndexedLocalLibrary ?: return
        referenceOrder = null
        val player = engine ?: return
        val profile = state.value.profileId
        val source = prefs.getString("source:$profile", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        val generation = ++indexedSelectionGeneration
        indexedWindowJob?.cancel()
        restoring = true
        indexedWindowJob = scope.launch {
            try {
                var selectedSource = source
                var window: LocalPlaybackWindow?
                var revision: Long
                var anchor = id
                var observedEngineId: String?
                do {
                    // An empty engine can still have a saved anchor. Retry only if the engine
                    // changed during IO, not merely because that saved ID is not loaded yet.
                    observedEngineId = player.currentMediaItem?.mediaId
                    if (preserveCurrent && waitingId == null) anchor = observedEngineId ?: id
                    selectedSource = source
                    revision = indexed.indexRevision
                    val selected = if (direction == null) anchor else indexedOrder!!.adjacent(anchor, direction, source,
                        state.value.shuffle, state.value.repeatMode)?.id
                    window = if (direction != null && selected == null) null else indexedOrder!!.window(selected, source,
                        state.value.shuffle, state.value.repeatMode)
                    if (window == null && id != null && allowSourceChange && direction == null) {
                        window = indexedOrder!!.window(id, null, state.value.shuffle, state.value.repeatMode)
                        if (window != null) selectedSource = null
                    }
                    if (window == null && fallbackFirst) window = indexedOrder!!.window(null, source, state.value.shuffle, state.value.repeatMode)
                } while (isActive && (indexed.indexRevision != revision || preserveCurrent && waitingId == null &&
                    player.currentMediaItem?.mediaId != observedEngineId))
                if (engine !== player || !ready || state.value.profileId != profile || !usesBoundedLocalWindow() ||
                    prefs.getString("source:$profile", null) != source?.name) return@launch
                if (window == null && id != null && !fallbackFirst) {
                    if (direction != null) { player.pause(); publish(); checkpoint() }
                    return@launch
                }
                if (rejectUnavailable && window?.current?.available != true) return@launch
                if (selectedSource != source) prefs.edit().remove("source:$profile").apply()
                val positions = window?.visible?.items?.mapIndexed { offset, track ->
                    track.id to window.visible.offset + offset
                }?.toMap().orEmpty()
                val playable = window?.media.orEmpty().map { (positions[it.id] ?: -1) to it }
                updating = true
                try {
                    logicalQueue = emptyList()
                    localMediaEntries = playable.associate { it.second.id to it }
                    waitingId = window?.current?.takeIf { !it.available }?.id
                    val current = window?.current
                    val seconds = if (current != null && current.id == id) position.coerceIn(0, current.durationSeconds) else 0
                    val preserve = preserveCurrent && current?.available == true && player.currentMediaItem?.mediaId == current.id
                    if (!preserve) player.pause()
                    mutable.value = state.value.copy(queue = window?.visible?.items.orEmpty(), index = window?.index ?: 0,
                        queueOffset = window?.visible?.offset ?: 0, queueTotal = window?.visible?.total ?: 0,
                        currentTrack = window?.current, automaticLocal = true, automaticSource = selectedSource,
                        queueRevision = revision, positionSeconds = seconds, playing = false, connected = true, error = null,
                        referenceQueue = false, explicitQueueIds = null)
                    if (waitingId != null || playable.isEmpty()) { player.pause(); player.clearMediaItems() }
                    else if (preserve) syncPlayerItems(player, playable)
                    else player.setMediaItems(playable.map { it.second.mediaItem(profile) },
                        playable.indexOfFirst { it.second.id == window?.current?.id }.coerceAtLeast(0), seconds.toLong() * 1000)
                } finally { updating = false }
                if (resume && waitingId == null && playable.isNotEmpty()) { player.prepare(); player.play() }
                publish(); checkpoint()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (engine === player && state.value.profileId == profile) {
                    player.pause()
                    mutable.value = state.value.copy(playing = false, buffering = false,
                        error = "Не удалось прочитать медиатеку. Обновите папки с музыкой и повторите выбор.")
                }
            } finally {
                finishDiskWork(player, generation)
            }
        }
    }

    private fun skipOutsideWindow(direction: Int, player: ExoPlayer): Boolean {
        if (library is IndexedLocalLibrary) {
            loadIndexedSelection(state.value.current?.id, 0, resume = player.playWhenReady, direction = if (direction > 0) 1 else -1)
            return true
        }
        val queue = activeQueue()
        val range = if (direction > 0) state.value.index + 1 until queue.size
            else state.value.index - 1 downTo 0
        val target = range.firstOrNull { queue[it].available } ?: return false
        val resume = player.playWhenReady
        load(queue, target, 0)
        player.prepare()
        if (resume) player.play()
        return true
    }

    private fun remoteEnabled(profile: String) = online?.catalog?.value?.let { it.profileId == profile && it.enabled } == true
    private fun knownTracks(profile: String): List<Track> = (if (library is IndexedLocalLibrary) emptyList() else library.tracks(profile)) + if (remoteEnabled(profile))
        (logicalQueue.filter { it.source == Source.YANDEX } + state.value.queue.filter { it.source == Source.YANDEX } + online!!.tracksForPlayback(profile) + offline?.tracks(profile).orEmpty())
            .associateBy(Track::id).values.map { offline?.decorate(profile, it) ?: it } else emptyList()

    /** Called on the Media3 loader thread, never the main thread. No token or signed URI enters a MediaItem. */
    internal fun resolveStream(profile: String, trackId: String): String {
        val music = online ?: throw java.io.IOException("Online source unavailable")
        if (profile == state.value.profileId && remoteEnabled(profile) &&
            offline?.tracks(profile)?.any { it.tasteTarget().key == trackId.removePrefix("yandex:").substringBefore(':') } == true)
            runBlocking { offline?.audio(profile, trackId) }?.let { return it }
        if (profile == state.value.profileId && state.value.origin.source == PlaybackSource.OFFLINE)
            throw java.io.IOException("Offline audio is unavailable")
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
        // Persist the chosen profile even if its asynchronous restore is interrupted.
        // Its saved track/position stay untouched until that restore finishes.
        prefs.edit().putString("profile", validProfile).apply()
        restoring = true
        var indexedRestore = false
        try {
        cancelWave()
        indexedOrder?.reset()
        referenceOrder = null
        logicalQueue = emptyList()
        mutable.value = state.value.copy(profileId = validProfile, queue = emptyList(), index = 0, positionSeconds = 0,
            playing = false, error = null, queueOffset = 0, queueTotal = null, currentTrack = null,
            automaticLocal = false, automaticSource = null, queueRevision = 0, referenceQueue = false, explicitQueueIds = null)
        online?.accounts?.activate(validProfile)
        val ids = json?.optJSONArray("ids")
        followLibrary = json?.optBoolean("followLibrary", ids == null || ids.length() == 0) ?: true
        val savedOrigin = json?.optJSONObject("origin")
        val origin = savedOrigin?.let { PlaybackOrigin(
            runCatching { PlaybackSource.valueOf(it.getString("source")) }.getOrDefault(PlaybackSource.DEVICE),
            it.optString("title"), it.optJSONObject("wave")?.let { wave -> runCatching { wave.readWaveRequest() }.getOrNull() }) } ?: PlaybackOrigin()
        mutable.value = state.value.copy(origin = origin)
            .withRepeat(runCatching { RepeatMode.valueOf(prefs.getString("repeat:$validProfile", "OFF")!!) }.getOrDefault(RepeatMode.OFF))
            .withShuffle(prefs.getBoolean("shuffle:$validProfile", false))
            .withContinuation(json?.optBoolean("continueWave", false) == true)
        applyOrderingModes()
        if (followLibrary && library is IndexedLocalLibrary && usesBoundedLocalWindow()) {
            indexedRestore = true
            loadIndexedSelection(json?.optString("current")?.takeIf(String::isNotBlank),
                json?.optInt("position", 0) ?: 0, resume = false, fallbackFirst = true)
            return
        }
        val wanted = if (followLibrary) emptySet() else (0 until (ids?.length() ?: 0)).mapTo(hashSetOf()) { ids!!.optString(it) }
        if (library is IndexedLocalLibrary) {
            indexedRestore = true
            if (json?.optBoolean("wave", false) != true) {
                val rows = if (json?.optBoolean("referenceOrder", false) == true)
                    runCatching { JSONArray(prefs.getString("references:$validProfile", "[]")) }.getOrNull()
                    else json?.optJSONArray("tracks")
                val saved = (0 until (rows?.length() ?: 0)).mapNotNull { i -> runCatching { PlaybackReference.read(rows!!.getJSONObject(i)) }.getOrNull() }.associateBy(PlaybackReference::id)
                val refs = if (json?.optBoolean("referenceOrder", false) == true) saved.values.toList()
                    else (0 until (ids?.length() ?: 0)).mapNotNull { saved[ids!!.optString(it)] }
                startDiskWork(::reconcileOnline) {
                    mutable.value = state.value.copy(recommendations = json?.optBoolean("recommendations", false) == true)
                    setReferences(refs, json?.optString("current"), json?.optInt("position", 0) ?: 0, resume = false, preserveCurrent = false)
                }
                return
            }
            startDiskWork(::reconcileOnline) { finishReferenceRestore(validProfile, json, localTracksByIds(validProfile, wanted)) }
            return
        }
        val localById = if (wanted.isEmpty()) emptyMap() else library.tracks(validProfile)
            .filter { it.id in wanted }.associateBy(Track::id)
        finishReferenceRestore(validProfile, json, localById)
        } finally { if (!indexedRestore) restoring = false }
        reconcileOnline()
    }

    private fun finishReferenceRestore(validProfile: String, json: JSONObject?, localById: Map<String, Track>) {
        val ids = json?.optJSONArray("ids")
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
        val current = json?.optString("current")
        val index = queue.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: queue.indexOfFirst { it.available }.coerceAtLeast(0)
        val position = if (queue.getOrNull(index)?.id == current) json?.optInt("position", 0) ?: 0 else 0
        mutable.value = state.value.copy(recommendations = json?.optBoolean("recommendations", false) == true)
        if (json?.optBoolean("wave", false) == true && waveApi != null) {
            val selection = json.optJSONObject("waveSelection")
            activeWaveRequest = selection?.let { WaveRequest(it.optString("station", "user:onyourwave"),
                it.optString("title", "Моя волна"), it.optJSONArray("settings")?.let { seeds ->
                    (0 until seeds.length()).map { i -> seeds.getString(i) }
                }.orEmpty()) } ?: WaveRequest()
            val batches = json.optJSONObject("waveBatches")
            queue.forEach { waveItems[it.id] = WaveTrack(it, batches?.optString(it.id).orEmpty(), activeWaveRequest.station) }
            waveBatch = WaveBatch(emptyList(), json.optString("waveSession"), json.optString("waveCursor"), activeWaveRequest)
                .takeUnless { queue.isEmpty() && it.sessionId.isBlank() && it.cursor.isBlank() }
            queue.getOrNull(index + 1)?.let { next ->
                pendingWaveBatch = waveBatch!!.copy(tracks = listOf(waveItems.getValue(next.id)), cursor = next.tasteTarget().key)
            }
            mutable.value = state.value.copy(wave = true, repeatMode = RepeatMode.OFF, shuffle = false, continueWave = false,
                origin = originForRestoredWave(state.value.origin, activeWaveRequest))
            engine?.repeatMode = Player.REPEAT_MODE_OFF; engine?.shuffleModeEnabled = false
        }
        val restoredQueue = if (state.value.wave) {
            val from = (index - 1).coerceAtLeast(0)
            queue.subList(from, (index + 2).coerceAtMost(queue.size)).toList()
        } else queue
        load(restoredQueue, if (state.value.wave) index - (index - 1).coerceAtLeast(0) else index, position)
    }

    private fun reconcileOnline() {
        if (radioMode) return
        val remote = online?.catalog?.value ?: return
        if (!ready || remote.profileId != state.value.profileId) return
        if (restoring) { deferredReconcile = true; return }
        if (remote.phase in setOf(AuthPhase.SIGNED_OUT, AuthPhase.GUEST, AuthPhase.UNCONFIGURED, AuthPhase.ERROR)) {
            cancelWave()
            referenceOrder?.let { order ->
                val keep = order.references.filter { it.saved.source != Source.YANDEX }
                if (keep != order.references) {
                    val before = state.value
                    val retained = before.current?.id in keep.map(PlaybackReference::id)
                    startDiskWork { setReferences(keep, if (retained) before.current?.id else null,
                        if (retained) before.positionSeconds else 0, before.playing && retained) }
                }
                return
            }
            val keep = logicalQueue.filter { it.source != Source.YANDEX }
            if (keep != logicalQueue) syncQueue(keep)
        } else reconcile()
    }

    private fun reconcile() {
        if (radioMode) return
        if (!ready) return
        if (restoring) { deferredReconcile = true; return }
        val state = state.value
        if (referenceOrder != null) {
            startDiskWork { refreshReferenceWindow(id = state.current?.id, position = state.positionSeconds, resume = state.playing) }
            return
        }
        if (followLibrary && library is IndexedLocalLibrary && usesBoundedLocalWindow()) {
            val source = prefs.getString("source:${state.profileId}", null)
                ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            if (state.automaticLocal && state.queueRevision == library.indexRevision && state.automaticSource == source) return
            loadIndexedSelection(state.current?.id, state.positionSeconds, resume = state.playing, fallbackFirst = true, preserveCurrent = true)
            return
        }
        val wanted = if (followLibrary) emptySet() else logicalQueue.mapTo(hashSetOf(), Track::id)
        if (library is IndexedLocalLibrary) {
            startDiskWork { reconcileReferences(localTracksByIds(state.profileId, wanted)) }
            return
        }
        val localById = if (wanted.isEmpty()) emptyMap() else library.tracks(state.profileId)
            .filter { it.id in wanted }.associateBy(Track::id)
        reconcileReferences(localById)
    }

    private fun reconcileReferences(localById: Map<String, Track>) {
        val state = state.value
        val wanted = logicalQueue.mapTo(hashSetOf(), Track::id)
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
        applyOrderingModes()
        val before = state.value
        val preferred = next.indexOfFirst { it.id == before.current?.id }
        val index = if (preferred >= 0) preferred else before.index.coerceAtMost((next.size - 1).coerceAtLeast(0))
        if (waitingId != null || next.getOrNull(index)?.available == false || preferred < 0) {
            load(next, index, if (preferred >= 0) before.positionSeconds else 0)
            return
        }
        val player = engine ?: return
        val playable = playableQueue(next, index)
        val bounded = followLibrary && library is IndexedLocalLibrary && usesBoundedLocalWindow()
        logicalQueue = if (bounded) emptyList() else next
        localMediaEntries = if (bounded) playable.associate { it.second.id to it } else emptyMap()
        updating = true
        try {
            mutable.value = before.withQueueView(next, index)
            syncPlayerItems(player, playable)
        } finally { updating = false }
        publish(); checkpoint()
    }

    private fun load(queue: List<Track>, index: Int, position: Int) {
        val player = engine ?: return
        referenceOrder = null
        applyOrderingModes()
        updating = true
        try {
            player.pause()
            val playable = playableQueue(queue, index)
            val bounded = followLibrary && library is IndexedLocalLibrary && usesBoundedLocalWindow()
            logicalQueue = if (bounded) emptyList() else queue
            localMediaEntries = if (bounded) playable.associate { it.second.id to it } else emptyMap()
            waitingId = queue.getOrNull(index)?.takeIf { !it.available }?.id
            mutable.value = state.value.withQueueView(queue, index).copy(
                positionSeconds = position.coerceIn(0, queue.getOrNull(index)?.durationSeconds ?: 0),
                playing = false, connected = true, error = null)
            if (waitingId != null || playable.isEmpty()) player.clearMediaItems()
            else player.setMediaItems(playable.map { it.second.mediaItem(state.value.profileId) },
                playable.indexOfFirst { it.second.id == state.value.current?.id }.coerceAtLeast(0), state.value.positionSeconds.toLong() * 1000)
        } finally { updating = false }
        publish(); checkpoint()
    }

    private fun publish() {
        if (radioMode) return
        val player = engine ?: return
        val id = waitingId ?: player.currentMediaItem?.mediaId
        val before = state.value
        val base = if (referenceOrder != null || followLibrary && library is IndexedLocalLibrary && usesBoundedLocalWindow()) {
            val entry = localMediaEntries[id]
            before.copy(index = entry?.first?.takeIf { it >= 0 } ?: before.index,
                currentTrack = entry?.second ?: before.current)
        } else {
            val index = logicalQueue.indexOfFirst { it.id == id }.coerceAtLeast(0)
            before.withQueueView(logicalQueue, index)
        }
        mutable.value = base.copy(
            positionSeconds = if (waitingId != null) state.value.positionSeconds else (player.currentPosition.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            playing = if (state.value.wave && waveAdvance && state.value.waveLoading) waveResume else waitingId == null &&
                player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                player.playbackState != Player.STATE_ENDED && player.playerError == null,
            buffering = waitingId == null && player.playbackState == Player.STATE_BUFFERING,
            repeatMode = before.repeatMode, shuffle = before.shuffle)
    }
    private fun checkpoint() {
        if (radioMode) return
        val state = state.value
        if (userCommanded && !restoring && launchState.read(state.profileId).output == PlaybackOutput.MUSIC) {
            val wanted = if (state.wave && waveAdvance) waveResume else engine?.let {
                it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playerError == null
            } == true
            launchState.write(state.profileId, PlaybackOutput.MUSIC, wanted)
        }
        lastSavedSecond = state.positionSeconds
        val editor = prefs.edit().putString("profile", state.profileId)
            .putString("repeat:${state.profileId}", state.repeatMode.name).putBoolean("shuffle:${state.profileId}", state.shuffle)
        // An automatically followed library is reconstructed from its index. Save only
        // the current item's label for media-button resumption before that index loads.
        val references = if (followLibrary || referenceOrder != null) listOfNotNull(state.current) else state.queue
        val json = JSONObject().put("ids", JSONArray(if (followLibrary || referenceOrder != null) emptyList<String>() else state.queue.map(Track::id)))
            .put("current", state.current?.id).put("position", state.positionSeconds).put("followLibrary", followLibrary)
            .put("tracks", JSONArray(references.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("source", it.source.name).put("duration", it.durationSeconds).put("tint", it.tint).put("album", it.album).put("artwork", it.artworkUri)
                .put("albumId", it.albumId).put("artists", JSONArray(it.artists.map { artist -> JSONObject().put("id", artist.id).put("name", artist.name) })) }))
            .put("wave", state.wave).put("waveSession", waveBatch?.sessionId).put("waveCursor", waveBatch?.cursor)
            .put("waveSelection", if (state.wave) JSONObject().put("station", activeWaveRequest.station)
                .put("title", activeWaveRequest.title).put("settings", JSONArray(activeWaveRequest.settings)) else JSONObject.NULL)
            .put("origin", JSONObject().put("source", state.origin.source.name).put("title", state.origin.title)
                .put("wave", state.origin.wave?.json() ?: JSONObject.NULL))
            .put("continueWave", state.continueWave)
            .put("recommendations", state.recommendations)
            .put("waveBatches", JSONObject().apply { if (state.wave) state.queue.forEach { track -> put(track.id, waveItems[track.id]?.batchId) } })
        json.put("referenceOrder", referenceOrder != null)
        referenceOrder?.let { order ->
            if (persistedReferenceOrder !== order || persistedReferenceRevision != order.revision) {
                editor.putString("references:${state.profileId}", JSONArray(order.references.map(PlaybackReference::json)).toString())
                persistedReferenceOrder = order; persistedReferenceRevision = order.revision
            }
        }
        editor.putString("queue:${state.profileId}", json.toString()).apply()
    }
    private var lastSavedSecond = -1
}

private fun Track.mediaItem(profile: String) = MediaItem.Builder().setMediaId(id).setUri(if (source == Source.YANDEX)
    Uri.Builder().scheme("ymplayer2").authority("yandex").appendPath(profile).appendPath(id).build().toString() else uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setDisplayTitle(title).setSubtitle(artist).setDescription(album)
        .setArtworkUri(artworkUri?.let(Uri::parse)).build()).build()
private fun RepeatMode.toPlayerMode() = when (this) { RepeatMode.OFF -> Player.REPEAT_MODE_OFF; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.ONE -> Player.REPEAT_MODE_ONE }
private fun Int.toRepeatMode() = when (this) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }

private fun WaveRequest.json() = JSONObject().put("station", station).put("title", title).put("settings", JSONArray(settings))
private fun JSONObject.readWaveRequest() = WaveRequest(getString("station"), optString("title"),
    optJSONArray("settings")?.let { seeds -> (0 until seeds.length()).map { seeds.getString(it) } }.orEmpty())
private fun originForRestoredWave(origin: PlaybackOrigin, request: WaveRequest) =
    if (origin.source in setOf(PlaybackSource.MY_WAVE, PlaybackSource.OBJECT_WAVE)) origin.copy(wave = request)
    else PlaybackOrigin(PlaybackSource.MY_WAVE, request.title, request)
