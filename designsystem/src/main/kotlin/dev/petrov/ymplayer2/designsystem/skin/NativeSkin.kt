package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Native views consume plain visual data, just as Compose consumes ColorScheme. */
data class ClipPalette(
    val text: Int, val accent: Int, val onAccent: Int, val secondary: Int,
    val topShade: Int, val bottomShade: Int, val clearShade: Int,
    val controlSurface: Int, val currentPanel: Int, val nextPanel: Int,
)

// Video overlays always use the dark half of the chosen skin over moving video.
fun AppSkin.clipPalette(): ClipPalette = dark.let {
    ClipPalette(it.onSurface.toArgb(), it.primary.toArgb(), it.onPrimary.toArgb(), it.secondary.toArgb(),
        it.background.copy(alpha = .8f).toArgb(), it.background.copy(alpha = .9f).toArgb(),
        it.background.copy(alpha = 0f).toArgb(), it.surfaceVariant.copy(alpha = .6f).toArgb(),
        it.primaryContainer.copy(alpha = 221f / 255f).toArgb(), it.secondaryContainer.copy(alpha = 221f / 255f).toArgb())
}

data class SideBarPalette(val background: Int, val outline: Int, val buttonFill: Int, val buttonOutline: Int, val icon: Int)

// K4811 has an explicit white-control requirement; it is separate from media accents.
val PrismSideBarPalette: SideBarPalette get() = SideBarPalette(
    PrismSkin.dark.background.copy(alpha = 205f / 255f).toArgb(),
    Color.White.copy(alpha = 220f / 255f).toArgb(), Color.White.copy(alpha = 48f / 255f).toArgb(),
    Color.White.copy(alpha = 190f / 255f).toArgb(), Color.White.toArgb(),
)
