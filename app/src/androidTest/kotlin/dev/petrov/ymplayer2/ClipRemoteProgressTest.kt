package dev.petrov.ymplayer2

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipControlsView
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.clips.ClipWaveState
import dev.petrov.ymplayer2.yandex.YandexClip
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

/** Production Activity/remote dispatch and real MP4; account/wave metadata is an explicit local fixture. */
@RunWith(AndroidJUnit4::class)
class ClipRemoteProgressTest {
    @Before fun fixtureLanguage() {
        instrument.runOnMainSync {
            dev.petrov.ymplayer2.localization.AppLanguages.initialize(instrument.targetContext, "fixture-language", "ru")
        }
    }
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun controller(activity: ClipActivity) = ClipActivity::class.java.getDeclaredField("clips")
        .apply { isAccessible = true }.get(activity) as ClipWaveController
    private fun controls(activity: ClipActivity) = (activity.findViewById<ViewGroup>(android.R.id.content)
        .getChildAt(0) as ViewGroup).getChildAt(1) as ClipControlsView
    private fun clip(id: String) = YandexClip(id, if (id == "next") "Следующий клип" else "Текущий клип", "Исполнитель", "", null, null, 0, emptyList(), "fixture")
    private fun waitFor(label: String, block: () -> Boolean) {
        val until = System.currentTimeMillis() + 12_000
        while (!block() && System.currentTimeMillis() < until) Thread.sleep(40)
        assertTrue(label, block())
    }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); instrument.waitForIdleSync() }
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun remoteRevealsHiddenControlsThenSelectsPausesResumesAndNavigates() {
        val context = instrument.targetContext
        val media = File(context.cacheDir, "remote-progress.mp4")
        instrument.context.assets.open("clip-transport/fixture.mp4").use { input -> media.outputStream().use(input::copyTo) }
        ActivityScenario.launch<ClipActivity>(Intent(context, ClipActivity::class.java)).use { scenario ->
            var settled = false
            waitFor("Initial account request must settle") {
                scenario.onActivity { settled = !controller(it).state.value.loading }
                settled
            }
            scenario.onActivity { activity ->
                val wave = controller(activity)
                @Suppress("UNCHECKED_CAST")
                val state = ClipWaveController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                    .get(wave) as MutableStateFlow<ClipWaveState>
                state.value = ClipWaveState(clip = clip("current"), nextClip = clip("next"), loading = false)
                wave.player.apply {
                    volume = 0f
                    playbackParameters = PlaybackParameters(.25f)
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(media)))
                    prepare(); play()
                }
            }
            var playing = false
            waitFor("MP4 must play") { scenario.onActivity { playing = controller(it).player.isPlaying }; playing }
            var focused = false
            waitFor("Video window must own input before real remote events are injected") {
                scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            // Move out of touch mode, establish focus, then let the real auto-hide run.
            key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT)
            var hidden = false
            waitFor("Controls must still auto-hide while progress is polled") {
                scenario.onActivity { hidden = texts(controls(it)).first { view -> view.contentDescription == "Пауза" }.visibility == View.VISIBLE &&
                    !texts(controls(it)).first { view -> view.contentDescription == "Пауза" }.isShown }
                hidden
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity {
                val pause = texts(controls(it)).first { view -> view.contentDescription == "Пауза" }
                assertTrue("Revealed pause must be visible", pause.isShown)
                assertTrue("Revealed pause must have remote focus", pause.hasFocus())
                assertTrue("The reveal press must not pause", controller(it).player.playWhenReady)
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Second center must pause") { scenario.onActivity { playing = controller(it).player.playWhenReady }; !playing }
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            waitFor("Dedicated media play must resume") { scenario.onActivity { playing = controller(it).player.isPlaying }; playing }
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            waitFor("Dedicated media pause must stop progression") { scenario.onActivity { playing = controller(it).player.playWhenReady }; !playing }
            // Check the Activity's real position polling, not only the drawing method in isolation.
            fun panelPixel(x: Float): Int {
                var color = 0
                scenario.onActivity {
                    val band = controls(it).findViewWithTag<View>("clip_info_band")
                    val bitmap = Bitmap.createBitmap(band.width, band.height, Bitmap.Config.ARGB_8888)
                    band.draw(Canvas(bitmap)); color = bitmap.getPixel((band.width * x).toInt(), band.height - 2); bitmap.recycle()
                }
                return color
            }
            val before = panelPixel(.32f)
            val nextBackground = panelPixel(.85f)
            scenario.onActivity { controller(it).player.let { player -> player.seekTo(player.duration * 3 / 4) } }
            waitFor("Real media position must update the current background") { panelPixel(.32f) != before }
            assertEquals("Next background must stay untouched", nextBackground, panelPixel(.85f))
            instrument.uiAutomation.takeScreenshot()?.let { snapshot ->
                File(context.getExternalFilesDir(null), "clip-remote-progress.png").outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                snapshot.recycle()
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { assertEquals("Следующий клип", it.currentFocus?.contentDescription) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            scenario.onActivity { assertEquals("← Назад", it.currentFocus?.contentDescription) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Back button must finish the clip screen") { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }
    @Test fun progressShadeClipsAtDiagonalAndDoesNotChangeNextPanelOrText() {
        val context = instrument.targetContext
        ActivityScenario.launch<ClipActivity>(Intent(context, ClipActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val ui = controls(activity)
                ui.render(ClipWaveState(clip = clip("one"), nextClip = clip("two"), loading = false))
                val band = ui.findViewWithTag<View>("clip_info_band")
                band.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
                band.layout(0, 0, 1000, 160)
                fun draw(position: Long, duration: Long): Bitmap {
                    ui.updateProgress(position, duration)
                    return Bitmap.createBitmap(1000, 160, Bitmap.Config.ARGB_8888).apply { band.draw(Canvas(this)) }
                }
                val empty = draw(0, 100)
                val half = draw(50, 100)
                val full = draw(100, 100)
                val unknown = draw(50, -1)
                val negative = draw(-10, 100)
                try {
                    val y = 158 // Below labels: compare only backgrounds.
                    assertNotEquals(empty.getPixel(150, y), half.getPixel(150, y))
                    assertEquals(empty.getPixel(400, y), half.getPixel(400, y))
                    assertNotEquals(empty.getPixel(570, y), full.getPixel(570, y))
                    for (x in listOf(590, 620, 800, 990)) assertEquals("Next panel x=$x", empty.getPixel(x, y), full.getPixel(x, y))
                    assertEquals(empty.getPixel(150, y), unknown.getPixel(150, y))
                    assertEquals(empty.getPixel(150, y), negative.getPixel(150, y))
                    val output = File(context.getExternalFilesDir(null), "clip-current-progress-half.png")
                    output.outputStream().use { half.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { listOf(empty, half, full, unknown, negative).forEach(Bitmap::recycle) }
            }
        }
    }
}
