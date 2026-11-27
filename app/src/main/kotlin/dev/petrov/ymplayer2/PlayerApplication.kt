package dev.petrov.ymplayer2

import android.app.Application
import android.content.Intent
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
import androidx.lifecycle.withResumed

class PlayerApplication : Application(), PlaybackHost {
    override fun onCreate() {
        super.onCreate()
        diagnostics.installCrashCapture()
        LegacyTls.install(this)
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
    internal val carArtwork by lazy { CarArtwork(this) }
    override val carCatalog: dev.petrov.ymplayer2.playback.CarCatalog by lazy { AppCarCatalog(this) }
    internal var handOffPhoneClips: (() -> Unit)? = null
    internal fun playCarClips() {
        handOffPhoneClips?.invoke()
        val profile = playback.state.value.profileId
        playback.startCarClips { engine, changed -> CarClipPlayback(this, profile, engine, changed) }
    }
    val playbackStart by lazy { loadPlaybackStartSettings(this) }
    private val uiEntries = dev.petrov.ymplayer2.core.PlaybackUiEntries()
    private var interfacePlayJob: Job? = null
    /** One entry across the main/video Activities, excluding configuration handoffs. */
    fun uiStarted(owner: androidx.activity.ComponentActivity): dev.petrov.ymplayer2.core.PlaybackStartMode? {
        if (!uiEntries.started(owner)) return null
        val profile = playback.state.value.profileId
        val mode = playbackStart.mode(profile)
        diagnostics.record(dev.petrov.ymplayer2.core.OperationDiagnostic(when (mode) {
            dev.petrov.ymplayer2.core.PlaybackStartMode.AUTO -> dev.petrov.ymplayer2.core.DiagnosticOperation.STARTUP_AUTO
            dev.petrov.ymplayer2.core.PlaybackStartMode.OFF -> dev.petrov.ymplayer2.core.DiagnosticOperation.STARTUP_OFF
            dev.petrov.ymplayer2.core.PlaybackStartMode.ON -> dev.petrov.ymplayer2.core.DiagnosticOperation.STARTUP_ON
        }, elapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime()))
        if (mode == dev.petrov.ymplayer2.core.PlaybackStartMode.ON) {
            interfacePlayJob?.cancel()
            val output = launchState.read(profile).output
            val revision = playback.launchRevision
            val radioRevision = radio.launchRevision
            interfacePlayJob = scope.launch {
                if (output == dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS) return@launch
                // Newer Android versions require a genuinely foreground UI to obtain audio focus.
                owner.lifecycle.withResumed { }
                if (output == dev.petrov.ymplayer2.playback.PlaybackOutput.RADIO || musicNeedsAccount(profile))
                    online.catalog.first { it.profileId == profile && it.phase != dev.petrov.ymplayer2.core.AuthPhase.LOADING }
                playback.state.first { it.connected }
                if (!uiEntries.isVisible || playback.state.value.profileId != profile ||
                    playbackStart.mode(profile) != dev.petrov.ymplayer2.core.PlaybackStartMode.ON ||
                    launchState.read(profile).output != output || playback.launchRevision != revision) return@launch
                when (output) {
                    dev.petrov.ymplayer2.playback.PlaybackOutput.MUSIC -> playback.playOnInterfaceOpen(revision)
                    dev.petrov.ymplayer2.playback.PlaybackOutput.RADIO -> if (radio.launchRevision == radioRevision &&
                        !radio.state.value.playing && !radio.state.value.buffering && !radio.state.value.reconnecting)
                        radio.restoreOnLaunch(profile, true)
                    dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS -> Unit
                }
            }
        }
        return mode
    }
    fun uiStopped(owner: Any, changingConfiguration: Boolean) {
        if (uiEntries.stopped(owner, changingConfiguration)) { interfacePlayJob?.cancel(); interfacePlayJob = null }
    }
    private fun musicNeedsAccount(profile: String): Boolean {
        val queue = runCatching { org.json.JSONObject(getSharedPreferences("playback", MODE_PRIVATE)
            .getString("queue:$profile", "{}")!!) }.getOrNull()
        val rows = queue?.optJSONArray("tracks")
        return queue?.optBoolean("wave") == true || (0 until (rows?.length() ?: 0)).any {
            rows?.optJSONObject(it)?.let { track -> track.optString("id") == queue?.optString("current") && track.optString("source") == "YANDEX" } == true
        }
    }
    val navigation by lazy { getSharedPreferences("navigation", MODE_PRIVATE) }
    val radioNavigation by lazy { getSharedPreferences("radio-navigation", MODE_PRIVATE) }
    val internet by lazy { AndroidInternetConnection(this, scope) }
    fun shellRoute(): String {
        val route = navigation.getString("route", "player") ?: "player"
        return (if (route == "clips") navigation.getString("clips_return_route", "player") else route)
            ?.takeUnless { it.isBlank() || it == "clips" } ?: "player"
    }
    fun enterClips() {
        val route = navigation.getString("route", "player") ?: "player"
        navigation.edit().apply {
            if (route != "clips") putString("clips_return_route", route)
            putString("route", "clips")
        }.apply()
    }
    fun leaveClips() {
        if (navigation.getString("route", "player") == "clips")
            navigation.edit().putString("route", shellRoute()).apply()
    }
    private var launchRestored = false
    internal var clipActivities = 0
    /** Foreground launcher only; rotation and metadata-only service starts never request playback. */
    fun restoreLaunch(freshActivity: Boolean = true): Boolean {
        if (launchRestored) {
            playback.connect()
            if (playbackStart.mode(playback.state.value.profileId) == dev.petrov.ymplayer2.core.PlaybackStartMode.AUTO)
                playback.restoreForegroundSessionIfNeeded()
            return freshActivity && !playback.carClipOutputActive && clipActivities == 0 && navigation.getString("route", "player") == "clips" &&
                launchState.read(playback.state.value.profileId).output == dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS
        }
        launchRestored = true
        val profile = playback.state.value.profileId
        val saved = launchState.read(profile)
        val automatic = playbackStart.mode(profile) == dev.petrov.ymplayer2.core.PlaybackStartMode.AUTO
        val revision = playback.launchRevision
        accounts.activate(profile)
        playback.connect()
        return when (saved.output) {
            dev.petrov.ymplayer2.playback.PlaybackOutput.MUSIC -> {
                if (automatic && !saved.stopped) {
                    val remote = musicNeedsAccount(profile)
                    if (!remote) playback.resumeOnLaunch(revision) else scope.launch {
                        // Resolve the checkpoint only after account restoration; never autoplay a replacement output.
                        online.catalog.first { it.profileId == profile && it.phase != dev.petrov.ymplayer2.core.AuthPhase.LOADING }
                        if (playback.state.value.profileId == profile && launchState.read(profile) == saved &&
                            playbackStart.mode(profile) == dev.petrov.ymplayer2.core.PlaybackStartMode.AUTO) playback.resumeOnLaunch(revision)
                    }
                }
                false
            }
            dev.petrov.ymplayer2.playback.PlaybackOutput.RADIO -> {
                if (radio.state.value.let { !it.playing && !it.buffering && !it.reconnecting })
                    radio.restoreOnLaunch(profile, automatic && saved.playing)
                false
            }
            dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS -> !playback.carClipOutputActive && clipActivities == 0 && navigation.getString("route", "player") == "clips"
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
    val users by lazy { dev.petrov.ymplayer2.library.loadUserProfiles(this, ::removeProfileData) }
    val library by lazy { SafLibrary(this, scope, users) }
    val collections by lazy { LocalCollections(this, library, scope) }
    val mediaFiles by lazy {
        dev.petrov.ymplayer2.library.LocalFileManager(this, library, collections).also { manager ->
            scope.launch { manager.recoverPending() }
        }
    }
    val history by lazy { dev.petrov.ymplayer2.library.ListeningHistory(this, scope) }
    private val musicApi by lazy { YandexMusicApi(accounts, diagnostic = diagnostics::record) }
    val online by lazy { OnlineMusic(accounts, musicApi, scope) }
    val cloudPlaylists by lazy { dev.petrov.ymplayer2.core.CloudPlaylists(accounts,
        dev.petrov.ymplayer2.yandex.YandexPlaylistApi(musicApi), scope, online::playlistChanged) }
    val taste: MusicTaste by lazy { MusicTaste(accounts, YandexTasteApi(musicApi), scope, online::refreshCollection,
        store = dev.petrov.ymplayer2.yandex.AndroidTasteStore(this), connection = internet.available,
        diagnostic = diagnostics::record, confirmed = { owner, target, action -> offline.confirmedReaction(owner, target, action) }) }
    private val offlinePrefs by lazy { getSharedPreferences("offline", MODE_PRIVATE) }
    val audioQuality by lazy { loadAudioQuality(this) }
    val offlineStore by lazy { LikedFileStore(this, diagnostics::record) }
    val cacheLocation: dev.petrov.ymplayer2.offline.CacheLocationController by lazy { dev.petrov.ymplayer2.offline.CacheLocationController(offlineStore, offline, scope) { audioExporter.state.value.running } }
    val audioExporter: dev.petrov.ymplayer2.offline.AudioExporter by lazy { dev.petrov.ymplayer2.offline.AudioExporter(this, accounts, musicApi, offline, cacheLocation, scope, diagnostics::record) }
    val offline: OfflineMusic by lazy { OfflineMusic(accounts, taste, YandexLikedMusicApi(musicApi), musicApi, offlineStore, scope,
        offlinePrefs.getBoolean("wifiOnly", true), { offlinePrefs.edit().putBoolean("wifiOnly", it).apply() }, { wifi ->
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!wifi || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        }, cacheQuality = { audioQuality.state.value.cache },
        enabled = offlinePrefs.getBoolean("enabled", true),
        saveEnabled = { offlinePrefs.edit().putBoolean("enabled", it).apply() }, diagnostic = diagnostics::record,
        automaticWork = { runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, OfflineSyncService::class.java)) } })
        .also { cache ->
            scope.launch { internet.available.collect { if (it) cache.retryAutomatic() } }
            scope.launch { while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(30_000); taste.retryPending(); cache.retryAutomatic()
            } }
        } }
    override val playback by lazy { AndroidPlayback(this, library, scope, online, taste, YandexWaveApi(musicApi, diagnostics::record), offline,
        streamQuality = { audioQuality.state.value.stream }, listened = history::record,
        waveFeedbackResult = { success -> scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            diagnostics.record(if (success) DiagnosticEvent.WAVE_FEEDBACK_SENT else DiagnosticEvent.WAVE_FEEDBACK_FAILED)
        } }, diagnostic = diagnostics::record, networkAvailable = { internet.available.value }) }
    private val radioApi by lazy { dev.petrov.ymplayer2.yandex.YandexRadioApi(accounts) }
    override val radio by lazy { dev.petrov.ymplayer2.playback.AndroidRadio(this, playback, radioApi, scope, diagnostics::record) }
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
    val accounts by lazy {
        val store = KeystoreAccountStore(this)
        AccountAuth({ library.profiles },
            YandexDeviceApi(this, BuildConfig.YANDEX_CLIENT_ID, BuildConfig.YANDEX_CLIENT_SECRET), store, scope).also { auth ->
            // Cached names of inactive accounts need neither network nor another OAuth exchange.
            users.setAccountsLoading(true)
            scope.launch {
                try { users.state.value.filter { it.canSignIn }.forEach { profile ->
                    try { val saved = store.read(profile.id); users.updateAccount(profile.id, saved?.account, saved != null) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Preserve cached details if encrypted storage cannot be read. */ }
                } } finally { users.setAccountsLoading(false) }
            }
            scope.launch {
                auth.state.collect { state ->
                    if (state.phase == dev.petrov.ymplayer2.core.AuthPhase.SIGNED_IN || state.phase == dev.petrov.ymplayer2.core.AuthPhase.SIGNED_OUT ||
                        state.phase == dev.petrov.ymplayer2.core.AuthPhase.ERROR && state.account != null) {
                        try { users.updateAccount(state.profileId, state.account, state.phase != dev.petrov.ymplayer2.core.AuthPhase.SIGNED_OUT) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Naming must never turn successful sign-in into an error. */ }
                    }
                }
            }
        }
    }

    private suspend fun removeProfileData(id: String, replacement: String) {
        // Switch first: no outgoing engine, OAuth poll or late catalog response may revive this user.
        if (playback.state.value.profileId == id) {
            navigation.edit().putString("route", "player").apply()
            playback.switchProfile(replacement)
            withTimeout(20_000) { playback.state.first { it.profileId == replacement } }
        }
        accounts.activate(playback.state.value.profileId)
        offlineStore.removeProfile(id, runCatching { accounts.savedAccount(id)?.id }.getOrNull())
        collections.removeProfile(id)
        history.clear(id)
        accounts.forgetProfile(id)
        playbackStart.remove(id)
        withContext(Dispatchers.IO) {
            listOf("playback", "local-list-queues", "local-list-progress", "launch-state", "radio", "radio-navigation", "clip-checkpoints",
                "wave-settings", "appearance", "app-language", "skins", "yandex-devices").forEach { name ->
                val prefs = getSharedPreferences(name, MODE_PRIVATE)
                val keys = prefs.all.keys.filter { key -> key == id || key.endsWith(":$id") ||
                    key.startsWith("$id:") || key.contains(":$id:") }
                if (keys.isNotEmpty()) check(prefs.edit().apply { keys.forEach(::remove) }.commit())
            }
        }
    }
}
