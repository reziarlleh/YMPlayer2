package dev.petrov.ymplayer2.shell

import android.os.Bundle
import dev.petrov.ymplayer2.core.ArtistRef

internal enum class AccountActionKind { ACCOUNT, CLIPS, RADIO, MY_WAVE, OFFLINE_PLAY, OFFLINE_LIST, OFFLINE_SEARCH, YANDEX_LIBRARY, YANDEX_SEARCH, ARTIST }
internal data class AccountAction(val kind: AccountActionKind, val query: String = "", val artist: ArtistRef? = null)
internal data class AccountContinuation(val action: AccountAction, val returnRoute: String,
    val originProfile: String, val targetProfile: String? = null) {
    fun bundle() = Bundle().apply {
        putString("kind", action.kind.name); putString("query", action.query.take(200))
        putString("artistId", action.artist?.id); putString("artistName", action.artist?.name)
        putString("route", returnRoute); putString("origin", originProfile); putString("target", targetProfile)
    }
    companion object {
        fun read(bundle: Bundle?): AccountContinuation? = bundle?.let { data -> runCatching {
            AccountContinuation(AccountAction(AccountActionKind.valueOf(data.getString("kind")!!), data.getString("query").orEmpty(),
                data.getString("artistId")?.let { ArtistRef(it, data.getString("artistName").orEmpty()) }),
                data.getString("route") ?: "player", data.getString("origin")!!, data.getString("target"))
        }.getOrNull() }
    }
}
