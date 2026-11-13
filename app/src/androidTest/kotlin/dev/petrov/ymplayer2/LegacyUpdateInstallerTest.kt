package dev.petrov.ymplayer2

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.updater.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in on our rooted API23 AVD: real v1-signed same-key APK upgrade. */
class LegacyUpdateInstallerTest {
    @Test fun permissionSettingsAndSystemInstallerUpgradeTheActualProduct() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("installSignedFixture") == "true" && Build.VERSION.SDK_INT in 23..25 &&
            Build.HARDWARE in listOf("ranchu", "goldfish"))
        val build = requireNotNull(args.getString("signedBuild")?.toLongOrNull())
        val target = instrument.targetContext
        val fixture = File(target.getExternalFilesDir(null), "audit-update.apk")
        val apk = File(target.filesDir, "updates/legacy-installer-fixture.apk").apply { parentFile!!.mkdirs() }
        fixture.copyTo(apk, overwrite = true)
        val product = object : ContextWrapper(target) { override fun getPackageName() = "dev.petrov.ymplayer2" }
        val release = UpdateRelease(build, requireNotNull(args.getString("signedVersion")), "stable", 23,
            "fixture", "https://release.test/update.apk", "", apk.length(), requireNotNull(args.getString("signedSha256")),
            "https://release.test/stable.json")
        val installer = UpdateInstaller(UpdateClient(product, channel = "stable"))
        val old = product.packageManager.getPackageInfo(product.packageName, 0)
        val previousPermission = Settings.Secure.getInt(target.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0)
        val navigation = target.getSharedPreferences("navigation", 0)
        val oldRoute = navigation.getString("route", "player")
        navigation.edit().putString("route", "player").commit()
        fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
        fun await(label: String, ready: () -> Boolean) {
            val until = System.currentTimeMillis() + 45_000
            while (!ready() && System.currentTimeMillis() < until) Thread.sleep(100)
            assertTrue(label, ready())
        }
        try {
            shell("settings put secure install_non_market_apps 0")
            assertFalse(canInstallUpdates(target))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it }
                assertEquals(InstallStep.PERMISSION_REQUIRED, kotlinx.coroutines.runBlocking { installer.request(activity, apk, release) })
                await("Global Android 6 security settings") {
                    instrument.uiAutomation.rootInActiveWindow?.packageName == "com.android.settings"
                }
                shell("settings put secure install_non_market_apps 1")
                assertTrue(canInstallUpdates(target))
                instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                await("Return from security settings") {
                    instrument.uiAutomation.rootInActiveWindow?.packageName == target.packageName
                }
                assertEquals(InstallStep.LAUNCHED, kotlinx.coroutines.runBlocking { installer.request(activity, apk, release) })
                var clicked = false
                await("Actual system installation confirmation") {
                    if (!clicked) {
                        val root = instrument.uiAutomation.rootInActiveWindow
                        if (root?.packageName?.toString()?.contains("packageinstaller") == true) {
                            val buttons = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/ok_button").orEmpty() +
                                listOf("Install", "INSTALL", "Установить", "УСТАНОВИТЬ").flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty() }
                            clicked = buttons.any { it.isVisibleToUser && it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        }
                    }
                    clicked
                }
                await("Updated product Build") { installedVersionCode(product) == build }
                val updated = product.packageManager.getPackageInfo(product.packageName, 0)
                assertEquals("Installation identity retained", old.applicationInfo!!.uid, updated.applicationInfo!!.uid)
                assertEquals("App data installation retained", old.firstInstallTime, updated.firstInstallTime)
            }
        } finally {
            shell("settings put secure install_non_market_apps $previousPermission")
            navigation.edit().putString("route", oldRoute).commit()
            apk.delete()
        }
    }
}
