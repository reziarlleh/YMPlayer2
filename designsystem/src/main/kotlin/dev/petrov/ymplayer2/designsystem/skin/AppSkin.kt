package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** Visual roles only. Commands, labels, hit targets and navigation stay in the UI. */
enum class UiIcon {
    PLAYER, LIBRARY, SEARCH, CLIPS, PROFILE, GUEST, SETTINGS, BACK, FORWARD,
    PLAY, PAUSE, STOP, PREVIOUS, NEXT, QUEUE, NOW_PLAYING, FOLDER, EXPAND,
    CLOSE, CHECK, CHOICE_ON, CHOICE_OFF, REPEAT, REPEAT_ONE, SHUFFLE,
    EDIT, UP, DOWN, REMOVE, ADD_QUEUE, CLEAR_QUEUE, ARTWORK,
    FAVORITE, FAVORITE_OFF, MORE, PLAYLIST, ADD, BLOCK, WAVE,
    DISLIKE, DISLIKE_OFF, UNKNOWN, REFRESH, EQUALIZER,
}

data class ArtworkPalette(val backgrounds: List<Color>, val end: Color) {
    init { require(backgrounds.isNotEmpty()) }
}

/** Built-in data contract. External package parsing/validation belongs to M11. */
data class AppSkin(
    val id: String,
    val name: String,
    val dark: ColorScheme,
    val light: ColorScheme,
    val icons: Map<UiIcon, ImageVector>,
    val artwork: ArtworkPalette,
    val typography: Typography = Typography(),
    val shapes: Shapes = Shapes(),
    val contractVersion: Int = 1,
) {
    fun icon(role: UiIcon): ImageVector = icons[role] ?: PrismIcons.getValue(role)
}

val LocalSkin = staticCompositionLocalOf { PrismSkin }
