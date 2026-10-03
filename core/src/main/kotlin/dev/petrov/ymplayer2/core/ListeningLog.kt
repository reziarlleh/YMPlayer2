package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.StateFlow

data class ListeningEntry(val track: Track, val playedAtMillis: Long)
data class ListeningLogState(val revision: Long = 0, val failed: Boolean = false)

/** Profile-scoped metadata only: recording must never persist a playable URI. */
interface ListeningLog {
    val state: StateFlow<ListeningLogState>
    suspend fun page(profileId: String, offset: Int = 0, limit: Int = 80): CatalogPage<ListeningEntry>
    suspend fun clear(profileId: String)
}
