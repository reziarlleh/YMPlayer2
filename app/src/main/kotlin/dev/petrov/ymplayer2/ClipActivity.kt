package dev.petrov.ymplayer2

import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.view.WindowInsets
import android.view.View
import android.view.ViewGroup
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

/** Video owns its player and stops audio on entry; closing never resumes audio automatically. */
class ClipActivity : ComponentActivity() {
    private var clips: ClipWaveController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        val graph = application as PlayerApplication
        graph.playback.connect()
        if (graph.playback.state.value.playing) graph.playback.toggle()
        val preloader = ClipMediaPreloader(this)
        val player = preloader.player.apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
        }
        clips = ClipWaveController(YandexClipApi(graph.accounts), graph.playback.state.value.profileId,
            player, lifecycleScope, preloader)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        root.addView(clipVideoView(this, player), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val controls = ClipControlsView(this, clips!!, graph.skins.state.value.active.clipPalette(), close = ::finish)
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.getChildAt(0).setOnClickListener { controls.toggleVisibility() }
        setContentView(root)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            clips?.state?.collect(controls::render)
        } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.skins.state.collect { controls.updatePalette(it.active.clipPalette()) }
        } }
        clips?.start()
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
    override fun onStop() { clips?.pause(); super.onStop() }
    override fun onDestroy() { clips?.close(); clips = null; super.onDestroy() }
}
