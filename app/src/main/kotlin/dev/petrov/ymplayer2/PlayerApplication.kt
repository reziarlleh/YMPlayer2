package dev.petrov.ymplayer2

import android.app.Application
import dev.petrov.ymplayer2.library.SafLibrary
import dev.petrov.ymplayer2.library.LocalCollections
import dev.petrov.ymplayer2.playback.AndroidPlayback
import dev.petrov.ymplayer2.playback.PlaybackHost
import dev.petrov.ymplayer2.playback.AudioServiceEvent
import dev.petrov.ymplayer2.core.AccountAuth
import dev.petrov.ymplayer2.core.OnlineMusic
import dev.petrov.ymplayer2.core.MusicTaste
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import dev.petrov.ymplayer2.yandex.YandexDeviceApi
import dev.petrov.ymplayer2.yandex.YandexMusicApi
import dev.petrov.ymplayer2.yandex.YandexTasteApi
import dev.petrov.ymplayer2.yandex.YandexWaveApi
import dev.petrov.ymplayer2.core.OfflineMusic
import dev.petrov.ymplayer2.offline.LikedFileStore
import dev.petrov.ymplayer2.yandex.YandexLikedMusicApi
import kotlinx.coroutines.*

class PlayerApplication : Application(), PlaybackHost {
    override fun onCreate() {
        super.onCreate()
        diagnostics.installCrashCapture()
        dev.petrov.ymplayer2.localization.AppLanguages.initialize(this)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dev.petrov.ymplayer2.localization.AppLanguages.refresh()
        diagnostics.refreshEnvironment()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val diagnostics: DiagnosticsJournal by lazy { DiagnosticsJournal(this, researchActions =
        if (BuildConfig.RADIO_AUTH_PROBE) listOf(dev.petrov.ymplayer2.shell.DiagnosticAction("Проверить вход в Яндекс Радио") {
            try {
                val report = withTimeout(120_000) {
                    accounts.withSession(accounts.state.value.profileId) { session ->
                        YandexRadioAuthProbe().run(session.credentials.accessToken, session.account?.id)
                    }
                }
                diagnostics.saveRadioProbe(report)
                report.summary
            } catch (_: TimeoutCancellationException) { "Проверка не завершилась за две минуты. Проверь сеть и повтори." }
            catch (e: CancellationException) { throw e }
            catch (_: dev.petrov.ymplayer2.core.MusicException) { "Нужен текущий авторизованный профиль. Вход не изменён." }
            catch (_: Exception) { "Не удалось завершить проверку Радио. Вход не изменён." }
        }) else emptyList()) }
    val skins by lazy { dev.petrov.ymplayer2.designsystem.skin.SkinRepository(this, scope) }
    override fun onAudioServiceEvent(event: AudioServiceEvent) {
        diagnostics.record(when (event) {
            AudioServiceEvent.CREATED -> DiagnosticEvent.AUDIO_SERVICE_CREATED
            AudioServiceEvent.DESTROYED -> DiagnosticEvent.AUDIO_SERVICE_DESTROYED
            AudioServiceEvent.RESUMPTION_REQUESTED -> DiagnosticEvent.RESUMPTION_REQUESTED
            AudioServiceEvent.RESUMPTION_AVAILABLE -> DiagnosticEvent.RESUMPTION_AVAILABLE
        })
    }
    val library by lazy { SafLibrary(this, scope) }
    val collections by lazy { LocalCollections(this, library, scope) }
    val history by lazy { dev.petrov.ymplayer2.library.ListeningHistory(this, scope) }
    private val musicApi by lazy { YandexMusicApi(accounts) }
    val online by lazy { OnlineMusic(accounts, musicApi, scope) }
    val cloudPlaylists by lazy { dev.petrov.ymplayer2.core.CloudPlaylists(accounts,
        dev.petrov.ymplayer2.yandex.YandexPlaylistApi(musicApi), scope, online::playlistChanged) }
    val taste by lazy { MusicTaste(accounts, YandexTasteApi(musicApi), scope, online::refreshCollection) }
    private val offlinePrefs by lazy { getSharedPreferences("offline", MODE_PRIVATE) }
    val audioQuality by lazy { loadAudioQuality(this) }
    val offline by lazy { OfflineMusic(accounts, taste, YandexLikedMusicApi(musicApi), musicApi, LikedFileStore(this), scope,
        offlinePrefs.getBoolean("wifiOnly", true), { offlinePrefs.edit().putBoolean("wifiOnly", it).apply() }, { wifi ->
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!wifi || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        }, cacheQuality = { audioQuality.state.value.cache },
        enabled = offlinePrefs.getBoolean("enabled", true),
        saveEnabled = { offlinePrefs.edit().putBoolean("enabled", it).apply() }) }
    override val playback by lazy { AndroidPlayback(this, library, scope, online, taste, YandexWaveApi(musicApi), offline,
        streamQuality = { audioQuality.state.value.stream }, listened = history::record) }
    val accounts by lazy { AccountAuth(library.profiles,
        YandexDeviceApi(this, BuildConfig.YANDEX_CLIENT_ID, BuildConfig.YANDEX_CLIENT_SECRET), KeystoreAccountStore(this), scope) }
}
