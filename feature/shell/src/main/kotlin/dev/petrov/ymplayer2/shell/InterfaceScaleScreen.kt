package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.localization.trMessage
import kotlin.math.roundToInt

@Composable internal fun InterfaceScaleScreen() {
    val scale = LocalInterfaceScale.current
    val percent = scale?.percent ?: 100
    // Keep the editor stable under the finger; only the preview uses the selected scale here.
    CompositionLocalProvider(LocalDensity provides (LocalUnscaledDensity.current ?: LocalDensity.current),
        LocalConfiguration provides (LocalUnscaledConfiguration.current ?: LocalConfiguration.current)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("scale_editor"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(trMessage("Масштаб интерфейса"), style = MaterialTheme.typography.headlineSmall)
            Text(trMessage("Меняются текст, кнопки и отступы. Изменения видны сразу и сохраняются для следующих запусков."))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ scale?.adjust?.invoke(-10) }, Modifier.prismFocus().testTag("scale_decrease"),
                    enabled = scale != null && percent > InterfaceScalePreferences.MIN) { Text("−") }
                Text("$percent%", Modifier.weight(1f).testTag("scale_value"), style = MaterialTheme.typography.titleLarge)
                OutlinedButton({ scale?.adjust?.invoke(10) }, Modifier.prismFocus().testTag("scale_increase"),
                    enabled = scale != null && percent < InterfaceScalePreferences.MAX) { Text("+") }
            }
            Slider(percent.toFloat(), { scale?.save?.invoke(it.roundToInt(), false) },
                Modifier.fillMaxWidth().prismFocus().horizontalSliderNavigation { direction -> scale?.adjust?.invoke(direction * 10) }.testTag("scale_slider"),
                valueRange = 50f..200f, steps = 14, enabled = scale != null,
                onValueChangeFinished = { scale?.finish?.invoke() })
            OutlinedButton({ scale?.save?.invoke(100, true) }, Modifier.fillMaxWidth().prismFocus().testTag("scale_reset")) {
                Text(trMessage("Сбросить к 100%"))
            }
            Text(trMessage("Предпросмотр"), style = MaterialTheme.typography.titleMedium)
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * percent / 100f, base.fontScale)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(trMessage("Плеер"), style = MaterialTheme.typography.titleLarge)
                    FilledIconButton({}, Modifier.size(64.dp).testTag("scale_preview_play"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        SkinIcon(dev.petrov.ymplayer2.designsystem.skin.UiIcon.PLAY,
                            trMessage("Воспроизвести"), Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}
