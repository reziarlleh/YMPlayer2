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
import dev.petrov.ymplayer2.shell.*
import kotlinx.coroutines.*

/** Explicit debug-only provider fixture. Real Media3, isolated checkpoints, no Yandex/user credentials. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlineHarness(app: Application) : AndroidViewModel(app) {
    val library = (app as PlayerApplication).library
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("online-fixture-$name", mode)
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
    val resolved = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    var requests = 0
    val online = OnlineMusic(auth, object : OnlineMusicApi {
        override suspend fun page(profileId: String, request: MusicRequest, page: Int): MusicPage {
            requests++; delay(50)
            failure?.let { throw MusicException(it) }
            if (request.query == "empty") return MusicPage(emptyList())
            if (request.kind != MusicKind.TRACKS && request.entity == null) return MusicPage(listOf(MusicEntry("album:7", "Тестовый альбом", "Два трека", entity = MusicEntity("7", "Тестовый альбом", MusicKind.ALBUMS))))
            val rows = library.state.value.tracks.take(2).mapIndexed { index, original ->
                val track = original.copy(id = "yandex:${index + 1}:7", title = "Онлайн: ${original.title}", source = Source.YANDEX, offline = false, uri = null, artworkUri = null)
                MusicEntry(track.id, track.title, track.artist, track)
            }
            return MusicPage(if (page == 0) rows.take(1) else rows.drop(1), if (page == 0 && rows.size > 1) 1 else null)
        }
        override suspend fun stream(profileId: String, trackId: String): String = auth.withSession(profileId) {
            resolved += profileId to trackId
            delay(streamDelayMillis)
            streamFailure?.let { throw MusicException(it) }
            library.state.value.tracks[trackId.removePrefix("yandex:").substringBefore(':').toInt() - 1].uri!!
        }
    }, viewModelScope)
    val player = AndroidPlayback(context, library, viewModelScope, online)
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
                initializer { ShellModel(harness.library, harness.player, createSavedStateHandle(), accounts = harness.auth, online = harness.online) }
            })
            ShellApp(model, "Online fixture", onExit = ::finish)
        }
    }
}
