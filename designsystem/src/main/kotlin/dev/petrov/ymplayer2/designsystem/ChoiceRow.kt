package dev.petrov.ymplayer2.designsystem

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/** Layout data supplied by focus events; reading it never starts another focus search. */
internal class ChoiceRowFocusPosition { var coordinates: LayoutCoordinates? = null }
internal class ChoiceRowFocusAnchor {
    var focused: ChoiceRowFocusPosition? = null
    fun centerX(): Float? = focused?.coordinates?.takeIf { it.isAttached }?.boundsInRoot()?.center?.x
}
internal val LocalChoiceRowFocusAnchor = staticCompositionLocalOf<ChoiceRowFocusAnchor?> { null }

/** Enter a wide choice row through its selected item, without activating it or changing selection. */
@Composable fun ChoiceRow(selectedKey: String, modifier: Modifier = Modifier, content: @Composable RowScope.((String) -> Modifier) -> Unit) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val children = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    val focusAnchor = LocalChoiceRowFocusAnchor.current
    var row by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(modifier.onGloballyPositioned { row = it }.focusProperties {
        val parent = row
        val incomingX = focusAnchor?.centerX()
        if (parent?.isAttached == true && incomingX != null) {
            // Use the incoming column as the row's entry area. Its full-width centre would
            // otherwise lose to a later button, especially when the selected item is far away.
            val x = (incomingX - parent.positionInRoot().x).coerceIn(0f, (parent.size.width - 1).coerceAtLeast(0).toFloat())
            focusRect = Rect(x, 0f, x + 1f, parent.size.height.toFloat())
        }
        onEnter = {
            if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) {
                if (children[selectedKey]?.isAttached == true) requesters[selectedKey]?.requestFocus()
            }
        }
    }.focusGroup().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content { key ->
            Modifier.focusRequester(requesters.getOrPut(key) { FocusRequester() }).onGloballyPositioned { children[key] = it }
        }
    }
}
