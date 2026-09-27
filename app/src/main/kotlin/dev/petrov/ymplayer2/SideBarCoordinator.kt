package dev.petrov.ymplayer2

import android.app.Activity
import android.content.ActivityNotFoundException
import dev.petrov.ymplayer2.shell.SideBarAccess
import dev.petrov.ymplayer2.shell.SideBarOption
import dev.petrov.ymplayer2.shell.SideBarUiState
import dev.petrov.ymplayer2.sidebar.SideBarButton
import dev.petrov.ymplayer2.sidebar.SideBarService
import dev.petrov.ymplayer2.sidebar.SideBarSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App bridge: settings and permission are device-wide, separate from every 1.x preference. */
internal class SideBarCoordinator(private val activity: Activity) : SideBarAccess {
    private val settings = SideBarSettings(activity)
    private val mutable = MutableStateFlow(SideBarUiState())
    override val state = mutable.asStateFlow()

    init { refresh() }

    fun refresh() {
        val config = settings.read()
        val permitted = settings.hasPermission()
        mutable.value = SideBarUiState(permitted, config.enabled && permitted, config.autoHide,
            SideBarButton.entries.map { SideBarOption(it.name, it.title, it in config.buttons) }, mutable.value.message)
        if (config.enabled && permitted) runCatching { SideBarService.start(activity) }
    }

    override fun requestPermission() {
        try { activity.startActivity(settings.permissionIntent()) }
        catch (_: ActivityNotFoundException) { updateMessage("Системный экран разрешения не найден.") }
        catch (_: SecurityException) { updateMessage("Система не разрешила открыть настройки наложения.") }
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled && settings.read().buttons.isEmpty()) {
            updateMessage("Выберите хотя бы одну кнопку. Пустая панель отключена.")
            return
        }
        if (enabled && !settings.hasPermission()) { updateMessage("Сначала разрешите показ поверх приложений."); return }
        settings.setEnabled(enabled)
        if (enabled) {
            try { SideBarService.start(activity); updateMessage("Панель включена.") }
            catch (_: RuntimeException) {
                settings.setEnabled(false)
                updateMessage("Не удалось запустить панель на этом устройстве.")
            }
        } else { SideBarService.stop(activity); updateMessage("Панель выключена.") }
        refresh()
    }

    override fun setAutoHide(enabled: Boolean) {
        settings.setAutoHide(enabled)
        if (settings.read().enabled && settings.hasPermission()) SideBarService.refresh(activity)
        refresh()
    }

    override fun setButton(id: String, enabled: Boolean) {
        val button = SideBarButton.entries.find { it.name == id } ?: return
        val selection = settings.read().buttons.toMutableSet()
        if (enabled) selection += button else selection -= button
        settings.setButtons(selection)
        if (selection.isEmpty()) {
            SideBarService.stop(activity)
            updateMessage("Все команды выключены — SideBar отключён.")
        } else if (settings.read().enabled && settings.hasPermission()) SideBarService.refresh(activity)
        refresh()
    }

    override fun toggle() {
        if (!settings.read().enabled || !settings.hasPermission()) return
        try { SideBarService.toggle(activity) }
        catch (_: RuntimeException) { updateMessage("Не удалось показать панель.") }
    }

    private fun updateMessage(message: String) { mutable.value = mutable.value.copy(message = message) }
}
