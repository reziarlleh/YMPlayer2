package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

data class SkinPackage(val skin: AppSkin, val author: String, val description: String, val bytes: ByteArray)
class SkinPackageException(message: String) : IllegalArgumentException(message)

/** Data-only ZIP reader. Never extracts paths or opens package-supplied URLs. */
object SkinPackageReader {
    const val MAX_ARCHIVE = 1024 * 1024
    private const val MAX_FILE = 256 * 1024
    val colorRoles = setOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
        "surfaceTint", "inverseSurface", "inverseOnSurface", "error", "onError", "errorContainer",
        "onErrorContainer", "outline", "outlineVariant", "scrim", "surfaceDim", "surfaceBright",
        "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    )
    fun read(input: InputStream): SkinPackage = readBytes(bounded(input, MAX_ARCHIVE))
    fun readBytes(bytes: ByteArray): SkinPackage = try {
        checkSkin(bytes.size <= MAX_ARCHIVE, "Слишком большой архив скина")
        checkSkin(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte(), "Нужен ZIP-пакет .ymskin")
        val files = linkedMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                checkSkin(++entries <= 64, "В скине слишком много файлов")
                val name = entry.name.removeSuffix("/")
                checkSkin(name.matches(Regex("[A-Za-z0-9_./-]{1,160}")) && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }, "Недопустимый путь в скине")
                checkSkin(names.add(name), "Повторяющийся путь в скине")
                val data = bounded(zip, MAX_FILE)
                total += data.size
                checkSkin(total <= 2 * MAX_ARCHIVE, "Слишком много распакованных данных")
                if (entry.isDirectory) checkSkin(data.isEmpty(), "Каталог содержит данные") else files[name] = data
                zip.closeEntry()
            }
        }
        val manifestBytes = files["manifest.json"] ?: fail("В корне нет manifest.json")
        checkSkin(manifestBytes.size <= 65536, "Manifest слишком большой")
        val manifest = json(manifestBytes)
        keys(manifest, setOf("schemaVersion", "id", "name", "author", "description", "dark", "light", "icons", "artwork"))
        checkSkin(manifest.opt("schemaVersion") == 1, "Версия формата скина не поддерживается")
        val id = string(manifest, "id", 64)
        checkSkin(id != "prism" && id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")), "Недопустимый ID скина")
        val name = string(manifest, "name", 80)
        val author = string(manifest, "author", 80)
        val description = if (manifest.has("description")) string(manifest, "description", 400) else ""
        val referenced = mutableSetOf("manifest.json")
        val icons = mutableMapOf<UiIcon, ImageVector>()
        objectOrNull(manifest, "icons")?.let { iconMap ->
            keys(iconMap, UiIcon.entries.mapTo(mutableSetOf()) { it.name })
            iconMap.keys().forEach { role ->
                val path = string(iconMap, role, 160)
                checkSkin(path.endsWith(".json") && path != "manifest.json", "Значок должен ссылаться на JSON-вектор")
                val data = files[path] ?: fail("Файл значка не найден: $role")
                referenced += path
                icons[UiIcon.valueOf(role)] = vector(json(data), role)
            }
        }
        checkSkin(files.keys.all { it in referenced }, "В пакете есть неиспользуемые файлы")
        val artwork = objectOrNull(manifest, "artwork")?.let {
            keys(it, setOf("backgrounds", "end"))
            val colors = it.getJSONArray("backgrounds")
            checkSkin(colors.length() in 1..12, "Нужно 1–12 цветов заглушек")
            ArtworkPalette((0 until colors.length()).map { index -> color(colors.get(index)) }, color(it.get("end")))
        } ?: PrismSkin.artwork
        SkinPackage(PrismSkin.copy(id = id, name = name, dark = palette(objectOrNull(manifest, "dark"), PrismSkin.dark),
            light = palette(objectOrNull(manifest, "light"), PrismSkin.light), icons = icons, artwork = artwork), author, description, bytes.copyOf())
    } catch (e: SkinPackageException) { throw e } catch (_: Exception) { fail("Не удалось прочитать пакет скина: проверьте ZIP и JSON") }

    private fun vector(json: JSONObject, name: String): ImageVector {
        keys(json, setOf("width", "height", "paths"))
        val width = number(json, "width")
        val height = number(json, "height")
        checkSkin(width > 0 && height > 0 && width <= 1024 && height <= 1024, "Недопустимый viewport значка")
        val paths = json.getJSONArray("paths")
        checkSkin(paths.length() in 1..64, "Нужно 1–64 пути значка")
        var length = 0
        return ImageVector.Builder(name, 24.dp, 24.dp, width, height).apply {
            for (i in 0 until paths.length()) {
                val path = paths.getJSONObject(i)
                keys(path, setOf("data", "fillType"))
                val data = string(path, "data", 65536)
                length += data.length
                checkSkin(length <= 65536 && data.matches(Regex("[MmZzLlHhVvCcSsQqTtAa0-9eE+.,\\s-]+")) && data.first() in "Mm", "Недопустимый путь значка")
                Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?").findAll(data).forEach {
                    val n = it.value.toFloatOrNull()
                    checkSkin(n != null && n.isFinite() && kotlin.math.abs(n) <= 100000, "Недопустимая координата значка")
                }
                val fillType = when (path.optString("fillType", "nonZero")) {
                    "nonZero" -> PathFillType.NonZero
                    "evenOdd" -> PathFillType.EvenOdd
                    else -> fail("Неизвестный fillType")
                }
                val nodes = PathParser().parsePathString(data).toNodes()
                checkSkin(nodes.isNotEmpty(), "Пустой путь значка")
                addPath(nodes, fill = SolidColor(Color.Black), pathFillType = fillType)
            }
        }.build()
    }
    private fun number(json: JSONObject, key: String): Float {
        checkSkin(json.opt(key) is Number, "Нужно число: $key")
        return json.getDouble(key).toFloat().also { checkSkin(it.isFinite(), "Число должно быть конечным") }
    }
    private fun color(value: Any): Color {
        checkSkin(value is String && value.matches(Regex("#[0-9A-Fa-f]{6}")), "Цвет должен иметь формат #RRGGBB")
        return Color(0xff000000L or (value as String).substring(1).toLong(16))
    }
    private fun json(bytes: ByteArray): JSONObject = JSONObject(Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
    private fun objectOrNull(json: JSONObject, key: String): JSONObject? = if (!json.has(key)) null else json.getJSONObject(key)
    private fun string(json: JSONObject, key: String, limit: Int): String {
        val value = json.opt(key)
        checkSkin(value is String && value.isNotBlank() && value.length <= limit && value.none { it.code < 32 }, "Неверное поле: $key")
        return value as String
    }
    private fun keys(json: JSONObject, allowed: Set<String>) = json.keys().forEach { checkSkin(it in allowed, "Неизвестное поле: ${it.take(80)}") }
    private fun bounded(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            checkSkin(out.size() + count <= max, "Превышен допустимый размер файла скина")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    private fun checkSkin(condition: Boolean, message: String) { if (!condition) fail(message) }
    private fun fail(message: String): Nothing = throw SkinPackageException(message)

    private fun palette(json: JSONObject?, base: ColorScheme): ColorScheme {
        if (json == null) return base
        keys(json, colorRoles)
        fun c(role: String, fallback: Color) = if (json.has(role)) color(json.get(role)) else fallback
        return base.copy(
            primary = c("primary", base.primary), onPrimary = c("onPrimary", base.onPrimary),
            primaryContainer = c("primaryContainer", base.primaryContainer), onPrimaryContainer = c("onPrimaryContainer", base.onPrimaryContainer), inversePrimary = c("inversePrimary", base.inversePrimary),
            secondary = c("secondary", base.secondary), onSecondary = c("onSecondary", base.onSecondary),
            secondaryContainer = c("secondaryContainer", base.secondaryContainer), onSecondaryContainer = c("onSecondaryContainer", base.onSecondaryContainer),
            tertiary = c("tertiary", base.tertiary), onTertiary = c("onTertiary", base.onTertiary),
            tertiaryContainer = c("tertiaryContainer", base.tertiaryContainer), onTertiaryContainer = c("onTertiaryContainer", base.onTertiaryContainer),
            background = c("background", base.background), onBackground = c("onBackground", base.onBackground),
            surface = c("surface", base.surface), onSurface = c("onSurface", base.onSurface), surfaceVariant = c("surfaceVariant", base.surfaceVariant), onSurfaceVariant = c("onSurfaceVariant", base.onSurfaceVariant),
            surfaceTint = c("surfaceTint", base.surfaceTint), inverseSurface = c("inverseSurface", base.inverseSurface), inverseOnSurface = c("inverseOnSurface", base.inverseOnSurface),
            error = c("error", base.error), onError = c("onError", base.onError), errorContainer = c("errorContainer", base.errorContainer), onErrorContainer = c("onErrorContainer", base.onErrorContainer),
            outline = c("outline", base.outline), outlineVariant = c("outlineVariant", base.outlineVariant), scrim = c("scrim", base.scrim),
            surfaceDim = c("surfaceDim", base.surfaceDim), surfaceBright = c("surfaceBright", base.surfaceBright),
            surfaceContainerLowest = c("surfaceContainerLowest", base.surfaceContainerLowest), surfaceContainerLow = c("surfaceContainerLow", base.surfaceContainerLow),
            surfaceContainer = c("surfaceContainer", base.surfaceContainer), surfaceContainerHigh = c("surfaceContainerHigh", base.surfaceContainerHigh), surfaceContainerHighest = c("surfaceContainerHighest", base.surfaceContainerHighest),
        )
    }
}
