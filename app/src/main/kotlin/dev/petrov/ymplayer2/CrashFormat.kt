package dev.petrov.ymplayer2

import java.util.Collections
import java.util.IdentityHashMap

/** Structural exception data only. Messages, file names and thread names can contain user data. */
internal object CrashFormat {
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_.$<>-]{0,199}")
    fun identifier(value: String): String = value.takeIf { identifier.matches(it) || it == "<init>" || it == "<clinit>" } ?: "unknown"

    fun stack(error: Throwable): String = buildString {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var count = 0
        fun appendError(current: Throwable, relation: String, depth: Int) {
            if (depth >= 8 || count >= 8) { append("[exception limit]\n"); return }
            if (!visited.add(current)) { append("[exception cycle]\n"); return }
            count++
            append(relation).append(identifier(current.javaClass.name)).append('\n')
            current.stackTrace.take(32).forEach { frame ->
                append("    at ").append(identifier(frame.className)).append('.')
                    .append(identifier(frame.methodName)).append(if (frame.lineNumber >= 0) "(SourceFile" else "(Unknown Source")
                if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                append(")\n")
            }
            if (current.stackTrace.size > 32) append("    [frame limit]\n")
            current.suppressed.take(2).forEach { appendError(it, "Suppressed: ", depth + 1) }
            current.cause?.let { appendError(it, "Caused by: ", depth + 1) }
        }
        appendError(error, "", 0)
    }.take(32 * 1024)
}

/** Recording failure must still reach Android's original fatal handler. */
internal class PersistingExceptionHandler(
    private val record: (Throwable) -> Unit,
    private val downstream: Thread.UncaughtExceptionHandler?,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val recording = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (recording.compareAndSet(false, true)) record(error)
        } catch (_: Throwable) {
            // Disk full, OOM or a broken report must not suppress Android's crash handling.
        } finally {
            if (downstream != null) downstream.uncaughtException(thread, error) else terminate()
        }
    }
}
