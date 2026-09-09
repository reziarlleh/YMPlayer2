package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Edit/replace vectors here; feature screens use roles, never these assets. */
val PrismIcons: Map<UiIcon, ImageVector> = mapOf(
    UiIcon.PLAYER to Icons.Default.PlayCircle,
    UiIcon.LIBRARY to Icons.Default.LibraryMusic,
    UiIcon.SEARCH to Icons.Default.Search,
    UiIcon.CLIPS to Icons.Default.SmartDisplay,
    UiIcon.PROFILE to Icons.Default.AccountCircle,
    UiIcon.GUEST to Icons.Default.PersonOutline,
    UiIcon.SETTINGS to Icons.Default.Settings,
    UiIcon.BACK to Icons.AutoMirrored.Filled.ArrowBack,
    UiIcon.FORWARD to Icons.Default.ChevronRight,
    UiIcon.PLAY to Icons.Default.PlayArrow,
    UiIcon.PAUSE to Icons.Default.Pause,
    UiIcon.STOP to Icons.Default.Stop,
    UiIcon.PREVIOUS to Icons.Default.SkipPrevious,
    UiIcon.NEXT to Icons.Default.SkipNext,
    UiIcon.QUEUE to Icons.AutoMirrored.Filled.QueueMusic,
    UiIcon.NOW_PLAYING to Icons.Default.GraphicEq,
    UiIcon.FOLDER to Icons.Default.FolderOpen,
    UiIcon.EXPAND to Icons.Default.ExpandMore,
    UiIcon.CLOSE to Icons.Default.Close,
    UiIcon.CHECK to Icons.Default.Check,
    UiIcon.CHOICE_ON to Icons.Default.RadioButtonChecked,
    UiIcon.CHOICE_OFF to Icons.Default.RadioButtonUnchecked,
    UiIcon.REPEAT to Icons.Default.Repeat,
    UiIcon.REPEAT_ONE to Icons.Default.RepeatOne,
    UiIcon.SHUFFLE to Icons.Default.Shuffle,
    UiIcon.EDIT to Icons.Default.Edit,
    UiIcon.UP to Icons.Default.KeyboardArrowUp,
    UiIcon.DOWN to Icons.Default.KeyboardArrowDown,
    UiIcon.REMOVE to Icons.Default.RemoveCircleOutline,
    UiIcon.ADD_QUEUE to Icons.Default.PlaylistAdd,
    UiIcon.CLEAR_QUEUE to Icons.Default.ClearAll,
    UiIcon.ARTWORK to ImageVector.Builder("Prism artwork", 100.dp, 100.dp, 100f, 100f).apply {
        path(stroke = Brush.linearGradient(listOf(Color(0xFF67DCF5), Color(0xFFEA94E0)), Offset.Zero, Offset(100f, 100f)), strokeLineWidth = 2.6f) {
            moveTo(18f, 75f); lineTo(48f, 18f); lineTo(80f, 75f); close()
        }
        path(stroke = SolidColor(Color.White.copy(alpha = .25f)), strokeLineWidth = 1.6f) {
            moveTo(0f, 78f); lineTo(100f, 38f)
        }
    }.build(),
)
