package dev.petrov.ymplayer2

import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.view.WindowInsets
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.clips.ClipMediaPreloader
import dev.petrov.ymplayer2.clips.ClipControlsView
import dev.petrov.ymplayer2.clips.clipVideoView
import dev.petrov.ymplayer2.yandex.YandexClipApi
import dev.petrov.ymplayer2.designsystem.skin.clipPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first

/** Video owns its player. A durable checkpoint survives process death without storing stream URLs. */
class ClipActivity : ComponentActivity() {
    private var clips: ClipWaveController? = null
    private var controls: ClipControlsView? = null
    private var clipProfile: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        val graph = application as PlayerApplication
        graph.clipActivities++
        val profile = graph.playback.state.value.profileId
        clipProfile = profile
        graph.accounts.activate(profile)
        val checkpoint = graph.clipCheckpoints.read(profile)
        graph.radio.release()
        graph.playback.connect()
        graph.playback.pauseForClips()
        graph.launchState.write(profile, dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS, checkpoint?.playing ?: true)
        graph.navigation.edit().putString("route", "clips").apply()
        val preloader = ClipMediaPreloader(this)
        val player = preloader.player.apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
        }
        clips = ClipWaveController(YandexClipApi(graph.accounts), graph.playback.state.value.profileId,
            player, lifecycleScope, preloader) { saved ->
                graph.clipCheckpoints.write(profile, saved)
                if (saved.playing || graph.launchState.read(profile).output == dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS) {
                    graph.launchState.write(profile, dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS, saved.playing)
                }
            }
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                if (controls?.handleRemoteKey(event) == true) true else super.dispatchKeyEvent(event)
        }.apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true; isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(clipVideoView(this, player), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val controls = ClipControlsView(this, clips!!, graph.skins.state.value.active.clipPalette(), close = ::finish)
        this.controls = controls
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.getChildAt(0).setOnClickListener { controls.toggleVisibility() }
        setContentView(root)
        root.post { if (!root.isInTouchMode) controls.showControls() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            clips?.state?.collect(controls::render)
        } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.skins.state.collect { controls.updatePalette(it.active.clipPalette()) }
        } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            dev.petrov.ymplayer2.localization.AppLanguages.state.collect { controls.refreshLanguage() }
        } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                controls.updateProgress(player.currentPosition, player.duration)
                clips?.checkpoint()
                delay(250)
            }
        } }
        lifecycleScope.launch {
            graph.accounts.state.first { it.profileId == profile && it.phase != dev.petrov.ymplayer2.core.AuthPhase.LOADING }
            clips?.start(checkpoint)
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
    }
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    override fun onStart() {
        super.onStart()
        val graph = application as PlayerApplication
        val profile = clipProfile ?: return
        if (graph.playback.state.value.profileId != profile) { finish(); return }
        val intent = graph.clipCheckpoints.read(profile)?.playing ?: graph.launchState.read(profile).playing
        // Music/CWG may have acquired audio while video was in the background.
        graph.radio.release()
        graph.playback.pauseForClips()
        graph.launchState.write(profile, dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS, intent)
        clips?.returnFromBackground()
    }
    override fun finish() {
        // Explicit Back/Close is a pause; backgrounding must preserve the former play intent.
        clips?.pause()
        super.finish()
    }
    override fun onStop() {
        clips?.suspendForBackground()
        val graph = application as PlayerApplication
        graph.clipCheckpoints.flush(); graph.launchState.flush(); graph.navigation.edit().commit()
        super.onStop()
    }
    override fun onDestroy() {
        controls = null; clips?.close(); clips = null
        val graph = application as PlayerApplication
        graph.clipActivities = (graph.clipActivities - 1).coerceAtLeast(0)
        super.onDestroy()
    }
}
