package dev.petrov.ymplayer2.shell

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

@Composable internal fun ProfilesScreen(profiles: List<Profile>, current: String, account: (() -> Unit)? = null, select: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Профили", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("У каждого профиля своя очередь. При переключении плеер остаётся на паузе.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (account != null) item {
            OutlinedButton(account, Modifier.fillMaxWidth().prismFocus().testTag("account_open")) {
                Text("Яндекс · ${profiles.first { it.id == current }.name}")
            }
        }
        items(profiles, key = Profile::id) { profile ->
            Surface(onClick = { select(profile.id) }, Modifier.fillMaxWidth().prismFocus().testTag("profile_${profile.id}"),
                color = if (current == profile.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SkinIcon(if (profile.guest) UiIcon.GUEST else UiIcon.PROFILE, null)
                    Column(Modifier.weight(1f)) { Text(profile.name, fontWeight = FontWeight.Bold); Text(profile.description, style = MaterialTheme.typography.bodySmall) }
                    if (current == profile.id) SkinIcon(UiIcon.CHECK, "Выбран")
                }
            }
        }
        item { Text(if (account == null) "Локальные профили используют общий каталог. В этом режиме аккаунты не подключены."
            else "Локальный каталог общий. Вход в Яндекс, очередь и списки принадлежат выбранному профилю. Гость работает без аккаунта.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun SettingsScreen(version: String, theme: String, setTheme: (String) -> Unit, catalog: CatalogState, setCatalog: (CatalogState) -> Unit, demo: Boolean = true, folders: () -> Unit = {}, offline: (() -> Unit)? = null, quality: (() -> Unit)? = null, diagnostics: (() -> Unit)? = null, sideBar: (() -> Unit)? = null, updates: (() -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (quality != null) item { OutlinedButton(quality, Modifier.fillMaxWidth().prismFocus().testTag("settings_quality")) { Text("Качество звука") } }
        item { Text("Оформление", style = MaterialTheme.typography.titleMedium) }
        items(listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе")) { (id, label) ->
            Choice(label, theme == id, "theme_$id") { setTheme(id) }
        }
        if (demo) {
            item { HorizontalDivider(); Text("Состояние демокаталога", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
            items(CatalogState.entries) { item -> Choice(item.label, catalog == item, "state_${item.name}") { setCatalog(item) } }
        } else item { OutlinedButton(folders, Modifier.prismFocus().testTag("manage_folders")) { Text("Папки с музыкой") } }
        if (offline != null) item { OutlinedButton(offline, Modifier.prismFocus().testTag("settings_offline")) { Text("«Мне нравится» офлайн") } }
        if (diagnostics != null) item { OutlinedButton(diagnostics, Modifier.fillMaxWidth().prismFocus().testTag("settings_diagnostics")) { Text("Диагностика") } }
        if (sideBar != null) item { OutlinedButton(sideBar, Modifier.fillMaxWidth().prismFocus().testTag("settings_sidebar")) { Text("Боковая панель") } }
        if (updates != null) item { OutlinedButton(updates, Modifier.fillMaxWidth().prismFocus().testTag("settings_updates")) { Text("Обновление приложения") } }
        item { HorizontalDivider(); Text("YMPlayer 2 · $version", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp)) }
        item { Text(if (demo) "Демонстрационный режим M1 · без звука" else "Локальная и онлайн-музыка. Вход в Яндекс — в профилях. Сохранённое «Мне нравится» — в разделе «Офлайн».", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

@Composable internal fun MessageScreen(title: String, subtitle: String, text: String, icon: UiIcon) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { SkinIcon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary) }
        item { Text(subtitle, style = MaterialTheme.typography.titleLarge) }
        item { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
