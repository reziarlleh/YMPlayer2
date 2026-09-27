package dev.petrov.ymplayer2.sidebar

import android.content.Context
import android.content.Intent
import android.os.Build

/** Commands observed in the K4811 firmware's own navigation bar. */
internal object K4811Controls {
    private const val KEY_ACTION = "com.nwd.action.ACTION_KEY_VALUE"
    private const val KEY_EXTRA = "extra_key_value"
    private const val MENU_ACTION = "com.nwd.action.suspension.DISPLAY_GRIDVIEW"

    fun isTargetDevice(): Boolean = Build.DISPLAY.startsWith("K4811", ignoreCase = true)

    fun volumeUp(context: Context): Boolean = sendKey(context, 14)
    fun volumeDown(context: Context): Boolean = sendKey(context, 15)
    fun mute(context: Context): Boolean = sendKey(context, 2)
    fun home(context: Context): Boolean = sendKey(context, 20)
    fun back(context: Context): Boolean = sendKey(context, 18)
    fun sleep(context: Context): Boolean = sendKey(context, 0)

    private fun sendKey(context: Context, value: Int): Boolean {
        if (!isTargetDevice()) return false
        context.sendBroadcast(Intent(KEY_ACTION).putExtra(KEY_EXTRA, value.toByte())
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        return true
    }

    /** Opens the stock K4811 launcher application grid, not a generic Android MENU key. */
    fun menu(context: Context): Boolean {
        if (!isTargetDevice()) return false
        context.sendBroadcast(Intent(MENU_ACTION).setPackage("com.android.launcher"))
        return true
    }
}
