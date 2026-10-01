package dev.petrov.ymplayer2.designsystem.skin

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class SkinChoice(val skin: AppSkin, val author: String, val description: String, val removable: Boolean = false)
data class SkinState(val active: AppSkin = PrismSkin, val choices: List<SkinChoice> = emptyList(),
    val preview: SkinChoice? = null, val busy: Boolean = true, val ready: Boolean = false, val issue: String? = null)

/** Owns only appearance; no playback, profile, provider or network dependencies. */
class SkinRepository(context: Context, private val scope: CoroutineScope, storeName: String = "skins") {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, storeName)
    private val prefs = this.context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(SkinState())
    val state: StateFlow<SkinState> = mutable.asStateFlow()
    private val packages = linkedMapOf<String, SkinPackage>()
    private val builtins = linkedSetOf("prism")
    private var pending: SkinPackage? = null
    private val initialized = scope.launch(Dispatchers.IO) { try { mutex.withLock {
        var issue: String? = null
        for (name in listOf("harbor", "olive", "silver")) {
            runCatching { this@SkinRepository.context.assets.open("skins/$name.ymskin").use(SkinPackageReader::read) }
                .onSuccess { packages[it.skin.id] = it; builtins += it.skin.id }
        }
        directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(".ymskin") }?.sortedBy { it.name }?.take(16)?.forEach { file ->
            runCatching { AtomicFile(file).openRead().use(SkinPackageReader::read) }.onSuccess {
                if (it.skin.id !in builtins && file.name == "${it.skin.id}.ymskin") packages[it.skin.id] = it
            }.onFailure { issue = "Один из сохранённых скинов повреждён. Его можно импортировать заново." }
        }
        val selected = prefs.getString("active", "prism")
        val active = if (selected == "prism") PrismSkin else packages[selected]?.skin ?: PrismSkin.also {
            prefs.edit().putString("active", "prism").commit()
            issue = "Сохранённый скин недоступен. Восстановлен «Оксид»."
        }
        mutable.value = SkinState(active, choices(), busy = false, ready = true, issue = issue)
    } } catch (e: CancellationException) { throw e } catch (_: Exception) {
        mutable.value = SkinState(choices = choices(), busy = false, ready = true,
            issue = "Не удалось прочитать сохранённое оформление. Используется «Оксид».")
    } }

    fun inspect(uri: Uri) = action {
        pending = null
        mutable.value = mutable.value.copy(preview = null)
        val packageData = context.contentResolver.openInputStream(uri)?.use(SkinPackageReader::read)
            ?: throw SkinPackageException("Не удалось открыть файл скина")
        check(packageData.skin.id !in builtins) { "Этот ID принадлежит встроенному скину. Измените id в manifest." }
        pending = packageData
        mutable.value = mutable.value.copy(preview = choice(packageData, true), issue = null)
    }
    fun preview(id: String) = action {
        pending = null
        mutable.value = mutable.value.copy(preview = choices().firstOrNull { it.skin.id == id }
            ?: throw SkinPackageException("Скин не найден"), issue = null)
    }
    fun cancelPreview() = action { pending = null; mutable.value = mutable.value.copy(preview = null, issue = null) }
    fun applyPreview() = action {
        val preview = mutable.value.preview ?: return@action
        pending?.let { data ->
            check(data.skin.id in packages || packages.keys.count { it !in builtins } < 16) { "Сохранено 16 скинов. Удалите ненужный перед импортом." }
            val atomic = AtomicFile(File(directory, "${data.skin.id}.ymskin"))
            val stream = atomic.startWrite()
            try { stream.write(data.bytes); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
            packages[data.skin.id] = data
        }
        check(prefs.edit().putString("active", preview.skin.id).commit()) { "Не удалось сохранить выбор оформления" }
        pending = null
        mutable.value = mutable.value.copy(active = preview.skin, choices = choices(), preview = null, issue = null)
    }
    fun restore() = action {
        check(prefs.edit().putString("active", "prism").commit()) { "Не удалось сохранить выбор оформления" }
        pending = null
        mutable.value = mutable.value.copy(active = PrismSkin, preview = null, issue = null)
    }
    fun remove(id: String) = action {
        check(id in packages && id !in builtins && id != mutable.value.active.id) { "Активный, встроенный или неизвестный скин нельзя удалить" }
        AtomicFile(File(directory, "$id.ymskin")).delete()
        packages.remove(id)
        if (mutable.value.preview?.skin?.id == id) pending = null
        mutable.value = mutable.value.copy(choices = choices(), preview = mutable.value.preview?.takeUnless { it.skin.id == id }, issue = null)
    }
    fun report(message: String) { mutable.value = mutable.value.copy(issue = message) }
    private fun choice(data: SkinPackage, removable: Boolean) = SkinChoice(data.skin, data.author, data.description, removable)
    private fun choices() = listOf(SkinChoice(PrismSkin, "YMPlayer", "Медь, графит и тёплая бумага")) + packages.values.map { choice(it, it.skin.id !in builtins) }
    private fun action(block: () -> Unit) = scope.launch(Dispatchers.IO) {
        initialized.join()
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(issue = when (e) {
                    is SkinPackageException, is IllegalStateException -> e.message
                    else -> "Не удалось сохранить или прочитать скин. Повторите выбор файла."
                })
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
