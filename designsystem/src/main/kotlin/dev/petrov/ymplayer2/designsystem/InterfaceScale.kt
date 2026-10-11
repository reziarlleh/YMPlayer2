package dev.petrov.ymplayer2.designsystem

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/** Application UI only: does not change Android display settings or media geometry. */
class InterfaceScalePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read(): Int = try { normalize(prefs.getInt(KEY, 100)) } catch (_: ClassCastException) { 100 }
    fun save(percent: Int, durable: Boolean = true) {
        val edit = prefs.edit().putInt(KEY, normalize(percent))
        if (durable) check(edit.commit()) else edit.apply()
    }
    fun observe(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) listener() }
        prefs.registerOnSharedPreferenceChangeListener(callback)
        return { prefs.unregisterOnSharedPreferenceChangeListener(callback) }
    }
    fun controlsContext(context: Context): Context = context.createConfigurationContext(Configuration().apply {
        densityDpi = (context.resources.configuration.densityDpi * read() / 100f).roundToInt()
    })
    companion object {
        const val MIN = 50
        const val MAX = 200
        const val STEP = 10
        private const val KEY = "interface_scale_percent"
        fun normalize(value: Int): Int = ((value.coerceIn(MIN, MAX) / STEP.toFloat()).roundToInt() * STEP).coerceIn(MIN, MAX)
    }
}

data class InterfaceScaleState(val percent: Int, val save: (Int, Boolean) -> Unit,
    val finish: () -> Unit, val adjust: (Int) -> Unit)
val LocalInterfaceScale = staticCompositionLocalOf<InterfaceScaleState?> { null }
val LocalUnscaledDensity = staticCompositionLocalOf<Density?> { null }
val LocalUnscaledConfiguration = staticCompositionLocalOf<Configuration?> { null }

@Composable fun InterfaceScale(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { InterfaceScalePreferences(context) }
    var percent by remember(preferences) { mutableIntStateOf(preferences.read()) }
    DisposableEffect(preferences) {
        val close = preferences.observe { percent = preferences.read() }
        onDispose(close)
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val factor = percent / 100f
    val scaledDensity = remember(density, percent) { Density(density.density * factor, density.fontScale) }
    val scaledConfiguration = remember(configuration, percent) { Configuration(configuration).apply {
        screenWidthDp = (screenWidthDp / factor).roundToInt()
        screenHeightDp = (screenHeightDp / factor).roundToInt()
        smallestScreenWidthDp = (smallestScreenWidthDp / factor).roundToInt()
        densityDpi = (densityDpi * factor).roundToInt()
    } }
    CompositionLocalProvider(LocalDensity provides scaledDensity, LocalConfiguration provides scaledConfiguration,
        LocalUnscaledDensity provides density, LocalUnscaledConfiguration provides configuration,
        LocalInterfaceScale provides InterfaceScaleState(percent, preferences::save,
            { preferences.save(preferences.read()) }, { delta -> preferences.save(preferences.read() + delta) }), content = content)
}
