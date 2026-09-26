package dev.petrov.ymplayer2

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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
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
    override fun onStart() {
        super.onStart()
        (application as PlayerApplication).accounts.retryAccount()
        lifecycleScope.launch { (application as PlayerApplication).library.refresh() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    ShellModel(graph.library, graph.playback, createSavedStateHandle(), graph.collections, graph.accounts, graph.online, graph.taste, graph.offline, graph.audioQuality, graph.cloudPlaylists)
                }
            })
            var source by rememberSaveable { mutableStateOf(Source.LOCAL) }
            var pickerIssue by rememberSaveable { mutableStateOf<String?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) model.addFolder(uri.toString(), source)
            }
            ShellApp(model, BuildConfig.VERSION_NAME, diagnostics = journal, openClips = {
                startActivity(Intent(this, ClipActivity::class.java))
            }, addFolder = {
                source = it; pickerIssue = null
                try { picker.launch(null) }
                catch (_: ActivityNotFoundException) { pickerIssue = "На устройстве нет системного выбора папки. Нужен файловый менеджер с поддержкой Storage Access Framework." }
            }, folderIssue = pickerIssue, onExit = ::finish, equalizer = equalizer::open, syncOffline = {
                try { startForegroundService(android.content.Intent(this, OfflineSyncService::class.java)) }
                catch (_: Exception) { graph.offline.report("Не удалось запустить фоновую синхронизацию. Повторите из открытого приложения.") }
            })
        }
    }
}
