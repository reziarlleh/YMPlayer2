@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.shell.*
import kotlinx.coroutines.*

/** Explicit debug-only account/transport fixtures; no product fallback or real requests. */
class AccountActionHarness : ViewModel() {
    val users = UserProfiles(listOf(UserProfiles.localProfile(), Profile("family", "Мария", "", accountConnected = true)), {})
    val catalog = object : Catalog {
        override val users get() = this@AccountActionHarness.users
        override val profiles get() = users.state.value
        override fun tracks(profileId: String) = emptyList<Track>()
    }
    private val demo = DemoPlaybackController(catalog)
    var waveCalls = 0
    var offlineCalls = 0
    val player = object : PlaybackController by demo {
        override fun playMyWave() { waveCalls++ }
        override fun playOffline() { offlineCalls++ }
    }
    val sessions = mutableMapOf("family" to AccountSession(YandexAccount("1", "Мария"), OAuthCredentials("fixture-family", null, null)))
    var codeRequests = 0
    var grant = false
    var accountGate: CompletableDeferred<Unit>? = null
    val auth = AccountAuth({ users.state.value }, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String): DeviceChallenge {
            codeRequests++; return DeviceChallenge("fixture-device", "FIXTURE", "https://oauth.yandex.ru/device", 60, 1)
        }
        override suspend fun poll(code: DeviceChallenge): TokenPoll = if (grant) TokenPoll.Granted(OAuthCredentials("fixture-new", null, null)) else TokenPoll.Pending
        override suspend fun account(credentials: OAuthCredentials): YandexAccount {
            accountGate?.await(); return YandexAccount("2", "Анна")
        }
    }, object : AccountStore {
        override suspend fun read(profileId: String) = sessions[profileId]
        override suspend fun write(profileId: String, session: AccountSession?) { if (session == null) sessions.remove(profileId) else sessions[profileId] = session }
    }, viewModelScope)
    val online = OnlineMusic(auth, object : OnlineMusicApi {
        override suspend fun page(profileId: String, request: MusicRequest, page: Int) = MusicPage(emptyList())
        override suspend fun stream(profileId: String, trackId: String, quality: AudioQuality) = error("No real streams")
    }, viewModelScope)
    val taste = MusicTaste(auth, object : MusicTasteApi {
        override suspend fun taste(profileId: String, kind: TasteKind) = TasteList()
        override suspend fun react(profileId: String, target: TasteTarget, action: TasteAction) = Unit
    }, viewModelScope)
    var clipsOpened = 0
    var currentRoute = "player"
    lateinit var model: ShellModel
    init { viewModelScope.launch {
        auth.state.collect { state ->
            if (state.phase == AuthPhase.SIGNED_IN || state.phase == AuthPhase.SIGNED_OUT)
                users.updateAccount(state.profileId, state.account, state.phase == AuthPhase.SIGNED_IN)
        }
    } }
}
class AccountActionTestActivity : ComponentActivity() {
    val harness get() = ViewModelProvider(this)[AccountActionHarness::class.java]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.petrov.ymplayer2.localization.AppLanguages.initialize(this, "fixture-account-action", "ru")
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer { ShellModel(harness.catalog, harness.player, createSavedStateHandle(), accounts = harness.auth, online = harness.online, taste = harness.taste) }
            })
            harness.model = model
            val playback by harness.player.state.collectAsState()
            key(playback.profileId) {
                ShellApp(model, "Account action fixture", initialRoute = harness.currentRoute,
                    onRouteChanged = { harness.currentRoute = it }, openClips = { harness.clipsOpened++ })
            }
        }
    }
}
