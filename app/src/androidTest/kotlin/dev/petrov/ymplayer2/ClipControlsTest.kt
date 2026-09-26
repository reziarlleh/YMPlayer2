package dev.petrov.ymplayer2

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
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
import java.io.File

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
                assertTrue(labels.contains("ДАЛЕЕ"))
                assertTrue(labels.contains("Next Title"))
                assertTrue(labels.contains("Next Artist"))
            } finally {
                controller.close()
                scope.cancel()
            }
        }
    }

    @Test fun diagonalInfoAndButtonsFitLandscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ClipActivity>(intent).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val end = System.currentTimeMillis() + 10_000
            var landscape = false
            while (!landscape && System.currentTimeMillis() < end) {
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                if (!landscape) Thread.sleep(50)
            }
            assertTrue("Landscape rotation did not complete", landscape)
            scenario.onActivity { activity ->
                val root = (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup)
                val controls = root.getChildAt(1) as ClipControlsView
                assertAndCapture(root, controls, context.getExternalFilesDir(null)!!,
                    "clip-info-diagonal.png")

                val scaled = activity.createConfigurationContext(
                    Configuration(activity.resources.configuration).apply { fontScale = 2f })
                val field = ClipActivity::class.java.getDeclaredField("clips").apply { isAccessible = true }
                val largeControls = ClipControlsView(scaled, field.get(activity) as ClipWaveController) { }
                root.removeView(controls)
                root.addView(largeControls, FrameLayout.LayoutParams(-1, -1))
                assertAndCapture(root, largeControls, context.getExternalFilesDir(null)!!,
                    "clip-info-diagonal-font200.png")
            }
        }
    }

    private fun assertAndCapture(root: ViewGroup, controls: ClipControlsView, directory: File, name: String) {
        controls.render(ClipWaveState(clip = clip("Титаник", "Nautilus Pompilius"),
            nextClip = clip("Группа крови", "Кино"), loading = false))
        controls.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        controls.layout(0, 0, root.width, root.height)
        val texts = textViews(controls)
        for (value in listOf("Титаник", "Nautilus Pompilius", "Группа крови", "Кино", "Предыдущий клип")) {
            val view = texts.firstOrNull { it.text.toString() == value || it.contentDescription == value }
            assertTrue("$value must be visible", view != null && view.getGlobalVisibleRect(Rect()))
        }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun clip(title: String, artist: String) = YandexClip(title, title, artist,
        "", null, null, 0, emptyList(), "batch")

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
