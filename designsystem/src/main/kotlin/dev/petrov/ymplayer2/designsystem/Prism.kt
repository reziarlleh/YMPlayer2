package dev.petrov.ymplayer2.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.petrov.ymplayer2.designsystem.skin.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity

@Composable fun PrismTheme(mode: String = "system", skin: AppSkin = PrismSkin, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val resolved = skin.takeIf { it.contractVersion == 1 } ?: PrismSkin
    CompositionLocalProvider(LocalSkin provides resolved) {
        MaterialTheme(colorScheme = if (dark) resolved.dark else resolved.light, typography = resolved.typography, shapes = resolved.shapes) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** A border, not scaling: focus never changes layout or obscures its neighbours. */
@Composable fun Modifier.prismFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.hasFocus }.border(
        2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium,
    )
}

@Composable fun ActionIcon(
    icon: UiIcon, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false,
) {
    if (primary) FilledIconButton(onClick, modifier.size(64.dp).prismFocus(), enabled = enabled) {
        SkinIcon(icon, label, Modifier.size(32.dp))
    } else IconButton(onClick, modifier.size(48.dp).prismFocus(), enabled = enabled) { SkinIcon(icon, label) }
}

/** Decorative placeholder from the current skin; provider covers will replace it when available. */
@Composable fun DemoArtwork(tint: Int, modifier: Modifier = Modifier) {
    val palette = LocalSkin.current.artwork
    val color = palette.backgrounds[Math.floorMod(tint, palette.backgrounds.size)]
    Box(modifier.clip(MaterialTheme.shapes.large).background(Brush.linearGradient(listOf(color, palette.end)))) {
        SkinIcon(UiIcon.ARTWORK, null, Modifier.fillMaxSize(), tint = Color.Unspecified)
    }
}

@Composable fun SkinIcon(role: UiIcon, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(LocalSkin.current.icon(role), contentDescription, modifier, tint = tint)
}
