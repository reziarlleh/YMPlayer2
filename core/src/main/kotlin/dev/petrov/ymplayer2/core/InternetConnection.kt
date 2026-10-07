package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Android supplies validated default-network availability; local playback never depends on it. */
interface InternetConnection {
    val available: StateFlow<Boolean>
    fun refresh()
}
enum class InternetStatus { WAITING, CONNECTED, OFFLINE }

/** One check per visible online surface; grace starts when that surface opens or Retry is pressed. */
class InternetCheck(private val connection: InternetConnection, private val scope: CoroutineScope,
    private val onReconnect: (manual: Boolean) -> Unit = {}, private val graceMillis: Long = 5000) {
    private val mutable = MutableStateFlow(if (connection.available.value) InternetStatus.CONNECTED else InternetStatus.WAITING)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var disconnected = false
    private var manual = false
    init { connection.refresh(); watch() }
    fun retry() {
        manual = true
        disconnected = true
        connection.refresh()
        watch()
    }
    private fun watch() {
        job?.cancel()
        mutable.value = if (connection.available.value) InternetStatus.CONNECTED else InternetStatus.WAITING
        job = scope.launch {
            connection.available.collectLatest { connected ->
                if (connected) {
                    mutable.value = InternetStatus.CONNECTED
                    if (disconnected) { val requested = manual; disconnected = false; manual = false; onReconnect(requested) }
                } else {
                    disconnected = true
                    mutable.value = InternetStatus.WAITING
                    delay(graceMillis)
                    mutable.value = InternetStatus.OFFLINE
                }
            }
        }
    }
    fun close() { job?.cancel() }
}
