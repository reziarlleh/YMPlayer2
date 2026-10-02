package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.*

@Composable internal fun LanguageScreen() {
    val state by AppLanguages.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("language_list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(trMessage("Язык приложения"), style = MaterialTheme.typography.headlineSmall) }
        item { Text(trMessage("Язык общий для устройства. Названия музыки, плейлистов и скинов не переводятся.")) }
        item { LanguageChoice("system", trMessage("Авто — как в системе"), state.selected == "system") }
        AppLanguages.available.forEach { language ->
            item(key = language.tag) { LanguageChoice(language.tag, language.label, state.selected == language.tag) }
        }
        item { Text(trMessage("При отсутствии перевода используется английский."), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun LanguageChoice(tag: String, label: String, selected: Boolean) {
    Surface({ AppLanguages.select(tag) }, Modifier.fillMaxWidth().prismFocus().testTag("language_$tag")
        .semantics { this.selected = selected; role = Role.RadioButton }, shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, null)
            Text(label, Modifier.padding(start = 12.dp))
        }
    }
}
