package dev.petrov.ymplayer2

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.sidebar.SideBarService
import dev.petrov.ymplayer2.sidebar.SideBarSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SideBarOverlayTest {
    @Test fun permittedServiceAddsAndRemovesItsWindow() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SideBarSettings(context)
        assumeTrue("Grant the special overlay app-op on an emulator for this runtime check", settings.hasPermission())
        val previous = settings.read().enabled
        val signature = "package=${context.packageName} appop=SYSTEM_ALERT_WINDOW"
        try {
            settings.setEnabled(true)
            SideBarService.start(context, show = true)
            assertTrue("SideBar overlay window was not added", waitFor { signature in windows() })
        } finally {
            SideBarService.stop(context)
            settings.setEnabled(previous)
        }
        assertTrue("SideBar overlay window remained after stop", waitFor { signature !in windows() })
    }

    private fun windows(): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor = automation.executeShellCommand("dumpsys window windows")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(30) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return false
    }
}
