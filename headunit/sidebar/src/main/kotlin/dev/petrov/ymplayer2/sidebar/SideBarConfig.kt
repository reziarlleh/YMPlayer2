package dev.petrov.ymplayer2.sidebar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

enum class SideBarButton(val title: String, val glyph: String) {
    VOLUME_UP("Громче", "+"),
    VOLUME_DOWN("Тише", "−"),
    MUTE("Без звука", "◖"),
    PLAY_PAUSE("Плей / пауза", "⏯"),
    HOME("Домой", "⌂"),
    MENU("Меню", "☰"),
    BACK("Назад", "↩"),
}

data class SideBarConfig(
    val enabled: Boolean = false,
    val autoHide: Boolean = true,
    val buttons: Set<SideBarButton> = SideBarButton.entries.toSet(),
)

class SideBarSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("ymplayer2_sidebar", Context.MODE_PRIVATE)
    fun read(): SideBarConfig {
        val saved = prefs.getString("buttons", null)
        val buttons = saved?.split(',')?.mapNotNull { id -> SideBarButton.entries.find { it.name == id } }?.toSet()
            ?: DEFAULT_BUTTONS
        return SideBarConfig(prefs.getBoolean("enabled", false) && buttons.isNotEmpty(), prefs.getBoolean("auto_hide", true), buttons)
    }
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value && read().buttons.isNotEmpty()).apply() }
    fun setAutoHide(value: Boolean) { prefs.edit().putBoolean("auto_hide", value).apply() }
    fun setButtons(buttons: Set<SideBarButton>) {
        prefs.edit()
            .putString("buttons", SideBarButton.entries.filter { it in buttons }.joinToString(",") { it.name })
            .apply { if (buttons.isEmpty()) putBoolean("enabled", false) }
            .apply()
    }
    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)
    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"))

    companion object {
        // Existing users keep their four original buttons when the three new controls arrive.
        private val DEFAULT_BUTTONS = setOf(SideBarButton.VOLUME_UP, SideBarButton.VOLUME_DOWN,
            SideBarButton.MUTE, SideBarButton.HOME)
    }
}
