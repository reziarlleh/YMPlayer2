package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.localization.*

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
        catch (_: ActivityNotFoundException) { updateMessage(tr(Msg.msg_6841e1070158)) }
        catch (_: SecurityException) { updateMessage(tr(Msg.msg_cfdce86cac0a)) }
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled && settings.read().buttons.isEmpty()) {
            updateMessage(tr(Msg.msg_23ae5f29feed))
            return
        }
        if (enabled && !settings.hasPermission()) { updateMessage(tr(Msg.msg_1f0f3db2bd8d)); return }
        settings.setEnabled(enabled)
        if (enabled) {
            try { SideBarService.start(activity); updateMessage(tr(Msg.msg_f77ad0247092)) }
            catch (_: RuntimeException) {
                settings.setEnabled(false)
                updateMessage(tr(Msg.msg_71c815fdaf60))
            }
        } else { SideBarService.stop(activity); updateMessage(tr(Msg.msg_d7b587c84b68)) }
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
            updateMessage(tr(Msg.msg_468ba659166c))
        } else if (settings.read().enabled && settings.hasPermission()) SideBarService.refresh(activity)
        refresh()
    }

    override fun toggle() {
        if (!settings.read().enabled || !settings.hasPermission()) return
        try { SideBarService.toggle(activity) }
        catch (_: RuntimeException) { updateMessage(tr(Msg.msg_59eedda3d148)) }
    }

    private fun updateMessage(message: String) { mutable.value = mutable.value.copy(message = message) }
}
