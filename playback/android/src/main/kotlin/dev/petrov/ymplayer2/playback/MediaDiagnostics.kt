package dev.petrov.ymplayer2.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import dev.petrov.ymplayer2.core.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun mediaFailureDiagnostic(operation: DiagnosticOperation, error: Throwable): OperationDiagnostic {
    val http = generateSequence<Throwable>(error) { it.cause }.take(20)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    return diagnosticFailure(operation, error, (error as? PlaybackException)?.errorCode, http)
}
