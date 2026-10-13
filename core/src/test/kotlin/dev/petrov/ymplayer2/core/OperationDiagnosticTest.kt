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
}
