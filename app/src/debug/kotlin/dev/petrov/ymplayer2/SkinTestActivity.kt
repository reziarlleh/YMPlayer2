package dev.petrov.ymplayer2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.petrov.ymplayer2.designsystem.skin.PrismSkin
import dev.petrov.ymplayer2.shell.ShellApp
import dev.petrov.ymplayer2.shell.ShellModel

/** Instrumentation harness only; no skin editor or test activity in release APKs. */
class SkinTestActivity : ComponentActivity() {
    var skin by mutableStateOf(PrismSkin)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = application as PlayerApplication
        graph.playback.connect()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer { ShellModel(graph.library, graph.playback, createSavedStateHandle(), graph.collections) }
            })
            ShellApp(model, "Skin contract test", skin = skin)
        }
    }
}
