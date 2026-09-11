package dev.petrov.ymplayer2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.shell.*

/** Explicit debug-only auth harness: no fake response is a production fallback. */
class AuthHarness : ViewModel() {
    val catalog = DemoCatalog()
    val player = DemoPlaybackController(catalog)
    val sessions = mutableMapOf<String, AccountSession>()
    var result: TokenPoll = TokenPoll.Pending
    var failure: AuthFailure? = null
    var lifetimeSeconds = 60L
    var requests = 0
    private val api = object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String): DeviceChallenge {
            requests++
            return DeviceChallenge("test-device-$profileId", "TEST1234", "https://oauth.yandex.ru/device", lifetimeSeconds, 1)
        }
        override suspend fun poll(code: DeviceChallenge): TokenPoll {
            failure?.let { throw AuthException(it) }
            return result
        }
        override suspend fun account(credentials: OAuthCredentials) = YandexAccount("123", "Тестовый слушатель")
    }
    val auth = AccountAuth(catalog.profiles, api, object : AccountStore {
        override suspend fun read(profileId: String) = sessions[profileId]
        override suspend fun write(profileId: String, session: AccountSession?) {
            if (session == null) sessions.remove(profileId) else sessions[profileId] = session
        }
    }, viewModelScope)
}
class AuthTestActivity : ComponentActivity() {
    val harness get() = ViewModelProvider(this)[AuthHarness::class.java]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer { ShellModel(harness.catalog, harness.player, createSavedStateHandle(), accounts = harness.auth) }
            })
            ShellApp(model, "Auth fixture", onExit = ::finish)
        }
    }
}
