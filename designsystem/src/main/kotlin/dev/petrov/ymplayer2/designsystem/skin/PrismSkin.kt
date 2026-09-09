package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

// The editable base skin: palettes, typography, shapes and vector bindings.
val PrismDark = darkColorScheme(
    primary = Color(0xFF67DCF5), onPrimary = Color(0xFF003642), secondary = Color(0xFFEA94E0),
    background = Color(0xFF10131B), surface = Color(0xFF191E2A), surfaceVariant = Color(0xFF232A39),
    onBackground = Color(0xFFF2F5FC), onSurface = Color(0xFFF2F5FC), onSurfaceVariant = Color(0xFFB2BDD0),
    primaryContainer = Color(0xFF153C4A), onPrimaryContainer = Color(0xFFC2F2FC),
    secondaryContainer = Color(0xFF3C2D41), onSecondaryContainer = Color(0xFFF8C6F1),
)
val PrismLight = lightColorScheme(
    primary = Color(0xFF006D85), onPrimary = Color.White, secondary = Color(0xFF922682),
    background = Color(0xFFF3F5FA), surface = Color.White, surfaceVariant = Color(0xFFE8EDF5),
    onBackground = Color(0xFF151B2A), onSurface = Color(0xFF151B2A), onSurfaceVariant = Color(0xFF526078),
    primaryContainer = Color(0xFFD4EEF4), onPrimaryContainer = Color(0xFF073E4C),
    secondaryContainer = Color(0xFFF2DFF0), onSecondaryContainer = Color(0xFF6D2563),
)

val PrismSkin = AppSkin(
    id = "prism", name = "PRISM",
    dark = PrismDark, light = PrismLight, icons = PrismIcons,
    artwork = ArtworkPalette(
        listOf(Color(0xFF146578), Color(0xFF593867), Color(0xFF315882), Color(0xFF296653), Color(0xFF76464C), Color(0xFF4B5361)),
        Color(0xFF141C30),
    ),
)
