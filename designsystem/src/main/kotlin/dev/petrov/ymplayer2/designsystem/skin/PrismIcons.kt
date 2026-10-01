package dev.petrov.ymplayer2.designsystem.skin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

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
    UiIcon.EQUALIZER to Icons.Default.Tune,
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
    UiIcon.DRAG_HANDLE to Icons.Default.DragHandle,
    UiIcon.ADD_QUEUE to Icons.Default.PlaylistAdd,
    UiIcon.CLEAR_QUEUE to Icons.Default.ClearAll,
    UiIcon.FAVORITE to Icons.Default.Favorite,
    UiIcon.FAVORITE_OFF to Icons.Default.FavoriteBorder,
    UiIcon.BLOCK to Icons.Default.Block,
    UiIcon.DISLIKE to Icons.Default.ThumbDown,
    UiIcon.DISLIKE_OFF to Icons.Default.ThumbDownOffAlt,
    UiIcon.UNKNOWN to Icons.Default.HelpOutline,
    UiIcon.REFRESH to Icons.Default.Refresh,
    UiIcon.WAVE to Icons.Default.GraphicEq,
    UiIcon.MORE to Icons.Default.MoreVert,
    UiIcon.PLAYLIST to Icons.AutoMirrored.Filled.QueueMusic,
    UiIcon.ADD to Icons.Default.Add,
    UiIcon.BRAND to ymBrandLogo(Color.White),
    UiIcon.ARTWORK to ymBrandLogo(Color(0xFFD77A50)),
)
