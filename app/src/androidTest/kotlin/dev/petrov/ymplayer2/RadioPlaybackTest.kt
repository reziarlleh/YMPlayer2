@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.Player
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    private fun waitFor(test: () -> Boolean) {
        try { compose.waitUntil(25000, test) }
        catch (e: Throwable) { throw AssertionError("Radio=${f.radio.state.value}; Music=${f.player.state.value}; requests=${f.radioApi.requests}", e) }
    }
    @Before fun prepare() {
        waitFor { f.library.state.value.ready && f.player.state.value.connected }
        compose.runOnIdle { f.player.stop(); f.player.switchProfile("owner"); f.player.clearQueue() }
        runBlocking { f.library.state.value.roots.forEach { f.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { f.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { f.library.testTracks.size == 2 && f.radio.state.value.profileId == "owner" }
    }
    @After fun stop() { compose.runOnIdle { f.radio.release(); f.player.stop() } }
    @Test fun tabsGlobalSearchAndStationCollectionAreUsable() {
        compose.onNodeWithTag("nav_radio").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("radio_station_one").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("radio_station_one").performClick()
        waitFor { f.radio.state.value.playing && f.radio.state.value.onAir.title.isNotBlank() }
        compose.onNodeWithTag("radio_like").performClick()
        waitFor { "one" in f.radioCatalog.state.value.favouriteSlugs }
        compose.onNodeWithTag("radio_favourites").assertExists()
        compose.onNodeWithTag("radio_like").performClick()
        waitFor { f.radioCatalog.state.value.favouriteSlugs.isEmpty() }
        compose.onNodeWithTag("radio_tab_CITIES").performClick()
        compose.onNodeWithTag("radio_city_moscow").performClick()
        waitFor { f.radioCatalog.state.value.filter?.slug == "moscow" && !f.radioCatalog.state.value.busy }
        compose.onNodeWithTag("radio_search").performTextInput("Рок")
        waitFor { f.radioCatalog.state.value.stations.singleOrNull()?.slug == "two" }
        compose.onNodeWithTag("radio_stop").performClick()
        waitFor { !f.radio.state.value.playing && !f.radio.state.value.buffering }
        compose.onNodeWithTag("radio_play").assertIsEnabled().performClick()
        waitFor { f.radio.state.value.playing }
    }
    @Test fun radioBorrowsEngineWithoutOverwritingMusicCheckpointAndSessionModes() {
        val tracks = f.library.testTracks
        compose.runOnIdle { f.player.playQueue(tracks.map { it.id }, tracks.first().id) }
        waitFor { f.player.state.value.current?.id == tracks.first().id }
        waitFor { f.player.state.value.positionSeconds >= 2 }
        compose.runOnIdle { f.radio.play(f.radioApi.one) }
        waitFor { f.radio.state.value.playing }
        assertFalse(f.player.state.value.playing)
        val checkpoint = f.checkpointText()
        compose.runOnIdle {
            val session = f.systemPlayer
            assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertFalse(session.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
            assertFalse(session.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
            session.pause()
        }
        waitFor { !f.radio.state.value.playing }
        compose.runOnIdle { f.systemPlayer.play() }
        waitFor { f.radio.state.value.playing }
        assertEquals(checkpoint, f.checkpointText())
        compose.runOnIdle { f.player.toggle() }
        waitFor { f.player.state.value.playing && f.player.state.value.positionSeconds >= 2 }
        assertFalse(f.radio.state.value.ownsOutput)
        assertEquals(tracks.first().id, f.player.state.value.current?.id)
    }
    @Test fun stopAndProfileChangeCancelPendingStreamAndReconnect() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { f.radioApi.gate = gate; f.radio.play(f.radioApi.one) }
        waitFor { f.radioApi.requests > 0 }
        compose.runOnIdle { f.radio.stop(); gate.complete(Unit) }
        compose.waitForIdle(); assertFalse(f.radio.state.value.playing)
        compose.runOnIdle { f.radioApi.gate = null; f.radioApi.failures = 1; f.radio.play(f.radioApi.one) }
        waitFor { f.radio.state.value.reconnecting }
        compose.runOnIdle { f.player.switchProfile("road") }
        waitFor { f.radio.state.value.profileId == "road" }
        Thread.sleep(2500)
        assertFalse(f.radio.state.value.playing); assertFalse(f.radio.state.value.ownsOutput)
    }
    @Test fun rotationRetainsStationAndStreamAndPlayerControls() {
        compose.onNodeWithTag("nav_radio").performClick()
        compose.runOnIdle { f.radio.play(f.radioApi.one) }
        waitFor { f.radio.state.value.playing }
        val requests = f.radioApi.requests
        capture("radio-before-rotation.png")
        compose.runOnIdle { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("radio_screen").assertExists()
        waitFor { f.radio.state.value.playing }
        assertEquals(requests, f.radioApi.requests)
        compose.onNodeWithTag("radio_stop").assertIsDisplayed()
        capture("radio-landscape.png")
        compose.onNodeWithTag("radio_stop").performClick()
    }
    private fun capture(name: String) {
        val file = java.io.File(compose.activity.getExternalFilesDir(null), name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun remoteFocusVisitsSelectedTabAndStationAndControls() {
        org.junit.Assume.assumeTrue(compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
        compose.onNodeWithTag("nav_radio").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("radio_station_one").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("radio_tab_COLLECTION").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("radio_tab_COLLECTION").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("radio_tab_CITIES").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("radio_tab_COLLECTION").assertIsFocused()
        compose.onNodeWithTag("radio_station_one").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("radio_station_one").performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor { f.radio.state.value.playing }
        compose.onNodeWithTag("radio_stop").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("radio_stop").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("radio_like").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor { "one" in f.radioCatalog.state.value.favouriteSlugs }
    }
    @Test fun recoverableFailureResolvesFreshStreamAndUpdatesOnAir() {
        compose.runOnIdle { f.radioApi.failures = 1; f.radio.play(f.radioApi.one) }
        waitFor { f.radio.state.value.playing && f.radio.state.value.onAir.title.isNotEmpty() }
        assertEquals(2, f.radioApi.requests); assertFalse(f.radio.state.value.reconnecting)
    }
}
