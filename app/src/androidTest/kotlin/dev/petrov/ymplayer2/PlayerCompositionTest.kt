@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerCompositionTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(25000, condition)
    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue(); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.state.value.tracks.size == 2 && fixture.taste.state.value.shelf(TasteKind.ARTIST).ready }
        compose.runOnIdle { fixture.collaborators = true; fixture.longLabels = true; fixture.online.collection() }
        waitFor { fixture.online.state.value.loaded }
        compose.runOnIdle { player.playQueue(fixture.online.state.value.entries.map { it.id }) }
        waitFor { player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.toggle() }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun fixedControls(wave: Boolean = false) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
        val viewport = compose.onNodeWithTag("player_viewport").fetchSemanticsNode().boundsInRoot
        val tags = listOf("player_equalizer", "player_queue", "player_more", "progress", "current_title", "position",
            "player_play", "player_previous", "player_next", "player_stop", "player_artist_5", "player_artist_8", "player_taste_TRACK_1_like", "player_taste_TRACK_1_block") +
            if (wave) listOf("wave_mode") else listOf("repeat_mode", "shuffle_mode")
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag clipped: $bounds outside $viewport", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1 && bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1)
        }
        compose.onNodeWithTag("player_play").assertWidthIsAtLeast(80.dp).assertHeightIsAtLeast(80.dp)
        for (tag in listOf("player_previous", "player_next", "player_stop")) compose.onNodeWithTag(tag).assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(64.dp)
    }
    private fun screenshot(prefix: String) {
        val label = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("compositionScreenshot") ?: return
        require(label.matches(Regex("[a-z0-9-]+")))
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "composition-$prefix-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun fixedPlayerFitsViewportAndUsesLargerTransport() {
        screenshot("player")
        fixedControls()
        compose.onNodeWithTag("player_taste_ARTIST_5_like").assertDoesNotExist()
        compose.onNodeWithTag("repeat_mode").performClick()
        compose.onNodeWithTag("shuffle_mode").performClick()
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
        compose.onNodeWithTag("player_equalizer").performClick()
        assertEquals(listOf(false), fixture.equalizerRequests)
        assertFalse(player.state.value.playing)
    }
    @Test fun artistCardHasPopularTracksAlbumsAndHierarchicalUpWithoutChangingAudio() {
        val before = player.state.value
        compose.onNodeWithTag("player_artist_5").performClick()
        waitFor { fixture.online.state.value.request.entity?.kind == MusicKind.ARTISTS && fixture.online.state.value.loaded }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("artist_section_TRACKS"))
        compose.onNodeWithTag("artist_section_TRACKS").assertIsSelected()
        compose.onNodeWithTag("artist_section_ALBUMS").performClick()
        waitFor { fixture.online.state.value.entries.firstOrNull()?.entity?.kind == MusicKind.ALBUMS }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_entity_7"))
        compose.onNodeWithTag("online_entity_7").performClick()
        waitFor { fixture.online.state.value.request.entity?.kind == MusicKind.ALBUMS && fixture.online.state.value.loaded }
        compose.onNodeWithTag("navigate_up").performClick()
        waitFor { fixture.online.state.value.request.entity?.kind == MusicKind.ARTISTS }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("artist_section_ALBUMS"))
        compose.onNodeWithTag("artist_section_ALBUMS").assertIsSelected()
        screenshot("artist")
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("player_play").assertIsDisplayed()
        assertNull(fixture.online.state.value.request.entity)
        assertEquals(before.current?.id, player.state.value.current?.id)
        assertEquals(before.positionSeconds, player.state.value.positionSeconds)
        assertFalse(player.state.value.playing)
    }
    @Test fun waveUsesSameFixedLayoutWithoutManualQueueModes() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.queue.size >= 2 }
        screenshot("wave")
        fixedControls(wave = true)
        compose.onNodeWithTag("repeat_mode").assertDoesNotExist()
        compose.onNodeWithTag("shuffle_mode").assertDoesNotExist()
    }
}
