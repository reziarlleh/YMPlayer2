package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.localization.*

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import java.util.Locale

/** 1.x openEqualizer/findEqualizerCandidates, isolated from the UI and player engine. */
internal class EqualizerLauncher(private val activity: Activity, private val audioSession: () -> Int) {
    private val prefs = activity.getSharedPreferences("equalizer", Activity.MODE_PRIVATE)
    fun open(choose: Boolean = false) {
        val manager = activity.packageManager
        val preferred = prefs.getString("package", null)
        if (!choose && preferred != null && launch(manager.getLaunchIntentForPackage(preferred) ?: manager.getLeanbackLaunchIntentForPackage(preferred))) return
        val apps = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).flatMap { category ->
            manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        }.distinctBy { it.activityInfo.packageName }.filter {
            val value = "${it.loadLabel(manager)} ${it.activityInfo.packageName}".lowercase(Locale.ROOT)
            it.activityInfo.packageName != activity.packageName && listOf("dsp", "equalizer", tr(Msg.msg_c61eb3c4706a), "eq", "audio", "sound", "fx").any(value::contains)
        }.sortedBy { it.loadLabel(manager).toString().lowercase(Locale.ROOT) }
        val choices = apps.map { it.loadLabel(manager).toString() to {
            val packageName = it.activityInfo.packageName
            if (launch(manager.getLaunchIntentForPackage(packageName) ?: manager.getLeanbackLaunchIntentForPackage(packageName))) prefs.edit().putString("package", packageName).apply()
            else missing()
        } }.toMutableList()
        val session = audioSession()
        val panel = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, activity.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        if (session > 0 && manager.resolveActivity(panel, 0) != null) choices += tr(Msg.msg_23d5ec1e3849) to { if (!launch(panel)) missing() }
        val dialog = AlertDialog.Builder(activity).setTitle(tr(Msg.msg_fdc54db3a2b0)).setNegativeButton(tr(Msg.msg_a7a4033657e8), null)
        if (choices.isEmpty()) dialog.setMessage(tr(Msg.msg_6276f8c77c1b))
        else dialog.setItems(choices.map { it.first }.toTypedArray()) { _, index -> choices[index].second() }
        dialog.show()
    }
    private fun launch(intent: Intent?): Boolean = try {
        if (intent == null) false else { activity.startActivity(intent); true }
    } catch (_: android.content.ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    private fun missing() { Toast.makeText(activity, tr(Msg.msg_0761f7d17c1f), Toast.LENGTH_LONG).show() }
}
