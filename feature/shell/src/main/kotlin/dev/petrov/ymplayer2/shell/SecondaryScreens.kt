package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun ProfilesScreen(profiles: List<Profile>, current: String, select: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Профили", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("У каждого профиля своя очередь. При переключении плеер остаётся на паузе.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(profiles, key = Profile::id) { profile ->
            Surface(onClick = { select(profile.id) }, Modifier.fillMaxWidth().prismFocus().testTag("profile_${profile.id}"),
                color = if (current == profile.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (profile.guest) Icons.Default.PersonOutline else Icons.Default.AccountCircle, null)
                    Column(Modifier.weight(1f)) { Text(profile.name, fontWeight = FontWeight.Bold); Text(profile.description, style = MaterialTheme.typography.bodySmall) }
                    if (current == profile.id) Icon(Icons.Default.Check, "Выбран")
                }
            }
        }
        item { Text("Вход в Яндекс и управление аккаунтами появятся после подключения провайдера. Здесь только вымышленные профили.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun SettingsScreen(version: String, theme: String, setTheme: (String) -> Unit, catalog: CatalogState, setCatalog: (CatalogState) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Оформление", style = MaterialTheme.typography.titleMedium) }
        items(listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе")) { (id, label) ->
            Choice(label, theme == id, "theme_$id") { setTheme(id) }
        }
        item { HorizontalDivider(); Text("Состояние демокаталога", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
        items(CatalogState.entries) { item -> Choice(item.label, catalog == item, "state_${item.name}") { setCatalog(item) } }
        item { HorizontalDivider(); Text("YMPlayer 2 · $version", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp)) }
        item { Text("Самостоятельное приложение. Данные YMPlayer 1.x не используются. В M1 нет звука, сетевых запросов, импорта файлов и обновлятора.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Диагностика: демонстрационный режим. Каталог локальный, аудиодвижок не подключён.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Choice(label: String, selected: Boolean, tag: String, click: () -> Unit) {
    Surface(click, Modifier.fillMaxWidth().prismFocus().testTag(tag).semantics { this.selected = selected; role = Role.RadioButton }, shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null)
            Text(label)
        }
    }
}

@Composable internal fun MessageScreen(title: String, subtitle: String, text: String, icon: ImageVector) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary) }
        item { Text(subtitle, style = MaterialTheme.typography.titleLarge) }
        item { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
