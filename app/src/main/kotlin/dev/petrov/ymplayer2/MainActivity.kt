package dev.petrov.ymplayer2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import dev.petrov.ymplayer2.core.DemoCatalog
import dev.petrov.ymplayer2.core.DemoPlaybackController
import dev.petrov.ymplayer2.shell.ShellApp
import dev.petrov.ymplayer2.shell.ShellModel
import dev.petrov.ymplayer2.shell.playbackCheckpoint

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer {
                    val catalog = DemoCatalog()
                    val savedState = createSavedStateHandle()
                    ShellModel(catalog, DemoPlaybackController(catalog, savedState.playbackCheckpoint()), savedState)
                }
            })
            ShellApp(model, BuildConfig.VERSION_NAME)
        }
    }
}
