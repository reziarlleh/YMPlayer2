package dev.petrov.ymplayer2

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.petrov.ymplayer2.shell.UpdateAccess
import dev.petrov.ymplayer2.shell.UpdateOffer
import dev.petrov.ymplayer2.shell.UpdateUiState
import dev.petrov.ymplayer2.updater.DownloadedUpdate
import dev.petrov.ymplayer2.updater.InstallStep
import dev.petrov.ymplayer2.updater.UpdateClient
import dev.petrov.ymplayer2.updater.UpdateInstaller
import dev.petrov.ymplayer2.updater.UpdateRelease
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

internal class UpdateCoordinator(private val activity: ComponentActivity,
    private val client: UpdateClient = UpdateClient(activity, channel = BuildConfig.UPDATE_CHANNEL)) : UpdateAccess {
    companion object { private const val DAY_MS = 24L * 60 * 60 * 1000 }
    private val prefs = activity.getSharedPreferences("app_updates_2", Context.MODE_PRIVATE)
    private val installer = UpdateInstaller(client)
    private val mutable = MutableStateFlow(UpdateUiState(autoCheck = prefs.getBoolean("auto_check", true)))
    override val state: StateFlow<UpdateUiState> = mutable
    private var release: UpdateRelease? = null
    private var downloaded: DownloadedUpdate? = null

    fun checkOnLaunch() {
        if (!mutable.value.autoCheck) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_check", 0)
        if (last > 0 && now >= last && now - last < DAY_MS) return
        activity.lifecycleScope.launch { delay(1_200); if (mutable.value.autoCheck) check(false) }
    }

    override fun setAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean("auto_check", enabled).apply()
        mutable.update { it.copy(autoCheck = enabled) }
    }

    override fun check(manual: Boolean) {
        if (mutable.value.checking || mutable.value.downloading) return
        mutable.update { it.copy(checking = true, status = if (manual) "Проверяем обновления…" else it.status) }
        activity.lifecycleScope.launch {
            try {
                val info = client.check()
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                release = info.takeIf { it.versionCode > installed }
                downloaded = null
                mutable.update { it.copy(checking = false, offer = release?.let { offer ->
                    UpdateOffer(offer.versionName, offer.notes, offer.alternativeUrl.isNotBlank())
                }, ready = false, prompt = !manual && release != null,
                    status = if (release == null) "Установлена актуальная версия." else "Доступна ${info.versionName}.") }
            } catch (error: Exception) {
                mutable.update { it.copy(checking = false,
                    status = if (manual) "Не удалось проверить обновления: ${error.message ?: "ошибка сети"}" else it.status) }
            }
        }
    }

    override fun download(preferAlternative: Boolean) {
        val info = release ?: return
        if (mutable.value.downloading) return
        mutable.update { it.copy(downloading = true, progress = 0, prompt = false, status = "Загружаем ${info.versionName}…") }
        activity.lifecycleScope.launch {
            try {
                val result = client.download(info, preferAlternative) { percent, source ->
                    mutable.update { it.copy(progress = percent, source = source) }
                }
                downloaded = result
                mutable.update { it.copy(downloading = false, ready = true,
                    status = "APK проверен${if (result.usedAlternative) " · резервный источник" else ""}.") }
                install()
            } catch (error: Exception) {
                mutable.update { it.copy(downloading = false,
                    status = "Не удалось загрузить обновление: ${error.message ?: "ошибка сети"}") }
            }
        }
    }

    override fun install() {
        val info = release ?: return
        val file = downloaded?.file ?: return
        try {
            when (installer.request(activity, file, info)) {
                InstallStep.PERMISSION_REQUIRED -> {
                    rememberPending(info)
                    mutable.update { it.copy(status = "Разрешите установку из YMPlayer 2; после возврата откроется системный установщик.") }
                }
                InstallStep.LAUNCHED -> {
                    prefs.edit().remove("pending").apply()
                    mutable.update { it.copy(status = "Открыт системный установщик. Подтвердите обновление.") }
                }
            }
        } catch (error: Exception) {
            mutable.update { it.copy(status = "Не удалось открыть установку: ${error.message ?: "ошибка"}") }
        }
    }

    fun resumePendingInstall() {
        val saved = prefs.getString("pending", null) ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        try {
            val info = client.parseManifest(JSONObject(saved), "ожидающее обновление")
            val file = File(activity.filesDir, "updates/YMPlayer2-${info.versionCode}.apk")
            release = info
            downloaded = DownloadedUpdate(file, false)
            mutable.update { it.copy(offer = UpdateOffer(info.versionName, info.notes, info.alternativeUrl.isNotBlank()), ready = true) }
            install()
        } catch (error: Exception) {
            prefs.edit().remove("pending").apply()
            mutable.update { it.copy(status = "Ожидающий APK не прошёл проверку: ${error.message ?: "ошибка"}") }
        }
    }

    override fun dismissPrompt() { mutable.update { it.copy(prompt = false) } }

    private fun rememberPending(info: UpdateRelease) {
        val data = JSONObject().put("schemaVersion", 1).put("packageName", activity.packageName)
            .put("versionCode", info.versionCode).put("versionName", info.versionName)
            .put("channel", info.channel)
            .put("minSdk", info.minSdk).put("releaseNotes", info.notes)
            .put("apk", JSONObject().put("primaryUrl", info.primaryUrl).put("alternativeUrl", info.alternativeUrl)
                .put("sizeBytes", info.sizeBytes).put("sha256", info.sha256))
        prefs.edit().putString("pending", data.toString()).apply()
    }
}
