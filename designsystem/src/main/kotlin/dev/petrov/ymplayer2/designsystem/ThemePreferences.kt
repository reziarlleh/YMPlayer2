package dev.petrov.ymplayer2.designsystem

import android.content.Context

/** Appearance choice belongs to the application, not an Activity's saved Bundle. */
class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read(): String = prefs.getString("mode", "dark").takeIf { it in modes } ?: "dark"
    fun save(mode: String) {
        require(mode in modes)
        check(prefs.edit().putString("mode", mode).commit())
    }
    private companion object { val modes = setOf("dark", "light", "system") }
}
