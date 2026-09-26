package dev.petrov.ymplayer2

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipControlsView
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.clips.ClipWaveState
import dev.petrov.ymplayer2.yandex.YandexClip
import dev.petrov.ymplayer2.yandex.YandexClipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipControlsTest {
    @Test fun infoContainsNextTitleAndArtist() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val app = context.applicationContext as PlayerApplication
        instrument.runOnMainSync {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val player = ExoPlayer.Builder(context).build()
            val controller = ClipWaveController(YandexClipApi(app.accounts), "owner", player, scope)
            try {
                val controls = ClipControlsView(context, controller) { }
                controls.render(ClipWaveState(clip = clip("Now", "Current Artist"),
                    nextClip = clip("Next Title", "Next Artist"), loading = false))
                val labels = labels(controls)
                assertTrue(labels.any { "Далее: Next Title" in it && "Next Artist" in it })
            } finally {
                controller.close()
                scope.cancel()
            }
        }
    }

    private fun clip(title: String, artist: String) = YandexClip(title, title, artist,
        "", null, null, 0, emptyList(), "batch")

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
