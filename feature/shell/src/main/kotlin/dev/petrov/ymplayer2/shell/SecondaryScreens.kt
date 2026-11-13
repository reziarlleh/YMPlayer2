package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun SettingsScreen(version: String, theme: String, setTheme: (String) -> Unit, catalog: CatalogState, setCatalog: (CatalogState) -> Unit, demo: Boolean = true, offline: (() -> Unit)? = null, quality: (() -> Unit)? = null, diagnostics: (() -> Unit)? = null, sideBar: (() -> Unit)? = null, updates: (() -> Unit)? = null, skins: (() -> Unit)? = null, about: () -> Unit = {}, language: () -> Unit = {}, scale: () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr(Msg.msg_985b5e0f2ccf), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { OutlinedButton(language, Modifier.fillMaxWidth().prismFocus().testTag("settings_language")) { Text(trMessage("Язык приложения")) } }
        item { OutlinedButton(scale, Modifier.fillMaxWidth().prismFocus().testTag("settings_scale")) { Text(trMessage("Масштаб интерфейса")) } }
        if (quality != null) item { OutlinedButton(quality, Modifier.fillMaxWidth().prismFocus().testTag("settings_quality")) { Text(tr(Msg.msg_995011f87d4b)) } }
        item { Text(tr(Msg.msg_1731619efa3a), style = MaterialTheme.typography.titleMedium) }
        if (skins != null) item { OutlinedButton(skins, Modifier.fillMaxWidth().prismFocus().testTag("settings_skins")) { Text(tr(Msg.msg_6dd43bf971fd)) } }
        items(listOf("dark" to tr(Msg.msg_1eecfcaa7bdb), "light" to tr(Msg.msg_f0cd3a603372), "system" to tr(Msg.msg_29e4a4bd7a17))) { (id, label) ->
            Choice(label, theme == id, "theme_$id") { setTheme(id) }
        }
        if (demo) {
            item { HorizontalDivider(); Text(tr(Msg.msg_25a86516eb0c), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
            items(CatalogState.entries) { item -> Choice(trMessage(item.label), catalog == item, "state_${item.name}") { setCatalog(item) } }
        }
        if (offline != null) item { OutlinedButton(offline, Modifier.fillMaxWidth().prismFocus().testTag("settings_offline")) { Text(tr(Msg.msg_fd6e72f72d55)) } }
        if (diagnostics != null) item { OutlinedButton(diagnostics, Modifier.fillMaxWidth().prismFocus().testTag("settings_diagnostics")) { Text(tr(Msg.msg_da4da4e3527c)) } }
        if (sideBar != null) item { OutlinedButton(sideBar, Modifier.fillMaxWidth().prismFocus().testTag("settings_sidebar")) { Text(tr(Msg.msg_43fb606c5b6f)) } }
        if (updates != null) item { OutlinedButton(updates, Modifier.fillMaxWidth().prismFocus().testTag("settings_updates")) { Text(tr(Msg.msg_7e0c5e62a006)) } }
        item { OutlinedButton(about, Modifier.fillMaxWidth().prismFocus().testTag("settings_about")) { Text(tr(Msg.msg_eca626bab07b)) } }
        item { HorizontalDivider(); Text("YMPlayer 2 · $version", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp)) }
        item { Text(if (demo) tr(Msg.msg_a75ec8e3fe3a) else tr(Msg.msg_eb2c88237bfa), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun Choice(label: String, selected: Boolean, tag: String, click: () -> Unit) {
    Surface(click, Modifier.fillMaxWidth().prismFocus().testTag(tag).semantics { this.selected = selected; role = Role.RadioButton }, shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkinIcon(if (selected) UiIcon.CHOICE_ON else UiIcon.CHOICE_OFF, null)
            Text(label)
        }
    }
}
