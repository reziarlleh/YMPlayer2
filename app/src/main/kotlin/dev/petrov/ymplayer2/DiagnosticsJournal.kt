package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.localization.*

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.petrov.ymplayer2.shell.DiagnosticsAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Event codes only; CrashDiagnostics separately stores message-free structural crash data. */
internal enum class DiagnosticEvent {
    APP_OPEN, LIBRARY_SCAN_STARTED, LIBRARY_SCAN_FINISHED, LIBRARY_ERROR,
    AUTH_CODE_REQUESTED, AUTH_WAITING, AUTH_SIGNED_IN, AUTH_ERROR, AUTH_SIGNED_OUT,
    PLAYBACK_STARTED, PLAYBACK_PAUSED, PLAYBACK_ERROR, WAVE_ERROR,
    AUDIO_SERVICE_CREATED, AUDIO_SERVICE_DESTROYED, RESUMPTION_REQUESTED, RESUMPTION_AVAILABLE,
    OFFLINE_SYNC_STARTED, OFFLINE_SYNC_FINISHED, OFFLINE_SYNC_ERROR,
    JOURNAL_CLEARED, JOURNAL_EXPORTED,
}

internal class DiagnosticsJournal(private val context: Context,
    private val file: File = File(context.filesDir, "diagnostics-2.log")) : DiagnosticsAccess {
    private val crashes = CrashDiagnostics(context, File(file.parentFile, "${file.name}.crash"))
    fun installCrashCapture() = crashes.install()
    fun refreshEnvironment() = crashes.refreshEnvironment()
    fun screen(route: String, imeVisible: Boolean, widthDp: Int, heightDp: Int) = crashes.screen(route, imeVisible, widthDp, heightDp)
    private val lock = Any()
    private val maxBytes = 96 * 1024
    private val keepBytes = 64 * 1024

    fun record(event: DiagnosticEvent) = synchronized(lock) {
        try {
            file.appendText("${stamp()} ${event.name}\n", StandardCharsets.UTF_8)
            if (file.length() > maxBytes) {
                val bytes = file.readBytes()
                val cut = (bytes.size - keepBytes).coerceAtLeast(0)
                var nextLine = cut
                while (nextLine < bytes.size && bytes[nextLine] != '\n'.code.toByte()) nextLine++
                if (nextLine < bytes.size) nextLine++
                file.writeBytes(bytes.copyOfRange(nextLine, bytes.size))
            }
        } catch (_: IOException) {
            // Diagnostics must never interrupt playback, auth or library work.
        }
    }

    override suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        synchronized(lock) { readSnapshot() } + "\n\n" + crashes.snapshot()
    }

    override suspend fun clear(): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                crashes.clear()
                if (file.exists() && !file.delete()) return@synchronized tr(Msg.msg_203dc0ea9f7d)
                record(DiagnosticEvent.JOURNAL_CLEARED)
                tr(Msg.msg_4a1236b53aa7)
            } catch (_: Exception) { tr(Msg.msg_203dc0ea9f7d) }
        }
    }

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val name = "YMPlayer2-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        if (Build.VERSION.SDK_INT < 29) {
            return@withContext withContext(Dispatchers.Main) {
                try {
                    context.startActivity(Intent(context, DiagnosticsExportActivity::class.java)
                        .putExtra(DiagnosticsExportActivity.FILE_NAME, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    tr(Msg.diagnostics_choose_destination)
                } catch (_: android.content.ActivityNotFoundException) { tr(Msg.msg_920c04770d7a) }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = try { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
        catch (_: Exception) { null } ?: return@withContext tr(Msg.msg_21c6b248c7e7)
        try {
            writeDocument(uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            record(DiagnosticEvent.JOURNAL_EXPORTED)
            tr(Msg.msg_be65ad9b7c99, name)
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            tr(Msg.msg_920c04770d7a)
        }
    }

    internal suspend fun exportDocument(uri: Uri, name: String): String = withContext(Dispatchers.IO) {
        try {
            writeDocument(uri)
            record(DiagnosticEvent.JOURNAL_EXPORTED)
            tr(Msg.diagnostics_saved_document, name)
        } catch (_: Exception) { tr(Msg.msg_920c04770d7a) }
    }

    private fun writeDocument(uri: Uri) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        val header = "YMPlayer 2 $version\nExported: ${stamp()}\nAndroid API ${Build.VERSION.SDK_INT}\n\n"
        val body = synchronized(lock) { readSnapshot() } + "\n\n" + crashes.snapshot()
        val output = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("No output")
        output.use { it.write((header + body).toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun readSnapshot(): String = try {
        if (!file.exists() || file.length() == 0L) tr(Msg.msg_5e1e0ac2d35b) else file.readText(StandardCharsets.UTF_8)
    } catch (_: IOException) { tr(Msg.msg_2dc14ee8f6a2) }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
