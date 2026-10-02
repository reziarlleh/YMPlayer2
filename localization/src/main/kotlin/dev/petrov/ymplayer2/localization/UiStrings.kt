package dev.petrov.ymplayer2.localization

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/** Keyed app text only. Arguments are opaque: titles and user data are never translated. */
fun tr(key: Msg, vararg arguments: Any?): String = UiStrings.text(key, *arguments)

/** Boundary for legacy app-owned enum labels/statuses. Never use for media or imported skin names. */
fun trMessage(message: String): String = UiStrings.message(message)
/** Known error envelopes can contain another app-owned error; metadata arguments stay opaque. */
fun trIssue(message: String): String = UiStrings.issue(message)

object UiStrings {
    // Reads are observable from Compose, including stable child composables. Native views
    // observe AppLanguages.state instead. No engine restart or route reset is needed.
    private data class Table(val language: String, val resources: Resources)
    private val table = mutableStateOf<Table?>(null)
    private val exact by lazy { buildMap {
        val fixed = Msg.entries.filter { !it.source.contains(Regex("@\\d+@")) }
        fixed.forEach { key -> put(key.source, key) }
        // Never guess which Russian action an ambiguous English label came from.
        fixed.groupBy(Msg::english).filterValues { it.size == 1 }.forEach { (english, keys) -> put(english, keys.single()) }
    } }
    private val parameter = Regex("@(\\d+)(?:\\|(track))?@")
    private data class Template(val key: Msg, val regex: Regex, val indexes: List<Int>)
    private val templates by lazy {
        Msg.entries.filter { it.source.contains(Regex("@\\d+@")) }
            .flatMap { key -> listOf(key to key.source, key to key.english) }
            .sortedByDescending { it.second.replace(parameter, "").length }
            .map { (key, source) ->
                var offset = 0
                val indexes = mutableListOf<Int>()
                val pattern = StringBuilder("^")
                parameter.findAll(source).forEach { match ->
                    pattern.append(Regex.escape(source.substring(offset, match.range.first)))
                        .append(if (match.groupValues[2] == "track") "(\\d+) tracks?" else "(.*?)")
                    indexes.add(match.groupValues[1].toInt()); offset = match.range.last + 1
                }
                pattern.append(Regex.escape(source.substring(offset))).append('$')
                Template(key, Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL), indexes)
            }
    }

    internal fun configure(context: Context, language: String) {
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        }
        table.value = Table(language, context.createConfigurationContext(config).resources)
    }

    fun text(key: Msg, vararg arguments: Any?): String {
        val resources = table.value?.resources
        val pattern = resources?.getString(key.resource) ?: key.source
        return parameter.replace(pattern) { match ->
            val value = arguments.getOrNull(match.groupValues[1].toInt())?.toString().orEmpty()
            if (match.groupValues[2] == "track") value.toIntOrNull()?.let { count ->
                resources?.getQuantityString(R.plurals.track_count, count, count)
            } ?: value else value
        }
    }

    fun message(source: String): String {
        exact[source]?.let { return text(it) }
        for (template in templates) {
            val match = template.regex.matchEntire(source) ?: continue
            val arguments = arrayOfNulls<String>(template.indexes.maxOrNull()!! + 1)
            template.indexes.forEachIndexed { group, index -> arguments[index] = match.groupValues[group + 1] }
            return text(template.key, *arguments)
        }
        return source
    }

    fun issue(source: String, depth: Int = 0): String {
        if (depth > 4) return source
        if (source.startsWith("Моя волна: ")) return message("Моя волна: ") + issue(source.removePrefix("Моя волна: "), depth + 1)
        val retry = Regex(" Повторим автоматически \\(\\d+/3\\)\\.$").find(source)
        if (retry != null) return issue(source.substring(0, retry.range.first), depth + 1) + message(retry.value)
        val uncertain = " Результат изменения не подтверждён. Проверьте плейлисты перед повтором."
        if (source.endsWith(uncertain)) return issue(source.removeSuffix(uncertain), depth + 1) + message(uncertain)
        val prefix = exact.keys.filter { it.endsWith(' ') && it.startsWith("Не удалось") && source.startsWith(it) }.maxByOrNull(String::length)
        if (prefix != null) return message(prefix) + issue(source.removePrefix(prefix), depth + 1)
        return message(source)
    }
}
