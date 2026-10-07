package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** FM stations are not tracks or Music/rotor queues. No private account data or stream URL in UI state. */
data class RadioStation(val slug: String, val name: String, val logoUri: String? = null,
    val logoColor: String? = null, val streamSlug: String? = null, val regionName: String? = null,
    val description: String? = null)
data class RadioFilter(val slug: String, val name: String)
data class RadioPage(val stations: List<RadioStation>, val hasNext: Boolean = false, val cursor: String? = null)
data class RadioOnAir(val title: String = "", val artist: String = "", val pollAfterMs: Long = 30_000)
class RadioStream(val station: RadioStation, val streamSlug: String, val url: String) {
    override fun toString() = "RadioStream(redacted)"
}
enum class RadioIssue { NETWORK, ACCESS, UNAVAILABLE, RESPONSE }
class RadioException(val issue: RadioIssue) : Exception(issue.name)
interface RadioApi {
    suspend fun stations(region: String?, cursor: String? = null): RadioPage
    suspend fun station(slug: String, region: String?): RadioStation
    suspend fun cities(): List<RadioFilter>
    suspend fun genres(): List<RadioFilter>
    suspend fun city(slug: String, cursor: String? = null): RadioPage
    suspend fun genre(slug: String, region: String?, cursor: String? = null): RadioPage
    suspend fun search(query: String, region: String?, cursor: String? = null): RadioPage
    suspend fun favourites(profile: String, region: String?, cursor: String? = null): RadioPage
    suspend fun favouriteSlugs(profile: String): Set<String>
    suspend fun setFavourite(profile: String, slug: String, liked: Boolean)
    suspend fun stream(station: RadioStation, region: String?): RadioStream
    suspend fun onAir(stationSlug: String, streamSlug: String): RadioOnAir
}
data class RadioPlaybackState(val profileId: String = "", val station: RadioStation? = null,
    val onAir: RadioOnAir = RadioOnAir(), val ownsOutput: Boolean = false,
    val playing: Boolean = false, val buffering: Boolean = false, val reconnecting: Boolean = false,
    val issue: RadioIssue? = null)
interface RadioAudio {
    val state: StateFlow<RadioPlaybackState>
    fun switchProfile(profile: String)
    fun play(station: RadioStation? = state.value.station, region: String? = null)
    fun stop()
}
enum class RadioTab { COLLECTION, CITIES, GENRES, ALL }
data class RadioNavigation(val tab: RadioTab = RadioTab.COLLECTION, val query: String = "", val filter: RadioFilter? = null)
data class RadioCatalogState(val profileId: String = "", val signedIn: Boolean = false,
    val tab: RadioTab = RadioTab.COLLECTION, val query: String = "", val filter: RadioFilter? = null,
    val stations: List<RadioStation> = emptyList(), val favourites: List<RadioStation> = emptyList(),
    val favouriteSlugs: Set<String> = emptySet(), val cities: List<RadioFilter> = emptyList(),
    val genres: List<RadioFilter> = emptyList(), val busy: Boolean = false,
    val citiesBusy: Boolean = false, val genresBusy: Boolean = false,
    val favouritesBusy: Boolean = false, val pendingLikes: Set<String> = emptySet(),
    val issue: RadioIssue? = null, val collectionIssue: RadioIssue? = null,
    val hasNext: Boolean = false, val cursor: String? = null,
    val favouritesHaveNext: Boolean = false, val favouritesCursor: String? = null,
    val citiesIssue: RadioIssue? = null, val genresIssue: RadioIssue? = null,
    val detail: RadioStation? = null, val detailBusy: Boolean = false, val detailIssue: RadioIssue? = null)

/** Commands run on the serialized UI dispatcher. Responses cannot cross a profile/logout or filter change. */
class RadioController(val accounts: AccountAuth, val api: RadioApi, val audio: RadioAudio, private val scope: CoroutineScope,
    private val readNavigation: (String) -> RadioNavigation? = { null },
    private val saveNavigation: (String, RadioNavigation) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(RadioCatalogState())
    val state = mutable.asStateFlow()
    val playback get() = audio.state
    private var opened = false
    private var epoch = 0L
    private var searchGeneration = 0L
    private var listJob: Job? = null
    private var collectionJob: Job? = null
    private var citiesLoaded = false
    private var genresLoaded = false
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    private val likeJobs = mutableMapOf<String, Job>()
    init { scope.launch {
        accounts.state.map { Triple(it.profileId, it.phase == AuthPhase.SIGNED_IN, it.account?.id) }
            .distinctUntilChanged().collect { (profile, signed, _) ->
                epoch++; collectionJob?.cancel(); likeJobs.values.toList().forEach(Job::cancel); likeJobs.clear()
                if (profile != state.value.profileId) {
                    closeStation()
                    listJob?.cancel(); searchGeneration++
                    audio.switchProfile(profile)
                    val navigation = readNavigation(profile) ?: RadioNavigation()
                    mutable.value = RadioCatalogState(profile, signed, cities = state.value.cities, genres = state.value.genres,
                        tab = navigation.tab, query = navigation.query.take(160), filter = navigation.filter)
                    if (opened) loadList()
                } else mutable.value = state.value.copy(signedIn = signed, favourites = emptyList(), favouriteSlugs = emptySet(),
                    pendingLikes = emptySet(), favouritesBusy = false, collectionIssue = null,
                    favouritesHaveNext = false, favouritesCursor = null)
                if (opened) refreshCollection()
            }
    } }
    fun open() {
        if (!opened) {
            opened = true; loadList()
            loadOptions(RadioTab.CITIES); loadOptions(RadioTab.GENRES)
        }
        refreshCollection()
    }
    fun tab(tab: RadioTab) {
        if (tab in setOf(RadioTab.CITIES, RadioTab.GENRES)) loadOptions(tab)
        if (state.value.tab == tab) return
        mutable.value = state.value.copy(tab = tab, filter = null)
        persistNavigation()
        loadList()
        if (tab == RadioTab.COLLECTION) refreshCollection()
    }
    private fun loadOptions(tab: RadioTab, refresh: Boolean = false) {
        val city = tab == RadioTab.CITIES
        if (if (city) state.value.citiesBusy else state.value.genresBusy) return
        if (!refresh && if (city) citiesLoaded else genresLoaded) return
        mutable.value = if (city) state.value.copy(citiesBusy = true, citiesIssue = null) else state.value.copy(genresBusy = true, genresIssue = null)
        scope.launch {
            try {
                // Await first: copy's receiver must be the current state, not a pre-request snapshot.
                val options = if (city) api.cities() else api.genres()
                if (city) { citiesLoaded = true; mutable.value = state.value.copy(cities = options) }
                else { genresLoaded = true; mutable.value = state.value.copy(genres = options) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = if (city) state.value.copy(citiesIssue = e.radioIssue()) else state.value.copy(genresIssue = e.radioIssue()) }
            finally { mutable.value = if (city) state.value.copy(citiesBusy = false) else state.value.copy(genresBusy = false) }
        }
    }
    fun filter(filter: RadioFilter?) { mutable.value = state.value.copy(filter = filter); persistNavigation(); loadList() }
    fun search(query: String) {
        val value = query.take(160)
        if (value == state.value.query) return
        mutable.value = state.value.copy(query = value)
        persistNavigation()
        loadList(debounce = true)
    }
    private fun persistNavigation() {
        val state = state.value
        if (state.profileId.isNotBlank()) saveNavigation(state.profileId, RadioNavigation(state.tab, state.query, state.filter))
    }
    fun refresh() { loadList(); refreshCollection(); if (state.value.tab in setOf(RadioTab.CITIES, RadioTab.GENRES)) loadOptions(state.value.tab, refresh = true) }
    fun more() { if (!state.value.busy && state.value.hasNext && state.value.issue == null) loadList(append = true) }
    fun retryMore() { if (!state.value.busy && state.value.hasNext) loadList(append = true) }
    fun openStation(station: RadioStation) {
        val ticket = ++detailGeneration
        detailJob?.cancel()
        val profile = state.value.profileId
        val region = region(state.value)
        mutable.value = state.value.copy(detail = station, detailBusy = true, detailIssue = null)
        detailJob = scope.launch {
            try {
                val full = api.station(station.slug, region)
                if (ticket == detailGeneration && profile == state.value.profileId) mutable.value = state.value.copy(
                    detail = full.copy(streamSlug = station.streamSlug ?: full.streamSlug, regionName = station.regionName ?: full.regionName), detailBusy = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == detailGeneration && profile == state.value.profileId) mutable.value = state.value.copy(detailBusy = false, detailIssue = e.radioIssue()) }
        }
    }
    fun closeStation() { detailGeneration++; detailJob?.cancel(); mutable.value = state.value.copy(detail = null, detailBusy = false, detailIssue = null) }
    private fun region(s: RadioCatalogState) = s.filter?.slug?.takeIf { s.tab == RadioTab.CITIES }
    private fun loadList(append: Boolean = false, debounce: Boolean = false) {
        val ticket = ++searchGeneration
        listJob?.cancel()
        val request = state.value
        mutable.value = request.copy(busy = true, issue = null,
            stations = if (append) request.stations else emptyList(), hasNext = if (append) request.hasNext else false,
            cursor = if (append) request.cursor else null)
        listJob = scope.launch {
            try {
                if (debounce) delay(350)
                val page = when {
                    request.query.isNotBlank() -> api.search(request.query.trim(), null, if (append) request.cursor else null)
                    request.tab == RadioTab.CITIES && request.filter != null -> api.city(request.filter.slug, if (append) request.cursor else null)
                    request.tab == RadioTab.GENRES && request.filter != null -> api.genre(request.filter.slug, null, if (append) request.cursor else null)
                    else -> api.stations(region(request), if (append) request.cursor else null)
                }
                if (ticket == searchGeneration) mutable.value = state.value.copy(busy = false,
                    stations = ((if (append) request.stations else emptyList()) + page.stations).distinctBy { "${it.slug}:${it.streamSlug}" },
                    hasNext = page.hasNext && page.cursor != (if (append) request.cursor else null), cursor = page.cursor)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == searchGeneration) mutable.value = state.value.copy(busy = false, issue = e.radioIssue()) }
        }
    }
    fun refreshCollection(more: Boolean = false) {
        val request = state.value
        if (!request.signedIn || request.profileId.isBlank() || request.favouritesBusy || request.pendingLikes.isNotEmpty()) return
        if (more && !request.favouritesHaveNext) return
        val ticket = epoch
        collectionJob?.cancel()
        mutable.value = state.value.copy(favouritesBusy = true, collectionIssue = null)
        collectionJob = scope.launch {
            try {
                val slugs = api.favouriteSlugs(request.profileId)
                val page = api.favourites(request.profileId, null, if (more) request.favouritesCursor else null)
                if (ticket == epoch) mutable.value = state.value.copy(favouritesBusy = false, favouriteSlugs = slugs,
                    favourites = ((if (more) request.favourites else emptyList()) + page.stations).distinctBy { it.slug },
                    favouritesHaveNext = page.hasNext && page.cursor != (if (more) request.favouritesCursor else null), favouritesCursor = page.cursor)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) mutable.value = state.value.copy(favouritesBusy = false, collectionIssue = e.radioIssue()) }
        }
    }
    fun like(station: RadioStation) {
        val request = state.value
        if (!request.signedIn || station.slug in request.pendingLikes || request.favouritesBusy) return
        val ticket = epoch
        val liked = station.slug !in request.favouriteSlugs
        mutable.value = request.copy(pendingLikes = request.pendingLikes + station.slug, collectionIssue = null)
        likeJobs[station.slug] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                api.setFavourite(request.profileId, station.slug, liked)
                if (ticket == epoch) mutable.value = state.value.copy(
                    favouriteSlugs = if (liked) state.value.favouriteSlugs + station.slug else state.value.favouriteSlugs - station.slug,
                    favourites = if (liked) (listOf(station) + state.value.favourites).distinctBy { it.slug }
                        else state.value.favourites.filterNot { it.slug == station.slug })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) mutable.value = state.value.copy(collectionIssue = e.radioIssue()) }
            finally { if (ticket == epoch) { mutable.value = state.value.copy(pendingLikes = state.value.pendingLikes - station.slug); likeJobs.remove(station.slug) } }
        }
        likeJobs[station.slug]?.start()
    }
    fun play(station: RadioStation? = playback.value.station) = audio.play(station, region(state.value))
}
fun Exception.radioIssue(): RadioIssue = when (this) {
    is RadioException -> issue
    is MusicException -> if (failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS)) RadioIssue.ACCESS else RadioIssue.NETWORK
    else -> RadioIssue.NETWORK
}
