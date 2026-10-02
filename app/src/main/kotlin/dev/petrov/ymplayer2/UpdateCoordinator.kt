package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.localization.*

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.petrov.ymplayer2.shell.UpdateAccess
import dev.petrov.ymplayer2.shell.UpdateOffer
import dev.petrov.ymplayer2.shell.UpdateUiState
import dev.petrov.ymplayer2.updater.DownloadedUpdate
import dev.petrov.ymplayer2.updater.InstallStep
import dev.petrov.ymplayer2.updater.UpdateClient
import dev.petrov.ymplayer2.updater.UpdateInstaller
import dev.petrov.ymplayer2.updater.UpdateRelease
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

internal class UpdateCoordinator(context: Context,
    private val client: UpdateClient = UpdateClient(context.applicationContext, channel = BuildConfig.UPDATE_CHANNEL)) : ViewModel(), UpdateAccess {
    companion object { private const val DAY_MS = 24L * 60 * 60 * 1000 }
    private val context = context.applicationContext
    private var host = WeakReference(context as? ComponentActivity)
    private val prefs = this.context.getSharedPreferences("app_updates_2", Context.MODE_PRIVATE)
    private val installer = UpdateInstaller(client)
    private val mutable = MutableStateFlow(UpdateUiState(autoCheck = prefs.getBoolean("auto_check", true)))
    override val state: StateFlow<UpdateUiState> = mutable
    private var release: UpdateRelease? = null
    private var downloaded: DownloadedUpdate? = null
    private var launchScheduled = false
    private var installOnResume = false
    private val cleanup = viewModelScope.launch(Dispatchers.IO) { runCatching { client.pruneInstalledApks() } }

    fun resume(activity: ComponentActivity) {
        host = WeakReference(activity)
        resumePendingInstall()
        if (installOnResume) install()
    }

    fun detach(activity: ComponentActivity) {
        if (host.get() === activity) host.clear()
    }

    fun checkOnLaunch() {
        if (!mutable.value.autoCheck || launchScheduled) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_check", 0)
        if (last > 0 && now >= last && now - last < DAY_MS) return
        launchScheduled = true
        viewModelScope.launch { delay(1_200); if (mutable.value.autoCheck) check(false) }
    }

    override fun setAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean("auto_check", enabled).apply()
        mutable.update { it.copy(autoCheck = enabled) }
    }

    override fun check(manual: Boolean) {
        if (mutable.value.checking || mutable.value.downloading) return
        mutable.update { it.copy(checking = true, status = if (manual) tr(Msg.msg_7cd2f0ff4082) else it.status) }
        viewModelScope.launch {
            try {
                cleanup.join()
                val info = client.check()
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                release = info.takeIf { it.versionCode > installed }
                downloaded = null
                mutable.update { it.copy(checking = false, offer = release?.let { offer ->
                    UpdateOffer(offer.versionName, offer.notes, offer.alternativeUrl.isNotBlank())
                }, ready = false, prompt = !manual && release != null,
                    status = if (release == null) tr(Msg.msg_2b7d2f3f797d) else tr(Msg.msg_8f88e588534f, info.versionName)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(checking = false,
                    status = if (manual) tr(Msg.msg_f434aef91735, error.message ?: tr(Msg.msg_7796e6a8c011)) else it.status) }
            }
        }
    }

    override fun download(preferAlternative: Boolean) {
        val info = release ?: return
        if (mutable.value.downloading) return
        mutable.update { it.copy(downloading = true, progress = 0, prompt = false, status = tr(Msg.msg_b2cbccffb035, info.versionName)) }
        viewModelScope.launch {
            try {
                val result = client.download(info, preferAlternative) { percent, source ->
                    mutable.update { it.copy(progress = percent, source = source) }
                }
                downloaded = result
                mutable.update { it.copy(downloading = false, ready = true,
                    status = tr(Msg.msg_460213556ee1, if (result.usedAlternative) tr(Msg.msg_ad6a1e7e39d2) else "")) }
                installOnResume = true
                install()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(downloading = false,
                    status = tr(Msg.msg_6dccc4a3f564, error.message ?: tr(Msg.msg_7796e6a8c011))) }
            }
        }
    }

    override fun install() {
        val info = release ?: return
        val file = downloaded?.file ?: return
        // MainActivity attaches in onResume and detaches in onPause. Lifecycle's
        // RESUMED event can be dispatched after onResume itself returns.
        val activity = host.get()
            ?: run { installOnResume = true; return }
        installOnResume = false
        try {
            when (installer.request(activity, file, info)) {
                InstallStep.PERMISSION_REQUIRED -> {
                    rememberPending(info)
                    mutable.update { it.copy(status = tr(Msg.msg_d8cafee5a654)) }
                }
                InstallStep.LAUNCHED -> {
                    prefs.edit().remove("pending").apply()
                    mutable.update { it.copy(status = tr(Msg.msg_78b7fcee0a88)) }
                }
            }
        } catch (error: Exception) {
            mutable.update { it.copy(status = tr(Msg.msg_73cec4b1fa25, error.message ?: tr(Msg.msg_44d4090ae9e4))) }
        }
    }

    fun resumePendingInstall() {
        val saved = prefs.getString("pending", null) ?: return
        if (!context.packageManager.canRequestPackageInstalls()) return
        try {
            val info = client.parseManifest(JSONObject(saved), tr(Msg.msg_c90ea6df373e))
            val file = File(context.filesDir, "updates/YMPlayer2-${info.versionCode}.apk")
            release = info
            downloaded = DownloadedUpdate(file, false)
            mutable.update { it.copy(offer = UpdateOffer(info.versionName, info.notes, info.alternativeUrl.isNotBlank()), ready = true) }
            install()
        } catch (error: Exception) {
            prefs.edit().remove("pending").apply()
            mutable.update { it.copy(status = tr(Msg.msg_0d19ca3fd269, error.message ?: tr(Msg.msg_44d4090ae9e4))) }
        }
    }

    override fun dismissPrompt() { mutable.update { it.copy(prompt = false) } }

    private fun rememberPending(info: UpdateRelease) {
        val data = JSONObject().put("schemaVersion", 1).put("packageName", context.packageName)
            .put("versionCode", info.versionCode).put("versionName", info.versionName)
            .put("channel", info.channel)
            .put("minSdk", info.minSdk).put("releaseNotes", info.notes)
            .put("apk", JSONObject().put("primaryUrl", info.primaryUrl).put("alternativeUrl", info.alternativeUrl)
                .put("sizeBytes", info.sizeBytes).put("sha256", info.sha256))
        prefs.edit().putString("pending", data.toString()).apply()
    }
}
