package dev.petrov.ymplayer2.designsystem

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource

/** Approved YM + Player2 outlines, with white/black lettering for the current surface. */
@Composable fun WideBrandLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painterResource(if (MaterialTheme.colorScheme.onSurface.luminance() > 0.5f) R.drawable.ym_wide_white else R.drawable.ym_wide_black),
        contentDescription, modifier,
    )
}
