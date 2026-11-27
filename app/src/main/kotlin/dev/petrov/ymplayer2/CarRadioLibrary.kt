package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import dev.petrov.ymplayer2.playback.CarRow

internal class CarRadioLibrary(private val app: PlayerApplication) {
    private val api get() = app.radioCatalog.api
    fun root() = listOf(
        folder("favorites", Msg.radio_b71a4a6a4ffe) { stations(RadioListSource(RadioListKind.FAVOURITES)) },
        folder("all", Msg.radio_6341f756cd9c) { stations(RadioListSource(RadioListKind.ALL)) },
        folder("cities", Msg.radio_46dff034a662) { filters(api.cities(), RadioListKind.CITY) },
        folder("genres", Msg.radio_3a856dcd9b2f) { filters(api.genres(), RadioListKind.GENRE) },
    )
    private fun filters(options: List<RadioFilter>, kind: RadioListKind) = carGroups(options) { filter ->
        CarRow(filter.slug, filter.name, children = { stations(RadioListSource(kind, filter.slug)) })
    }
    suspend fun search(query: String) = stations(RadioListSource(RadioListKind.SEARCH, query), searchPreview = true)
    private suspend fun stations(source: RadioListSource, prior: RadioLineup? = null, searchPreview: Boolean = false): List<CarRow> {
        val profile = app.playback.state.value.profileId
        val page = source.page(api, profile, prior?.cursor)
        val lineup = (prior ?: RadioLineup(source, emptyList(), 0, null)).append(page)
        val start = prior?.stations?.size ?: 0
        val indices = lineup.stations.indices.drop(start)
        val rows = carGroups(if (searchPreview) indices.take(20) else indices) { index ->
            val station = lineup.stations[index]
            CarRow("fm:${station.slug}:${station.streamSlug.orEmpty()}", station.name, station.regionName,
                app.carArtwork.uri(station.logoUri), prepare = { {
                    app.radio.switchProfile(profile)
                    app.radio.play(station, source.region, lineup.copy(index = index))
                } })
        }
        return if (searchPreview) rows else rows + listOfNotNull(lineup.cursor?.let { more(it) { stations(source, lineup) } })
    }
}
