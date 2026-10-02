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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.designsystem.skin.AppSkin
import dev.petrov.ymplayer2.designsystem.skin.SkinRepository
import dev.petrov.ymplayer2.designsystem.skin.PrismSkin
import dev.petrov.ymplayer2.shell.ShellApp
import dev.petrov.ymplayer2.shell.ShellModel

/** Instrumentation harness; release uses the same skin screen through MainActivity. */
class SkinTestActivity : ComponentActivity() {
    var skin by mutableStateOf<AppSkin?>(null)
    val skins by lazy { SkinRepository(this, lifecycleScope, "skin-harness") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.petrov.ymplayer2.localization.AppLanguages.initialize(this, "fixture-language", "ru")
        enableEdgeToEdge()
        val graph = application as PlayerApplication
        graph.playback.connect()
        setContent {
            val model: ShellModel = viewModel(factory = viewModelFactory {
                initializer { ShellModel(graph.library, graph.playback, createSavedStateHandle(), graph.collections) }
            })
            val state by skins.state.collectAsStateWithLifecycle()
            ShellApp(model, "Skin contract test", skin = skin ?: state.active, skins = skins, onExit = ::finish)
        }
    }
}
