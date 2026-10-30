@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** The request happens BEFORE catalog.ready; UI and actual decoder are asserted separately. */
@RunWith(AndroidJUnit4::class)
class OfflineColdSwitchTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private val owner = OfflineOwner("owner","1")
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun control(method: String, arg: String? = null): Bundle = h.context.contentResolver.call(
        Uri.parse("content://dev.petrov.ymplayer2.test.control"),method,arg,null) ?: Bundle.EMPTY
    private fun waitFor(timeout: Long = 25_000, predicate: () -> Boolean) = compose.waitUntil(timeout,predicate)
    @Before fun prepare() {
        waitFor { h.library.state.value.ready && !h.library.state.value.scanning && player.state.value.connected }
        control("storage-reset")
        compose.runOnIdle {
            player.stop(); player.switchProfile("owner"); player.chooseSource(null); player.clearQueue(); h.offline.setEnabled(true)
            h.context.getSharedPreferences("playback",0).edit().remove("offlineState:owner:1").remove("offlineOrder:owner:1").commit()
            h.cacheLocation.move(null)
        }
        waitFor { !h.cacheLocation.state.value.moving && h.offline.state.value.owner==owner && h.offline.state.value.ready }
        compose.runOnIdle { h.offline.clear() }
        waitFor { h.offline.state.value.ready && h.offline.state.value.tracks.isEmpty() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        control("fixtures"); runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(),Source.LOCAL) }
        waitFor { h.library.testTracks.size==2 }
        compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK]=TasteList(setOf("1","2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { h.taste.state.value.shelf(TasteKind.TRACK).list.liked==setOf("1","2") && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
        compose.runOnIdle { h.offline.sync() }
        waitFor { !h.offline.state.value.running && h.offline.state.value.tracks.size==2 }
        compose.runOnIdle { h.cacheLocation.move(TestStorageProvider.tree.toString()) }
        waitFor { !h.cacheLocation.state.value.moving && h.offline.state.value.ready }
        compose.onNodeWithTag("nav_player").performClick()
    }
    @After fun cleanup() {
        control("storage-catalog-gate","open"); control("releaseScan")
        compose.runOnIdle { player.stop(); h.offline.cancel(); h.offline.setEnabled(true) }
        waitFor { !h.library.state.value.scanning }
        compose.runOnIdle { h.cacheLocation.move(null) }
        waitFor { !h.cacheLocation.state.value.moving }
        work.cancel()
    }
    private fun holdColdCatalog() {
        control("storage-catalog-gate","children")
        compose.runOnIdle { h.offline.setEnabled(false); h.offline.setEnabled(true) }
        waitFor(5_000) { !h.offline.state.value.ready && control("storage-catalog-stats").getInt("waiters")>0 }
    }
    private fun assertActualOfflineAudio() {
        waitFor(7_000) {
            var decoding=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { decoding=h.systemPlayer.isPlaying }
            player.state.value.origin.source==PlaybackSource.OFFLINE && decoding && player.state.value.positionSeconds>=1
        }
    }
    @Test fun firstTapShowsOfflineSelectionAndProgressBeforeCatalogIsReady() {
        holdColdCatalog()
        compose.onNodeWithTag("player_source_offline").performClick()
        compose.onNodeWithTag("player_source_offline").assertIsSelected()
        compose.onNodeWithTag("player_source_label").assertTextEquals("Оффлайн")
        compose.onNodeWithTag("player_source_loading").assertExists()
        // The UI can process another real button while provider IO is still blocked.
        compose.onNodeWithTag("player_queue").assertIsEnabled()
        assertFalse(h.offline.state.value.ready)
        control("storage-catalog-gate","open")
        assertActualOfflineAudio()
        compose.onNodeWithTag("player_source_loading").assertDoesNotExist()
    }
    @Test fun explicitStopCancelsColdOfflineIntentWithoutLateAutoplay() {
        holdColdCatalog(); compose.onNodeWithTag("player_source_offline").performClick()
        compose.onNodeWithTag("player_source_loading").assertExists()
        compose.runOnIdle { player.stop() }
        compose.onNodeWithTag("player_source_loading").assertDoesNotExist()
        control("storage-catalog-gate","open")
        waitFor { h.offline.state.value.ready }
        compose.waitForIdle(); assertFalse(player.state.value.playing)
        assertNotEquals(PlaybackSource.OFFLINE,player.state.value.origin.source)
    }
    @Test fun anotherProfileCannotCompleteAnOldColdOfflineRequest() {
        holdColdCatalog(); compose.onNodeWithTag("player_source_offline").performClick()
        compose.onNodeWithTag("player_source_loading").assertExists()
        compose.runOnIdle { player.switchProfile("road") }
        control("storage-catalog-gate","open")
        waitFor { player.state.value.profileId=="road" }
        compose.onNodeWithTag("player_source_loading").assertDoesNotExist()
        assertNotEquals(PlaybackSource.OFFLINE,player.state.value.origin.source)
    }
    @Test fun cachedAudioDoesNotWaitForAnUnrelatedLocalScanAfterEngineAttach() {
        control("holdScan"); work.launch { h.library.refresh() }
        assertTrue("Local scan gate entered",control("waitScan").getBoolean("entered"))
        compose.runOnIdle { h.restartEngine(); player.playOffline() }
        assertActualOfflineAudio()
        assertTrue("Offline must be audible while the other storage scan is still held",h.library.state.value.scanning)
        control("releaseScan")
    }
    @Test fun outgoingWaveEndCannotCancelThePendingOfflineSelection() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.current != null && player.state.value.playing }
        holdColdCatalog(); compose.onNodeWithTag("player_source_offline").performClick()
        compose.onNodeWithTag("player_source_loading").assertExists()
        // Real playback callback while catalog IO is held, rather than a user Next command.
        compose.runOnIdle { h.systemPlayer.seekTo(h.systemPlayer.duration) }
        Thread.sleep(750); compose.waitForIdle()
        compose.onNodeWithTag("player_source_offline").assertIsSelected()
        compose.onNodeWithTag("player_source_loading").assertExists()
        control("storage-catalog-gate","open")
        assertActualOfflineAudio()
    }
}
