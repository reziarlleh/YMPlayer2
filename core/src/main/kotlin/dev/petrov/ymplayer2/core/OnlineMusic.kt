package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class MusicKind(val label: String) { TRACKS("Треки"), ALBUMS("Альбомы"), ARTISTS("Исполнители"), PLAYLISTS("Плейлисты") }
data class MusicEntity(val id: String, val title: String, val kind: MusicKind, val ownerId: String? = null)
data class MusicEntry(val id: String, val title: String, val subtitle: String, val track: Track? = null, val entity: MusicEntity? = null)
data class MusicRequest(val query: String = "", val kind: MusicKind = MusicKind.TRACKS, val collection: Boolean = false, val entity: MusicEntity? = null, val recommended: Boolean = false)
data class MusicPage(val entries: List<MusicEntry>, val nextPage: Int? = null)
enum class MusicFailure { SIGN_IN, NETWORK, ACCESS, UNAVAILABLE, RESPONSE }
class MusicException(val failure: MusicFailure, val httpStatus: Int? = null) : Exception(failure.name)
fun MusicFailure.message() = when (this) {
    MusicFailure.SIGN_IN -> "Войдите в Яндекс в этом профиле."
    MusicFailure.NETWORK -> "Нет связи с Яндекс Музыкой. Проверьте сеть и повторите."
    MusicFailure.ACCESS -> "Яндекс не разрешил доступ. Проверьте аккаунт и подписку на Музыку."
    MusicFailure.UNAVAILABLE -> "Этот трек или список сейчас недоступен."
    MusicFailure.RESPONSE -> "Не удалось прочитать ответ Яндекса. Повторите запрос."
}

interface OnlineMusicApi {
    suspend fun page(profileId: String, request: MusicRequest, page: Int): MusicPage
    suspend fun stream(profileId: String, trackId: String, quality: AudioQuality = AudioQuality.AUTO): String
}
data class OnlineCatalogState(val profileId: String = "", val phase: AuthPhase = AuthPhase.LOADING, val tracks: List<Track> = emptyList()) {
    val enabled get() = phase == AuthPhase.SIGNED_IN
}
data class OnlineMusicState(
    val profileId: String = "", val signedIn: Boolean = false,
    val request: MusicRequest = MusicRequest(), val entries: List<MusicEntry> = emptyList(),
    val loading: Boolean = false, val loaded: Boolean = false, val nextPage: Int? = null,
    val failedPage: Int? = null, val issue: String? = null,
)

/** Main-dispatcher screen coordinator. Search cancellation is backed by explicit response tickets. */
class OnlineMusic(val accounts: AccountAuth, val api: OnlineMusicApi, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(OnlineMusicState())
    val state = mutable.asStateFlow()
    private val mutableCatalog = MutableStateFlow(OnlineCatalogState())
    val catalog = mutableCatalog.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private val parents = mutableListOf<OnlineMusicState>()
    private var artistCardDepth: Int? = null
    private var parentNeedsRefresh = false

    init {
        scope.launch {
            accounts.state.map { it.profileId to it.phase }.distinctUntilChanged().collect { (profile, phase) ->
                val previous = catalog.value
                mutableCatalog.value = OnlineCatalogState(profile, phase,
                    if (profile == previous.profileId && phase == AuthPhase.SIGNED_IN && previous.enabled) previous.tracks else emptyList())
                if (profile != previous.profileId || (phase == AuthPhase.SIGNED_IN) != previous.enabled) {
                    invalidateRequest(); parents.clear(); artistCardDepth = null
                    mutable.value = OnlineMusicState(profile, phase == AuthPhase.SIGNED_IN)
                }
            }
        }
    }

    private fun invalidateRequest() { generation++; job?.cancel(); job = null }
    fun tracksForPlayback(profileId: String): List<Track> {
        if (catalog.value.profileId != profileId || !catalog.value.enabled) return emptyList()
        // Visible results (including a restored parent page) stay playable after lookup-cache eviction.
        val visible = state.value.takeIf { it.profileId == profileId }?.entries?.mapNotNull(MusicEntry::track).orEmpty()
        return (catalog.value.tracks + visible).associateBy(Track::id).values.toList()
    }
    /** Metadata restored from history; stream access still goes through the current account/API. */
    fun restoreForPlayback(profileId: String, track: Track): Boolean {
        if (track.source != Source.YANDEX || catalog.value.profileId != profileId || !catalog.value.enabled) return false
        mutableCatalog.value = catalog.value.copy(tracks = (catalog.value.tracks + track.copy(uri = null, available = true))
            .associateBy(Track::id).values.toList().takeLast(4000))
        return true
    }
    fun search(query: String, kind: MusicKind = state.value.request.kind, debounce: Boolean = true) {
        val request = MusicRequest(query.take(200), kind)
        if (request == state.value.request && (state.value.loading || state.value.loaded)) return
        parents.clear(); invalidateRequest()
        mutable.value = OnlineMusicState(state.value.profileId, state.value.signedIn, request)
        if (query.isNotBlank()) load(0, if (debounce) 350 else 0)
    }
    fun collection(kind: MusicKind = MusicKind.TRACKS) {
        parents.clear(); invalidateRequest()
        mutable.value = OnlineMusicState(state.value.profileId, state.value.signedIn, MusicRequest(kind = kind, collection = true))
        load(0)
    }
    fun recommendations() {
        parents.clear(); invalidateRequest()
        mutable.value = OnlineMusicState(state.value.profileId, state.value.signedIn,
            MusicRequest(kind = MusicKind.PLAYLISTS, collection = true, recommended = true))
        load(0)
    }
    /** Refresh a visible account shelf after a confirmed mutation, without resetting search/details. */
    fun refreshCollection() {
        if (parents.any { it.request.collection }) parentNeedsRefresh = true
        if (state.value.request.collection && state.value.request.entity == null) load(0)
    }
    fun playlistChanged(playlist: CloudPlaylist, deleted: Boolean) {
        val entity = state.value.request.entity
        val matching = entity?.kind == MusicKind.PLAYLISTS && entity.id == playlist.id && entity.ownerId == playlist.ownerId
        if (matching) {
            if (deleted) { collection(MusicKind.PLAYLISTS); return }
            mutable.value = state.value.copy(request = state.value.request.copy(entity = playlist.entity()))
            load(0)
        }
        refreshCollection()
    }
    fun open(entity: MusicEntity, replace: Boolean = false) {
        if (!state.value.signedIn) return
        if (!replace) parents += state.value.copy(loading = false)
        invalidateRequest()
        mutable.value = state.value.copy(request = state.value.request.copy(entity = entity, kind = MusicKind.TRACKS), entries = emptyList(), loaded = false, nextPage = null, issue = null, failedPage = null)
        load(0)
    }
    fun artistSection(kind: MusicKind) {
        if (state.value.request.entity?.kind != MusicKind.ARTISTS || kind !in setOf(MusicKind.TRACKS, MusicKind.ALBUMS)) return
        invalidateRequest()
        mutable.value = state.value.copy(request = state.value.request.copy(kind = kind), entries = emptyList(), loaded = false, nextPage = null, issue = null, failedPage = null)
        load(0)
    }
    /** A card opened from player/queue must restore the catalog it temporarily covers. */
    fun openArtistCard(artist: ArtistRef) {
        if (!state.value.signedIn) return
        val depth = artistCardDepth
        if (depth == null) artistCardDepth = parents.size
        else while (parents.size > depth + 1) parents.removeAt(parents.lastIndex)
        open(MusicEntity(artist.id, artist.name, MusicKind.ARTISTS), replace = depth != null)
    }
    fun closeArtistCard() {
        val depth = artistCardDepth ?: return
        artistCardDepth = null
        while (parents.size > depth) up()
    }
    fun up(): Boolean {
        val previous = parents.removeLastOrNull() ?: return false
        invalidateRequest(); mutable.value = previous
        if (parentNeedsRefresh && previous.request.entity == null) { parentNeedsRefresh = false; load(0) }
        return true
    }
    fun more() { if (!state.value.loading) state.value.nextPage?.let { load(it) } }
    fun retry() { if (!state.value.loading) load(state.value.failedPage ?: 0) }
    /** A restored connection replaces a pending request against the old network. */
    fun reconnect() { load(state.value.failedPage ?: 0) }

    private fun load(page: Int, delayMillis: Long = 0) {
        if (!state.value.signedIn) return
        invalidateRequest()
        val ticket = generation
        val before = state.value
        mutable.value = before.copy(loading = true, issue = null, failedPage = null)
        job = scope.launch {
            try {
                delay(delayMillis)
                val response = api.page(before.profileId, before.request, page)
                ensureActive()
                if (generation != ticket || state.value.profileId != before.profileId) return@launch
                val entries = ((if (page == 0) emptyList() else before.entries) + response.entries).distinctBy(MusicEntry::id)
                val tracks = (catalog.value.tracks + response.entries.mapNotNull(MusicEntry::track)).associateBy(Track::id).values.toList().takeLast(4000)
                mutableCatalog.value = catalog.value.copy(tracks = tracks)
                // A duplicate/empty page cannot create an endless "more" loop.
                val next = response.nextPage?.takeIf { it > page && response.entries.isNotEmpty() && (page == 0 || entries.size > before.entries.size) }
                mutable.value = before.copy(entries = entries, loading = false, loaded = true, nextPage = next, failedPage = null, issue = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == ticket && state.value.profileId == before.profileId) mutable.value = before.copy(loading = false,
                    failedPage = page, issue = ((e as? MusicException)?.failure ?: MusicFailure.RESPONSE).message())
            }
        }
    }
}
