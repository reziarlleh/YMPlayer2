package dev.petrov.ymplayer2

import org.junit.Assert.*
import org.junit.Test

class CrashFormatTest {
    @Test fun reportPreservesStructureButNeverMessagesPathsOrThreadNames() {
        val error = IllegalStateException("access_token=secret /storage/private song name", IllegalArgumentException("account secret"))
        error.addSuppressed(RuntimeException("request query secret"))
        error.stackTrace = arrayOf(StackTraceElement("dev.petrov.Player", "openSearch", "/storage/private/token.txt", 42),
            StackTraceElement("invalid/path", "secret?query", "private.txt", -1))
        val report = CrashFormat.stack(error)
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(report.contains("Suppressed: java.lang.RuntimeException"))
        assertTrue(report.contains("dev.petrov.Player.openSearch(SourceFile:42)"))
        listOf("access_token", "secret", "/storage", "private.txt", "song name", "query").forEach { assertFalse(it, report.contains(it)) }
    }

    @Test fun cyclesAndHugeChainsAreBounded() {
        val first = Exception("private")
        val second = Exception("private", first)
        first.initCause(second)
        first.stackTrace = Array(10000) { StackTraceElement("valid.Class", "method", "private", it) }
        val report = CrashFormat.stack(first)
        assertTrue(report.contains("[exception cycle]"))
        assertTrue(report.contains("[frame limit]"))
        assertTrue(report.length < 32 * 1024)
    }

    @Test fun diskFailureStillDelegatesTheSameThrowableAndThread() {
        val error = Error("fatal")
        val thread = Thread.currentThread()
        var delegated = false
        val handler = PersistingExceptionHandler({ throw java.io.IOException("disk full") }, { t, e ->
            assertSame(thread, t); assertSame(error, e); delegated = true
        }) { fail("Unexpected fallback") }
        handler.uncaughtException(thread, error)
        assertTrue(delegated)
    }

    @Test fun repeatedFatalCallsDoNotRecordRecursivelyButAlwaysDelegate() {
        var writes = 0
        var delegated = 0
        val handler = PersistingExceptionHandler({ writes++ }, { _, _ -> delegated++ }) { fail() }
        repeat(2) { handler.uncaughtException(Thread.currentThread(), Exception()) }
        assertEquals(1, writes); assertEquals(2, delegated)
        var terminated = false
        PersistingExceptionHandler({}, null) { terminated = true }.uncaughtException(Thread.currentThread(), Error())
        assertTrue(terminated)
    }
}
