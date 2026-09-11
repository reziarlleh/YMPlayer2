package dev.petrov.ymplayer2

import android.os.Bundle
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
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.shell.ShellApp
import dev.petrov.ymplayer2.shell.ShellModel

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { (application as PlayerApplication).library.refresh() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = application as PlayerApplication
        graph.playback.connect()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer {
                    ShellModel(graph.library, graph.playback, createSavedStateHandle(), graph.collections, graph.accounts)
                }
            })
            var source by rememberSaveable { mutableStateOf(Source.LOCAL) }
            var pickerIssue by rememberSaveable { mutableStateOf<String?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) model.addFolder(uri.toString(), source)
            }
            ShellApp(model, BuildConfig.VERSION_NAME, addFolder = {
                source = it; pickerIssue = null
                try { picker.launch(null) }
                catch (_: ActivityNotFoundException) { pickerIssue = "На устройстве нет системного выбора папки. Нужен файловый менеджер с поддержкой Storage Access Framework." }
            }, folderIssue = pickerIssue, onExit = ::finish)
        }
    }
}
