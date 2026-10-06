@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.AndroidPlayback
import dev.petrov.ymplayer2.playback.onlineDataSourceFactory
import dev.petrov.ymplayer2.playback.sessionPlayer
import dev.petrov.ymplayer2.shell.*
import kotlinx.coroutines.*

/** Explicit debug-only provider fixture. Real Media3, isolated checkpoints, no Yandex/user credentials. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlineHarness(app: Application) : AndroidViewModel(app) {
    val library = (app as PlayerApplication).library
    private fun fixtureTracks(): List<Track> = runBlocking { library.pageTracks(CatalogFilter(), limit = 2).items }
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("online-fixture-$name", mode)
        override fun getFilesDir() = java.io.File(super.getFilesDir(), "online-fixture").apply { mkdirs() }
    }
    init { context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit() }
    private val sessions = mutableMapOf("owner" to session("1"), "road" to session("2"))
    val auth = AccountAuth(library.profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String): DeviceChallenge = error("fixture login not supported")
        override suspend fun poll(code: DeviceChallenge): TokenPoll = error("fixture login not supported")
        override suspend fun account(credentials: OAuthCredentials) = YandexAccount("1", "Тестовый слушатель")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = sessions[profileId]
        override suspend fun write(profileId: String, session: AccountSession?) { if (session == null) sessions.remove(profileId) else sessions[profileId] = session }
    }, viewModelScope)
    var failure: MusicFailure? = null
    @Volatile var streamFailure: MusicFailure? = null
    @Volatile var streamDelayMillis = 0L
    @Volatile var brokenStreamTrackIds: Set<String> = emptySet()
    @Volatile var streamGates: Map<String, CompletableDeferred<Unit>> = emptyMap()
    val resolved = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    var requests = 0
    val cloudRows = mutableMapOf("owner" to mutableListOf(CloudPlaylist("77", "1", "Дорожный плейлист")),
        "road" to mutableListOf(CloudPlaylist("88", "2", "Другой аккаунт")))
    val cloudWrites = mutableListOf<String>()
    var cloudGate: CompletableDeferred<Unit>? = null
    var cloudFailAdd = false
    val cloudTracks = mutableMapOf<String, List<CloudPlaylistEntry>>()
    val cloudRevisions = mutableMapOf<String, Long>()
    var cloudFailAfterEdit = false
    fun seedCloudEditor() {
        cloudTracks["owner:77"] = listOf(CloudPlaylistEntry("1", "7", "Первый трек", "Первый исполнитель"),
            CloudPlaylistEntry("2", "7", "Второй трек", "Второй исполнитель"), CloudPlaylistEntry("1", "7", "Первый трек", "Первый исполнитель"))
        cloudRows.getValue("owner")[0] = cloudRows.getValue("owner")[0].copy(trackCount = 3)
        cloudRevisions["owner:77"] = 1
    }
    val online = OnlineMusic(auth, object : OnlineMusicApi {
        override suspend fun page(profileId: String, request: MusicRequest, page: Int): MusicPage {
            requests++; delay(50)
            failure?.let { throw MusicException(it) }
            if (request.query == "empty") return MusicPage(emptyList())
            if (request.kind == MusicKind.PLAYLISTS && request.entity == null) return MusicPage(cloudRows[profileId].orEmpty().map {
                MusicEntry("PLAYLISTS:${it.ownerId}:${it.id}", it.title, "${it.trackCount} треков", entity = it.entity())
            })
            if (request.entity?.kind == MusicKind.ARTISTS && request.kind == MusicKind.ALBUMS) return MusicPage(listOf(MusicEntry("album:7", "Тестовый альбом", "Альбом", entity = MusicEntity("7", "Тестовый альбом", MusicKind.ALBUMS))))
            if (request.kind == MusicKind.ARTISTS && request.entity == null) return MusicPage(listOf(MusicEntry("artist:5", "Первый исполнитель", "Исполнитель", entity = MusicEntity("5", "Первый исполнитель", MusicKind.ARTISTS))))
            if (request.kind != MusicKind.TRACKS && request.entity == null) return MusicPage(listOf(MusicEntry("album:7", "Тестовый альбом", "Два трека", entity = MusicEntity("7", "Тестовый альбом", MusicKind.ALBUMS))))
            val rows = fixtureTracks().take(2).mapIndexed { index, original ->
                val track = onlineTrack(index + 1)
                MusicEntry(track.id, track.title, track.artist, track)
            }
            return MusicPage(if (page == 0) rows.take(1) else rows.drop(1), if (page == 0 && rows.size > 1) 1 else null)
        }
        override suspend fun stream(profileId: String, trackId: String, quality: AudioQuality): String = auth.withSession(profileId) {
            resolvedQualities += trackId to quality
            resolved += profileId to trackId
            delay(streamDelayMillis)
            streamGates[trackId]?.await()
            streamFailure?.let { throw MusicException(it) }
            if (trackId in brokenStreamTrackIds) return@withSession android.provider.DocumentsContract.buildDocumentUriUsingTree(
                android.net.Uri.parse(fixtureTracks().first().uri), "broken.wav").toString()
            fixtureTracks()[(trackId.removePrefix("yandex:").substringBefore(':').toInt() - 1) % 2].uri!!
        }
    }, viewModelScope)
    val cloudPlaylists = CloudPlaylists(auth, object : CloudPlaylistApi {
        override suspend fun list(owner: PlaylistOwner) = cloudRows[owner.profileId].orEmpty().toList()
        override suspend fun create(owner: PlaylistOwner, title: String): CloudPlaylist {
            cloudWrites += "create:${owner.profileId}"; cloudGate?.await()
            val p = CloudPlaylist("${100 + cloudWrites.size}", owner.accountId, title)
            cloudRows.getOrPut(owner.profileId) { mutableListOf() }.add(p); return p
        }
        override suspend fun add(owner: PlaylistOwner, playlist: CloudPlaylist, track: Track): CloudPlaylist {
            cloudWrites += "add:${playlist.id}"; cloudGate?.await()
            if (cloudFailAdd) throw MusicException(MusicFailure.NETWORK)
            val updated = playlist.copy(trackCount = playlist.trackCount + 1)
            val key = "${owner.profileId}:${playlist.id}"
            cloudTracks[key] = cloudTracks[key].orEmpty() + CloudPlaylistEntry(track.cloudTrackKey()!!.first, track.cloudTrackKey()!!.second, track.title, track.artist)
            cloudRevisions[key] = (cloudRevisions[key] ?: 1) + 1
            val rows = cloudRows.getValue(owner.profileId); rows[rows.indexOfFirst { it.id == playlist.id }] = updated
            return updated
        }
        override suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist) {
            cloudWrites += "delete:${playlist.id}"; cloudGate?.await()
            cloudRows.getValue(owner.profileId).removeAll { it.id == playlist.id }
        }
        override suspend fun load(owner: PlaylistOwner, playlist: CloudPlaylist): CloudPlaylistSnapshot {
            val key = "${owner.profileId}:${playlist.id}"
            return CloudPlaylistSnapshot(cloudRows.getValue(owner.profileId).first { it.id == playlist.id }, cloudRevisions[key] ?: 1, cloudTracks[key].orEmpty())
        }
        override suspend fun rename(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, title: String): CloudPlaylistSnapshot {
            cloudWrites += "rename:$title"; cloudGate?.await()
            val rows = cloudRows.getValue(owner.profileId); val index = rows.indexOfFirst { it.id == snapshot.playlist.id }
            rows[index] = rows[index].copy(title = title)
            cloudRevisions["${owner.profileId}:${snapshot.playlist.id}"] = snapshot.revision + 1
            if (cloudFailAfterEdit) throw MusicException(MusicFailure.NETWORK)
            return load(owner, snapshot.playlist)
        }
        override suspend fun edit(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, change: CloudTrackEdit): CloudPlaylistSnapshot {
            cloudWrites += "edit:$change"; cloudGate?.await()
            val key = "${owner.profileId}:${snapshot.playlist.id}"
            if ((cloudRevisions[key] ?: 1) != snapshot.revision) throw MusicException(MusicFailure.RESPONSE, 409)
            val tracks = snapshot.edited(change); cloudTracks[key] = tracks; cloudRevisions[key] = snapshot.revision + 1
            val rows = cloudRows.getValue(owner.profileId); val index = rows.indexOfFirst { it.id == snapshot.playlist.id }
            rows[index] = rows[index].copy(trackCount = tracks.size)
            if (cloudFailAfterEdit) throw MusicException(MusicFailure.NETWORK)
            return load(owner, snapshot.playlist)
        }
    }, viewModelScope, online::playlistChanged)
    var collaborators = false
    var longLabels = false
    val equalizerRequests = mutableListOf<Boolean>()
    private fun onlineTrack(id: Int): Track = fixtureTracks()[(id - 1) % 2].copy(id = "yandex:$id:7", title = if (longLabels) "Очень длинное название композиции — концертная версия с дополнительными исполнителями $id" else "Онлайн: трек $id",
        source = Source.YANDEX, offline = false, uri = null, artworkUri = null, artists = listOf(ArtistRef(if (id % 2 == 1) "5" else "6", if (id % 2 == 1) "Первый исполнитель" else "Второй исполнитель")) + if (collaborators) listOf(ArtistRef("8", "Совместный исполнитель")) else emptyList(), albumId = "7")
    val tasteLists = mutableMapOf<Pair<String, TasteKind>, TasteList>()
    val tasteWrites = mutableListOf<Pair<TasteTarget, TasteAction>>()
    var tasteReadGate: CompletableDeferred<Unit>? = null
    var tasteReadFailure: MusicFailure? = null
    val taste = MusicTaste(auth, object : MusicTasteApi {
        override suspend fun taste(profileId: String, kind: TasteKind): TasteList {
            tasteReadGate?.await()
            tasteReadFailure?.let { throw MusicException(it) }
            return tasteLists[profileId to kind] ?: TasteList()
        }
        override suspend fun react(profileId: String, target: TasteTarget, action: TasteAction) {
            tasteWrites += target to action
            val key = profileId to target.kind; val old = tasteLists[key] ?: TasteList()
            tasteLists[key] = when (action) {
                TasteAction.LIKE -> old.copy(liked = old.liked + target.key, blocked = old.blocked - target.key)
                TasteAction.UNLIKE -> old.copy(liked = old.liked - target.key)
                TasteAction.BLOCK -> old.copy(blocked = old.blocked + target.key, liked = old.liked - target.key)
                TasteAction.UNBLOCK -> old.copy(blocked = old.blocked - target.key)
            }
        }
    }, viewModelScope, online::refreshCollection)
    var offlineArtwork: String? = null
    val audioQuality = loadAudioQuality(context)
    val resolvedQualities = java.util.concurrent.CopyOnWriteArrayList<Pair<String, AudioQuality>>()
    fun restoredAudioQuality() = loadAudioQuality(context).state.value
    var offlineNetwork = true
    val offlineStore = dev.petrov.ymplayer2.offline.LikedFileStore(context)
    val offline = OfflineMusic(auth, taste, object : LikedMusicApi {
        override suspend fun keys(profileId: String) = tasteLists[profileId to TasteKind.TRACK]?.listKeys().orEmpty()
        override suspend fun snapshot(profileId: String): LikedSnapshot {
            failure?.let { throw MusicException(it) }
            val keys = keys(profileId)
            return LikedSnapshot(keys, keys.map { onlineTrack(it.toInt()).copy(artworkUri = offlineArtwork) })
        }
        private fun TasteList.listKeys() = liked - blocked
    }, online.api, offlineStore, viewModelScope, network = { offlineNetwork }, cacheQuality = { audioQuality.state.value.cache })
    var waveDelayMillis = 100L
    var waveFailure: MusicFailure? = null
    var waveFailuresRemaining = 0
    val waveRequests = mutableListOf<Int>()
    val waveFeedback = mutableListOf<Triple<String, WaveTrack, WaveFeedback>>()
    val waveSelections = mutableListOf<WaveRequest>()
    var waveOptionsFailure: MusicFailure? = null
    val waveApi = object : MyWaveApi {
        private suspend fun batch(id: Int): WaveBatch {
            waveRequests += id
            delay(waveDelayMillis); waveFailure?.let { throw MusicException(it) }
            if (waveFailuresRemaining > 0) { waveFailuresRemaining--; throw MusicException(MusicFailure.NETWORK) }
            return WaveBatch(listOf(WaveTrack(onlineTrack(id), "batch$id")), "session", id.toString())
        }
        override suspend fun start(profileId: String) = batch(1)
        override suspend fun start(profileId: String, request: WaveRequest): WaveBatch {
            waveSelections += request
            return batch(1).copy(request = request)
        }
        override suspend fun next(profileId: String, previous: WaveBatch) = batch(previous.cursor.toInt() + 1).copy(request = previous.request)
        override suspend fun options(profileId: String, language: String): WaveOptions {
            waveOptionsFailure?.let { throw MusicException(it) }
            return WaveOptions(listOf(
                WaveOptionGroup("contexts", "Под занятие", listOf(WaveOption("user:onyourwave", "Любое", true), WaveOption("activity:road-trip", "В дороге"))),
                WaveOptionGroup("diversity", "По характеру", listOf(WaveOption("settingDiversity:default", "Любое", true), WaveOption("settingDiversity:discover", "Незнакомое"))),
                WaveOptionGroup("moodEnergy", "Под настроение", listOf(WaveOption("settingMoodEnergy:all", "Любое", true), WaveOption("settingMoodEnergy:calm", "Спокойное"))),
                WaveOptionGroup("language", "По языку", listOf(WaveOption("settingLanguage:any", "Любой", true), WaveOption("settingLanguage:russian", "Казахский")))))
        }
        override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) { waveFeedback += Triple(profileId, item, type) }
    }
    val history = dev.petrov.ymplayer2.library.ListeningHistory(context, viewModelScope, "fixture-history.db")
    val player = AndroidPlayback(context, library, viewModelScope, online, taste, waveApi, offline, streamQuality = { audioQuality.state.value.stream }, listened = history::record)
    val systemPlayer get() = sessionPlayer(engine!!, player)
    private var engine: ExoPlayer? = null
    init { createEngine() }
    private fun createEngine() {
        engine = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context)
            .setDataSourceFactory(onlineDataSourceFactory(context) { profile, track -> player.resolveStream(profile, track) })).build().also { player.attach(it) }
    }
    fun restartEngine() { player.detach(); engine?.release(); createEngine() }
    fun checkpointText() = context.getSharedPreferences("playback", Context.MODE_PRIVATE).getString("queue:owner", "").orEmpty()
    override fun onCleared() { player.detach(); engine?.release(); super.onCleared() }
    private fun session(id: String) = AccountSession(YandexAccount(id, "Тестовый слушатель"), OAuthCredentials("fixture-$id", null, null))
}

class OnlineTestActivity : ComponentActivity() {
    val harness get() = ViewModelProvider(this)[OnlineHarness::class.java]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.petrov.ymplayer2.localization.AppLanguages.initialize(this, "fixture-language", "ru"); enableEdgeToEdge()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer { ShellModel(harness.library, harness.player, createSavedStateHandle(), accounts = harness.auth, online = harness.online, taste = harness.taste, offline = harness.offline, audioQuality = harness.audioQuality, cloudPlaylists = harness.cloudPlaylists, history = harness.history) }
            })
            ShellApp(model, "Online fixture", onExit = ::finish, equalizer = { harness.equalizerRequests += it })
        }
    }
}
