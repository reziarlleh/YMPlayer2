package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlaylistOwner(val profileId: String, val accountId: String)
data class CloudPlaylist(val id: String, val ownerId: String, val title: String, val trackCount: Int = 0) {
    fun entity() = MusicEntity(id, title, MusicKind.PLAYLISTS, ownerId)
}
interface CloudPlaylistApi {
    suspend fun list(owner: PlaylistOwner): List<CloudPlaylist>
    suspend fun create(owner: PlaylistOwner, title: String): CloudPlaylist
    suspend fun add(owner: PlaylistOwner, playlist: CloudPlaylist, track: Track): CloudPlaylist
    suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist)
}
/** 1.x inserts an id/albumId pair. Local files never enter a cloud write. */
fun Track.cloudTrackKey(): Pair<String, String>? {
    if (source != Source.YANDEX || !id.startsWith("yandex:")) return null
    val parts = id.removePrefix("yandex:").split(':')
    val album = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: albumId.orEmpty()
    val numeric = Regex("[0-9]{1,30}")
    return if (parts.size in 1..2 && parts[0].matches(numeric) && album.matches(numeric)) parts[0] to album else null
}
enum class PlaylistDialog { CHOOSE, CREATE, DELETE, RESULT }
data class CloudPlaylistState(
    val owner: PlaylistOwner? = null, val dialog: PlaylistDialog? = null,
    val track: Track? = null, val target: CloudPlaylist? = null,
    val playlists: List<CloudPlaylist> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val issue: String? = null, val message: String? = null,
)

/** Main-dispatcher commands. No player/cache dependency and no automatic retry of writes. */
class CloudPlaylists(private val accounts: AccountAuth, private val api: CloudPlaylistApi,
    private val scope: CoroutineScope, private val changed: (CloudPlaylist, Boolean) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(CloudPlaylistState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private fun owner() = accounts.state.value.let {
        if (it.phase == AuthPhase.SIGNED_IN) it.account?.let { account -> PlaylistOwner(it.profileId, account.id) } else null
    }
    init { scope.launch { accounts.state.map { Triple(it.profileId, it.phase, it.account?.id) }.distinctUntilChanged().collect {
        val next = owner()
        if (next != state.value.owner) { generation++; job?.cancel(); mutable.value = CloudPlaylistState(next) }
    } } }
    private fun valid(ticket: Long, expected: PlaylistOwner) = ticket == generation && owner() == expected && state.value.owner == expected
    fun editable(entity: MusicEntity) = entity.kind == MusicKind.PLAYLISTS && owner()?.accountId == entity.ownerId && entity.ownerId != null
    fun dismiss() {
        if (state.value.busy) return
        generation++; job?.cancel(); mutable.value = CloudPlaylistState(owner())
    }
    fun choose(track: Track) {
        if (state.value.busy || owner() == null || track.cloudTrackKey() == null) return
        mutable.value = CloudPlaylistState(owner(), PlaylistDialog.CHOOSE, track)
        refresh()
    }
    fun newPlaylist(track: Track? = state.value.track) {
        if (state.value.busy || owner() == null || track != null && track.cloudTrackKey() == null) return
        generation++; job?.cancel()
        mutable.value = CloudPlaylistState(owner(), PlaylistDialog.CREATE, track)
    }
    fun askDelete(entity: MusicEntity) {
        if (state.value.busy || !editable(entity)) return
        generation++; job?.cancel()
        mutable.value = CloudPlaylistState(owner(), PlaylistDialog.DELETE, target = CloudPlaylist(entity.id, entity.ownerId!!, entity.title))
    }
    fun refresh() {
        if (state.value.dialog != PlaylistDialog.CHOOSE || state.value.busy) return
        runCommand(writing = false) { expected, ticket ->
            val lists = api.list(expected); ensureCurrent(ticket, expected)
            mutable.value = state.value.copy(playlists = lists.filter { it.ownerId == expected.accountId }.distinctBy(CloudPlaylist::id), loaded = true)
        }
    }
    fun create(title: String) {
        val name = title.trim()
        if (state.value.dialog != PlaylistDialog.CREATE || name.isEmpty() || name.length > 200) return
        val track = state.value.track
        runCommand { expected, ticket ->
            val created = api.create(expected, name); ensureCurrent(ticket, expected)
            require(created.ownerId == expected.accountId)
            // Publish the completed first step before attempting the second. Never recreate on failure.
            mutable.value = state.value.copy(target = created, dialog = PlaylistDialog.CHOOSE, playlists = listOf(created), loaded = true,
                message = "Плейлист «${created.title}» создан.")
            changed(created, false)
            if (track != null) {
                val updated = api.add(expected, created, track); ensureCurrent(ticket, expected)
                changed(updated, false)
            }
            mutable.value = state.value.copy(dialog = PlaylistDialog.RESULT,
                message = if (track == null) "Плейлист «${created.title}» создан." else "Трек добавлен в «${created.title}».")
        }
    }
    fun add(playlist: CloudPlaylist) {
        val before = state.value
        val track = before.track ?: return
        if (before.dialog != PlaylistDialog.CHOOSE || !before.loaded || playlist !in before.playlists || playlist.ownerId != owner()?.accountId) return
        runCommand { expected, ticket ->
            val updated = api.add(expected, playlist, track); ensureCurrent(ticket, expected)
            changed(updated, false)
            mutable.value = state.value.copy(dialog = PlaylistDialog.RESULT, message = "Трек добавлен в «${updated.title}».")
        }
    }
    fun delete() {
        val before = state.value
        val target = before.target ?: return
        if (before.dialog != PlaylistDialog.DELETE || target.ownerId != owner()?.accountId) return
        runCommand { expected, ticket ->
            api.delete(expected, target); ensureCurrent(ticket, expected)
            changed(target, true)
            mutable.value = state.value.copy(dialog = PlaylistDialog.RESULT, message = "Плейлист «${target.title}» удалён.")
        }
    }
    private suspend fun ensureCurrent(ticket: Long, expected: PlaylistOwner) {
        currentCoroutineContext().ensureActive()
        if (!valid(ticket, expected)) throw CancellationException("Playlist owner changed")
    }
    private fun runCommand(writing: Boolean = true, action: suspend (PlaylistOwner, Long) -> Unit) {
        val expected = owner() ?: return
        if (state.value.busy || expected != state.value.owner) return
        val ticket = ++generation
        mutable.value = state.value.copy(busy = true, issue = null)
        job = scope.launch {
            try { action(expected, ticket) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(ticket, expected)) {
                    val issue = if ((e as? MusicException)?.httpStatus == 409) "Плейлист изменился в Яндексе. Проверьте его состав и повторите добавление."
                    else ((e as? MusicException)?.failure ?: MusicFailure.RESPONSE).message()
                    mutable.value = state.value.copy(issue = issue + if (writing) " Результат изменения не подтверждён. Проверьте плейлисты перед повтором." else "",
                        dialog = if (writing) PlaylistDialog.CHOOSE else state.value.dialog, loaded = false)
                }
            } finally { if (valid(ticket, expected)) mutable.value = state.value.copy(busy = false) }
        }
    }
}
