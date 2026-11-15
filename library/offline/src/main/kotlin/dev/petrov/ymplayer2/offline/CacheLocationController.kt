package dev.petrov.ymplayer2.offline

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Folder selection is owned by the current signed-in user, including confirmation and cancellation. */
class CacheLocationController(private val store: LikedFileStore, private val offline: OfflineMusic,
    private val scope: CoroutineScope, private val otherTransfer: () -> Boolean = { false }) : CacheLocationAccess {
    private val mutable = MutableStateFlow(store.locationState(offline.state.value.owner).copy(enabled = offline.state.value.enabled))
    override val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var revision = 0L
    private data class Pending(val owner: OfflineOwner, val destination: String, val candidates: List<CacheDirectories.Candidate>)
    private var pending: Pending? = null
    init {
        scope.launch {
            offline.state.map { Triple(it.owner, it.enabled, it.ready) }.distinctUntilChanged().collect { (owner, enabled, _) ->
                val current = mutable.value
                if (owner != current.owner || enabled != current.enabled) {
                    revision++; job?.cancel(); pending = null
                    mutable.value = store.locationState(owner).copy(enabled = enabled)
                } else if (!current.moving) mutable.value = store.locationState(owner).copy(enabled = enabled,
                    message = current.message, selection = current.selection)
            }
        }
    }
    private fun allowed(owner: OfflineOwner, ticket: Long) = ticket == revision && offline.state.value.enabled && offline.state.value.owner == owner
    private fun owner() = offline.state.value.takeIf { it.enabled }?.owner
    override fun move(destination: String?) {
        val owner = owner() ?: return
        if (mutable.value.moving || otherTransfer()) return
        pending = null
        work(owner) { ticket ->
            val candidates = destination?.let { store.inspectLocation(owner, it) }.orEmpty()
            if (!allowed(owner, ticket)) throw CancellationException("Cache owner changed")
            if (candidates.isNotEmpty()) {
                pending = Pending(owner, destination!!, candidates)
                mutable.value = mutable.value.copy(selection = CacheFolderSelection(candidates.mapIndexed { index, item ->
                    CacheFolderOption(index.toString(), item.label, item.tracks, item.legacy)
                }))
                null
            } else {
                store.move(owner, destination, null, null, { allowed(owner, ticket) }) { done, total -> if (allowed(owner, ticket)) progress(done, total) }
                "Офлайн-кэш перенесён."
            }
        }
    }
    override fun confirm(option: String) {
        val request = pending ?: return
        val selected = option.toIntOrNull()?.let { request.candidates.getOrNull(it) } ?: return
        if (owner() != request.owner || mutable.value.moving || otherTransfer()) { dismiss(); return }
        pending = null
        work(request.owner) { ticket ->
            val candidate = store.inspectLocation(request.owner, request.destination).firstOrNull { it.uri == selected.uri }
                ?: throw java.io.IOException("Выбранная папка недоступна.")
            val keys = if (candidate.legacy) offline.confirmedCacheKeys(request.owner) else null
            store.move(request.owner, request.destination, candidate, keys, { allowed(request.owner, ticket) }) { done, total -> if (allowed(request.owner, ticket)) progress(done, total) }
            "Офлайн-кэш подключён."
        }
    }
    private fun progress(done: Long, total: Long) {
        mutable.value = mutable.value.copy(copiedBytes = done, totalBytes = total, message = "Перенос офлайн-кэша…")
    }
    private fun work(owner: OfflineOwner, action: suspend (Long) -> String?) {
        val ticket = ++revision
        val initial = store.locationIdentity(owner)
        val lease = offline.beginStorageChange()
        mutable.value = store.locationState(owner).copy(enabled = true, moving = true, message = "Подготовка переноса кэша…")
        job = scope.launch {
            var message: String? = null
            try { message = action(ticket) }
            catch (e: CancellationException) {
                message = if (initial != store.locationIdentity(owner)) "Офлайн-кэш перенесён." else "Перенос отменён. Исходный кэш сохранён."
            } catch (e: Exception) {
                message = when {
                    initial != store.locationIdentity(owner) -> "Кэш перенесён. Старую папку не удалось удалить."
                    e is MusicException -> e.failure.message()
                    e is AuthException && e.failure == AuthFailure.NETWORK -> "Нет сети для синхронизации."
                    e.message in messages -> e.message
                    else -> "Не удалось подключить или перенести кэш. Исходные файлы сохранены."
                }
            } finally {
                offline.endStorageChange(lease, reload = initial != store.locationIdentity(owner))
                if (allowed(owner, ticket)) mutable.value = store.locationState(owner).copy(enabled = true,
                    message = message, selection = mutable.value.selection)
            }
        }
    }
    override fun cancel() { if (mutable.value.moving) job?.cancel() }
    override fun dismiss() { if (!mutable.value.moving) { pending = null; mutable.value = mutable.value.copy(selection = null) } }
    fun report(message: String) { mutable.value = mutable.value.copy(message = message) }
    private companion object {
        val messages = setOf("Выбранная папка недоступна.", "Выберите папку вне текущего офлайн-кэша.",
            "Недостаточно свободного места для переноса офлайн-кэша.", "Не удалось создать папку кэша.",
            "Проверка перенесённого файла не пройдена.", "Не удалось сохранить папку кэша.",
            "Не удалось завершить предыдущий перенос кэша.", "Не удалось определить свободное место. Выберите папку на устройстве или USB.",
            "Повреждено описание офлайн-кэша.", "Этот формат кэша требует другой версии YMPlayer2.",
            "Этот кэш принадлежит другому аккаунту Яндекса.", "Эта папка уже используется другим профилем.",
            "Для подключения старого кэша нужен список «Мне нравится».")
    }
}
