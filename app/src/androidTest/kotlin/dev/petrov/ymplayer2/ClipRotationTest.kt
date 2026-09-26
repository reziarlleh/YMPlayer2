package dev.petrov.ymplayer2

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipRotationTest {
    @Test fun turningTheScreenKeepsTheVideoSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ClipActivity>(intent).use { scenario ->
            lateinit var activity: ClipActivity
            lateinit var controller: Any
            val controllerField = ClipActivity::class.java.getDeclaredField("clips").apply { isAccessible = true }
            scenario.onActivity {
                activity = it
                controller = controllerField.get(it)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitFor { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            scenario.onActivity {
                assertSame("Rotation must keep the Activity and its playback position", activity, it)
                assertSame("Rotation must keep the video player", controller, controllerField.get(it))
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitFor { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity {
                assertSame(activity, it)
                assertSame(controller, controllerField.get(it))
            }
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("Screen rotation did not complete", predicate())
    }
}
