package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import dev.petrov.ymplayer2.playback.CarRow

internal class CarOnlineLibrary(private val app: PlayerApplication) {
    private val player get() = app.playback
    private val profile get() = player.state.value.profileId
    fun root() = listOf(
        button("wave", Msg.msg_de1ea8c09caa, player::playMyWave),
        collection(MusicKind.TRACKS, Msg.msg_840370e3fad2), collection(MusicKind.PLAYLISTS, Msg.msg_44d8126b8321),
        collection(MusicKind.ALBUMS, Msg.msg_b86ab3d74350), collection(MusicKind.ARTISTS, Msg.msg_263e20096cc0),
        folder("recommended", Msg.msg_fb7d156c5bfe) { page(MusicRequest(recommended = true)) },
        button("clip-audio", Msg.car_clip_audio, app::playCarClips),
    )

    private fun collection(kind: MusicKind, title: Msg) = folder(kind.name, title) {
        page(MusicRequest(kind = kind, collection = true))
    }
    private suspend fun page(request: MusicRequest, number: Int = 0): List<CarRow> {
        val result = app.online.api.page(profile, request, number)
        val tracks = result.entries.mapNotNull(MusicEntry::track).filter(Track::available)
        return buildList {
            if (number == 0) request.entity?.waveRequest()?.let { add(wave(it)) }
            if (tracks.isNotEmpty()) add(CarRow("play", tr(Msg.msg_bdd37eb21746), prepare = { prepareTrack(request, null, number, result) }))
            addAll(carGroups(result.entries) { entry -> row(entry, request, number, result) })
            val seeds = tracks.mapNotNull(Track::waveRequest)
            if (seeds.isNotEmpty()) add(folder("track-waves", Msg.car_track_waves) { carGroups(seeds, row = ::wave) })
            result.nextPage?.takeIf { it > number }?.let { next -> add(more(next.toString()) { page(request, next) }) }
        }
    }
    suspend fun search(query: String): List<CarRow> {
        val request = MusicRequest(query, topSearch = true)
        val result = app.online.api.page(profile, request, 0)
        return result.entries.filter { it.track?.available != false }.take(30).map { row(it, request, 0, result) }
    }
    private fun row(entry: MusicEntry, request: MusicRequest, number: Int, page: MusicPage): CarRow {
        entry.wave?.let { return wave(it.request, it.artworkUri) }
        entry.entity?.let { entity -> return CarRow(entry.id, entity.title, entry.subtitle, children = {
            page(MusicRequest(entity = entity))
        }) }
        val track = entry.track
        return CarRow(entry.id, entry.title, entry.subtitle, app.carArtwork.uri(track?.artworkUri),
            prepare = if (track?.available == true) suspend { prepareTrack(request, track.id, number, page) } else null)
    }
    private fun wave(seed: WaveRequest, art: String? = null) = CarRow("wave:${seed.station}", tr(Msg.wave_source_title, seed.title),
        artwork = app.carArtwork.uri(art), prepare = { { player.playWave(seed) } })

    private suspend fun prepareTrack(request: MusicRequest, first: String?, selectedPage: Int, selected: MusicPage): () -> Unit {
        val id = profile
        val tracks = linkedMapOf<String, Track>()
        var number = if (request.topSearch) selectedPage else 0
        do {
            val page = if (number == selectedPage) selected else app.online.api.page(id, request, number)
            val oldSize = tracks.size
            page.entries.mapNotNull(MusicEntry::track).filter(Track::available).forEach { tracks[it.id] = it }
            val next = page.nextPage ?: break
            if (request.topSearch || next <= number || page.entries.isEmpty() ||
                number > 0 && page.entries.any { it.track?.available == true } && tracks.size == oldSize) break
            number = next
        } while (profile == id)
        require(profile == id && tracks.isNotEmpty() && (first == null || first in tracks))
        val origin = request.playbackOrigin(app.accounts.state.value.account?.id)
        return { player.playCatalogTracks(tracks.values.toList(), first, origin) }
    }
}
