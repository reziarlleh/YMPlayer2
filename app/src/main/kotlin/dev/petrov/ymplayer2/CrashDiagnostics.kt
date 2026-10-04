package dev.petrov.ymplayer2

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local last-crash report, read only through the existing manual diagnostics UI/export. */
internal class CrashDiagnostics(private val context: Context, file: File) {
    private val crashFile = AtomicFile(file)
    private val prefs = context.getSharedPreferences("diagnostics-${file.name}", Context.MODE_PRIVATE)
    @Volatile private var environment = ""
    @Volatile private var screen = "startup"
    @Volatile private var window = "unknown"
    private val screens = setOf("player", "library", "search", "artist", "profiles", "account", "settings", "quality",
        "diagnostics", "sidebar", "updates", "skins", "offline_settings", "language", "playlists", "favorites",
        "folders", "offline", "history", "recent", "queue", "clips", "startup")

    fun install() {
        refreshEnvironment()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(PersistingExceptionHandler(::record, original) {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        })
    }

    fun refreshEnvironment() {
        // Precompute outside the fatal path: it must not query another process while crashing.
        environment = try {
            val config = context.resources.configuration
            val method = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            val component = android.content.ComponentName.unflattenFromString(method)
            val ime = component?.let { CrashFormat.identifier(it.packageName) + "/" + CrashFormat.identifier(it.className) } ?: "unknown"
            "Version: ${BuildConfig.VERSION_NAME}\nAndroid API: ${Build.VERSION.SDK_INT}\n" +
                "Device: ${safeDevice(Build.MANUFACTURER)} / ${safeDevice(Build.MODEL)}\n" +
                "Android release: ${safeDevice(Build.VERSION.RELEASE)}\nBuild: ${safeDevice(Build.ID)}\n" +
                "Density DPI: ${context.resources.displayMetrics.densityDpi}\nFont scale: ${config.fontScale}\n" +
                "Orientation: ${config.orientation}\nSystem locales: ${safeDevice(config.locales.toLanguageTags())}\nIME: $ime\n"
        } catch (_: Exception) { "Version: ${BuildConfig.VERSION_NAME}\nAndroid API: ${Build.VERSION.SDK_INT}\nEnvironment: unavailable\n" }
    }

    fun screen(route: String, imeVisible: Boolean, widthDp: Int, heightDp: Int) {
        screen = route.takeIf { it in screens } ?: "unknown"
        window = "Window DP: ${widthDp.coerceIn(0, 20000)}x${heightDp.coerceIn(0, 20000)}; IME visible: $imeVisible"
    }

    fun record(error: Throwable) {
        val report = "LAST_JAVA_CRASH\nTime: ${stamp()}\n$environment" +
            "Screen: $screen\n$window\n${CrashFormat.stack(error)}"
        var output: java.io.FileOutputStream? = null
        try {
            output = crashFile.startWrite()
            output.write(report.toByteArray(Charsets.UTF_8))
            crashFile.finishWrite(output)
        } catch (failure: Throwable) {
            crashFile.failWrite(output)
            throw failure
        }
    }

    fun snapshot(): String = buildString {
        append("DIAGNOSTIC_ENVIRONMENT\n").append(environment)
        append("Screen: $screen\n$window\n\n")
        try {
            crashFile.openRead().use { input ->
                // Bound even a damaged/stale file before allocating. Normal records are smaller.
                append(input.readBytesBounded(48 * 1024).toString(Charsets.UTF_8)).append('\n')
            }
        } catch (_: java.io.FileNotFoundException) { append("Last Java crash: none recorded\n") }
        catch (_: Exception) { append("Last Java crash: unreadable\n") }
        if (Build.VERSION.SDK_INT >= 30) {
            append("\nSYSTEM_PROCESS_EXITS\n")
            try {
                val clearedAfter = prefs.getLong("clearedAfter", 0)
                val exits = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 10)
                    .filter { it.timestamp > clearedAfter }.take(5)
                if (exits.isEmpty()) append("none\n")
                exits.forEach { exit ->
                    // Never include description, process-state summary or raw native/ANR traces.
                    append("time=").append(exit.timestamp).append(" reason=").append(exit.reason)
                        .append(" status=").append(exit.status).append(" importance=").append(exit.importance)
                        .append(" pssKB=").append(exit.pss).append(" rssKB=").append(exit.rss).append('\n')
                }
            } catch (_: Exception) { append("unavailable\n") }
        } else append("\nSystem process exits: requires Android API30+\n")
    }

    fun clear() {
        crashFile.delete()
        check(!crashFile.baseFile.exists())
        check(prefs.edit().putLong("clearedAfter", System.currentTimeMillis()).commit())
    }

    private fun safeDevice(value: String) = value.take(120).replace(Regex("[^A-Za-z0-9 .,_+-]"), "?")
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) { val count = read(buffer, total, limit - total); if (count < 0) break; total += count }
        return buffer.copyOf(total)
    }
}
