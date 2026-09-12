package dev.petrov.ymplayer2

import android.app.Application
import dev.petrov.ymplayer2.library.SafLibrary
import dev.petrov.ymplayer2.library.LocalCollections
import dev.petrov.ymplayer2.playback.AndroidPlayback
import dev.petrov.ymplayer2.playback.PlaybackHost
import dev.petrov.ymplayer2.core.AccountAuth
import dev.petrov.ymplayer2.core.OnlineMusic
import dev.petrov.ymplayer2.core.MusicTaste
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import dev.petrov.ymplayer2.yandex.YandexDeviceApi
import dev.petrov.ymplayer2.yandex.YandexMusicApi
import dev.petrov.ymplayer2.yandex.YandexTasteApi
import dev.petrov.ymplayer2.yandex.YandexWaveApi
import kotlinx.coroutines.*

class PlayerApplication : Application(), PlaybackHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val library by lazy { SafLibrary(this, scope) }
    val collections by lazy { LocalCollections(this, library, scope) }
    private val musicApi by lazy { YandexMusicApi(accounts) }
    val online by lazy { OnlineMusic(accounts, musicApi, scope) }
    val taste by lazy { MusicTaste(accounts, YandexTasteApi(musicApi), scope, online::refreshCollection) }
    override val playback by lazy { AndroidPlayback(this, library, scope, online, taste, YandexWaveApi(musicApi)) }
    val accounts by lazy { AccountAuth(library.profiles,
        YandexDeviceApi(this, BuildConfig.YANDEX_CLIENT_ID, BuildConfig.YANDEX_CLIENT_SECRET), KeystoreAccountStore(this), scope) }
}
