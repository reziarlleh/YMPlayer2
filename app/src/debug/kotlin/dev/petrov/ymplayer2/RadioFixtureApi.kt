package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*

/** Explicit station/API fixture; media itself is decoded by the real shared Media3 engine. */
class RadioFixtureApi(private val uri: () -> String) : RadioApi {
    val one = RadioStation("one", "Тестовое радио", streamSlug = "moscow", regionName = "Москва")
    val two = one.copy(slug = "two", name = "Рок-эфир")
    var gate: CompletableDeferred<Unit>? = null
    var failures = 0
    var requests = 0
    val likes = mutableMapOf<String, Set<String>>()
    override suspend fun stations(region: String?, cursor: String?) = RadioPage(listOf(one, two))
    override suspend fun station(slug: String, region: String?) = if (slug == "one") one else two
    override suspend fun cities() = listOf(RadioFilter("moscow", "Москва"))
    override suspend fun genres() = listOf(RadioFilter("rock", "Рок"))
    override suspend fun city(slug: String) = RadioPage(listOf(one))
    override suspend fun genre(slug: String, region: String?) = RadioPage(listOf(two))
    override suspend fun search(query: String, region: String?, cursor: String?) = RadioPage(listOf(one, two).filter { it.name.contains(query, true) })
    override suspend fun favourites(profile: String, region: String?, cursor: String?) = RadioPage(listOf(one, two).filter { it.slug in likes[profile].orEmpty() })
    override suspend fun favouriteSlugs(profile: String) = likes[profile].orEmpty()
    override suspend fun setFavourite(profile: String, slug: String, liked: Boolean) {
        likes[profile] = if (liked) likes[profile].orEmpty() + slug else likes[profile].orEmpty() - slug
    }
    override suspend fun stream(station: RadioStation, region: String?): RadioStream {
        requests++; gate?.await()
        if (failures > 0) { failures--; throw RadioException(RadioIssue.NETWORK) }
        return RadioStream(station, "moscow", uri())
    }
    override suspend fun onAir(stationSlug: String, streamSlug: String) = RadioOnAir("Тестовая композиция", "Тестовый исполнитель", 5000)
}
