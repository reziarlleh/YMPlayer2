package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepeatMode { OFF, ALL, ONE;
    fun next() = entries[(ordinal + 1) % entries.size]
}

data class PlaybackState(
    val profileId: String,
    val queue: List<Track>,
    val index: Int = 0,
    val positionSeconds: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val connected: Boolean = true,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(index)
}

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun toggle()
    fun stop()
    fun seek(seconds: Int)
    fun skip(direction: Int)
    fun select(trackId: String)
    fun switchProfile(profileId: String)
    fun chooseSource(source: Source?)
    fun setRepeatMode(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)
    fun enqueue(trackId: String)
    fun moveInQueue(trackId: String, toIndex: Int)
    fun removeFromQueue(trackId: String)
    fun clearQueue()
}

data class PlaybackCheckpoint(val profileId: String, val queueIds: List<String>, val currentId: String?, val positionSeconds: Int,
    val repeatMode: RepeatMode = RepeatMode.OFF, val shuffle: Boolean = false)

fun PlaybackState.checkpoint() = PlaybackCheckpoint(profileId, queue.map(Track::id), current?.id, positionSeconds, repeatMode, shuffle)

/** Commands are serialized by the caller (UI thread). No timer and no sound in M1. */
class DemoPlaybackController(private val catalog: Catalog, restored: PlaybackCheckpoint? = null) : PlaybackController {
    private val sessions = mutableMapOf<String, PlaybackState>()
    private val mutableState = MutableStateFlow(restore(restored))
    override val state = mutableState.asStateFlow()
    private fun initial(id: String) = PlaybackState(id, catalog.tracks(id))
    private fun restore(checkpoint: PlaybackCheckpoint?): PlaybackState {
        if (checkpoint == null || catalog.profiles.none { it.id == checkpoint.profileId }) return initial(catalog.profiles.first().id)
        val allowed = catalog.tracks(checkpoint.profileId).associateBy(Track::id)
        val queue = checkpoint.queueIds.distinct().mapNotNull(allowed::get)
        val index = queue.indexOfFirst { it.id == checkpoint.currentId }.coerceAtLeast(0)
        return PlaybackState(checkpoint.profileId, queue, index,
            checkpoint.positionSeconds.coerceIn(0, queue.getOrNull(index)?.durationSeconds ?: 0), repeatMode = checkpoint.repeatMode, shuffle = checkpoint.shuffle)
    }

    override fun toggle() {
        val s = state.value
        if (s.current?.available == true) mutableState.value = s.copy(playing = !s.playing)
    }
    override fun stop() { mutableState.value = state.value.copy(playing = false, positionSeconds = 0) }
    override fun seek(seconds: Int) {
        mutableState.value = state.value.copy(positionSeconds = seconds.coerceIn(0, state.value.current?.durationSeconds ?: 0))
    }
    override fun skip(direction: Int) {
        if (direction == 0) return
        val s = state.value
        val order = s.queue.indices.filter { s.queue[it].available }.let { if (s.shuffle) it.shuffled(kotlin.random.Random(42)) else it }
        val position = order.indexOf(s.index)
        val step = direction.compareTo(0)
        val next = order.getOrNull(position + step) ?: if (s.repeatMode == RepeatMode.ALL && order.isNotEmpty()) order[if (step > 0) 0 else order.lastIndex] else null
        mutableState.value = if (next == null) s.copy(playing = false) else s.copy(index = next, positionSeconds = 0)
    }
    override fun select(trackId: String) {
        val s = state.value
        val track = catalog.tracks(s.profileId).firstOrNull { it.id == trackId && it.available } ?: return
        val queue = if (s.queue.any { it.id == trackId }) s.queue else catalog.tracks(s.profileId)
        mutableState.value = s.copy(queue = queue, index = queue.indexOf(track), positionSeconds = 0, playing = true)
    }
    override fun switchProfile(profileId: String) {
        if (catalog.profiles.none { it.id == profileId } || state.value.profileId == profileId) return
        val s = state.value.copy(playing = false)
        sessions[s.profileId] = s
        mutableState.value = (sessions[profileId] ?: initial(profileId)).copy(playing = false)
    }
    override fun chooseSource(source: Source?) {
        val s = state.value
        val queue = catalog.tracks(s.profileId).filter { source == null || it.source == source }
        mutableState.value = PlaybackState(s.profileId, queue, index = queue.indexOfFirst { it.available }.coerceAtLeast(0), repeatMode = s.repeatMode, shuffle = s.shuffle)
    }
    override fun setRepeatMode(mode: RepeatMode) { mutableState.value = state.value.copy(repeatMode = mode) }
    override fun setShuffle(enabled: Boolean) { mutableState.value = state.value.copy(shuffle = enabled) }
    override fun enqueue(trackId: String) {
        val s = state.value
        val track = catalog.tracks(s.profileId).find { it.id == trackId && it.available } ?: return
        if (s.queue.none { it.id == trackId }) mutableState.value = s.copy(queue = s.queue + track)
    }
    override fun moveInQueue(trackId: String, toIndex: Int) {
        val s = state.value
        val from = s.queue.indexOfFirst { it.id == trackId }
        if (from < 0 || toIndex !in s.queue.indices) return
        val queue = s.queue.toMutableList().apply { add(toIndex, removeAt(from)) }
        mutableState.value = s.copy(queue = queue, index = queue.indexOfFirst { it.id == s.current?.id }.coerceAtLeast(0))
    }
    override fun removeFromQueue(trackId: String) {
        val s = state.value
        val queue = s.queue.filterNot { it.id == trackId }
        if (queue.size == s.queue.size) return
        val removingCurrent = s.current?.id == trackId
        mutableState.value = s.copy(queue = queue,
            index = if (removingCurrent) s.index.coerceAtMost((queue.size - 1).coerceAtLeast(0)) else queue.indexOfFirst { it.id == s.current?.id }.coerceAtLeast(0),
            positionSeconds = if (removingCurrent) 0 else s.positionSeconds, playing = s.playing && !removingCurrent)
    }
    override fun clearQueue() { mutableState.value = state.value.copy(queue = emptyList(), index = 0, positionSeconds = 0, playing = false) }
}
