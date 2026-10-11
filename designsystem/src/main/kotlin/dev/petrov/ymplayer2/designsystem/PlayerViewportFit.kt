package dev.petrov.ymplayer2.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/** A finite player must retain every transport control even at 200% on a small window. */
@Composable fun PlayerViewportFit(content: @Composable () -> Unit) {
    if ((LocalInterfaceScale.current?.percent ?: 100) <= 100) { content(); return }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight * 1.25f
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val factor = minOf(1f, maxWidth.value / if (landscape) 552f else 320f,
            maxHeight.value / ((if (landscape) 280f else 380f) * maxOf(1f, density.fontScale))).coerceAtLeast(.1f)
        val fittedDensity = remember(density, factor) { Density(density.density * factor, density.fontScale) }
        val fittedConfiguration = remember(configuration, factor) { Configuration(configuration).apply {
            screenWidthDp = (screenWidthDp / factor).roundToInt()
            screenHeightDp = (screenHeightDp / factor).roundToInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / factor).roundToInt()
            densityDpi = (densityDpi * factor).roundToInt()
        } }
        CompositionLocalProvider(LocalDensity provides fittedDensity, LocalConfiguration provides fittedConfiguration, content = content)
    }
}
