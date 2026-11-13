package dev.petrov.ymplayer2.designsystem

import android.content.Context

/** A user's appearance survives Activity recreation; legacy device preferences are migration defaults. */
class ThemePreferences(context: Context, profileId: String? = null) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val profile = profileId ?: context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
        .getString("profile", "owner")!!
    fun read(): String = prefs.getString("mode:$profile", prefs.getString("mode", "dark")).takeIf { it in modes } ?: "dark"
    fun save(mode: String) {
        require(mode in modes)
        check(prefs.edit().putString("mode:$profile", mode).commit())
    }
    private companion object { val modes = setOf("dark", "light", "system") }
}
