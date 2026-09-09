package dev.petrov.ymplayer2.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity

private val Dark = darkColorScheme(
    primary = Color(0xFF67DCF5), onPrimary = Color(0xFF003642), secondary = Color(0xFFEA94E0),
    background = Color(0xFF10131B), surface = Color(0xFF191E2A), surfaceVariant = Color(0xFF232A39),
    onBackground = Color(0xFFF2F5FC), onSurface = Color(0xFFF2F5FC), onSurfaceVariant = Color(0xFFB2BDD0),
    primaryContainer = Color(0xFF153C4A), onPrimaryContainer = Color(0xFFC2F2FC),
    secondaryContainer = Color(0xFF3C2D41), onSecondaryContainer = Color(0xFFF8C6F1),
)
private val Light = lightColorScheme(
    primary = Color(0xFF006D85), onPrimary = Color.White, secondary = Color(0xFF922682),
    background = Color(0xFFF3F5FA), surface = Color.White, surfaceVariant = Color(0xFFE8EDF5),
    onBackground = Color(0xFF151B2A), onSurface = Color(0xFF151B2A), onSurfaceVariant = Color(0xFF526078),
    primaryContainer = Color(0xFFD4EEF4), onPrimaryContainer = Color(0xFF073E4C),
    secondaryContainer = Color(0xFFF2DFF0), onSecondaryContainer = Color(0xFF6D2563),
)
@Composable fun PrismTheme(mode: String = "system", content: @Composable () -> Unit) {
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
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** A border, not scaling: focus never changes layout or obscures its neighbours. */
@Composable fun Modifier.prismFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.hasFocus }.border(
        2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp),
    )
}

@Composable fun ActionIcon(
    icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false,
) {
    if (primary) FilledIconButton(onClick, modifier.size(64.dp).prismFocus(), enabled = enabled) {
        Icon(icon, label, Modifier.size(32.dp))
    } else IconButton(onClick, modifier.size(48.dp).prismFocus(), enabled = enabled) { Icon(icon, label) }
}

/** Original geometric placeholder, deliberately not provider artwork. */
@Composable fun DemoArtwork(tint: Int, modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFF146578), Color(0xFF593867), Color(0xFF315882), Color(0xFF296653), Color(0xFF76464C), Color(0xFF4B5361))
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(colors[tint % colors.size], Color(0xFF141C30))))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .18f, size.height * .75f)
                lineTo(size.width * .48f, size.height * .18f)
                lineTo(size.width * .8f, size.height * .75f)
                close()
            }
            drawPath(path, Brush.linearGradient(listOf(Color(0xFF67DCF5), Color(0xFFEA94E0))), style = androidx.compose.ui.graphics.drawscope.Stroke(size.minDimension * .026f))
            drawLine(Color.White.copy(alpha = .25f), androidx.compose.ui.geometry.Offset(0f, size.height * .78f), androidx.compose.ui.geometry.Offset(size.width, size.height * .38f), size.minDimension * .016f)
        }
    }
}
