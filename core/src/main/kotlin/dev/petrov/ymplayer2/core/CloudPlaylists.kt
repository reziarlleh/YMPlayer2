package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlaylistOwner(val profileId: String, val accountId: String)
data class CloudPlaylist(val id: String, val ownerId: String, val title: String, val trackCount: Int = 0) {
    fun entity() = MusicEntity(id, title, MusicKind.PLAYLISTS, ownerId)
}
/** Ordered occurrences, including duplicates and unavailable tracks. Never use the playback list's indices. */
data class CloudPlaylistEntry(val id: String, val albumId: String?, val title: String, val artist: String = "") {
    val key get() = id to albumId
    val movable get() = id.matches(Regex("[0-9]+")) && albumId?.matches(Regex("[0-9]+")) == true
}
data class CloudPlaylistSnapshot(val playlist: CloudPlaylist, val revision: Long, val tracks: List<CloudPlaylistEntry>)
sealed interface CloudTrackEdit {
    data class Remove(val index: Int) : CloudTrackEdit
    /** Destination index in the final list, after removing the source occurrence. */
    data class Move(val from: Int, val to: Int) : CloudTrackEdit
}
fun CloudPlaylistSnapshot.edited(edit: CloudTrackEdit): List<CloudPlaylistEntry> = tracks.toMutableList().apply {
    when (edit) {
        is CloudTrackEdit.Remove -> { require(edit.index in indices); removeAt(edit.index) }
        is CloudTrackEdit.Move -> {
            require(edit.from in indices && edit.to in indices && tracks[edit.from].movable)
            add(edit.to, removeAt(edit.from))
        }
    }
}
interface CloudPlaylistApi {
    suspend fun list(owner: PlaylistOwner): List<CloudPlaylist>
    suspend fun create(owner: PlaylistOwner, title: String): CloudPlaylist
    suspend fun add(owner: PlaylistOwner, playlist: CloudPlaylist, track: Track): CloudPlaylist
    suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist)
    suspend fun load(owner: PlaylistOwner, playlist: CloudPlaylist): CloudPlaylistSnapshot
    suspend fun rename(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, title: String): CloudPlaylistSnapshot
    suspend fun edit(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, change: CloudTrackEdit): CloudPlaylistSnapshot
}
/** 1.x inserts an id/albumId pair. Local files never enter a cloud write. */
fun Track.cloudTrackKey(): Pair<String, String>? {
    if (source != Source.YANDEX || !id.startsWith("yandex:")) return null
    val parts = id.removePrefix("yandex:").split(':')
    val album = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: albumId.orEmpty()
    val numeric = Regex("[0-9]{1,30}")
    return if (parts.size in 1..2 && parts[0].matches(numeric) && album.matches(numeric)) parts[0] to album else null
}
enum class PlaylistDialog { CHOOSE, CREATE, DELETE, RESULT, EDIT, RENAME, REMOVE, MOVE }
data class CloudPlaylistState(
    val owner: PlaylistOwner? = null, val dialog: PlaylistDialog? = null,
    val track: Track? = null, val target: CloudPlaylist? = null,
    val playlists: List<CloudPlaylist> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val issue: String? = null, val message: String? = null,
    val snapshot: CloudPlaylistSnapshot? = null, val selectedIndex: Int? = null,
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
    fun openEditor(entity: MusicEntity) {
        if (state.value.busy || !editable(entity)) return
        generation++; job?.cancel()
        mutable.value = CloudPlaylistState(owner(), PlaylistDialog.EDIT, target = CloudPlaylist(entity.id, entity.ownerId!!, entity.title))
        refreshEditor()
    }
    fun refreshEditor() {
        val target = state.value.target ?: return
        if (state.value.dialog != PlaylistDialog.EDIT) return
        runCommand(writing = false, editing = true) { expected, ticket ->
            val snapshot = api.load(expected, target); ensureCurrent(ticket, expected)
            publishEditor(snapshot)
        }
    }
    private fun publishEditor(snapshot: CloudPlaylistSnapshot, message: String? = null) {
        require(snapshot.playlist.ownerId == owner()?.accountId && snapshot.playlist.id == state.value.target?.id)
        mutable.value = state.value.copy(dialog = PlaylistDialog.EDIT, target = snapshot.playlist, snapshot = snapshot,
            loaded = true, selectedIndex = null, message = message)
        changed(snapshot.playlist, false)
    }
    fun editorAction(mode: PlaylistDialog, index: Int? = null) {
        val before = state.value
        if (before.busy || !before.loaded || before.dialog != PlaylistDialog.EDIT) return
        val snapshot = before.snapshot ?: return
        if (mode !in setOf(PlaylistDialog.RENAME, PlaylistDialog.REMOVE, PlaylistDialog.MOVE)) return
        if (mode != PlaylistDialog.RENAME && (index == null || index !in snapshot.tracks.indices)) return
        if (mode == PlaylistDialog.MOVE && !snapshot.tracks[index!!].movable) return
        mutable.value = before.copy(dialog = mode, selectedIndex = index, message = null, issue = null)
    }
    fun backToEditor() {
        if (!state.value.busy && state.value.dialog in setOf(PlaylistDialog.RENAME, PlaylistDialog.REMOVE, PlaylistDialog.MOVE))
            mutable.value = state.value.copy(dialog = PlaylistDialog.EDIT, selectedIndex = null)
    }
    fun rename(title: String) {
        val before = state.value; val snapshot = before.snapshot ?: return
        val name = title.trim()
        if (!before.loaded || before.dialog != PlaylistDialog.RENAME || name.isBlank() || name.length > 200) return
        if (name == snapshot.playlist.title) { backToEditor(); return }
        runCommand(editing = true) { expected, ticket ->
            val updated = api.rename(expected, snapshot, name); ensureCurrent(ticket, expected)
            publishEditor(updated, "Название сохранено.")
        }
    }
    fun removeTrack() {
        if (state.value.dialog != PlaylistDialog.REMOVE) return
        state.value.selectedIndex?.let { editTracks(CloudTrackEdit.Remove(it)) }
    }
    fun moveTrack(to: Int) {
        val before = state.value; val from = before.selectedIndex ?: return
        if (before.dialog != PlaylistDialog.MOVE || to !in before.snapshot!!.tracks.indices) return
        if (from == to) { backToEditor(); return }
        editTracks(CloudTrackEdit.Move(from, to))
    }
    /** Commit one completed drag, never the intermediate preview positions. */
    fun dragMove(from: Int, to: Int) {
        val before = state.value
        val tracks = before.snapshot?.tracks ?: return
        if (before.dialog != PlaylistDialog.EDIT || before.busy || !before.loaded ||
            from !in tracks.indices || to !in tracks.indices || from == to || !tracks[from].movable) return
        editTracks(CloudTrackEdit.Move(from, to))
    }
    private fun editTracks(change: CloudTrackEdit) {
        val before = state.value; val snapshot = before.snapshot ?: return
        if (!before.loaded) return
        runCommand(editing = true) { expected, ticket ->
            val updated = api.edit(expected, snapshot, change); ensureCurrent(ticket, expected)
            publishEditor(updated, if (change is CloudTrackEdit.Remove) "Трек убран из плейлиста." else "Порядок треков сохранён.")
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
    private fun runCommand(writing: Boolean = true, editing: Boolean = false, action: suspend (PlaylistOwner, Long) -> Unit) {
        val expected = owner() ?: return
        if (state.value.busy || expected != state.value.owner) return
        val ticket = ++generation
        mutable.value = state.value.copy(busy = true, issue = null)
        job = scope.launch {
            try { action(expected, ticket) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(ticket, expected)) {
                    val issue = if ((e as? MusicException)?.httpStatus == 409) "Плейлист изменился в Яндексе. Обновите его и проверьте состав перед повтором."
                    else ((e as? MusicException)?.failure ?: MusicFailure.RESPONSE).message()
                    mutable.value = state.value.copy(issue = issue + if (writing) " Результат изменения не подтверждён. Проверьте плейлисты перед повтором." else "",
                        dialog = if (editing) PlaylistDialog.EDIT else if (writing) PlaylistDialog.CHOOSE else state.value.dialog, loaded = false, selectedIndex = null)
                }
            } finally { if (valid(ticket, expected)) mutable.value = state.value.copy(busy = false) }
        }
    }
}
