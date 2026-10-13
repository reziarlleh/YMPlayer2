package dev.petrov.ymplayer2.offline

import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.datasource.*
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** One user-requested operation, separate from likes sync and playback. No writes to the source collection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioExporter(private val context: Context, private val accounts: AccountAuth, private val music: OnlineMusicApi,
    private val offline: OfflineMusic, private val locations: CacheLocationAccess, private val scope: CoroutineScope) : AudioExportAccess {
    private val prefs = context.getSharedPreferences("mp3-export", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AudioExportState(template = prefs.getString("template", ExportFileName.DEFAULT)!!.take(300)))
    override val state = mutable.asStateFlow()
    private var job: Job? = null
    private var requestedAccount: String? = null
    private fun pending() = runCatching { org.json.JSONObject(prefs.getString("temporary", "{}")!!) }.getOrElse { org.json.JSONObject() }
    private fun savePending(value: org.json.JSONObject) {
        if (!prefs.edit().putString("temporary", value.toString()).commit()) throw IOException("Cannot save export recovery")
    }
    private fun forgetTemporary(name: String) {
        val journal = pending()
        journal.keys().asSequence().toList().filter { journal.optString(it) == name }.forEach(journal::remove)
        savePending(journal)
    }
    private fun temporary(folder: StorageNode, prefix: String): StorageNode {
        val file = StorageNode.temporary(folder, prefix) // Unverified output must never have a .mp3 extension.
        try { savePending(pending().put(file.uri.toString(), file.name)) }
        catch (e: Exception) { runCatching { file.delete() }; throw e }
        return file
    }
    private fun discardTemporary(file: StorageNode) {
        runCatching {
            // Forget a successful deletion immediately: querying its old URI may throw afterwards.
            if (file.currentName() != file.name || file.delete()) forgetTemporary(file.name)
        }
    }
    private fun recoverTemporary() {
        val journal = pending()
        for (key in journal.keys().asSequence().toList()) {
            val expected = journal.optString(key)
            if (!expected.startsWith(".YMPlayer2-") || !expected.endsWith(".part")) { journal.remove(key); continue }
            try {
                val uri = Uri.parse(key)
                val file = if (uri.scheme == "file") StorageNode.file(context, java.io.File(uri.path!!)) else StorageNode.tree(context, uri)
                // Some providers keep the document ID on rename. Never remove an already-published MP3.
                if (file.currentName() != expected || file.delete()) journal.remove(key)
            } catch (_: Exception) { /* Unavailable media retains its recovery record until it is accessible. */ }
        }
        savePending(journal)
    }
    override fun ask(request: AudioExportRequest) {
        if (!state.value.running) {
            requestedAccount = accounts.state.value.account?.id
            mutable.value = AudioExportState(request, state.value.template)
        }
    }
    override fun template(value: String) {
        if (state.value.running) return
        val text = value.take(300)
        mutable.value = state.value.copy(template = text)
        prefs.edit().putString("template", text).apply()
    }
    override fun dismiss() { if (!state.value.running) mutable.value = state.value.copy(request = null) }
    override fun cancel() { job?.cancel() }
    fun report(message: String) { mutable.value = state.value.copy(message = message) }
    override fun start(destination: String) {
        val before = state.value; val request = before.request ?: return
        if (before.running || locations.state.value.moving || !ExportFileName.valid(before.template)) return
        val profile = accounts.state.value.profileId
        if (profile != request.profile || accounts.state.value.account?.id != requestedAccount) return
        val account = accounts.state.value.account?.id
        mutable.value = before.copy(running = true, completed = 0, failed = 0, total = 0, message = "Подготовка экспорта MP3…")
        job = scope.launch {
            var message = "Экспорт MP3 завершён."
            try {
                withContext(Dispatchers.IO) {
                    recoverTemporary()
                    val folder = Uri.parse(destination).let { if (it.scheme == "file") StorageNode.file(context, java.io.File(it.path!!)) else StorageNode.tree(context, it) }
                    if (!folder.isDirectory) throw IOException("Destination unavailable")
                    val tracks = request.tracks ?: resolve(request)
                    mutable.value = state.value.copy(total = tracks.size)
                    if (tracks.isEmpty()) throw IOException("No tracks")
                    val names = folder.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name.lowercase(Locale.ROOT) }
                    val writer = Mp3AudioWriter(context)
                    for ((index, track) in tracks.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (accounts.state.value.profileId != profile || accounts.state.value.account?.id != account) throw CancellationException("Profile changed")
                        if (locations.state.value.moving) throw CancellationException("Cache location changed")
                        mutable.value = state.value.copy(current = track.title, message = "Экспорт MP3…")
                        var temp: StorageNode? = null
                        var downloaded: StorageNode? = null
                        try {
                            val expected = (track.durationSeconds.toLong().coerceAtLeast(1) * 40_000) + 64 * 1024
                            if (folder.usableSpace < expected + 16L * 1024 * 1024) throw IOException("Недостаточно места для экспорта MP3.")
                            val source = if (track.source == Source.YANDEX) offline.audio(profile, track.id)
                                ?: music.stream(profile, track.id, AudioQuality.HIGH)
                                else track.uri ?: throw IOException("Audio unavailable")
                            val input = if (Uri.parse(source).scheme in listOf("http", "https")) {
                                downloaded = temporary(folder, ".YMPlayer2-source-")
                                download(source, downloaded!!, folder, expected)
                                downloaded!!.uri.toString()
                            } else source
                            temp = temporary(folder, ".YMPlayer2-export-")
                            temp.outputStream().buffered().use { output -> writer.write(input, track, index, tracks.size, output) }
                            currentCoroutineContext().ensureActive()
                            if (accounts.state.value.profileId != profile || accounts.state.value.account?.id != account) throw CancellationException("Profile changed")
                            validate(temp, track.durationSeconds)
                            val base = ExportFileName.render(before.template, track, index, tracks.size)
                            var name = base; var suffix = 2
                            while (name.lowercase(Locale.ROOT) in names || folder.child(name).exists()) {
                                name = base.removeSuffix(".mp3") + " (${suffix++}).mp3"
                            }
                            val result = temp.rename(name)
                            names += name.lowercase(Locale.ROOT)
                            if (!result.isFile) throw IOException("Export publication failed")
                            forgetTemporary(temp.name)
                            mutable.value = state.value.copy(completed = state.value.completed + 1)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            mutable.value = state.value.copy(failed = state.value.failed + 1)
                            if (e.message == "Недостаточно места для экспорта MP3.") throw e
                        } finally {
                            temp?.let(::discardTemporary)
                            downloaded?.let(::discardTemporary)
                            recoverTemporary()
                        }
                    }
                    if (state.value.failed > 0) message = "Экспорт завершён с ошибками. Готовые MP3 сохранены."
                }
            } catch (e: CancellationException) { message = "Экспорт отменён. Готовые MP3 сохранены." }
            catch (e: Exception) { message = when (e.message) {
                "Недостаточно места для экспорта MP3." -> e.message!!
                "Не удалось определить свободное место. Выберите папку на устройстве или USB." -> e.message!!
                else -> "Не удалось завершить экспорт MP3. Проверьте доступ к папке, сеть и свободное место."
            } }
            finally { mutable.value = state.value.copy(running = false, current = "", message = message) }
        }
    }
    private suspend fun download(uri: String, target: StorageNode, folder: StorageNode, outputBytes: Long) {
        val data = DefaultHttpDataSource.Factory().setUserAgent("Yandex-Music-API").setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000).createDataSource()
        try {
            val length = data.open(DataSpec(Uri.parse(uri)))
            if (length > 128L * 1024 * 1024) throw IOException("Audio too large")
            if (length > 0 && folder.usableSpace < length + outputBytes + 16L * 1024 * 1024) throw IOException("Недостаточно места для экспорта MP3.")
            target.outputStream().buffered().use { stream ->
                val buffer = ByteArray(128 * 1024); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = data.read(buffer, 0, buffer.size); if (count < 0) break
                    total += count
                    if (total > 128L * 1024 * 1024) throw IOException("Audio too large")
                    if (folder.usableSpace < count + outputBytes + 16L * 1024 * 1024) throw IOException("Недостаточно места для экспорта MP3.")
                    stream.write(buffer, 0, count)
                }
                if (total == 0L || length > 0 && total != length) throw IOException("Incomplete audio")
            }
        } finally { data.close() }
    }
    private suspend fun resolve(request: AudioExportRequest): List<Track> {
        val entity = request.entity ?: throw IOException("Export source missing")
        require(entity.kind in listOf(MusicKind.PLAYLISTS, MusicKind.ALBUMS))
        val tracks = mutableListOf<Track>(); val visited = mutableSetOf<Int>(); var page: Int? = 0
        while (page != null) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(page) || page !in 0..1000) throw IOException("Invalid catalog pagination")
            val data = music.page(request.profile, MusicRequest(kind = MusicKind.TRACKS, entity = entity), page)
            tracks += data.entries.mapNotNull { it.track }
            page = data.nextPage
        }
        return tracks
    }
    private suspend fun validate(file: StorageNode, durationSeconds: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, file.uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg" }
                ?: throw IOException("Not MP3")
            extractor.selectTrack(index)
            val buffer = java.nio.ByteBuffer.allocate(64 * 1024); var packets = 0; var first = 0L; var last = 0L; var delta = 0L
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                buffer.clear(); if (extractor.readSampleData(buffer, 0) <= 0) throw IOException("Incomplete MP3")
                if (packets == 0) first = extractor.sampleTime else delta = extractor.sampleTime - last
                last = extractor.sampleTime; packets++; if (!extractor.advance()) break
            }
            if (packets == 0 || durationSeconds > 0 && (last - first + delta) / 1000 + 5000 < durationSeconds * 1000L) throw IOException("Truncated MP3")
        } finally { extractor.release() }
    }
}
