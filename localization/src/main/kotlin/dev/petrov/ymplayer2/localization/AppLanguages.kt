package dev.petrov.ymplayer2.localization

import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppLanguage(val tag: String, val englishName: String, val nativeName: String) {
    val label get() = "$englishName ($nativeName)"
}
data class LanguageState(val selected: String = "system", val effective: String = "en")

/** Device-wide UI preference. Does not recreate Activities or touch player/profile data. */
object AppLanguages {
    val available = supportedLanguages
    private var context: Context? = null
    private var preferenceName = "app-language"
    private var defaultSelection = "system"
    private val mutable = MutableStateFlow(LanguageState())
    val state = mutable.asStateFlow()

    fun initialize(context: Context, preferenceName: String = "app-language", defaultSelection: String = "system") {
        this.context = context.applicationContext
        this.preferenceName = preferenceName
        this.defaultSelection = defaultSelection
        refresh()
    }

    fun resolve(selected: String, system: LocaleList): String {
        if (selected != "system" && available.any { it.tag == selected }) return selected
        for (index in 0 until system.size()) {
            val tag = system[index].language
            if (available.any { it.tag == tag }) return tag
        }
        return "en"
    }

    fun select(tag: String) {
        require(tag == "system" || available.any { it.tag == tag })
        val app = checkNotNull(context) { "Languages must be initialized by the application" }
        app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().putString("selected", tag).apply()
        refresh()
    }

    fun refresh() {
        val app = context ?: return
        val selected = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).getString("selected", defaultSelection)
            ?.takeIf { it == "system" || available.any { language -> language.tag == it } } ?: "system"
        val value = LanguageState(selected, resolve(selected, Resources.getSystem().configuration.locales))
        UiStrings.configure(app, value.effective)
        mutable.value = value
    }
}
