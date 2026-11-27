package dev.petrov.ymplayer2

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.core.AuthPhase
import dev.petrov.ymplayer2.playback.CarClipAudio
import dev.petrov.ymplayer2.playback.PlaybackOutput
import dev.petrov.ymplayer2.yandex.YandexClipApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Project only the soundtrack. The phone video and car audio share the clip checkpoint. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CarClipPlayback(private val app: PlayerApplication, private val profile: String,
    private val engine: ExoPlayer, private val commandsChanged: () -> Unit) : CarClipAudio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stream: ClipWaveController? = null
    private var monitor: Job? = null
    private val tracks = engine.trackSelectionParameters
    private val account = app.accounts.state.value.account?.id
    private fun authorized() = app.accounts.state.value.let {
        it.profileId == profile && it.account?.id == account && it.phase == AuthPhase.SIGNED_IN
    }
    init {
        engine.trackSelectionParameters = tracks.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        scope.launch { app.accounts.state.collect { if (!authorized() && stream != null) stop() } }
    }
    override val canPrevious get() = stream?.state?.value?.canGoBack == true
    override val canNext get() = stream?.state?.value?.let { !it.loading && it.clip != null } == true

    override fun play() {
        if (!authorized()) return
        val existing = stream
        if (existing != null && existing.state.value.issue == null) { existing.playOnInterfaceOpen(); return }
        if (existing != null) stop()
        val controller = ClipWaveController(YandexClipApi(app.accounts, diagnostic = app.diagnostics::record),
            profile, engine, scope, diagnostic = app.diagnostics::record) { checkpoint ->
            app.clipCheckpoints.write(profile, checkpoint)
            app.launchState.write(profile, PlaybackOutput.CLIPS, checkpoint.playing)
        }
        stream = controller
        app.launchState.write(profile, PlaybackOutput.CLIPS, true)
        controller.start(app.clipCheckpoints.read(profile), playing = true)
        monitor = scope.launch {
            launch { controller.state.map { it.canGoBack to (!it.loading && it.clip != null) }
                .distinctUntilChanged().collect { commandsChanged() } }
            while (isActive) { controller.checkpoint(); delay(1000) }
        }
    }
    override fun skip(direction: Int) { if (direction > 0) stream?.next() else stream?.previous() }
    override fun pause() {
        stream?.pause()
        app.launchState.write(profile, PlaybackOutput.CLIPS, false)
    }
    override fun stop() {
        pause()
        monitor?.cancel()
        stream?.close(releasePlayer = false)
        stream = null
        engine.stop()
        engine.clearMediaItems()
        commandsChanged()
    }
    override fun close(preserveIntent: Boolean) {
        if (preserveIntent) {
            stream?.checkpoint(force = true)
            monitor?.cancel()
            stream?.close(releasePlayer = false)
            stream = null
        } else stop()
        engine.trackSelectionParameters = tracks
        scope.cancel()
    }
}
