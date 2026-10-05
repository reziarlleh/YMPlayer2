package dev.petrov.ymplayer2

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class DiagnosticsJournalTest {
    @Test fun journalIsBoundedAndExportContainsOnlyEventCodes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // No preceding UI test is required to choose Russian in this isolated storage test.
        dev.petrov.ymplayer2.localization.AppLanguages.initialize(context, "diagnostics-test-language", "ru")
        val file = File(context.cacheDir, "diagnostics-test-${System.nanoTime()}.log")
        try {
            val journal = DiagnosticsJournal(context, file)
            repeat(5500) { journal.record(DiagnosticEvent.PLAYBACK_STARTED) }
            val text = journal.snapshot()
            assertTrue(file.length() <= 96 * 1024)
            assertTrue(text.startsWith("20"))
            assertTrue(text.substringBefore("\n\nDIAGNOSTIC_ENVIRONMENT").endsWith("PLAYBACK_STARTED\n"))
            assertFalse(text.contains("access_token"))
            val result = journal.export()
            assertTrue(result, result.startsWith("Сохранено в Downloads:"))
            val name = result.substringAfter(": ")
            val resolver = context.contentResolver
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name), null)?.use { cursor ->
                assertTrue(cursor.moveToFirst())
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                val exported = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                assertTrue(exported.contains("PLAYBACK_STARTED"))
                assertFalse(exported.contains("access_token"))
                resolver.delete(uri, null, null)
            } ?: fail("Export missing from Downloads")
            assertEquals("Журнал очищен.", journal.clear())
            assertTrue(journal.snapshot().contains("JOURNAL_CLEARED"))
            assertFalse(journal.snapshot().contains("PLAYBACK_STARTED"))
        } finally {
            file.delete()
            dev.petrov.ymplayer2.localization.AppLanguages.initialize(context)
        }
    }
}
