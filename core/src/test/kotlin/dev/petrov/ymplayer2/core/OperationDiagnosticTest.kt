package dev.petrov.ymplayer2.core
import org.junit.Assert.*
import org.junit.Test
class OperationDiagnosticTest {
    @Test fun serverNetworkProtocolExhaustionAndDecoderAreDistinctWithoutLeakingMessages() {
        val cases = listOf(
            MusicException(MusicFailure.NETWORK, 503) to DiagnosticCategory.SERVER,
            MusicException(MusicFailure.NETWORK, networkIssue = NetworkIssue.DNS) to DiagnosticCategory.NETWORK,
            MusicException(MusicFailure.RESPONSE) to DiagnosticCategory.PROTOCOL,
            MusicException(MusicFailure.NO_PROGRESS) to DiagnosticCategory.NO_PROGRESS,
            IllegalStateException("secret-token-https://private") to DiagnosticCategory.APPLICATION)
        for ((error, expected) in cases) {
            val record = diagnosticFailure(DiagnosticOperation.WAVE_NEXT, error)
            assertEquals(expected, record.category); assertFalse(record.line().contains("secret")); assertFalse(record.line().contains("https"))
        }
        assertEquals(DiagnosticCategory.DECODER, diagnosticFailure(DiagnosticOperation.CLIP_PLAYBACK, Exception(), 4001).category)
        assertEquals(DiagnosticCategory.SERVER, diagnosticFailure(DiagnosticOperation.CLIP_PLAYBACK, Exception(), 2004, 503).category)
    }
    @Test fun waveFilterCountsAreBoundedAndContainOnlyCounts() {
        val record = OperationDiagnostic(DiagnosticOperation.WAVE_DRAIN, unavailable = -2, seen = Int.MAX_VALUE, blocked = 3)
        assertEquals("operation=WAVE_DRAIN category=OK unavailable=0 seen=1000000 blocked=3", record.line())
    }
    @Test fun localAudioStorageFailureIsNeverReportedAsNetwork() {
        val record = diagnosticFailure(DiagnosticOperation.AUDIO_PREPARE,
            RuntimeException("private-file-path", MusicException(MusicFailure.STORAGE)))
        assertEquals(DiagnosticCategory.STORAGE, record.category)
        assertNull(record.network)
        assertFalse(record.line().contains("private"))
    }
    @Test fun sessionStateUsesOnlyTypedIntentReasonAndBoundedPosition() {
        val record = OperationDiagnostic(DiagnosticOperation.SESSION_INTENT, intent = SessionIntent.PAUSE,
            reason = SessionStateReason.AUDIO_BECOMING_NOISY, positionSeconds = -1, engineState = Int.MAX_VALUE)
        assertEquals("operation=SESSION_INTENT category=OK intent=PAUSE reason=AUDIO_BECOMING_NOISY position=0 engineState=4", record.line())
    }
    @Test fun transportRequestContainsBoundedControllerIdentityAndOriginalIntent() {
        val record = OperationDiagnostic(DiagnosticOperation.SESSION_COMMAND, intent = SessionIntent.PLAY,
            transportCommand = SessionTransportCommand.PAUSE, controllerOrigin = SessionCommandOrigin.MEDIA_CONTROLLER,
            controllerPackage = "com.example.launcher", elapsedRealtimeMillis = -5)
        assertEquals("operation=SESSION_COMMAND category=OK intent=PLAY command=PAUSE controllerOrigin=MEDIA_CONTROLLER controllerPackage=com.example.launcher elapsedMs=0", record.line())
    }
    @Test fun arbitraryControllerDataCannotLeakIntoDiagnosticLines() {
        for (value in listOf("https://signed.example/track?token=secret", "com.widget\nintent=PLAY",
                "a".repeat(201), "com.widget secret", "", "com..widget")) {
            val line = OperationDiagnostic(DiagnosticOperation.SESSION_COMMAND, controllerPackage = value).line()
            assertEquals("operation=SESSION_COMMAND category=OK", line)
        }
    }
}
