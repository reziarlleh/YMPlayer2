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
            val rows = library.state.value.tracks.take(2).mapIndexed { index, original ->
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
                android.net.Uri.parse(library.state.value.tracks.first().uri), "broken.wav").toString()
            library.state.value.tracks[(trackId.removePrefix("yandex:").substringBefore(':').toInt() - 1) % 2].uri!!
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
            val rows = cloudRows.getValue(owner.profileId); rows[rows.indexOfFirst { it.id == playlist.id }] = updated
            return updated
        }
        override suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist) {
            cloudWrites += "delete:${playlist.id}"; cloudGate?.await()
            cloudRows.getValue(owner.profileId).removeAll { it.id == playlist.id }
        }
    }, viewModelScope, online::playlistChanged)
    var collaborators = false
    var longLabels = false
    val equalizerRequests = mutableListOf<Boolean>()
    private fun onlineTrack(id: Int): Track = library.state.value.tracks[(id - 1) % 2].copy(id = "yandex:$id:7", title = if (longLabels) "Очень длинное название композиции — концертная версия с дополнительными исполнителями $id" else "Онлайн: трек $id",
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
    val waveApi = object : MyWaveApi {
        private suspend fun batch(id: Int): WaveBatch {
            waveRequests += id
            delay(waveDelayMillis); waveFailure?.let { throw MusicException(it) }
            if (waveFailuresRemaining > 0) { waveFailuresRemaining--; throw MusicException(MusicFailure.NETWORK) }
            return WaveBatch(listOf(WaveTrack(onlineTrack(id), "batch$id")), "session", id.toString())
        }
        override suspend fun start(profileId: String) = batch(1)
        override suspend fun next(profileId: String, previous: WaveBatch) = batch(previous.cursor.toInt() + 1)
        override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) { waveFeedback += Triple(profileId, item, type) }
    }
    val player = AndroidPlayback(context, library, viewModelScope, online, taste, waveApi, offline, streamQuality = { audioQuality.state.value.stream })
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
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer { ShellModel(harness.library, harness.player, createSavedStateHandle(), accounts = harness.auth, online = harness.online, taste = harness.taste, offline = harness.offline, audioQuality = harness.audioQuality, cloudPlaylists = harness.cloudPlaylists) }
            })
            ShellApp(model, "Online fixture", onExit = ::finish, equalizer = { harness.equalizerRequests += it })
        }
    }
}
