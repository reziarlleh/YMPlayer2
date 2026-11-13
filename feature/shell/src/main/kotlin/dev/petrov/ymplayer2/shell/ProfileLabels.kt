package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.localization.trMessage
import dev.petrov.ymplayer2.localization.tr
import dev.petrov.ymplayer2.localization.Msg

/** Built-in labels only; a renamed profile must retain the owner's text. */
internal fun profileName(profile: Profile): String {
    if (profile.userNamed) return profile.name
    profile.defaultNumber?.let { return tr(Msg.profile_default_name, it) }
    val builtIn = when (profile.id) { "owner" -> "Основной"; "road" -> "В дороге"; "guest" -> "Гость"; else -> null }
    return if (profile.name == builtIn) trMessage(profile.name) else profile.name
}
