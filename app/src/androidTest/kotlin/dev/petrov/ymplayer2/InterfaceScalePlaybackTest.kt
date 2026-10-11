package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.InterfaceScalePreferences
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceScalePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private var original = 100
    @After fun restore() {
        compose.runOnIdle { InterfaceScalePreferences(compose.activity).save(original); compose.activity.harness.player.stop() }
    }
    @Test fun liveScalePreservesRealAudioPositionQueueAndEngine() {
        val harness = compose.activity.harness
        val player = harness.player
        original = InterfaceScalePreferences(compose.activity).read()
        compose.runOnIdle { InterfaceScalePreferences(compose.activity).save(100) }
        compose.waitUntil(25000) { harness.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { harness.library.state.value.roots.forEach { harness.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { harness.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        compose.waitUntil(20000) { harness.library.testTracks.size == 2 }
        compose.runOnIdle { player.playQueue(harness.library.testTracks.map { it.id }) }
        compose.waitUntil(15000) { player.state.value.positionSeconds >= 2 }
        val before = player.state.value
        val engineField = OnlineHarness::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val engine = compose.runOnIdle { engineField.get(harness) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_scale").performScrollTo().performClick()
        compose.onNodeWithTag("scale_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(130f) }
        compose.onNodeWithTag("scale_value").assertTextEquals("130%")
        compose.waitUntil(15000) { player.state.value.positionSeconds >= before.positionSeconds + 2 }
        assertEquals(before.current?.id, player.state.value.current?.id)
        assertEquals(before.queue, player.state.value.queue)
        assertTrue(player.state.value.playing)
        assertTrue(player.state.value.connected)
        compose.runOnIdle { assertSame("Scaling must not replace the audio decoder", engine, engineField.get(harness)) }
        compose.onNodeWithTag("nav_player").performClick()
        compose.onNodeWithTag("player_play").assertContentDescriptionEquals("Пауза")
    }
}
