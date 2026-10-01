package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

// Oxide: approved palette #1. Historical id is kept for compatibility.
val PrismDark = darkColorScheme(
    primary = Color(0xFFD77A50), onPrimary = Color(0xFF191715), secondary = Color(0xFFB6B19A),
    background = Color(0xFF191715), surface = Color(0xFF282420), surfaceVariant = Color(0xFF36302A),
    onBackground = Color(0xFFEFE5D7), onSurface = Color(0xFFEFE5D7), onSurfaceVariant = Color(0xFFB6AA9A),
    primaryContainer = Color(0xFF533426), onPrimaryContainer = Color(0xFFFFDBC8),
    secondaryContainer = Color(0xFF454337), onSecondaryContainer = Color(0xFFE7E1C9),
    tertiary = Color(0xFFCBAA7B), onTertiary = Color(0xFF352715),
    tertiaryContainer = Color(0xFF4E3C24), onTertiaryContainer = Color(0xFFF5DFC3),
    outline = Color(0xFF938676), outlineVariant = Color(0xFF554A3F), surfaceTint = Color(0xFFD77A50),
    inverseSurface = Color(0xFFEFE5D7), inverseOnSurface = Color(0xFF36302A), inversePrimary = Color(0xFF98431F),
    surfaceDim = Color(0xFF191715), surfaceBright = Color(0xFF403930),
    surfaceContainerLowest = Color(0xFF141210), surfaceContainerLow = Color(0xFF221F1B),
    surfaceContainer = Color(0xFF282420), surfaceContainerHigh = Color(0xFF302B25), surfaceContainerHighest = Color(0xFF36302A),
)
val PrismLight = lightColorScheme(
    primary = Color(0xFF98431F), onPrimary = Color(0xFFFFFAF2), secondary = Color(0xFF626044),
    background = Color(0xFFEEE6DA), surface = Color(0xFFFFFAF2), surfaceVariant = Color(0xFFE3D9C8),
    onBackground = Color(0xFF29231E), onSurface = Color(0xFF29231E), onSurfaceVariant = Color(0xFF685C50),
    primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF533426),
    secondaryContainer = Color(0xFFE7E1C9), onSecondaryContainer = Color(0xFF454337),
    tertiary = Color(0xFF795A30), onTertiary = Color(0xFFFFFAF2),
    tertiaryContainer = Color(0xFFF5DFC3), onTertiaryContainer = Color(0xFF4E3C24),
    outline = Color(0xFF837568), outlineVariant = Color(0xFFD4C6B6), surfaceTint = Color(0xFF98431F),
    inverseSurface = Color(0xFF36302A), inverseOnSurface = Color(0xFFEFE5D7), inversePrimary = Color(0xFFD77A50),
    surfaceDim = Color(0xFFDFD5C7), surfaceBright = Color(0xFFFFFAF2),
    surfaceContainerLowest = Color(0xFFFFFAF2), surfaceContainerLow = Color(0xFFF6F0E6),
    surfaceContainer = Color(0xFFEEE6DA), surfaceContainerHigh = Color(0xFFE8DFD1), surfaceContainerHighest = Color(0xFFE3D9C8),
)

val PrismSkin = AppSkin(
    id = "prism", name = "Оксид",
    dark = PrismDark, light = PrismLight, icons = PrismIcons,
    artwork = ArtworkPalette(
        listOf(Color(0xFF533426), Color(0xFF454337), Color(0xFF494038), Color(0xFF524536), Color(0xFF49352D), Color(0xFF36302A)),
        Color(0xFF191715),
    ),
)
