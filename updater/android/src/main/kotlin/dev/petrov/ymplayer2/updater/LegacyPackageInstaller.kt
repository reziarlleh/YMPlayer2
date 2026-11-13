package dev.petrov.ymplayer2.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Android 6's installer accepts file: only. A session keeps the APK private instead. */
internal object LegacyPackageInstaller {
    suspend fun launch(context: Context, file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(release.sizeBytes)
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                file.inputStream().use { input -> session.openWrite("base.apk", 0, release.sizeBytes).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer); if (read < 0) break
                        total += read
                        if (total > release.sizeBytes) throw IOException("Размер или SHA-256 APK не совпадает")
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    }
                    session.fsync(output)
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != release.sizeBytes || !hash.equals(release.sha256, true))
                    throw IOException("Размер или SHA-256 APK не совпадает")
                currentCoroutineContext().ensureActive()
                val callback = Intent(context, PackageInstallResultReceiver::class.java).setAction(PackageInstallResultReceiver.ACTION)
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(sender.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }
}

/** Explicit, non-exported PendingIntent target; Android still asks the user to approve. */
class PackageInstallResultReceiver : BroadcastReceiver() {
    companion object {
        internal const val ACTION = "dev.petrov.ymplayer2.PACKAGE_INSTALL_RESULT"
        private const val PREFS = "package-install-result"
        fun takeError(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val error = prefs.getString("error", null)
            if (error != null) prefs.edit().remove("error").apply()
            return error
        }
    }
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        try {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        ?: throw IOException("Package installer did not provide a confirmation")
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> prefs.edit().remove("error").apply()
                else -> prefs.edit().putString("error", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.take(1000) ?: "PackageInstaller status=$status").apply()
            }
        } catch (error: Exception) {
            prefs.edit().putString("error", error.message?.take(1000) ?: error.javaClass.simpleName).apply()
        }
    }
}
