package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.localization.*

import android.os.Bundle
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import dev.petrov.ymplayer2.core.AuthPhase
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.shell.ShellApp
import dev.petrov.ymplayer2.shell.ShellModel

class MainActivity : ComponentActivity() {
    private lateinit var sideBar: SideBarCoordinator
    private lateinit var updates: UpdateCoordinator
    override fun onResume() {
        super.onResume()
        (application as PlayerApplication).diagnostics.refreshEnvironment()
        if (::updates.isInitialized) updates.resume(this)
    }
    override fun onPause() {
        if (::updates.isInitialized) updates.detach(this)
        super.onPause()
    }
    override fun onStart() {
        super.onStart()
        if (::sideBar.isInitialized) sideBar.refresh()
        (application as PlayerApplication).accounts.retryAccount()
        lifecycleScope.launch { (application as PlayerApplication).library.refresh() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sideBar = SideBarCoordinator(this)
        updates = ViewModelProvider(this, viewModelFactory {
            initializer { UpdateCoordinator(applicationContext) }
        })[UpdateCoordinator::class.java]
        updates.checkOnLaunch()
        val graph = application as PlayerApplication
        graph.playback.connect()
        val journal = graph.diagnostics
        lifecycleScope.launch(Dispatchers.IO) { journal.record(DiagnosticEvent.APP_OPEN) }
        fun observe(events: kotlinx.coroutines.flow.Flow<DiagnosticEvent?>) {
            lifecycleScope.launch {
                events.distinctUntilChanged().filterNotNull().collect { event ->
                    withContext(Dispatchers.IO) { journal.record(event) }
                }
            }
        }
        observe(graph.library.state.map { when {
            it.scanning -> DiagnosticEvent.LIBRARY_SCAN_STARTED
            it.issue != null -> DiagnosticEvent.LIBRARY_ERROR
            it.ready -> DiagnosticEvent.LIBRARY_SCAN_FINISHED
            else -> null
        } })
        observe(graph.accounts.state.map { when (it.phase) {
            AuthPhase.REQUESTING -> DiagnosticEvent.AUTH_CODE_REQUESTED
            AuthPhase.WAITING, AuthPhase.VERIFYING -> DiagnosticEvent.AUTH_WAITING
            AuthPhase.SIGNED_IN -> DiagnosticEvent.AUTH_SIGNED_IN
            AuthPhase.ERROR -> DiagnosticEvent.AUTH_ERROR
            AuthPhase.SIGNED_OUT -> DiagnosticEvent.AUTH_SIGNED_OUT
            else -> null
        } })
        observe(graph.playback.state.map { when {
            it.error != null -> DiagnosticEvent.PLAYBACK_ERROR
            it.waveIssue != null -> DiagnosticEvent.WAVE_ERROR
            it.playing -> DiagnosticEvent.PLAYBACK_STARTED
            it.current != null -> DiagnosticEvent.PLAYBACK_PAUSED
            else -> null
        } })
        observe(graph.radio.state.map { when {
            it.reconnecting -> DiagnosticEvent.RADIO_RECONNECTING
            it.issue != null -> DiagnosticEvent.RADIO_ERROR
            it.buffering -> DiagnosticEvent.RADIO_CONNECTING
            it.playing -> DiagnosticEvent.RADIO_STARTED
            it.ownsOutput -> DiagnosticEvent.RADIO_STOPPED
            else -> null
        } })
        observe(graph.offline.state.map { when {
            it.running -> DiagnosticEvent.OFFLINE_SYNC_STARTED
            it.audioFailures > 0 || it.coverFailures > 0 -> DiagnosticEvent.OFFLINE_SYNC_ERROR
            it.ready && it.total > 0 -> DiagnosticEvent.OFFLINE_SYNC_FINISHED
            else -> null
        } })
        val equalizer = EqualizerLauncher(this, graph.playback::audioSessionId)
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer {
                    ShellModel(graph.library, graph.playback, createSavedStateHandle(), graph.collections, graph.accounts, graph.online, graph.taste, graph.offline, graph.audioQuality, graph.cloudPlaylists, graph.history, graph.radioCatalog)
                }
            })
            var source by rememberSaveable { mutableStateOf(Source.LOCAL) }
            var pickerIssue by rememberSaveable { mutableStateOf<String?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) model.addFolder(uri.toString(), source)
            }
            val skinState by graph.skins.state.collectAsStateWithLifecycle()
            val skinPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) graph.skins.inspect(uri)
            }
            ShellApp(model, BuildConfig.VERSION_NAME, skin = skinState.active, skins = graph.skins, importSkin = {
                try { skinPicker.launch(arrayOf("*/*")) }
                catch (_: ActivityNotFoundException) { graph.skins.report(tr(Msg.msg_80cedf587a28)) }
            }, diagnostics = journal, diagnosticScreen = journal::screen, sideBar = sideBar, updates = updates, openClips = {
                startActivity(Intent(this, ClipActivity::class.java))
            }, addFolder = {
                source = it; pickerIssue = null
                try { picker.launch(null) }
                catch (_: ActivityNotFoundException) { pickerIssue = tr(Msg.msg_5e9967fcef8f) }
            }, folderIssue = pickerIssue, onExit = ::finish, equalizer = equalizer::open, syncOffline = {
                try { startForegroundService(android.content.Intent(this, OfflineSyncService::class.java)) }
                catch (_: Exception) { graph.offline.report(tr(Msg.msg_91cf8019c9e4)) }
            })
        }
    }
}
