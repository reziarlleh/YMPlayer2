package dev.petrov.ymplayer2.core

enum class PlaybackSource { DEVICE, MY_WAVE, OFFLINE, LOCAL_FAVORITES, YANDEX_LIKES, LIST, OBJECT_WAVE }

/** A label and a radio seed, not a second queue or a copy of the remote collection. */
data class PlaybackOrigin(val source: PlaybackSource = PlaybackSource.DEVICE, val title: String = "",
    val wave: WaveRequest? = null)

fun MusicEntity.waveRequest(): WaveRequest? {
    if (!id.matches(Regex("[0-9]+"))) return null
    val station = when (kind) {
        MusicKind.TRACKS -> "track:$id"
        MusicKind.ARTISTS -> "artist:$id"
        MusicKind.ALBUMS -> "album:$id"
        MusicKind.PLAYLISTS -> ownerId?.takeIf { it.matches(Regex("[0-9]+")) }?.let { "playlist:${it}_$id" } ?: return null
    }
    return WaveRequest(station, title)
}

fun Track.waveRequest(): WaveRequest? = if (source == Source.YANDEX)
    MusicEntity(tasteTarget().key, title, MusicKind.TRACKS).waveRequest() else null

fun MusicRequest.playbackOrigin(accountId: String?): PlaybackOrigin = when {
    entity != null -> PlaybackOrigin(PlaybackSource.LIST, entity.title, entity.waveRequest())
    collection && !recommended && kind == MusicKind.TRACKS -> PlaybackOrigin(PlaybackSource.YANDEX_LIKES,
        wave = accountId?.let { MusicEntity("3", "Мне нравится", MusicKind.PLAYLISTS, it).waveRequest() })
    else -> PlaybackOrigin(PlaybackSource.LIST)
}

/** One authoritative transition, shared by UI, MediaSession and restoration. */
fun PlaybackState.withRepeat(mode: RepeatMode) = copy(repeatMode = mode,
    shuffle = if (mode != RepeatMode.OFF) false else shuffle,
    continueWave = if (mode != RepeatMode.OFF) false else continueWave)
fun PlaybackState.withShuffle(enabled: Boolean) = copy(shuffle = enabled,
    repeatMode = if (enabled) RepeatMode.OFF else repeatMode,
    continueWave = if (enabled) false else continueWave)
fun PlaybackState.withContinuation(enabled: Boolean) = if (enabled && (origin.wave == null || wave)) this else copy(continueWave = enabled,
    repeatMode = if (enabled) RepeatMode.OFF else repeatMode, shuffle = if (enabled) false else shuffle)
