package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.StateFlow

data class CacheLocationState(val label: String = "Внутренняя память приложения", val external: Boolean = false,
    val moving: Boolean = false, val copiedBytes: Long = 0, val totalBytes: Long = 0, val message: String? = null)
interface CacheLocationAccess {
    val state: StateFlow<CacheLocationState>
    fun move(destination: String?)
    fun cancel()
}

data class AudioExportRequest(val profile: String, val title: String, val entity: MusicEntity? = null,
    val tracks: List<Track>? = null)
data class AudioExportState(val request: AudioExportRequest? = null, val template: String = ExportFileName.DEFAULT,
    val running: Boolean = false, val total: Int = 0, val completed: Int = 0, val failed: Int = 0,
    val current: String = "", val message: String? = null)
interface AudioExportAccess {
    val state: StateFlow<AudioExportState>
    fun ask(request: AudioExportRequest)
    fun template(value: String)
    fun start(destination: String)
    fun cancel()
    fun dismiss()
}

/** Stable tokens across languages; substitutions are data and never paths or executable code. */
object ExportFileName {
    const val DEFAULT = "{number}. {artist} - {title}"
    val variables = listOf("number", "artist", "title", "album", "id")
    private val token = Regex("\\{([^{}]+)\\}")
    fun valid(template: String): Boolean = template.isNotBlank() && template.length <= 300 &&
        token.findAll(template).all { it.groupValues[1] in variables } &&
        !token.replace(template, "").let { '{' in it || '}' in it }
    fun render(template: String, track: Track, index: Int, count: Int): String {
        require(valid(template))
        val values = mapOf("number" to (index + 1).toString().padStart(count.toString().length.coerceAtLeast(2), '0'),
            "artist" to track.artist, "title" to track.title, "album" to track.album, "id" to track.id.removePrefix("yandex:"))
        return safe(token.replace(template) { values.getValue(it.groupValues[1]) }.replace(Regex("(?i)\\.mp3$"), "")) + ".mp3"
    }
    fun safe(value: String): String {
        val clean = value.replace(Regex("[\\x00-\\x1f\\x7f<>:\"/\\\\|?*]"), "_").trim().trimEnd('.', ' ')
            .ifBlank { "track" }
        val bounded = buildString {
            for (cp in clean.codePoints().toArray()) {
                val text = String(Character.toChars(cp))
                if ((toString() + text).toByteArray(Charsets.UTF_8).size > 180) break
                append(text)
            }
        }.trimEnd('.', ' ').ifBlank { "track" }
        return if (bounded.substringBefore('.').uppercase() in listOf("CON", "PRN", "AUX", "NUL") ||
            Regex("(?i)^(COM|LPT)[1-9](\\..*)?$").matches(bounded)) "_$bounded" else bounded
    }
}
