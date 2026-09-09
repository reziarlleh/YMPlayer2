package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.RepeatMode
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.designsystem.skin.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkinPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<SkinTestActivity>()
    @Test fun changedPaletteAndVectorKeepAudioAndFallbackControlsWorking() {
        val graph = compose.activity.application as PlayerApplication
        val player = graph.playback
        val library = graph.library
        compose.waitUntil(15000) { library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.chooseSource(null); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        compose.waitUntil(15000) { player.state.value.queue.size == 2 }
        compose.onNodeWithTag("player_play").performScrollTo().performClick()
        compose.waitUntil(15000) { player.state.value.positionSeconds >= 2 }
        val before = player.state.value
        val accent = Color(0xFFAA33EE)
        val custom = PrismSkin.copy(id = "instrumentation", dark = PrismSkin.dark.copy(primary = accent), light = PrismSkin.light.copy(primary = accent),
            icons = mapOf(UiIcon.PAUSE to PrismIcons.getValue(UiIcon.STOP)))
        assertEquals(UiIcon.entries.toSet(), PrismIcons.keys)
        assertSame(PrismIcons.getValue(UiIcon.PLAY), custom.icon(UiIcon.PLAY))
        compose.runOnIdle { compose.activity.skin = custom }
        compose.onNodeWithTag("player_play").assertContentDescriptionEquals("Пауза")
        val pixels = compose.onNodeWithTag("player_play").captureToImage().toPixelMap()
        var accentPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y] == accent) accentPixels++
        assertTrue("Custom palette must reach the rendered control", accentPixels > 20)
        compose.waitUntil(15000) { player.state.value.positionSeconds >= before.positionSeconds + 2 }
        assertEquals(before.current?.id, player.state.value.current?.id)
        assertEquals(before.queue, player.state.value.queue)
        assertTrue(player.state.value.playing)
        compose.onNodeWithTag("player_play").performClick()
        compose.waitUntil(15000) { !player.state.value.playing }
        compose.onNodeWithTag("player_play").assertContentDescriptionEquals("Воспроизвести")
        val paused = player.state.value
        compose.runOnIdle { compose.activity.skin = custom.copy(contractVersion = 99) }
        compose.waitForIdle()
        assertEquals(paused, player.state.value)
        val fallback = compose.onNodeWithTag("player_play").captureToImage().toPixelMap()
        assertFalse((0 until fallback.width).any { x -> (0 until fallback.height).any { y -> fallback[x, y] == accent } })
        compose.onNodeWithTag("player_play").performClick()
        compose.waitUntil(15000) { player.state.value.playing }
        compose.runOnIdle { player.stop() }
    }
}
