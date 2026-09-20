package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.SkinIcon
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun AudioQualityScreen(preferences: AudioQualityPreferences) {
    val state by preferences.state.collectAsStateWithLifecycle()
    var editingCache by remember { mutableStateOf<Boolean?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("quality_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Качество звука", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Настройки общие для этого устройства.") }
        item { Text("Онлайн-воспроизведение", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton({ editingCache = false }, Modifier.fillMaxWidth().prismFocus().testTag("quality_stream")) { Text(state.stream.label) } }
        item { Text("Новые потоки и предзагрузка «Моей волны».", style = MaterialTheme.typography.bodyMedium) }
        item { Text("Офлайн-кэш «Мне нравится»", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton({ editingCache = true }, Modifier.fillMaxWidth().prismFocus().testTag("quality_cache")) { Text(state.cache.label) } }
        item { Text("Новые загрузки при синхронизации «Мне нравится».", style = MaterialTheme.typography.bodyMedium) }
        item { Text("Яндекс может предложить другой битрейт: выбирается лучший доступный до указанного значения, а если его нет — ближайший выше. «Авто» и «Максимальное» выбирают наибольший доступный битрейт.") }
        item { Text("Текущий трек, уже подготовленные треки волны и сохранённые файлы сохраняют своё качество. Для повторной загрузки всей офлайн-коллекции удалите её файлы в разделе «Офлайн» и запустите синхронизацию. Лайки останутся в Яндексе.") }
    }
    editingCache?.let { cache ->
        val current = if (cache) state.cache else state.stream
        AlertDialog(onDismissRequest = { editingCache = null }, title = { Text(if (cache) "Качество офлайн-кэша" else "Качество онлайн-звука") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp).testTag("quality_options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AudioQuality.entries, key = AudioQuality::key) { option ->
                        Surface(onClick = {
                            if (cache) preferences.setCache(option) else preferences.setStream(option)
                            editingCache = null
                        }, modifier = Modifier.fillMaxWidth().prismFocus().testTag("quality_option_${option.name}").semantics {
                            selected = option == current; role = Role.RadioButton
                        }, shape = MaterialTheme.shapes.medium,
                            color = if (option == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SkinIcon(if (option == current) UiIcon.CHOICE_ON else UiIcon.CHOICE_OFF, null)
                                Text(option.label, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ editingCache = null }) { Text("Закрыть") } })
    }
}
