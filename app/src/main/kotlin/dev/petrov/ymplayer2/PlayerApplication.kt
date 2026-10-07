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
import kotlinx.coroutines.flow.first

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
    internal val diagnostics: DiagnosticsJournal by lazy { DiagnosticsJournal(this) }
    val skins by lazy { dev.petrov.ymplayer2.designsystem.skin.SkinRepository(this, scope) }
    val launchState by lazy { dev.petrov.ymplayer2.playback.LaunchStateStore(this) }
    val clipCheckpoints by lazy { dev.petrov.ymplayer2.clips.ClipCheckpointStore(this) }
    val navigation by lazy { getSharedPreferences("navigation", MODE_PRIVATE) }
    val radioNavigation by lazy { getSharedPreferences("radio-navigation", MODE_PRIVATE) }
    private var launchRestored = false
    internal var clipActivities = 0
    /** Foreground launcher only; rotation and metadata-only service starts never request playback. */
    fun restoreLaunch(freshActivity: Boolean = true): Boolean {
        if (launchRestored) {
            playback.connect()
            return freshActivity && clipActivities == 0 && navigation.getString("route", "player") == "clips" &&
                launchState.read(playback.state.value.profileId).output == dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS
        }
        launchRestored = true
        val profile = playback.state.value.profileId
        val saved = launchState.read(profile)
        accounts.activate(profile)
        playback.connect()
        return when (saved.output) {
            dev.petrov.ymplayer2.playback.PlaybackOutput.MUSIC -> {
                if (saved.playing) {
                    val queue = runCatching { org.json.JSONObject(getSharedPreferences("playback", MODE_PRIVATE)
                        .getString("queue:$profile", "{}")!!) }.getOrNull()
                    val rows = queue?.optJSONArray("tracks")
                    val remote = queue?.optBoolean("wave") == true || (0 until (rows?.length() ?: 0)).any {
                        rows?.optJSONObject(it)?.let { track -> track.optString("id") == queue?.optString("current") && track.optString("source") == "YANDEX" } == true
                    }
                    if (!remote) playback.resumeOnLaunch() else scope.launch {
                        // Resolve the checkpoint only after account restoration; never autoplay a replacement output.
                        online.catalog.first { it.profileId == profile && it.phase != dev.petrov.ymplayer2.core.AuthPhase.LOADING }
                        if (playback.state.value.profileId == profile && launchState.read(profile) == saved) playback.resumeOnLaunch()
                    }
                }
                false
            }
            dev.petrov.ymplayer2.playback.PlaybackOutput.RADIO -> {
                radio.restoreOnLaunch(profile, saved.playing); false
            }
            dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS -> clipActivities == 0 && navigation.getString("route", "player") == "clips"
        }
    }
    override fun onAudioServiceEvent(event: AudioServiceEvent) {
        if (event == AudioServiceEvent.DESTROYED &&
            launchState.read(playback.state.value.profileId).output != dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS) {
            launchRestored = false
        }
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
    private val radioApi by lazy { dev.petrov.ymplayer2.yandex.YandexRadioApi(accounts) }
    override val radio by lazy { dev.petrov.ymplayer2.playback.AndroidRadio(this, playback, radioApi, scope) }
    val radioCatalog by lazy { dev.petrov.ymplayer2.core.RadioController(accounts, radioApi, radio, scope,
        readNavigation = { profile -> runCatching {
            val data = org.json.JSONObject(radioNavigation.getString(profile, "")!!)
            dev.petrov.ymplayer2.core.RadioNavigation(dev.petrov.ymplayer2.core.RadioTab.valueOf(data.getString("tab")),
                data.optString("query"), data.optJSONObject("filter")?.let {
                    dev.petrov.ymplayer2.core.RadioFilter(it.getString("slug"), it.getString("name"))
                })
        }.getOrNull() },
        saveNavigation = { profile, state ->
            val data = org.json.JSONObject().put("tab", state.tab.name).put("query", state.query)
                .put("filter", state.filter?.let { org.json.JSONObject().put("slug", it.slug).put("name", it.name) })
            radioNavigation.edit().putString(profile, data.toString()).apply()
        }) }
    val accounts by lazy { AccountAuth(library.profiles,
        YandexDeviceApi(this, BuildConfig.YANDEX_CLIENT_ID, BuildConfig.YANDEX_CLIENT_SECRET), KeystoreAccountStore(this), scope) }
}
