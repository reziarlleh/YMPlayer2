package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CrashDiagnosticsTest {
    @Test fun persistedCrashReopensAndClearsWithoutExportingMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "crash-test-${System.nanoTime()}.txt")
        try {
            val capture = CrashDiagnostics(context, file)
            capture.refreshEnvironment()
            capture.screen("search", true, 411, 700)
            capture.record(IllegalStateException("access_token=private", IllegalArgumentException("search secret")))
            assertTrue(file.length() < 48 * 1024)
            val restored = CrashDiagnostics(context, file).snapshot()
            assertTrue(restored.contains("LAST_JAVA_CRASH"))
            assertTrue(restored.contains("Screen: search"))
            assertTrue(restored.contains("Caused by: java.lang.IllegalArgumentException"))
            assertFalse(restored.contains("access_token")); assertFalse(restored.contains("search secret"))
            capture.clear()
            assertFalse(capture.snapshot().contains("LAST_JAVA_CRASH"))
        } finally { android.util.AtomicFile(file).delete() }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun realFatalChildProcessIsRecordedAndIncludedInManualExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val journal = (context.applicationContext as PlayerApplication).diagnostics
        journal.clear()
        val beforePid = android.os.Process.myPid()
        context.startActivity(android.content.Intent(context, DiagnosticsCrashProbeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.currentTimeMillis() + 15000
        var report = ""
        while (System.currentTimeMillis() < deadline) {
            report = journal.snapshot()
            if (report.contains("LAST_JAVA_CRASH") && (android.os.Build.VERSION.SDK_INT < 30 || report.contains("reason=4"))) break
            kotlinx.coroutines.delay(100)
        }
        assertTrue(report, report.contains("LAST_JAVA_CRASH"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Screen: search"))
        assertTrue(report.contains("DiagnosticsCrashProbeActivity"))
        assertFalse(report.contains("diagnostics-secret-token-for-test"))
        assertFalse(report.contains("private-search-text"))
        assertEquals(beforePid, android.os.Process.myPid())
        val exported = journal.export()
        val name = exported.substringAfter(": ")
        val resolver = context.contentResolver
        resolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.MediaColumns._ID), "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name), null)!!.use { cursor ->
            assertTrue(exported, cursor.moveToFirst())
            val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            val body = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertTrue(body.contains("LAST_JAVA_CRASH")); assertTrue(body.contains("IME:"))
            assertFalse(body.contains("diagnostics-secret-token-for-test"))
            if (android.os.Build.VERSION.SDK_INT >= 30) assertTrue(body.contains("reason=4"))
            else assertTrue(body.contains("requires Android API30+"))
            resolver.delete(uri, null, null)
        }
        journal.clear()
        assertFalse(journal.snapshot().contains("LAST_JAVA_CRASH"))
    }
}
