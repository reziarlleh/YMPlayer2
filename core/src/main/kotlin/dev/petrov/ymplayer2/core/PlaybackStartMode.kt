package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackStartMode { AUTO, OFF, ON;
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: AUTO
    }
}

/** Per-user policy; absent or unrecognised preferences preserve the existing Auto behaviour. */
class PlaybackStartPreferences(initial: Map<String, PlaybackStartMode> = emptyMap(),
    private val persist: (String, PlaybackStartMode?) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(initial.toMap())
    val state = mutable.asStateFlow()
    fun mode(profile: String) = state.value[profile] ?: PlaybackStartMode.AUTO
    fun set(profile: String, mode: PlaybackStartMode) {
        require(profile.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        persist(profile, mode)
        mutable.value = state.value + (profile to mode)
    }
    fun remove(profile: String) {
        persist(profile, null)
        mutable.value = state.value - profile
    }
}

/** Main/video Activity handoffs and configuration recreation keep one visible app session. */
class PlaybackUiEntries {
    private val visible = mutableSetOf<Any>()
    private var configurationHandoff = false
    val isVisible get() = visible.isNotEmpty()
    fun started(owner: Any): Boolean {
        if (owner in visible) return false
        val entry = visible.isEmpty() && !configurationHandoff
        visible += owner
        configurationHandoff = false
        return entry
    }
    /** Returns true only when the whole UI really leaves the foreground. */
    fun stopped(owner: Any, changingConfiguration: Boolean): Boolean {
        if (!visible.remove(owner) || visible.isNotEmpty()) return false
        configurationHandoff = changingConfiguration
        return !changingConfiguration
    }
}
