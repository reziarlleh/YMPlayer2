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

data class TasteMutation(val target: TasteTarget, val action: TasteAction)
data class TasteSnapshot(val lists: Map<TasteKind, TasteList> = emptyMap(), val pending: List<TasteMutation> = emptyList())
interface TasteStore {
    suspend fun read(owner: OfflineOwner): TasteSnapshot
    suspend fun write(owner: OfflineOwner, snapshot: TasteSnapshot)
}
class MemoryTasteStore : TasteStore {
    private val data = mutableMapOf<OfflineOwner, TasteSnapshot>()
    override suspend fun read(owner: OfflineOwner) = data[owner] ?: TasteSnapshot()
    override suspend fun write(owner: OfflineOwner, snapshot: TasteSnapshot) { data[owner] = snapshot }
}

/** Account-scoped server collections. Album/artist favourites never expand into liked track IDs. */
class MusicTaste(private val accounts: AccountAuth, private val api: MusicTasteApi, private val scope: CoroutineScope,
    private val changed: () -> Unit = {}, private val store: TasteStore = MemoryTasteStore(),
    private val connection: StateFlow<Boolean>? = null, private val diagnostic: (OperationDiagnostic) -> Unit = {},
    private val confirmed: suspend (OfflineOwner, TasteTarget, TasteAction) -> Unit = { _, _, _ -> }) {
    private val mutable = MutableStateFlow(TasteState())
    val state = mutable.asStateFlow()
    private val locks = TasteKind.entries.associateWith { Mutex() }
    private var generation = 0L
    private var tasks: Job? = null
    private val persistence = Mutex()
    private var lists = emptyMap<TasteKind, TasteList>()
    private var pending = emptyList<TasteMutation>()
    private var snapshotLoaded = false
    private var bootstrapLikes = emptySet<String>()
    private fun owner() = state.value.accountId?.let { OfflineOwner(state.value.profileId, it) }
    private fun connected() = connection?.value != false
    private fun applied(list: TasteList, mutation: TasteMutation): TasteList = when (mutation.action) {
        TasteAction.LIKE -> list.copy(liked = list.liked + mutation.target.key, blocked = list.blocked - mutation.target.key)
        TasteAction.UNLIKE -> list.copy(liked = list.liked - mutation.target.key)
        TasteAction.BLOCK -> list.copy(liked = list.liked - mutation.target.key, blocked = list.blocked + mutation.target.key)
        TasteAction.UNBLOCK -> list.copy(blocked = list.blocked - mutation.target.key)
    }
    private fun overlaid(kind: TasteKind) = pending.filter { it.target.kind == kind }
        .fold(lists[kind] ?: TasteList(), ::applied)
    /** Bootstrap track controls after an offline upgrade from a version without a taste snapshot.
     * Cached files are account-owned evidence of former likes, not complete recommendation filters. */
    fun seedCachedTrackLikes(owner: OfflineOwner, keys: Set<String>) {
        if (owner != owner()) return
        bootstrapLikes = bootstrapLikes + keys
        if (!snapshotLoaded || TasteKind.TRACK in lists || bootstrapLikes.isEmpty()) return
        lists = lists + (TasteKind.TRACK to TasteList(bootstrapLikes))
        val shelf = state.value.shelf(TasteKind.TRACK)
        put(TasteKind.TRACK, shelf.copy(list = overlaid(TasteKind.TRACK), ready = true))
    }
    fun excludedPendingTracks(owner: OfflineOwner): Set<String> {
        if (owner != owner()) return emptySet()
        val desired = overlaid(TasteKind.TRACK)
        return pending.filter { it.target.kind == TasteKind.TRACK }
            .map { it.target.key }.filter { it !in desired.liked || it in desired.blocked }.toSet()
    }
    private suspend fun persist(owner: OfflineOwner, ticket: Long) = persistence.withLock {
        if (ticket != generation) return@withLock
        store.write(owner, TasteSnapshot(lists.toMap(), pending.toList()))
    }

    init { scope.launch {
        accounts.state.map { Triple(it.profileId, it.phase, it.account?.id) }.distinctUntilChanged().collect { (profile, phase, account) ->
            generation++; tasks?.cancel()
            lists = emptyMap(); pending = emptyList()
            snapshotLoaded = false; bootstrapLikes = emptySet()
            mutable.value = TasteState(profile, phase == AuthPhase.SIGNED_IN, accountId = account)
            if (phase == AuthPhase.SIGNED_IN && account != null) tasks = launch {
                val ticket = generation; val owner = OfflineOwner(profile, account)
                try {
                    val saved = store.read(owner)
                    if (ticket != generation) return@launch
                    lists = saved.lists; pending = saved.pending
                    for (kind in TasteKind.entries) if (kind in lists || pending.any { it.target.kind == kind })
                        put(kind, TasteShelf(overlaid(kind), ready = true, issue = if (pending.any { it.target.kind == kind })
                            "Изменение сохранено. Отправим его в Яндекс после восстановления связи." else null))
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { diagnostic(diagnosticFailure(DiagnosticOperation.TASTE_READ, e)) }
                if (ticket != generation) return@launch
                snapshotLoaded = true
                seedCachedTrackLikes(owner, bootstrapLikes)
                TasteKind.entries.forEach { kind -> launch { refreshNow(kind) } }
            }
        }
    } }
    init { connection?.let { network -> scope.launch { network.collect { if (it) retryPending() } } } }
    fun retryPending() {
        if (!connected()) return
        pending.map { it.target.kind }.distinct().forEach(::refresh)
    }
    fun refresh(kind: TasteKind) { scope.launch { refreshNow(kind) } }
    private fun put(kind: TasteKind, shelf: TasteShelf) { mutable.value = state.value.copy(shelves = state.value.shelves + (kind to shelf)) }

    private suspend fun refreshNow(kind: TasteKind) = locks.getValue(kind).withLock {
        val before = state.value; val ticket = generation
        if (!before.signedIn) return@withLock
        val owner = owner() ?: return@withLock
        if (!connected()) return@withLock
        put(kind, before.shelf(kind).copy(busy = true, issue = null))
        try {
            flush(kind, owner, ticket)
            val list = api.taste(before.profileId, kind)
            currentCoroutineContext().ensureActive()
            if (ticket == generation) {
                lists = lists + (kind to list); persist(owner, ticket)
                put(kind, TasteShelf(overlaid(kind), ready = true, verifiedRevision = before.shelf(kind).verifiedRevision + 1))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            diagnostic(diagnosticFailure(DiagnosticOperation.TASTE_READ, e))
            if (ticket == generation) put(kind, before.shelf(kind).copy(list = overlaid(kind), ready = kind in lists,
                busy = false, issue = failure(e)))
        }
    }

    suspend fun requireRecommendationFilters(profile: String) {
        for (kind in listOf(TasteKind.TRACK, TasteKind.ARTIST)) {
            if (!state.value.shelf(kind).ready || state.value.shelf(kind).verifiedRevision == 0L) refreshNow(kind)
            if (state.value.profileId != profile || !state.value.signedIn) throw MusicException(MusicFailure.SIGN_IN)
            if (!state.value.shelf(kind).ready || state.value.shelf(kind).verifiedRevision == 0L) throw MusicException(MusicFailure.NETWORK)
        }
    }

    fun react(target: TasteTarget, action: TasteAction) {
        val before = state.value; val shelf = before.shelf(target.kind); val ticket = generation
        if (!before.signedIn || !shelf.ready || shelf.busy || (target.kind == TasteKind.ALBUM && action in setOf(TasteAction.BLOCK, TasteAction.UNBLOCK))) return
        put(target.kind, shelf.copy(busy = true, issue = null))
        scope.launch {
            locks.getValue(target.kind).withLock {
                if (ticket != generation) return@withLock
                val owner = owner() ?: return@withLock
                val mutation = TasteMutation(target, action)
                // Keep order: block→unblock must still remove the old like; like→unlike clears an old block.
                pending = pending + mutation
                try {
                    persist(owner, ticket)
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    if (ticket == generation) pending = pending.filterNot { it === mutation }
                    if (ticket == generation) put(target.kind, shelf.copy(busy = false, issue = "Не удалось сохранить отложенное изменение."))
                    diagnostic(diagnosticFailure(DiagnosticOperation.TASTE_WRITE, e)); return@withLock
                }
                try {
                    if (connected()) {
                        flush(target.kind, owner, ticket)
                        if (ticket == generation) {
                            if (pending.any { it.target.kind == target.kind }) deferred(target.kind, shelf, ticket)
                            else put(target.kind, state.value.shelf(target.kind).copy(busy = false))
                        }
                    }
                    else deferred(target.kind, shelf, ticket)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    diagnostic(diagnosticFailure(DiagnosticOperation.TASTE_WRITE, e))
                    deferred(target.kind, shelf, ticket, failure(e))
                }
            }
        }
    }
    private fun deferred(kind: TasteKind, shelf: TasteShelf, ticket: Long, error: String? = null) {
        if (ticket != generation) return
        put(kind, shelf.copy(list = overlaid(kind), busy = false, ready = true,
            issue = "Изменение сохранено. Отправим его в Яндекс после восстановления связи." + error?.let { " $it" }.orEmpty()))
        mutable.value = state.value.copy(revision = state.value.revision + 1)
        diagnostic(OperationDiagnostic(DiagnosticOperation.TASTE_DEFERRED, accepted = pending.size))
        changed()
    }
    private suspend fun flush(kind: TasteKind, owner: OfflineOwner, ticket: Long) {
        for (mutation in pending.filter { it.target.kind == kind }) {
            if (ticket != generation || !connected()) return
            var failure: Exception? = null
            try { api.react(owner.profileId, mutation.target, mutation.action) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
            // The provider's block/like write can partially succeed; verify all final bits.
            val list = try { api.taste(owner.profileId, kind) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw failure ?: e }
            currentCoroutineContext().ensureActive()
            if (ticket != generation) return
            lists = lists + (kind to list)
            if (applied(list, mutation) != list) throw failure ?: MusicException(MusicFailure.RESPONSE)
            put(kind, TasteShelf(list, ready = true, busy = true, verifiedRevision = state.value.shelf(kind).verifiedRevision + 1))
            confirmed(owner, mutation.target, mutation.action)
            if (ticket != generation) return
            pending = pending - mutation
            persist(owner, ticket)
            if (ticket != generation) return
            put(kind, state.value.shelf(kind).copy(list = overlaid(kind)))
            mutable.value = state.value.copy(revision = state.value.revision + 1)
            diagnostic(OperationDiagnostic(DiagnosticOperation.TASTE_WRITE))
            changed()
        }
    }
    private fun failure(e: Exception) = ((e as? MusicException)?.failure ?: MusicFailure.RESPONSE).message()
}
