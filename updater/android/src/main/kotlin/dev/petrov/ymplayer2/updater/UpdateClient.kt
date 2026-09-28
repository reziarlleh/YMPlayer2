package dev.petrov.ymplayer2.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val channel: String,
    val minSdk: Int,
    val notes: String,
    val primaryUrl: String,
    val alternativeUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val manifestUrl: String,
) {
    fun sources(preferAlternative: Boolean): List<String> =
        (if (preferAlternative) listOf(alternativeUrl, primaryUrl) else listOf(primaryUrl, alternativeUrl))
            .filter(String::isNotBlank).distinct()
}

data class DownloadedUpdate(val file: File, val usedAlternative: Boolean)

fun interface UpdateConnectionFactory { fun open(url: URL): HttpURLConnection }

/** Independent 2.x update transport. No account token is sent to either source. */
class UpdateClient(
    private val context: Context,
    private val connections: UpdateConnectionFactory = UpdateConnectionFactory { it.openConnection() as HttpURLConnection },
    private val channel: String = "beta",
    private val manifests: List<String> = manifestUrls(channel),
) {
    companion object {
        const val PRIMARY_MANIFEST = "https://raw.githubusercontent.com/reziarlleh/YMPlayer2/main/update/manifest.json"
        const val ALTERNATIVE_MANIFEST = "https://cdn.jsdelivr.net/gh/reziarlleh/YMPlayer2@main/update/manifest.json"
        fun manifestUrls(channel: String): List<String> = if (channel == "stable") listOf(
            "https://raw.githubusercontent.com/reziarlleh/YMPlayer2/main/update/stable.json",
            "https://cdn.jsdelivr.net/gh/reziarlleh/YMPlayer2@main/update/stable.json",
        ) else listOf(PRIMARY_MANIFEST, ALTERNATIVE_MANIFEST)
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_REDIRECTS = 5
    }

    suspend fun check(): UpdateRelease = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for (source in manifests) {
            try {
                val bytes = readBounded(source, MAX_MANIFEST_BYTES)
                return@withContext parseManifest(JSONObject(bytes.toString(Charsets.UTF_8)), source)
            } catch (error: Exception) {
                failures += "${host(source)}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw IOException("Источники обновлений недоступны: ${failures.joinToString("; ")}")
    }

    fun parseManifest(json: JSONObject, source: String): UpdateRelease {
        if (json.optInt("schemaVersion") != 1) throw IOException("Неподдерживаемый формат манифеста")
        if (json.optString("packageName") != context.packageName) throw IOException("Другой пакет приложения")
        val versionCode = json.optLong("versionCode")
        val versionName = json.optString("versionName").trim()
        val remoteChannel = json.optString("channel").trim()
        val minSdk = json.optInt("minSdk", 29)
        if (versionCode <= 0 || !versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(beta)?-build[0-9]+")))
            throw IOException("Неверная версия обновления")
        if (remoteChannel !in listOf("beta", "stable") || channel == "stable" && remoteChannel != "stable")
            throw IOException("Обновление не относится к выбранному каналу")
        if ((remoteChannel == "beta") != versionName.contains("beta-"))
            throw IOException("Канал и версия не совпадают")
        if (minSdk > Build.VERSION.SDK_INT) throw IOException("Требуется Android API $minSdk")
        val apk = json.optJSONObject("apk") ?: throw IOException("Нет сведений об APK")
        val primary = apk.optString("primaryUrl").trim()
        val alternative = apk.optString("alternativeUrl").trim()
        if (primary.isBlank() && alternative.isBlank()) throw IOException("Нет адреса APK")
        listOf(primary, alternative).filter(String::isNotBlank).forEach(::httpsUrl)
        val sha256 = apk.optString("sha256").lowercase()
        val size = apk.optLong("sizeBytes")
        if (!sha256.matches(Regex("[0-9a-f]{64}")) || size !in 1..MAX_APK_BYTES)
            throw IOException("Неверные размер или SHA-256 APK")
        return UpdateRelease(versionCode, versionName, remoteChannel, minSdk, json.optString("releaseNotes").take(16_000),
            primary, alternative, size, sha256, source)
    }

    suspend fun download(release: UpdateRelease, preferAlternative: Boolean = false,
        progress: (Int, String) -> Unit = { _, _ -> }): DownloadedUpdate = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Не удалось создать папку обновлений")
        val destination = File(directory, "YMPlayer2-${release.versionCode}.apk")
        if (destination.isFile && verifyFile(destination, release)) {
            verifyArchive(destination, release)
            return@withContext DownloadedUpdate(destination, false)
        }
        val failures = mutableListOf<String>()
        for (source in release.sources(preferAlternative)) {
            val partial = File(directory, "YMPlayer2-${release.versionCode}.part")
            try {
                partial.delete()
                downloadOne(source, partial, release, progress)
                if (destination.exists() && !destination.delete()) throw IOException("Не удалось заменить старый APK")
                if (!partial.renameTo(destination)) throw IOException("Не удалось завершить загрузку")
                verifyArchive(destination, release)
                return@withContext DownloadedUpdate(destination, source == release.alternativeUrl)
            } catch (error: Exception) {
                partial.delete()
                destination.delete()
                failures += "${host(source)}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw IOException("Не удалось загрузить обновление: ${failures.joinToString("; ")}")
    }

    fun verifyFile(file: File, release: UpdateRelease): Boolean = file.isFile &&
        file.length() == release.sizeBytes && sha256(file).equals(release.sha256, true)

    /** PackageManager and finally the system installer enforce the installed signing identity. */
    fun verifyArchive(file: File, release: UpdateRelease) {
        if (!verifyFile(file, release)) throw IOException("Контрольная сумма APK не совпадает")
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("APK не читается")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName || archive.longVersionCode != release.versionCode ||
            archive.longVersionCode <= installed.longVersionCode) throw IOException("APK не обновляет этот YMPlayer 2")
        val newSigners = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val oldSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        if (newSigners.isEmpty() || newSigners != oldSigners) throw IOException("Подпись APK не совпадает с установленной")
    }

    private fun downloadOne(source: String, file: File, release: UpdateRelease, progress: (Int, String) -> Unit) {
        val connection = connect(source)
        try {
            val declared = connection.contentLengthLong
            if (declared > MAX_APK_BYTES || declared > 0 && declared != release.sizeBytes)
                throw IOException("Неожиданный размер APK")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastPercent = -1
            connection.inputStream.use { input -> FileOutputStream(file).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_APK_BYTES || total > release.sizeBytes) throw IOException("APK слишком велик")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    val percent = (total * 100 / release.sizeBytes).toInt()
                    if (percent == 100 || percent - lastPercent >= 2) {
                        lastPercent = percent
                        progress(percent, host(source))
                    }
                }
                output.fd.sync()
            } }
            if (total != release.sizeBytes || !digest.digest().hex().equals(release.sha256, true))
                throw IOException("Размер или SHA-256 APK не совпадает")
        } finally { connection.disconnect() }
    }

    private fun readBounded(source: String, limit: Int): ByteArray {
        val connection = connect(source)
        try {
            if (connection.contentLengthLong > limit) throw IOException("Манифест слишком велик")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > limit) throw IOException("Манифест слишком велик")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun connect(raw: String): HttpURLConnection {
        var url = httpsUrl(raw)
        repeat(MAX_REDIRECTS + 1) { redirects ->
            val connection = connections.open(url).apply {
                connectTimeout = 12_000; readTimeout = 25_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "YMPlayer2-Updater")
            }
            val code = try { connection.responseCode } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (code in 200..299) return connection
            if (code in listOf(301, 302, 303, 307, 308) && redirects < MAX_REDIRECTS) {
                val target = connection.getHeaderField("Location")
                connection.disconnect()
                if (target == null) throw IOException("Редирект без адреса")
                url = httpsUrl(URL(url, target).toString())
            } else {
                connection.disconnect()
                throw IOException("HTTP $code от ${url.host}")
            }
        }
        throw IOException("Слишком много редиректов")
    }
}

private fun httpsUrl(raw: String): URL = URL(raw).also {
    if (it.protocol != "https") throw IOException("Разрешён только HTTPS")
}
private fun host(raw: String): String = runCatching { URL(raw).host }.getOrDefault("источник")
private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest().hex()
}
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
