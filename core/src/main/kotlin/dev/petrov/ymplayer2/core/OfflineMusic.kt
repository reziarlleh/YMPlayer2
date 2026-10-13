package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OfflineOwner(val profileId: String, val accountId: String)
data class LikedSnapshot(val keys: Set<String>, val tracks: List<Track>)
interface LikedMusicApi {
    suspend fun snapshot(profileId: String): LikedSnapshot
    suspend fun keys(profileId: String): Set<String>
    suspend fun tracks(profileId: String, keys: Set<String>): List<Track> = snapshot(profileId).tracks.filter { it.tasteTarget().key in keys }
}
data class OfflineItem(val track: Track?, val audioFailed: Boolean, val coverFailed: Boolean, val noCover: Boolean)
interface OfflineStore {
    suspend fun load(owner: OfflineOwner): List<Track>
    /** Fast local listing; audio() still verifies the selected file before opening it. */
    suspend fun catalog(owner: OfflineOwner): List<Track> = load(owner)
    suspend fun sync(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean, transfer: () -> Unit): OfflineItem
    suspend fun retain(owner: OfflineOwner, keys: Set<String>, allowed: () -> Boolean = { true })
    suspend fun audio(owner: OfflineOwner, trackId: String): String?
    suspend fun clear(owner: OfflineOwner)
    suspend fun pendingDownloads(owner: OfflineOwner): Set<String> = emptySet()
    suspend fun pendingDownloads(owner: OfflineOwner, keys: Set<String>) = Unit
}
class OfflineException(message: String) : Exception(message)
data class OfflineState(
    val owner: OfflineOwner? = null, val ready: Boolean = false, val running: Boolean = false,
    val tracks: List<Track> = emptyList(), val total: Int = 0, val checked: Int = 0,
    val audioFailures: Int = 0, val coverFailures: Int = 0, val noCover: Int = 0,
    val message: String? = null, val wifiOnly: Boolean = true, val enabled: Boolean = true,
) {
    val bytes get() = tracks.sumOf(Track::sizeBytes)
}

/** Serialized on the application dispatcher. Disk/network work belongs to the adapters.
 * Permanent files belong to a profile AND account; only confirmed track likes authorize them. */
class OfflineMusic(private val accounts: AccountAuth, private val taste: MusicTaste, private val api: LikedMusicApi,
    private val online: OnlineMusicApi, private val store: OfflineStore, private val scope: CoroutineScope,
    wifiOnly: Boolean = true, private val saveWifi: (Boolean) -> Unit = {},
    private val network: (Boolean) -> Boolean = { true }, private val cacheQuality: () -> AudioQuality = { AudioQuality.AUTO },
    enabled: Boolean = true, private val saveEnabled: (Boolean) -> Unit = {},
    private val diagnostic: (OperationDiagnostic) -> Unit = {}, private val automaticWork: () -> Unit = {}) {
    private val mutable = MutableStateFlow(OfflineState(wifiOnly = wifiOnly, enabled = enabled))
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    @Volatile private var excluded = emptySet<String>()
    private var passKeys: Set<String>? = null
    @Volatile private var membership: Pair<OfflineOwner, Set<String>>? = null
    @Volatile private var membershipRevision = 0L
    private var seenTaste: Pair<OfflineOwner, Long>? = null
    private var job: Job? = null
    private val downloadQueueLock = kotlinx.coroutines.sync.Mutex()
    private var automaticJob: Job? = null

    init {
        scope.launch {
            accounts.state.map { auth -> auth.takeIf { it.phase == AuthPhase.SIGNED_IN }?.account?.id
                ?.let { OfflineOwner(auth.profileId, it) } }.distinctUntilChanged().collect { owner ->
                generation++; job?.cancel(); automaticJob?.cancel(); excluded = emptySet(); passKeys = null; membership = null; seenTaste = null
                mutable.value = OfflineState(owner = owner, wifiOnly = state.value.wifiOnly,
                    enabled = state.value.enabled, ready = owner != null && !state.value.enabled)
                if (owner != null && state.value.enabled) { load(owner); retryAutomatic() }
            }
        }
        scope.launch {
            combine(accounts.state, taste.state, state.map { it.enabled }.distinctUntilChanged()) { _, preferences, _ -> preferences }.collect { preferences ->
                if (!state.value.enabled) return@collect
                val owner = state.value.owner ?: return@collect
                val shelf = preferences.shelf(TasteKind.TRACK)
                if (preferences.profileId != owner.profileId || preferences.accountId != owner.accountId || !preferences.signedIn || !shelf.ready || shelf.busy) return@collect
                val keys = shelf.list.liked - shelf.list.blocked
                // A queued offline unlike changes desired membership without a new server revision.
                if (seenTaste == owner to shelf.verifiedRevision && membership?.takeIf { it.first == owner }?.second == keys) return@collect
                seenTaste = owner to shelf.verifiedRevision
                if (state.value.running) excluded = excluded + (passKeys.orEmpty() - keys)
                applyMembership(owner, keys)
                retryAutomatic()
            }
        }
    }

    /** Called only after server verification of a track like; never for artist or album likes. */
    suspend fun confirmedReaction(owner: OfflineOwner, target: TasteTarget, action: TasteAction) {
        if (!state.value.enabled || !owns(owner) || target.kind != TasteKind.TRACK) return
        downloadQueueLock.lock()
        try {
            val queued = store.pendingDownloads(owner)
            store.pendingDownloads(owner, if (action == TasteAction.LIKE) queued + target.key else queued - target.key)
        } finally { downloadQueueLock.unlock() }
        if (owns(owner) && !state.value.running) mutable.value = state.value.copy(message = when {
            action != TasteAction.LIKE -> null
            network(state.value.wifiOnly) -> null
            state.value.wifiOnly -> "Трек отмечен. Скачивание начнётся при подключении к Wi-Fi."
            else -> "Трек отмечен. Скачивание начнётся после восстановления интернета."
        })
        retryAutomatic()
    }
    fun retryAutomatic() {
        val owner = state.value.owner ?: return
        if (!state.value.enabled || state.value.running || automaticJob?.isActive == true || !network(state.value.wifiOnly)) return
        val ticket = generation
        automaticJob = scope.launch {
            try {
                val queued = store.pendingDownloads(owner)
                if (queued.isEmpty() || !valid(owner, ticket)) return@launch
                val shelf = taste.state.value.shelf(TasteKind.TRACK)
                if (!shelf.ready || shelf.busy || taste.state.value.accountId != owner.accountId) return@launch
                val wanted = queued.intersect(shelf.list.liked - shelf.list.blocked)
                if (wanted.isEmpty()) return@launch
                mutable.value = state.value.copy(running = true, total = wanted.size, checked = 0, message = "Сохраняем отмеченные треки офлайн…")
                automaticWork()
                for (track in api.tracks(owner.profileId, wanted)) {
                    currentCoroutineContext().ensureActive()
                    val key = track.tasteTarget().key
                    fun allowed() = valid(owner, ticket) && state.value.enabled &&
                        key in taste.state.value.shelf(TasteKind.TRACK).list.liked && key !in taste.state.value.shelf(TasteKind.TRACK).list.blocked &&
                        key !in taste.excludedPendingTracks(owner)
                    fun transfer() {
                        if (!allowed()) throw CancellationException("Like removed")
                        if (!network(state.value.wifiOnly)) throw MusicException(MusicFailure.NETWORK)
                    }
                    if (key !in wanted || !allowed()) continue
                    try {
                        val result = store.sync(owner, track, { online.stream(owner.profileId, track.id, cacheQuality()) }, ::allowed, ::transfer)
                        if (!valid(owner, ticket)) return@launch
                        if (result.track != null && allowed()) {
                            mutable.value = state.value.copy(tracks = state.value.tracks.filterNot { it.tasteTarget().key == key } + result.track,
                                checked = state.value.checked + 1)
                            downloadQueueLock.lock()
                            try { store.pendingDownloads(owner, store.pendingDownloads(owner) - key) }
                            finally { downloadQueueLock.unlock() }
                            diagnostic(OperationDiagnostic(DiagnosticOperation.OFFLINE_DOWNLOAD))
                        } else diagnostic(OperationDiagnostic(DiagnosticOperation.OFFLINE_DOWNLOAD, DiagnosticCategory.STORAGE))
                    } catch (e: CancellationException) { if (!valid(owner, ticket)) throw e }
                    catch (e: Exception) { diagnostic(diagnosticFailure(DiagnosticOperation.OFFLINE_DOWNLOAD, e)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { diagnostic(diagnosticFailure(DiagnosticOperation.OFFLINE_DOWNLOAD, e)) }
            finally { if (valid(owner, ticket)) mutable.value = state.value.copy(running = false,
                message = "Автоматическое сохранение офлайн завершено. Неудачные загрузки остаются в очереди для повтора.") }
        }
    }

    private fun load(owner: OfflineOwner) {
        val ticket = generation
        scope.launch {
            try {
                val tracks = store.catalog(owner)
                if (valid(owner, ticket) && state.value.enabled) {
                    taste.seedCachedTrackLikes(owner, tracks.mapTo(linkedSetOf()) { it.tasteTarget().key })
                    mutable.value = state.value.copy(ready = true, tracks = permitted(owner, tracks))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (valid(owner, ticket) && state.value.enabled) mutable.value = state.value.copy(ready = true, message = "Не удалось прочитать офлайн-коллекцию. Повторите синхронизацию.") }
        }
    }

    /** Device-wide opt-out. In-flight writes lose their generation; existing files require explicit clear. */
    fun setEnabled(value: Boolean) {
        if (state.value.enabled == value) return
        generation++; membershipRevision++; job?.cancel(); automaticJob?.cancel(); job = null; passKeys = null; seenTaste = null
        mutable.value = state.value.copy(enabled = value, ready = state.value.owner != null && !value,
            running = false, tracks = emptyList(), total = 0, checked = 0, audioFailures = 0, coverFailures = 0, noCover = 0,
            message = if (value) null else "Офлайн-кэш выключен. Для освобождения места удалите ранее сохранённые файлы.")
        saveEnabled(value)
        if (value) state.value.owner?.let(::load)
    }

    private fun owns(owner: OfflineOwner) = state.value.owner == owner &&
        accounts.state.value.let { it.phase == AuthPhase.SIGNED_IN && it.profileId == owner.profileId && it.account?.id == owner.accountId }
    private fun valid(owner: OfflineOwner, ticket: Long) = ticket == generation && owns(owner)
    private fun applyMembership(owner: OfflineOwner, keys: Set<String>) {
        membership = owner to keys
        val revision = ++membershipRevision
        mutable.value = state.value.copy(tracks = state.value.tracks.filter { it.tasteTarget().key in keys })
        // A confirmed unlike belongs to the account, not to the cancellable download pass.
        scope.launch {
            try {
                store.retain(owner, keys) { state.value.enabled && owns(owner) && revision == membershipRevision }
                if (!owns(owner) || revision != membershipRevision) return@launch
                downloadQueueLock.lock()
                try { store.pendingDownloads(owner, store.pendingDownloads(owner).intersect(keys)) }
                finally { downloadQueueLock.unlock() }
                diagnostic(OperationDiagnostic(DiagnosticOperation.OFFLINE_DELETE))
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (owns(owner) && revision == membershipRevision) report("Не удалось удалить файлы снятых лайков. Повторите синхронизацию.") }
        }
    }
    private fun permitted(owner: OfflineOwner, tracks: List<Track>) = tracks.filter {
        membership?.takeIf { pair -> pair.first == owner }?.second?.contains(it.tasteTarget().key) != false
    }
    fun setWifiOnly(value: Boolean) { mutable.value = state.value.copy(wifiOnly = value); saveWifi(value) }
    fun report(message: String) { mutable.value = state.value.copy(message = message) }
    fun cancel() {
        generation++; job?.cancel(); automaticJob?.cancel(); job = null; passKeys = null
        mutable.value = state.value.copy(running = false, message = "Синхронизация остановлена. Готовые файлы сохранены.")
    }
    /** Re-read URIs after a committed location change without changing membership or account. */
    fun reloadCache() {
        cancel()
        if (state.value.enabled) state.value.owner?.let { owner ->
            mutable.value = state.value.copy(ready = false, tracks = emptyList())
            load(owner)
        }
    }
    fun sync() {
        val owner = state.value.owner ?: return
        if (!state.value.enabled || !state.value.ready || state.value.running) return
        val ticket = ++generation; val revision = membershipRevision
        excluded = emptySet(); passKeys = null
        mutable.value = state.value.copy(running = true, total = 0, checked = 0, audioFailures = 0, coverFailures = 0, noCover = 0, message = "Получаем «Мне нравится»…")
        fun transfer() {
            if (!valid(owner, ticket)) throw CancellationException("Offline owner changed")
            if (!network(state.value.wifiOnly)) throw OfflineException(if (state.value.wifiOnly) "Для синхронизации нужен Wi-Fi." else "Нет сети для синхронизации.")
        }
        job = scope.launch {
            try {
                transfer()
                val snapshot = api.snapshot(owner.profileId)
                ensureActive()
                if (!valid(owner, ticket)) return@launch
                passKeys = snapshot.keys
                excluded = excluded + taste.excludedPendingTracks(owner)
                if (revision != membershipRevision) excluded = snapshot.keys - membership?.second.orEmpty()
                val keep = snapshot.keys - excluded
                applyMembership(owner, keep)
                store.retain(owner, keep) { valid(owner, ticket) }
                if (!valid(owner, ticket)) return@launch
                val rows = snapshot.tracks.distinctBy { it.tasteTarget().key }.filter { it.tasteTarget().key in keep }
                mutable.value = state.value.copy(total = snapshot.keys.size, tracks = state.value.tracks.filter { it.tasteTarget().key in keep },
                    audioFailures = (snapshot.keys - snapshot.tracks.map { it.tasteTarget().key }.toSet() - state.value.tracks.map { it.tasteTarget().key }.toSet()).size)
                for (track in rows) {
                    ensureActive(); transfer()
                    val key = track.tasteTarget().key
                    if (key in excluded) continue
                    mutable.value = state.value.copy(message = "${state.value.checked + 1}/${rows.size} · ${track.title}")
                    val quality = cacheQuality()
                    val result = store.sync(owner, track, { online.stream(owner.profileId, track.id, quality) },
                        { valid(owner, ticket) && key !in excluded && key !in taste.excludedPendingTracks(owner) }, ::transfer)
                    ensureActive()
                    if (!valid(owner, ticket)) return@launch
                    if (key !in excluded) {
                        val tracks = state.value.tracks.filter { it.tasteTarget().key != key } + listOfNotNull(result.track)
                        mutable.value = state.value.copy(tracks = tracks, checked = state.value.checked + 1,
                            audioFailures = state.value.audioFailures + if (result.audioFailed) 1 else 0,
                            coverFailures = state.value.coverFailures + if (result.coverFailed) 1 else 0,
                            noCover = state.value.noCover + if (result.noCover) 1 else 0)
                    }
                }
                transfer()
                val keys = api.keys(owner.profileId) - excluded - taste.excludedPendingTracks(owner)
                ensureActive()
                if (!valid(owner, ticket)) return@launch
                applyMembership(owner, keys)
                store.retain(owner, keys) { valid(owner, ticket) }
                if (valid(owner, ticket)) mutable.value = state.value.copy(tracks = state.value.tracks.filter { it.tasteTarget().key in keys }, message = "Синхронизация завершена.")
                taste.refresh(TasteKind.TRACK)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(owner, ticket)) mutable.value = state.value.copy(message = when (e) {
                    is OfflineException -> e.message
                    is MusicException -> e.failure.message()
                    else -> "Не удалось завершить синхронизацию. Готовые файлы сохранены; повторите попытку."
                })
            } finally { if (valid(owner, ticket)) { passKeys = null; mutable.value = state.value.copy(running = false) } }
        }
    }
    fun clear() {
        val owner = state.value.owner ?: return
        cancel(); val ticket = generation
        mutable.value = state.value.copy(ready = false, tracks = emptyList(), message = "Удаляем офлайн-файлы этого аккаунта…")
        scope.launch {
            try {
                downloadQueueLock.lock()
                try { store.pendingDownloads(owner, emptySet()); store.clear(owner) }
                finally { downloadQueueLock.unlock() }
                if (valid(owner, ticket)) mutable.value = state.value.copy(ready = true, message = "Офлайн-файлы удалены. Лайки в Яндексе сохранены.")
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (valid(owner, ticket)) mutable.value = state.value.copy(ready = true, message = "Не удалось очистить офлайн-файлы.") }
        }
    }
    fun tracks(profile: String) = state.value.takeIf { it.enabled && it.owner?.profileId == profile }?.tracks.orEmpty()
    suspend fun readyTracks(profile: String): List<Track> {
        if (!state.value.enabled) return emptyList()
        val auth = accounts.state.first { it.profileId != profile || it.phase != AuthPhase.LOADING }
        if (auth.profileId != profile || auth.phase != AuthPhase.SIGNED_IN || auth.account == null) return emptyList()
        val owner = OfflineOwner(profile, auth.account.id)
        val (loaded, current) = combine(state, accounts.state) { cached, current -> cached to current }.first { (cached, current) ->
            !cached.enabled || current.profileId != profile || current.phase != AuthPhase.SIGNED_IN ||
                current.account?.id != owner.accountId || cached.owner == owner && cached.ready
        }
        return loaded.takeIf { it.enabled && it.owner == owner && it.ready && current.profileId == profile &&
            current.phase == AuthPhase.SIGNED_IN && current.account?.id == owner.accountId }?.tracks.orEmpty()
    }
    fun decorate(profile: String, track: Track): Track {
        val cached = tracks(profile).firstOrNull { it.tasteTarget().key == track.tasteTarget().key } ?: return track
        return track.copy(offline = true, available = true, artworkUri = cached.artworkUri ?: track.artworkUri)
    }
    suspend fun audio(profile: String, trackId: String): String? {
        if (!state.value.enabled) return null
        val owner = state.value.owner?.takeIf { it.profileId == profile } ?: return null
        if (membership?.takeIf { it.first == owner }?.second?.contains(trackId.removePrefix("yandex:").substringBefore(':')) == false) return null
        val ticket = generation
        val uri = store.audio(owner, trackId)
        return uri?.takeIf { state.value.enabled && valid(owner, ticket) }
    }
}
