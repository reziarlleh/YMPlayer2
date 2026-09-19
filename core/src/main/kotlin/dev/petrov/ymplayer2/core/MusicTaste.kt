package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TasteKind(val label: String) { TRACK("Трек"), ARTIST("Исполнитель"), ALBUM("Альбом") }
enum class TasteAction { LIKE, UNLIKE, BLOCK, UNBLOCK }
data class TasteTarget(val kind: TasteKind, val id: String, val title: String) {
    val key get() = if (kind == TasteKind.TRACK) id.removePrefix("yandex:").substringBefore(':') else id
}
fun Track.tasteTarget() = TasteTarget(TasteKind.TRACK, id.removePrefix("yandex:"), title)
fun MusicEntity.tasteTarget(): TasteTarget? = when (kind) {
    MusicKind.ARTISTS -> TasteTarget(TasteKind.ARTIST, id, title)
    MusicKind.ALBUMS -> TasteTarget(TasteKind.ALBUM, id, title)
    else -> null
}

data class TasteList(val liked: Set<String> = emptySet(), val blocked: Set<String> = emptySet()) {
    fun allows(track: Track, artists: TasteList): Boolean =
        track.tasteTarget().key !in blocked && track.artists.none { it.id in artists.blocked }
}
data class TasteShelf(val list: TasteList = TasteList(), val ready: Boolean = false, val busy: Boolean = false, val issue: String? = null, val verifiedRevision: Long = 0)
data class TasteState(val profileId: String = "", val signedIn: Boolean = false, val shelves: Map<TasteKind, TasteShelf> = emptyMap(), val revision: Long = 0, val accountId: String? = null) {
    fun shelf(kind: TasteKind) = shelves[kind] ?: TasteShelf()
    fun allows(track: Track) = shelf(TasteKind.TRACK).list.allows(track, shelf(TasteKind.ARTIST).list)
}
interface MusicTasteApi {
    suspend fun taste(profileId: String, kind: TasteKind): TasteList
    suspend fun react(profileId: String, target: TasteTarget, action: TasteAction)
}

/** Account-scoped server collections. Album/artist favourites never expand into liked track IDs. */
class MusicTaste(private val accounts: AccountAuth, private val api: MusicTasteApi, private val scope: CoroutineScope,
    private val changed: () -> Unit = {}) {
    private val mutable = MutableStateFlow(TasteState())
    val state = mutable.asStateFlow()
    private val locks = TasteKind.entries.associateWith { Mutex() }
    private var generation = 0L
    private var tasks: Job? = null

    init { scope.launch {
        accounts.state.map { Triple(it.profileId, it.phase, it.account?.id) }.distinctUntilChanged().collect { (profile, phase, account) ->
            generation++; tasks?.cancel()
            mutable.value = TasteState(profile, phase == AuthPhase.SIGNED_IN, accountId = account)
            if (phase == AuthPhase.SIGNED_IN) tasks = launch { TasteKind.entries.forEach { kind -> launch { refreshNow(kind) } } }
        }
    } }
    fun refresh(kind: TasteKind) { scope.launch { refreshNow(kind) } }
    private fun put(kind: TasteKind, shelf: TasteShelf) { mutable.value = state.value.copy(shelves = state.value.shelves + (kind to shelf)) }

    private suspend fun refreshNow(kind: TasteKind) = locks.getValue(kind).withLock {
        val before = state.value; val ticket = generation
        if (!before.signedIn) return@withLock
        put(kind, before.shelf(kind).copy(busy = true, issue = null))
        try {
            val list = api.taste(before.profileId, kind)
            currentCoroutineContext().ensureActive()
            if (ticket == generation) put(kind, TasteShelf(list, ready = true, verifiedRevision = before.shelf(kind).verifiedRevision + 1))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (ticket == generation) put(kind, before.shelf(kind).copy(busy = false, issue = failure(e))) }
    }

    suspend fun requireRecommendationFilters(profile: String) {
        for (kind in listOf(TasteKind.TRACK, TasteKind.ARTIST)) {
            if (!state.value.shelf(kind).ready) refreshNow(kind)
            if (state.value.profileId != profile || !state.value.signedIn) throw MusicException(MusicFailure.SIGN_IN)
            if (!state.value.shelf(kind).ready) throw MusicException(MusicFailure.NETWORK)
        }
    }

    fun react(target: TasteTarget, action: TasteAction) {
        val before = state.value; val shelf = before.shelf(target.kind); val ticket = generation
        if (!before.signedIn || !shelf.ready || shelf.busy || (target.kind == TasteKind.ALBUM && action in setOf(TasteAction.BLOCK, TasteAction.UNBLOCK))) return
        put(target.kind, shelf.copy(busy = true, issue = null))
        scope.launch {
            locks.getValue(target.kind).withLock {
                if (ticket != generation) return@withLock
                var writeFailure: Exception? = null
                try { api.react(before.profileId, target, action) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { writeFailure = e }
                // A multi-step mutation can partially succeed. Re-read the actual account state,
                // including on error, instead of showing a speculative heart/block status.
                try {
                    val list = api.taste(before.profileId, target.kind)
                    currentCoroutineContext().ensureActive()
                    if (ticket == generation) {
                        put(target.kind, TasteShelf(list, ready = true, issue = writeFailure?.let(::failure), verifiedRevision = shelf.verifiedRevision + 1))
                        mutable.value = state.value.copy(revision = state.value.revision + 1)
                        changed()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (ticket == generation) put(target.kind, TasteShelf(shelf.list, issue = "Не удалось проверить отметку. Обновите состояние. " + failure(writeFailure ?: e)))
                }
            }
        }
    }
    private fun failure(e: Exception) = ((e as? MusicException)?.failure ?: MusicFailure.RESPONSE).message()
}
