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
    private val parameter = Regex("@(\\d+)(?:\\|(track))?@")
    private val exact by lazy { buildMap {
        val fixed = Msg.entries.filter { !parameter.containsMatchIn(it.source) }
        fixed.forEach { key -> put(key.source, key) }
        // An identical label can represent different actions. Do not guess its key.
        messagePatterns.filter { !parameter.containsMatchIn(it.source) }
            .groupBy(MessagePattern::source).forEach { (source, variants) ->
                variants.map(MessagePattern::key).distinct().singleOrNull()?.let { key ->
                    if (!containsKey(source)) put(source, key)
                }
            }
    } }
    private data class Template(val key: Msg, val regex: Regex, val indexes: List<Int>)
    private val trackRegexes by lazy { trackCountForms.mapValues { (_, forms) ->
        "(\\d+)(?:" + forms.map { Regex.escape(it.removePrefix("%d")) }.distinct().joinToString("|") + ")"
    } }
    private fun template(variant: MessagePattern): Template {
        var offset = 0
        val indexes = mutableListOf<Int>()
        val pattern = StringBuilder("^")
        parameter.findAll(variant.source).forEach { match ->
            pattern.append(Regex.escape(variant.source.substring(offset, match.range.first)))
                .append(if (match.groupValues[2] == "track") trackRegexes.getValue(variant.language) else "(.*?)")
            indexes.add(match.groupValues[1].toInt()); offset = match.range.last + 1
        }
        pattern.append(Regex.escape(variant.source.substring(offset))).append('$')
        return Template(variant.key, Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL), indexes)
    }
    private val templates by lazy {
        messagePatterns.filter { parameter.containsMatchIn(it.source) }
            .sortedByDescending { it.source.replace(parameter, "").length }
            .map(::template).groupBy { it.regex.pattern }.values.mapNotNull { variants ->
                variants.first().takeIf { variants.map(Template::key).distinct().size == 1 }
            }
    }
    private val waveKey by lazy { Msg.entries.first { it.source == "Моя волна: " } }
    private val retryKey by lazy { Msg.entries.first { it.source == " Повторим автоматически (@0@/3)." } }
    private val uncertainKey by lazy { Msg.entries.first { it.source == " Результат изменения не подтверждён. Проверьте плейлисты перед повтором." } }
    private val retrySuffixes by lazy { templates.filter { it.key == retryKey }.map {
        it.copy(regex = Regex(it.regex.pattern.removePrefix("^"), RegexOption.DOT_MATCHES_ALL))
    } }
    private val issuePrefixes by lazy { messagePatterns.filter {
        it.key == waveKey || it.key.source.startsWith("Не удалось") && it.key.source.endsWith(' ')
    }.distinctBy(MessagePattern::source).sortedByDescending { it.source.length } }

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
        for (prefix in issuePrefixes) if (source.startsWith(prefix.source)) {
            return text(prefix.key) + issue(source.removePrefix(prefix.source), depth + 1)
        }
        for (suffix in retrySuffixes) {
            val retry = suffix.regex.find(source) ?: continue
            return issue(source.substring(0, retry.range.first), depth + 1) + text(retryKey, retry.groupValues[1])
        }
        for (suffix in messagePatterns.filter { it.key == uncertainKey }) if (source.endsWith(suffix.source)) {
            return issue(source.removeSuffix(suffix.source), depth + 1) + text(uncertainKey)
        }
        return message(source)
    }
}
