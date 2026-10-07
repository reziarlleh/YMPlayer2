package dev.petrov.ymplayer2.clips

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.core.MusicException
import dev.petrov.ymplayer2.core.MusicFailure
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClipWaveState(
    val clip: YandexClip? = null,
    val nextClip: YandexClip? = null,
    val loading: Boolean = true,
    val playing: Boolean = false,
    val issue: String? = null,
    val canGoBack: Boolean = false,
    val preview: Boolean = false,
)

private data class QueuedClip(val clip: YandexClip, val sessionId: String)

/** A video-only session. Playback history, pending requests and prefetch never enter the audio queue. */
class ClipWaveController(
    private val api: YandexClipApi,
    private val profileId: String,
    val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val preloader: ClipMediaPreloader? = null,
    private val saveCheckpoint: (ClipCheckpoint) -> Unit = {},
) {
    private val mutable = MutableStateFlow(ClipWaveState())
    val state = mutable.asStateFlow()
    private var sessionId = ""
    private val pending = ArrayDeque<QueuedClip>()
    private val history = mutableListOf<QueuedClip>()
    private var cursor = -1
    private var operation = 0
    private var loadingJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetched: Pair<String, ClipStream>? = null
    private val announcedSessions = linkedSetOf<String>()
    private var reportedStart = false
    private var closed = false
    private var backgroundPlaying: Boolean? = null
    private var savedSecond = -1L
    fun checkpoint(force: Boolean = false) {
        val clip = current() ?: return
        if (backgroundPlaying != null || closed || mutable.value.loading) return
        val position = player.currentPosition.coerceAtLeast(0)
        if (!force && position / 1000 == savedSecond) return
        savedSecond = position / 1000
        saveCheckpoint(ClipCheckpoint(clip.clip, clip.sessionId, position, player.playWhenReady))
    }
    fun suspendForBackground() {
        if (backgroundPlaying != null) return
        checkpoint(force = true)
        backgroundPlaying = player.playWhenReady
        player.pause()
    }
    fun returnFromBackground() {
        val resume = backgroundPlaying ?: return
        backgroundPlaying = null
        if (resume) player.play()
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { checkpoint(force = true) }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mutable.value = mutable.value.copy(playing = isPlaying)
            if (isPlaying && !reportedStart) {
                reportedStart = true
                current()?.let { send(it, ClipFeedback.STARTED) }
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && !closed) advance(false)
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            mutable.value = mutable.value.copy(loading = false, issue = "Не удалось воспроизвести клип. Попробуйте следующий.")
        }
    }

    init { player.addListener(listener) }
    fun start(checkpoint: ClipCheckpoint? = null) {
        if (closed || loadingJob?.isActive == true || sessionId.isNotEmpty()) return
        mutable.value = ClipWaveState()
        loadingJob = scope.launch {
            val generation = ++operation
            try {
                if (checkpoint != null) {
                    sessionId = checkpoint.sessionId
                    val restored = QueuedClip(checkpoint.clip, checkpoint.sessionId)
                    val stream = resolve(restored) ?: throw MusicException(MusicFailure.UNAVAILABLE)
                    if (stale(generation)) return@launch
                    history += restored; cursor = 0
                    show(restored, stream, checkpoint.positionMs, checkpoint.playing)
                    prefetch()
                    return@launch
                }
                val session = api.start(profileId)
                if (stale(generation)) return@launch
                sessionId = session.id
                pending.addAll(session.batch.clips.map { QueuedClip(it, session.id) })
                chooseNext(generation)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (!stale(generation)) fail(e) }
        }
    }
    fun retry() = retryFrom(null)
    fun retryFrom(checkpoint: ClipCheckpoint?) {
        loadingJob?.cancel(); prefetchJob?.cancel(); ++operation
        sessionId = ""; pending.clear(); history.clear(); cursor = -1; prefetched = null
        announcedSessions.clear()
        player.stop(); preloader?.reset(); start(checkpoint)
    }
    fun pause() { backgroundPlaying = null; player.pause(); checkpoint(force = true) }
    fun toggle() {
        if (player.playWhenReady) player.pause() else if (mutable.value.clip != null) player.play()
        checkpoint(force = true)
    }
    fun previous() {
        if (cursor <= 0 || closed) return
        current()?.let { send(it, ClipFeedback.SKIPPED, player.currentPosition / 1000f) }
        load(history[--cursor])
    }
    fun next() = advance(true)
    private fun advance(manual: Boolean) {
        if (closed || mutable.value.loading || sessionId.isBlank()) return
        current()?.let { send(it, if (manual) ClipFeedback.SKIPPED else ClipFeedback.FINISHED,
            player.currentPosition / 1000f) }
        if (cursor < history.lastIndex) load(history[++cursor])
        else {
            loadingJob?.cancel()
            loadingJob = scope.launch {
                val generation = ++operation
                mutable.value = mutable.value.copy(loading = true, issue = null)
                try { chooseNext(generation) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (!stale(generation)) fail(e) }
            }
        }
    }
    private suspend fun chooseNext(generation: Int) {
        var attempts = 0
        while (!stale(generation) && attempts++ < 12) {
            if (pending.isEmpty()) prefetchJob?.join()
            if (pending.isEmpty()) fetchNextBatch(generation)
            if (pending.isEmpty()) break
            val clip = pending.removeFirst()
            if (history.any { it.clip.id == clip.clip.id }) continue
            val stream = resolve(clip) ?: continue
            if (stale(generation)) return
            history += clip
            if (history.size > 40) history.removeAt(0)
            cursor = history.lastIndex
            show(clip, stream)
            prefetch()
            return
        }
        mutable.value = mutable.value.copy(loading = false, issue = "Новых доступных клипов пока нет. Повторите позже.")
    }
    private fun load(clip: QueuedClip) {
        loadingJob?.cancel()
        prefetchJob?.cancel()
        loadingJob = scope.launch {
            val generation = ++operation
            mutable.value = mutable.value.copy(loading = true, issue = null)
            try {
                val stream = resolve(clip) ?: throw MusicException(MusicFailure.UNAVAILABLE)
                if (stale(generation)) return@launch
                show(clip, stream)
                prefetch()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (!stale(generation)) fail(e) }
        }
    }
    private suspend fun resolve(clip: QueuedClip): ClipStream? {
        prefetched?.takeIf { it.first == clip.clip.id }?.let { prefetched = null; return it.second }
        return try { api.stream(profileId, clip.clip) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    private fun show(clip: QueuedClip, stream: ClipStream, positionMs: Long = 0, playing: Boolean = true) {
        if (announcedSessions.add(clip.sessionId)) {
            send(clip, ClipFeedback.QUEUE_STARTED)
            if (announcedSessions.size > 40) announcedSessions.remove(announcedSessions.first())
        }
        reportedStart = false
        val item = mediaItem(clip.clip, stream)
        if (preloader != null) preloader.play(item, positionMs, playing && backgroundPlaying == null) else {
            player.setMediaItem(item)
            player.seekTo(positionMs.coerceAtLeast(0))
            player.playWhenReady = playing && backgroundPlaying == null
            player.prepare()
        }
        if (backgroundPlaying != null) backgroundPlaying = playing
        mutable.value = ClipWaveState(clip = clip.clip, nextClip = upcoming(), loading = false,
            canGoBack = cursor > 0, preview = stream.preview)
        checkpoint(force = true)
    }
    private fun prefetch() {
        prefetchJob?.cancel()
        val generation = operation
        if (cursor < history.lastIndex) {
            val candidate = history[cursor + 1]
            prefetchJob = scope.launch {
                val stream = resolve(candidate) ?: return@launch
                if (stale(generation)) return@launch
                prefetched = candidate.clip.id to stream
                try { preloader?.warmNext(mediaItem(candidate.clip, stream)) }
                catch (_: Exception) { /* URL prefetch still serves the next clip. */ }
            }
            return
        }
        prefetchJob = scope.launch {
            try {
                // Publish only a playable candidate, so "Далее" matches the actual next clip.
                var attempts = 0
                while (attempts++ < 12 && !stale(generation)) {
                    if (pending.isEmpty()) fetchNextBatch(generation)
                    if (pending.isEmpty() || stale(generation)) return@launch
                    val candidate = pending.first()
                    if (history.any { it.clip.id == candidate.clip.id }) { pending.removeFirst(); continue }
                    val stream = try { api.stream(profileId, candidate.clip) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    if (stale(generation)) return@launch
                    if (stream == null) { pending.removeFirst(); continue }
                    prefetched = candidate.clip.id to stream
                    mutable.value = mutable.value.copy(nextClip = upcoming())
                    try { preloader?.warmNext(mediaItem(candidate.clip, stream)) }
                    catch (_: Exception) { /* URL prefetch still serves the next clip. */ }
                    return@launch
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The normal request reports availability when Next is pressed. */ }
        }
    }
    private suspend fun fetchNextBatch(generation: Int) {
        val batch = try { api.next(profileId, sessionId,
            history.filter { it.sessionId == sessionId }.takeLast(40).map { it.clip.id }) }
        catch (e: MusicException) {
            if (e.failure !in setOf(MusicFailure.RESPONSE, MusicFailure.UNAVAILABLE)) throw e
            ClipBatch(emptyList(), false) // An expired restored rotor gets a fresh session below.
        }
        if (stale(generation)) return
        pending.addAll(batch.clips.filter { candidate -> history.none { it.clip.id == candidate.id } }
            .map { QueuedClip(it, sessionId) })
        if (pending.isNotEmpty()) return

        // Match 1.x: a drained rotor gets one fresh session, not an endless /next loop.
        val restarted = api.start(profileId)
        if (stale(generation)) return
        sessionId = restarted.id
        pending.addAll(restarted.batch.clips.filter { candidate -> history.none { it.clip.id == candidate.id } }
            .map { QueuedClip(it, restarted.id) })
    }
    private fun upcoming(): YandexClip? = history.getOrNull(cursor + 1)?.clip
        ?: pending.firstOrNull()?.takeIf { it.clip.id == prefetched?.first }?.clip
    private fun mediaItem(clip: YandexClip, stream: ClipStream) = MediaItem.Builder()
        .setMediaId(clip.id).setUri(stream.url).apply { stream.mimeType?.let(::setMimeType) }.build()
    private fun send(clip: QueuedClip, event: ClipFeedback, seconds: Float = 0f) {
        if (clip.sessionId.isBlank()) return
        scope.launch { try { api.feedback(profileId, clip.sessionId, clip.clip, event, seconds) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Feedback cannot interrupt video. */ } }
    }
    private fun fail(e: Exception) {
        val message = when ((e as? MusicException)?.failure) {
            MusicFailure.SIGN_IN -> "Войдите в Яндекс Музыку для просмотра клипов."
            MusicFailure.ACCESS -> "Войдите в Яндекс Музыку для просмотра клипов."
            MusicFailure.NETWORK -> "Нет связи с Яндексом. Проверьте сеть и повторите."
            else -> "Не удалось загрузить клипы. Повторите позже."
        }
        mutable.value = mutable.value.copy(loading = false, issue = message)
    }
    private fun current() = history.getOrNull(cursor)
    private fun stale(generation: Int) = closed || generation != operation
    fun close() {
        closed = true; ++operation
        loadingJob?.cancel(); prefetchJob?.cancel()
        player.removeListener(listener)
        player.release()
        preloader?.release()
    }
}
