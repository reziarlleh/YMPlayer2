package dev.petrov.ymplayer2.localization

import android.content.Context
import android.content.res.Resources
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppLanguage(val tag: String, val englishName: String, val nativeName: String) {
    val label get() = "$englishName ($nativeName)"
}
data class LanguageState(val selected: String = "system", val effective: String = "en")

/** Per-user UI preference. Does not recreate Activities or change playback intent. */
object AppLanguages {
    val available = supportedLanguages
    private var context: Context? = null
    private var preferenceName = "app-language"
    private var defaultSelection = "system"
    private var profiles: android.content.SharedPreferences? = null
    private val profileListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "profile") refresh()
    }
    private fun selectionKey() = "selected:${profiles?.getString("profile", "owner") ?: "owner"}"
    private val mutable = MutableStateFlow(LanguageState())
    val state = mutable.asStateFlow()

    fun initialize(context: Context, preferenceName: String = "app-language", defaultSelection: String = "system") {
        this.context = context.applicationContext
        this.preferenceName = preferenceName
        this.defaultSelection = defaultSelection
        profiles?.unregisterOnSharedPreferenceChangeListener(profileListener)
        profiles = this.context!!.getSharedPreferences("playback", Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(profileListener)
        }
        refresh()
    }

    @Suppress("DEPRECATION")
    fun locales(configuration: Configuration): List<Locale> = if (Build.VERSION.SDK_INT >= 24) {
        val locales = configuration.locales
        (0 until locales.size()).map { locales[it] }
    } else listOfNotNull(configuration.locale)

    fun resolve(selected: String, system: List<Locale>): String {
        if (selected != "system" && available.any { it.tag == selected }) return selected
        for (locale in system) {
            val tag = locale.language
            if (available.any { it.tag == tag }) return tag
        }
        return "en"
    }

    fun select(tag: String) {
        require(tag == "system" || available.any { it.tag == tag })
        val app = checkNotNull(context) { "Languages must be initialized by the application" }
        app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().putString(selectionKey(), tag).apply()
        refresh()
    }

    fun refresh() {
        val app = context ?: return
        val prefs = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val selected = prefs.getString(selectionKey(), prefs.getString("selected", defaultSelection))
            ?.takeIf { it == "system" || available.any { language -> language.tag == it } } ?: "system"
        val value = LanguageState(selected, resolve(selected, locales(Resources.getSystem().configuration)))
        UiStrings.configure(app, value.effective)
        mutable.value = value
    }
}
