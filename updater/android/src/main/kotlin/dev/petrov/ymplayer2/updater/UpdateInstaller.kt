package dev.petrov.ymplayer2.updater

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class InstallStep { LAUNCHED, PERMISSION_REQUIRED }

/** Android always owns final user approval of an APK installation. */
class UpdateInstaller(private val client: UpdateClient) {
    suspend fun request(activity: Activity, file: File, release: UpdateRelease): InstallStep {
        withContext(Dispatchers.IO) { client.verifyArchive(file, release) }
        if (!canInstallUpdates(activity)) {
            val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")) else Intent(Settings.ACTION_SECURITY_SETTINGS)
            withContext(Dispatchers.Main.immediate) {
                try { activity.startActivity(intent) }
                catch (_: ActivityNotFoundException) { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
            return InstallStep.PERMISSION_REQUIRED
        }
        if (Build.VERSION.SDK_INT < 24) {
            LegacyPackageInstaller.launch(activity.applicationContext, file, release)
            return InstallStep.LAUNCHED
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update_files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("YMPlayer 2 update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main.immediate) { activity.startActivity(intent) }
        return InstallStep.LAUNCHED
    }
}
