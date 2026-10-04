package dev.petrov.ymplayer2

/** Debug-only separate process: verifies a real fatal crash without killing the test runner. */
class DiagnosticsCrashProbeActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        (application as PlayerApplication).diagnostics.screen("search", true, 411, 700)
        android.os.Handler(mainLooper).post {
            throw IllegalStateException("diagnostics-secret-token-for-test", IllegalArgumentException("private-search-text"))
        }
    }
}
