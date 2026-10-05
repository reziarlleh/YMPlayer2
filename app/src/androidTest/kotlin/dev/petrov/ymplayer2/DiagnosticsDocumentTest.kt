package dev.petrov.ymplayer2

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.localization.AppLanguages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiagnosticsDocumentTest {
    @Test fun selectedDestinationContainsRedactedReportAndFailureDoesNotClaimSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppLanguages.initialize(context, "diagnostics-document-test-language", "ru")
        val log = File(context.cacheDir, "document-log-${System.nanoTime()}")
        val document = File(context.cacheDir, "document-export-${System.nanoTime()}.txt")
        try {
            val journal = DiagnosticsJournal(context, log)
            journal.record(DiagnosticEvent.PLAYBACK_STARTED)
            val result = journal.exportDocument(Uri.fromFile(document), document.name)
            assertTrue(result, result.startsWith("Сохранено:"))
            val body = document.readText()
            assertTrue(body.contains("Android API ${android.os.Build.VERSION.SDK_INT}"))
            assertTrue(body.contains("PLAYBACK_STARTED"))
            assertFalse(body.contains("access_token"))
            assertTrue(journal.snapshot().contains("JOURNAL_EXPORTED"))
            journal.clear()
            assertEquals("Не удалось экспортировать журнал.",
                journal.exportDocument(Uri.parse("content://invalid.destination/report"), "failure.txt"))
            assertFalse(journal.snapshot().contains("JOURNAL_EXPORTED"))
        } finally {
            log.delete(); document.delete(); AppLanguages.initialize(context)
        }
    }
}
