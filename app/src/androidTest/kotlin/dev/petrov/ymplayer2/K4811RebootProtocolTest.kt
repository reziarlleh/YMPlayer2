package dev.petrov.ymplayer2

import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.RemoteException
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.sidebar.K4811RebootActivity
import dev.petrov.ymplayer2.sidebar.K4811RebootProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Local Binder only: these tests cannot reboot a device. */
@RunWith(AndroidJUnit4::class)
class K4811RebootProtocolTest {
    @Test fun confirmationStaysDisabledWithoutK4811Service() {
        assumeFalse(Build.DISPLAY.startsWith("K4811"))
        ActivityScenario.launch(K4811RebootActivity::class.java).use {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            var foundDisabledConfirmation = false
            for (attempt in 0 until 30) {
                val nodes = automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Перезагрузить").orEmpty()
                if (nodes.any { it.className?.toString()?.contains("Button") == true && !it.isEnabled }) {
                    foundDisabledConfirmation = true
                    break
                }
                Thread.sleep(100)
            }
            assertTrue("Confirmation became available without K4811 SettingService", foundDisabledConfirmation)
        }
    }

    @Test fun serviceIntentMatchesTheK4811Firmware() {
        val intent = K4811RebootProtocol.serviceIntent()
        assertEquals("com.nwd.setting.service.ACTION_SETTING_SERVICE", intent.action)
        assertEquals("com.nwd.setting.service/com.nwd.setting.service.SettingService",
            intent.component?.flattenToString())
    }

    @Test fun onlyRebootTypeTwoIsSentOnce() {
        val service = RecordingBinder()
        assertTrue(K4811RebootProtocol.reboot(service) { false })
        assertEquals(1, service.calls)
        assertEquals(0x1c, service.code)
        assertEquals(2, service.type)
        assertEquals(0, service.flags)
    }

    @Test fun cancellationAndUnknownInterfaceSendNothing() {
        val service = RecordingBinder()
        assertFalse(K4811RebootProtocol.reboot(service) { true })
        assertEquals(0, service.calls)
        service.attachInterface(null, "different.service")
        try {
            K4811RebootProtocol.reboot(service) { false }
            throw AssertionError("Unknown Binder interface accepted")
        } catch (_: RemoteException) {
            assertEquals(0, service.calls)
        }
    }

    @Test fun unsupportedTransactionIsNotSuccess() {
        val service = RecordingBinder().apply { supported = false }
        try {
            K4811RebootProtocol.reboot(service) { false }
            throw AssertionError("Unsupported transaction accepted")
        } catch (_: RemoteException) {
            assertEquals(1, service.calls)
        }
    }

    private class RecordingBinder : Binder() {
        var calls = 0
        var code = -1
        var type = -1
        var flags = -1
        var supported = true

        init { attachInterface(null, "com.nwd.setting.service.SettingFeature") }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            calls++
            this.code = code
            this.flags = flags
            data.enforceInterface("com.nwd.setting.service.SettingFeature")
            type = data.readByte().toInt()
            assertEquals(0, data.dataAvail())
            if (!supported) return false
            reply?.writeNoException()
            return true
        }
    }
}
