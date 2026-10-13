package dev.petrov.ymplayer2.core

enum class DiagnosticOperation { HTTP, WAVE_START, WAVE_NEXT, WAVE_DRAIN, WAVE_RECOVER, WAVE_FEEDBACK,
    AUDIO_PREPARE, AUDIO_PLAYBACK, LIST_CONTINUATION, TASTE_READ, TASTE_WRITE, TASTE_DEFERRED,
    OFFLINE_DOWNLOAD, OFFLINE_DELETE, MP3_EXPORT, CLIP_STREAM, CLIP_PLAYBACK, RADIO_STREAM, RADIO_PLAYBACK, RADIO_ON_AIR }
enum class DiagnosticCategory { OK, NETWORK, SERVER, ACCESS, UNAVAILABLE, PROTOCOL, NO_PROGRESS, DECODER, STORAGE, APPLICATION }
/** Only typed codes and counts: never exception messages, response bodies, account IDs or signed URLs. */
data class OperationDiagnostic(val operation: DiagnosticOperation, val category: DiagnosticCategory = DiagnosticCategory.OK,
    val httpStatus: Int? = null, val network: NetworkIssue? = null, val returned: Int? = null,
    val accepted: Int? = null, val attempt: Int? = null, val mediaCode: Int? = null,
    val session: Boolean? = null, val cursorAdvanced: Boolean? = null) {
    fun line() = buildString {
        append("operation=${operation.name} category=${category.name}")
        httpStatus?.let { append(" http=$it") }; network?.let { append(" network=${it.name}") }
        returned?.let { append(" returned=${it.coerceIn(0, 1000000)}") }; accepted?.let { append(" accepted=${it.coerceIn(0, 1000000)}") }
        attempt?.let { append(" attempt=${it.coerceIn(0, 1000)}") }; mediaCode?.let { append(" mediaCode=$it") }
        session?.let { append(" session=$it") }; cursorAdvanced?.let { append(" cursorAdvanced=$it") }
    }
}
fun diagnosticFailure(operation: DiagnosticOperation, error: Throwable, mediaCode: Int? = null, httpStatus: Int? = null): OperationDiagnostic {
    val causes = generateSequence(error) { it.cause }.take(20).toList()
    val music = causes.filterIsInstance<MusicException>().firstOrNull()
    val radio = causes.filterIsInstance<RadioException>().firstOrNull()
    val status = httpStatus ?: music?.httpStatus ?: radio?.httpStatus
    val network = music?.networkIssue ?: radio?.networkIssue ?: when {
        causes.any { it is java.net.UnknownHostException } -> NetworkIssue.DNS
        causes.any { it is javax.net.ssl.SSLException } -> NetworkIssue.TLS
        causes.any { it is java.net.SocketTimeoutException } -> NetworkIssue.TIMEOUT
        causes.any { it is java.io.IOException } && operation in setOf(DiagnosticOperation.HTTP, DiagnosticOperation.WAVE_NEXT,
            DiagnosticOperation.WAVE_START, DiagnosticOperation.AUDIO_PREPARE, DiagnosticOperation.CLIP_STREAM) -> NetworkIssue.CONNECTION
        else -> null
    }
    val category = when {
        status == 429 || status in 500..599 -> DiagnosticCategory.SERVER
        status == 408 -> DiagnosticCategory.NETWORK
        status in setOf(401, 403) -> DiagnosticCategory.ACCESS
        status in setOf(404, 410) -> DiagnosticCategory.UNAVAILABLE
        status != null && status !in 200..299 -> DiagnosticCategory.PROTOCOL
        music?.failure == MusicFailure.NO_PROGRESS -> DiagnosticCategory.NO_PROGRESS
        music?.failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS) -> DiagnosticCategory.ACCESS
        radio?.issue == RadioIssue.ACCESS -> DiagnosticCategory.ACCESS
        music?.failure == MusicFailure.UNAVAILABLE -> DiagnosticCategory.UNAVAILABLE
        radio?.issue == RadioIssue.UNAVAILABLE -> DiagnosticCategory.UNAVAILABLE
        music?.failure == MusicFailure.RESPONSE -> DiagnosticCategory.PROTOCOL
        radio?.issue == RadioIssue.RESPONSE -> DiagnosticCategory.PROTOCOL
        network != null || music?.failure == MusicFailure.NETWORK || radio?.issue == RadioIssue.NETWORK -> DiagnosticCategory.NETWORK
        causes.any { it is java.io.IOException } && operation in setOf(DiagnosticOperation.OFFLINE_DELETE, DiagnosticOperation.OFFLINE_DOWNLOAD, DiagnosticOperation.MP3_EXPORT) -> DiagnosticCategory.STORAGE
        mediaCode in setOf(2001, 2002) -> DiagnosticCategory.NETWORK
        mediaCode in setOf(2005, 2006) -> DiagnosticCategory.STORAGE
        mediaCode in 3000..3999 -> DiagnosticCategory.PROTOCOL
        mediaCode in 4000..4999 -> DiagnosticCategory.DECODER
        else -> DiagnosticCategory.APPLICATION
    }
    return OperationDiagnostic(operation, category, status, network, mediaCode = mediaCode)
}
