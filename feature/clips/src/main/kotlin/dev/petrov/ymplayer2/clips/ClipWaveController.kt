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

/** A video-only session. Playback history, pending requests and prefetch never enter the audio queue. */
class ClipWaveController(
    private val api: YandexClipApi,
    private val profileId: String,
    val player: ExoPlayer,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(ClipWaveState())
    val state = mutable.asStateFlow()
    private var sessionId = ""
    private val pending = ArrayDeque<YandexClip>()
    private val history = mutableListOf<YandexClip>()
    private var cursor = -1
    private var operation = 0
    private var loadingJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetched: Pair<String, ClipStream>? = null
    private var reportedStart = false
    private var closed = false
    private val listener = object : Player.Listener {
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
    fun start() {
        if (closed || loadingJob?.isActive == true || sessionId.isNotEmpty()) return
        mutable.value = ClipWaveState()
        loadingJob = scope.launch {
            val generation = ++operation
            try {
                val session = api.start(profileId)
                if (stale(generation)) return@launch
                sessionId = session.id
                pending.addAll(session.batch.clips)
                session.batch.clips.firstOrNull()?.let { send(it, ClipFeedback.QUEUE_STARTED) }
                chooseNext(generation)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (!stale(generation)) fail(e) }
        }
    }
    fun retry() {
        loadingJob?.cancel(); prefetchJob?.cancel(); ++operation
        sessionId = ""; pending.clear(); history.clear(); cursor = -1; prefetched = null
        player.stop(); start()
    }
    fun pause() { player.pause() }
    fun toggle() { if (player.isPlaying) player.pause() else if (mutable.value.clip != null) player.play() }
    fun previous() {
        if (cursor <= 0 || closed) return
        current()?.let { send(it, ClipFeedback.SKIPPED, player.currentPosition / 1000f) }
        load(history[--cursor], backwards = true)
    }
    fun next() = advance(true)
    private fun advance(manual: Boolean) {
        if (closed || mutable.value.loading || sessionId.isBlank()) return
        current()?.let { send(it, if (manual) ClipFeedback.SKIPPED else ClipFeedback.FINISHED,
            player.currentPosition / 1000f) }
        if (cursor < history.lastIndex) load(history[++cursor], backwards = false)
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
            if (pending.isEmpty()) {
                val batch = api.next(profileId, sessionId, history.takeLast(40).map(YandexClip::id))
                if (stale(generation)) return
                pending.addAll(batch.clips.filter { candidate -> history.none { it.id == candidate.id } })
                if (pending.isEmpty()) break
            }
            val clip = pending.removeFirst()
            if (history.any { it.id == clip.id }) continue
            val stream = resolve(clip) ?: continue
            if (stale(generation)) return
            history += clip
            cursor = history.lastIndex
            show(clip, stream)
            prefetch()
            return
        }
        mutable.value = mutable.value.copy(loading = false, issue = "Новых доступных клипов пока нет. Повторите позже.")
    }
    private fun load(clip: YandexClip, backwards: Boolean) {
        loadingJob?.cancel()
        prefetchJob?.cancel()
        loadingJob = scope.launch {
            val generation = ++operation
            mutable.value = mutable.value.copy(loading = true, issue = null)
            try {
                val stream = resolve(clip) ?: throw MusicException(MusicFailure.UNAVAILABLE)
                if (stale(generation)) return@launch
                show(clip, stream)
                if (!backwards) prefetch()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (!stale(generation)) fail(e) }
        }
    }
    private suspend fun resolve(clip: YandexClip): ClipStream? {
        prefetched?.takeIf { it.first == clip.id }?.let { prefetched = null; return it.second }
        return try { api.stream(profileId, clip) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    private fun show(clip: YandexClip, stream: ClipStream) {
        reportedStart = false
        val item = MediaItem.Builder().setUri(stream.url).apply { stream.mimeType?.let(::setMimeType) }.build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        mutable.value = ClipWaveState(clip = clip, nextClip = upcoming(), loading = false,
            canGoBack = cursor > 0, preview = stream.preview)
    }
    private fun prefetch() {
        prefetchJob?.cancel()
        val generation = operation
        prefetchJob = scope.launch {
            try {
                if (pending.isEmpty()) {
                    val batch = api.next(profileId, sessionId, history.takeLast(40).map(YandexClip::id))
                    if (stale(generation)) return@launch
                    pending.addAll(batch.clips.filter { candidate -> history.none { it.id == candidate.id } })
                }
                // Publish only a playable candidate, so "Далее" matches the actual next clip.
                var attempts = 0
                while (pending.isNotEmpty() && attempts++ < 12 && !stale(generation)) {
                    val candidate = pending.first()
                    val stream = try { api.stream(profileId, candidate) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    if (stale(generation)) return@launch
                    if (stream == null) { pending.removeFirst(); continue }
                    prefetched = candidate.id to stream
                    mutable.value = mutable.value.copy(nextClip = upcoming())
                    return@launch
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The normal request reports availability when Next is pressed. */ }
        }
    }
    private fun upcoming(): YandexClip? = history.getOrNull(cursor + 1)
        ?: pending.firstOrNull()?.takeIf { it.id == prefetched?.first }
    private fun send(clip: YandexClip, event: ClipFeedback, seconds: Float = 0f) {
        if (sessionId.isBlank()) return
        scope.launch { try { api.feedback(profileId, sessionId, clip, event, seconds) }
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
    }
}
