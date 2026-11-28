package dev.petrov.ymplayer2

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.localization.Msg
import dev.petrov.ymplayer2.localization.tr
import dev.petrov.ymplayer2.sidebar.SideBarService
import dev.petrov.ymplayer2.sidebar.SideBarSettings
import org.junit.Assert.assertEquals
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

    @Test fun removingTheLastButtonRemovesTheOverlay() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SideBarSettings(context)
        assumeTrue("Grant the special overlay app-op on an emulator for this runtime check", settings.hasPermission())
        val previous = settings.read()
        val signature = "package=${context.packageName} appop=SYSTEM_ALERT_WINDOW"
        try {
            settings.setButtons(setOf(dev.petrov.ymplayer2.sidebar.SideBarButton.HOME))
            settings.setEnabled(true)
            SideBarService.start(context)
            assertTrue("SideBar handle was not added", waitFor { signature in windows() })
            settings.setButtons(emptySet())
            SideBarService.refresh(context)
            assertTrue("Empty SideBar kept its window", waitFor { signature !in windows() })
        } finally {
            SideBarService.stop(context)
            settings.setButtons(previous.buttons)
            settings.setEnabled(previous.enabled)
        }
    }

    @Test fun collapsedHandlesExposeAnAccessibilityClickThatOpensThePanel() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SideBarSettings(context)
        assumeTrue("Grant the special overlay app-op on an emulator for this runtime check", settings.hasPermission())
        val previous = settings.read().enabled
        val automation = instrument.uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        val label = tr(Msg.msg_57816af2d9ca)
        fun handles() = automation.windows.mapNotNull { it.root }.filter {
            it.packageName == context.packageName && it.contentDescription?.toString() == label
        }
        try {
            info.flags = (previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) and
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
            automation.serviceInfo = info
            settings.setEnabled(true)
            SideBarService.start(context)
            assertTrue("Three labelled handles were not exposed", waitFor { handles().size == 3 })
            val available = handles()
            assertEquals(3, available.count { it.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) })
            assertTrue("Accessibility click was rejected", available.first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue("Accessibility click did not open the panel", waitFor {
                automation.windows.any { window -> window.root?.let { root ->
                    root.packageName == context.packageName &&
                        root.findAccessibilityNodeInfosByText(tr(Msg.msg_16e5a7c68a87)).isNotEmpty()
                } == true }
            })
        } finally {
            SideBarService.stop(context)
            settings.setEnabled(previous)
            info.flags = previousFlags
            automation.serviceInfo = info
        }
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
