package dev.petrov.ymplayer2.core

enum class RadioListKind { ALL, FAVOURITES, CITY, GENRE, SEARCH }

data class RadioListSource(val kind: RadioListKind, val value: String = "") {
    val region: String? get() = value.takeIf { kind == RadioListKind.CITY }
    suspend fun page(api: RadioApi, profile: String, cursor: String?) = when (kind) {
        RadioListKind.ALL -> api.stations(null, cursor)
        RadioListKind.FAVOURITES -> api.favourites(profile, null, cursor)
        RadioListKind.CITY -> api.city(value, cursor)
        RadioListKind.GENRE -> api.genre(value, null, cursor)
        RadioListKind.SEARCH -> api.search(value, null, cursor)
    }
}

internal fun RadioCatalogState.listSource(favourites: Boolean = false): RadioListSource = when {
    favourites -> RadioListSource(RadioListKind.FAVOURITES)
    query.isNotBlank() -> RadioListSource(RadioListKind.SEARCH, query.trim())
    tab == RadioTab.CITIES && filter != null -> RadioListSource(RadioListKind.CITY, filter.slug)
    tab == RadioTab.GENRES && filter != null -> RadioListSource(RadioListKind.GENRE, filter.slug)
    else -> RadioListSource(RadioListKind.ALL)
}

/** Retains the station list selected for listening while the user browses another screen. */
data class RadioLineup(val source: RadioListSource, val stations: List<RadioStation>, val index: Int, val cursor: String?) {
    val canPrevious get() = index > 0
    val canNext get() = index < stations.lastIndex || cursor != null

    fun append(page: RadioPage): RadioLineup {
        val rows = if (source.kind == RadioListKind.FAVOURITES) (stations + page.stations).distinctBy { it.slug }
            else (stations + page.stations).distinctBy { it.slug to it.streamSlug }
        val next = page.cursor?.takeIf { page.hasNext && it.isNotBlank() && it != cursor && rows.size > stations.size }
        return copy(stations = rows, cursor = next)
    }
}

internal fun RadioCatalogState.lineup(station: RadioStation, fromFavourites: Boolean): RadioLineup? {
    val rows = if (fromFavourites) favourites else stations
    val exact = rows.indexOfFirst { it.slug == station.slug && it.streamSlug == station.streamSlug }
    val index = if (exact >= 0) exact else rows.indexOfFirst { it.slug == station.slug }
    if (index < 0) return null
    val cursor = if (fromFavourites) favouritesCursor.takeIf { favouritesHaveNext } else cursor.takeIf { hasNext }
    return RadioLineup(listSource(fromFavourites), rows, index, cursor)
}
