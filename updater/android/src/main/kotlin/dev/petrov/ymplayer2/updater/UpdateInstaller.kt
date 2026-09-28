package dev.petrov.ymplayer2.updater

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

enum class InstallStep { LAUNCHED, PERMISSION_REQUIRED }

/** Android always owns final user approval of an APK installation. */
class UpdateInstaller(private val client: UpdateClient) {
    fun request(activity: Activity, file: File, release: UpdateRelease): InstallStep {
        client.verifyArchive(file, release)
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            try { activity.startActivity(intent) }
            catch (_: ActivityNotFoundException) { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            return InstallStep.PERMISSION_REQUIRED
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update_files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("YMPlayer 2 update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        return InstallStep.LAUNCHED
    }
}
