package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.OfflineMusic

/** Only the debug manifest exposes this explicit fixture dependency; production uses its own graph. */
class OfflineSyncTestService : OfflineSyncService() {
    override val offline get() = requireNotNull(controller)
    override fun onCreate() { super.onCreate(); active = this }
    override fun onDestroy() { super.onDestroy(); active = null }
    companion object {
        var controller: OfflineMusic? = null
        var active: OfflineSyncTestService? = null
    }
}
