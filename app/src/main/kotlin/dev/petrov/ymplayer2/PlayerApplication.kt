package dev.petrov.ymplayer2

import android.app.Application
import dev.petrov.ymplayer2.library.SafLibrary
import dev.petrov.ymplayer2.playback.AndroidPlayback
import dev.petrov.ymplayer2.playback.PlaybackHost
import kotlinx.coroutines.*

class PlayerApplication : Application(), PlaybackHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val library by lazy { SafLibrary(this, scope) }
    override val playback by lazy { AndroidPlayback(this, library, scope) }
}
