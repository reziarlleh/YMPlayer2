package dev.petrov.ymplayer2.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat

fun installedVersionCode(context: Context): Long =
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

@Suppress("DEPRECATION")
internal fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
    // Android 9/10 archive parsing also needs GET_SIGNATURES to collect certificates.
    PackageManager.GET_SIGNING_CERTIFICATES or
        if (Build.VERSION.SDK_INT <= 29) PackageManager.GET_SIGNATURES else 0
} else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
internal fun signingCertificates(info: PackageInfo): Set<List<Byte>> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return signatures.orEmpty().map { it.toByteArray().toList() }.toSet()
}

@Suppress("DEPRECATION")
fun canInstallUpdates(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 26) {
    context.packageManager.canRequestPackageInstalls()
} else Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
