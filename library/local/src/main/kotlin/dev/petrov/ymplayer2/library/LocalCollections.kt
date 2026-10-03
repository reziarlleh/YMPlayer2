package dev.petrov.ymplayer2.library

import android.content.Context
import android.util.AtomicFile
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One private metadata file; no access to a ContentResolver or music files. */
class LocalCollections(context: Context, private val catalog: Catalog, scope: CoroutineScope) : UserCollections {
    private val file = AtomicFile(File(context.filesDir, "user-collections.json"))
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val mutable = MutableStateFlow(CollectionsState())
    override val state = mutable.asStateFlow()
    init { scope.launch { try { reload() } finally { loaded.complete(Unit) } } }

    override suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val profiles = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyMap() else decode(file.openRead().bufferedReader().use { it.readText() })
                mutable.value = CollectionsState(profiles, ready = true, writable = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(ready = true, writable = false,
                    issue = "Не удалось прочитать плейлисты. Данные сохранены на диске; изменения временно недоступны. Повторите чтение.")
            }
        }
    }
    override suspend fun edit(profileId: String, change: CollectionEdit): Boolean {
        loaded.await()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!state.value.writable || catalog.profiles.none { it.id == profileId }) return@withLock false
                try {
                    val before = state.value
                    val referenceIds = when (change) {
                        is CollectionEdit.Create -> listOfNotNull(change.initialTrackId)
                        is CollectionEdit.Add -> listOf(change.trackId)
                        is CollectionEdit.AddMany -> change.trackIds
                        is CollectionEdit.CreateMany -> change.trackIds
                        is CollectionEdit.Favorite -> listOfNotNull(change.trackId.takeIf { change.selected })
                        else -> emptyList()
                    }
                    val known = if (referenceIds.isEmpty()) emptyMap() else if (catalog is IndexedLocalLibrary)
                        catalog.tracksByIds(referenceIds) else catalog.tracks(profileId).filter { it.id in referenceIds }.associateBy(Track::id)
                    val profile = before.profile(profileId).edited(change, known) { UUID.randomUUID().toString() }
                    val next = before.copy(profiles = before.profiles + (profileId to profile), issue = null)
                    if (profile != before.profile(profileId)) write(next.profiles)
                    mutable.value = next
                    true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (invalid: IllegalArgumentException) { mutable.value = state.value.copy(issue = invalid.message); false }
                catch (_: Exception) { mutable.value = state.value.copy(issue = "Не удалось сохранить плейлисты. Изменение не применено; повторите попытку."); false }
            }
        }
    }
    private fun write(profiles: Map<String, ProfileCollections>) {
        val json = JSONObject().put("schema", 1).put("profiles", JSONObject().apply {
            profiles.forEach { (id, data) -> put(id, JSONObject().put("favorites", encodeTracks(data.favorites))
                .put("playlists", JSONArray(data.playlists.map { JSONObject().put("id", it.id).put("name", it.name).put("tracks", encodeTracks(it.tracks)) }))) }
        })
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun decode(text: String): Map<String, ProfileCollections> {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1)
        val profiles = json.getJSONObject("profiles")
        return profiles.keys().asSequence().associateWith { id ->
            val data = profiles.getJSONObject(id)
            val playlists = data.getJSONArray("playlists").objects().map {
                LocalPlaylist(it.getString("id"), it.getString("name"), decodeTracks(it.getJSONArray("tracks")))
            }
            require(playlists.map { it.id }.distinct().size == playlists.size)
            ProfileCollections(playlists, decodeTracks(data.getJSONArray("favorites")))
        }
    }
    private fun encodeTracks(tracks: List<SavedTrack>) = JSONArray(tracks.map {
        JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("source", it.source.name).put("duration", it.duration).put("tint", it.tint)
    })
    private fun decodeTracks(array: JSONArray) = array.objects().map {
        SavedTrack(it.getString("id"), it.getString("title"), it.getString("artist"), Source.valueOf(it.getString("source")), it.getInt("duration"), it.getInt("tint"))
    }.distinctBy { it.id }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
}
