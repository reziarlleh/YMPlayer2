package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun SkinsScreen(repository: SkinRepository, import: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    var lightPreview by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<SkinChoice?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("skins_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(tr(Msg.msg_6dd43bf971fd), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(tr(Msg.msg_de13383c3766, state.active.name), Modifier.testTag("skin_active"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OutlinedButton(import, Modifier.fillMaxWidth().prismFocus().testTag("skin_import"), enabled = !state.busy) { SkinIcon(UiIcon.ADD, null); Spacer(Modifier.width(8.dp)); Text(tr(Msg.msg_9065b44df928)) } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.issue?.let { item { Text(trMessage(it), Modifier.testTag("skin_issue"), color = MaterialTheme.colorScheme.error) } }
        state.preview?.let { preview ->
            item {
                Text(tr(Msg.msg_c05d6b17ae05, preview.skin.name), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("skin_preview_name"))
                Text(preview.author, style = MaterialTheme.typography.bodySmall)
                if (preview.description.isNotEmpty()) Text(preview.description, style = MaterialTheme.typography.bodySmall)
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!lightPreview, { lightPreview = false }, { Text(tr(Msg.msg_21943a44634e)) }, Modifier.prismFocus().testTag("skin_preview_dark"))
                FilterChip(lightPreview, { lightPreview = true }, { Text(tr(Msg.msg_5405eb821e3a)) }, Modifier.prismFocus().testTag("skin_preview_light"))
            } }
            item { SkinPreview(preview.skin, lightPreview) }
            item { Text(tr(Msg.msg_173c0a00334a), style = MaterialTheme.typography.bodySmall) }
            item { Button({ repository.applyPreview() }, Modifier.fillMaxWidth().prismFocus().testTag("skin_apply"), enabled = !state.busy) { Text(tr(Msg.msg_e15f960d93eb)) } }
            item { OutlinedButton({ repository.cancelPreview() }, Modifier.fillMaxWidth().prismFocus().testTag("skin_cancel"), enabled = !state.busy) { Text(tr(Msg.msg_8fbe9b75cbdf)) } }
        }
        items(state.choices, key = { it.skin.id }) { choice ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(choice.skin.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        if (choice.skin.id == state.active.id) SkinIcon(UiIcon.CHECK, tr(Msg.msg_54b80039a9ec))
                    }
                    Text(choice.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ repository.preview(choice.skin.id) }, Modifier.fillMaxWidth().prismFocus().testTag("skin_choose_${choice.skin.id}"), enabled = !state.busy) { Text(tr(Msg.msg_d4998bc84141)) }
                    if (choice.removable && choice.skin.id != state.active.id) TextButton({ remove = choice }, Modifier.prismFocus().testTag("skin_remove_${choice.skin.id}"), enabled = !state.busy) { Text(tr(Msg.msg_be99b1361201)) }
                }
            }
        }
        item { OutlinedButton({ repository.restore() }, Modifier.fillMaxWidth().prismFocus().testTag("skin_restore"), enabled = !state.busy) { Text(tr(Msg.msg_66c5d76f8451)) } }
    }
    remove?.let { choice -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(tr(Msg.msg_f91135efb9aa, choice.skin.name)) }, text = { Text(tr(Msg.msg_d73874ba53a6)) },
        confirmButton = { TextButton({ repository.remove(choice.skin.id); remove = null }, Modifier.prismFocus()) { Text(tr(Msg.msg_be99b1361201)) } },
        dismissButton = { TextButton({ remove = null }, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } }) }
}

@Composable private fun SkinPreview(skin: AppSkin, light: Boolean) {
    PrismTheme(if (light) "light" else "dark", skin, systemBars = false) {
        Card(Modifier.fillMaxWidth().testTag("skin_preview"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Msg.msg_f4d6290e6f4b), style = MaterialTheme.typography.titleLarge)
                Text(tr(Msg.msg_126edfaa6bc7), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { .4f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    SkinIcon(UiIcon.PREVIOUS, null)
                    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.large) { SkinIcon(UiIcon.PLAY, null, Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    SkinIcon(UiIcon.NEXT, null)
                    SkinIcon(UiIcon.FAVORITE, null, tint = MaterialTheme.colorScheme.primary)
                }
                Text(tr(Msg.msg_501cfd0b1690), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
