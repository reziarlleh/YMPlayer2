@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AudioQualityPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(30000, condition)
    @Before fun setup() {
        waitFor { h.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue(); h.audioQuality.setStream(AudioQuality.AUTO); h.audioQuality.setCache(AudioQuality.AUTO) }
        waitFor { h.offline.state.value.ready && h.offline.state.value.owner == OfflineOwner("owner", "1") }
        compose.runOnIdle { h.offline.clear() }; waitFor { h.offline.state.value.ready && h.offline.state.value.tracks.isEmpty() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 && h.taste.state.value.shelf(TasteKind.ARTIST).ready }
        h.resolvedQualities.clear()
    }
    @After fun stop() { compose.runOnIdle { h.offline.cancel(); player.stop(); h.audioQuality.setStream(AudioQuality.AUTO); h.audioQuality.setCache(AudioQuality.AUTO) } }
    private fun openSettings() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_quality"))
        compose.onNodeWithTag("settings_quality").performClick()
    }
    private fun choose(cache: Boolean, quality: AudioQuality) {
        val button = if (cache) "quality_cache" else "quality_stream"
        compose.onNodeWithTag("quality_list").performScrollToNode(hasTestTag(button))
        compose.onNodeWithTag(button).performClick()
        compose.onNodeWithTag("quality_options").performScrollToNode(hasTestTag("quality_option_${quality.name}"))
        compose.onNodeWithTag("quality_option_${quality.name}").performClick()
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    private fun likeAndSync() {
        compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1", "2") && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
        compose.runOnIdle { h.offline.sync() }; waitFor { !h.offline.state.value.running && h.offline.state.value.tracks.size == 2 }
    }
    private fun file(id: String) = File(Uri.parse(h.offline.state.value.tracks.first { it.tasteTarget().key == id }.uri).path!!)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    @Test fun settingsPersistBothChoicesAndLeavePlayingAudioAlone() {
        val local = h.library.testTracks.first().id
        compose.runOnIdle { player.playQueue(listOf(local)) }; waitFor { player.state.value.positionSeconds >= 1 }
        val before = player.state.value.positionSeconds
        openSettings(); choose(false, AudioQuality.ECONOMY); choose(true, AudioQuality.HIGH)
        assertEquals(AudioQualities(AudioQuality.ECONOMY, AudioQuality.HIGH), h.restoredAudioQuality())
        assertTrue(player.state.value.playing); assertEquals(local, player.state.value.current?.id)
        assertTrue(player.state.value.positionSeconds >= before)
        snapshot("audio-quality.png")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("quality_list").assertExists()
        compose.onNodeWithTag("quality_list").performScrollToNode(hasTestTag("quality_cache"))
        compose.onNodeWithTag("quality_cache").assertTextContains(AudioQuality.HIGH.label)
        compose.onNodeWithTag("quality_cache").performClick()
        compose.onNodeWithTag("quality_options").performScrollToNode(hasTestTag("quality_option_HIGH"))
        compose.onNodeWithTag("quality_option_HIGH").assertIsSelected()
        snapshot("audio-quality-dialog.png")
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_list").assertExists()
    }
    @Test fun onlineRequestCapturesSelectedQualityAndNextRequestUsesNewChoice() {
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.ECONOMY); h.audioQuality.setCache(AudioQuality.MAX); h.online.search("fixture", MusicKind.TRACKS, false) }
        waitFor { h.online.state.value.loaded && !h.online.state.value.loading }
        val track = h.online.state.value.entries.first().track!!
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf(track.id to gate); player.playQueue(listOf(track.id)) }
        waitFor { h.resolvedQualities.isNotEmpty() }
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.HIGH) }; gate.complete(Unit)
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertEquals(AudioQuality.ECONOMY, h.resolvedQualities.first().second)
        compose.runOnIdle { player.stop(); player.playQueue(listOf(track.id)) }
        waitFor { h.resolvedQualities.size >= 2 && player.state.value.playing }
        assertEquals(AudioQuality.HIGH, h.resolvedQualities.last().second)
    }
    @Test fun offlineQualityIsIndependentAndHealthyFilesAreKeptUntilExplicitRemoval() {
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.ECONOMY); h.audioQuality.setCache(AudioQuality.HIGH) }
        likeAndSync()
        assertTrue(h.resolvedQualities.all { it.second == AudioQuality.HIGH })
        val first = hash(file("1")); val second = hash(file("2")); val requests = h.resolvedQualities.size
        compose.runOnIdle { h.audioQuality.setCache(AudioQuality.STANDARD); h.offline.sync() }
        waitFor { !h.offline.state.value.running }
        assertEquals(requests, h.resolvedQualities.size); assertEquals(first, hash(file("1"))); assertEquals(second, hash(file("2")))
        compose.runOnIdle { player.playQueue(h.offline.state.value.tracks.map(Track::id)) }
        waitFor { player.state.value.positionSeconds >= 1 }
        assertEquals(requests, h.resolvedQualities.size)
        compose.runOnIdle { player.stop(); h.offline.clear() }; waitFor { h.offline.state.value.ready && h.offline.state.value.tracks.isEmpty() }
        likeAndSync()
        assertEquals(listOf(AudioQuality.STANDARD, AudioQuality.STANDARD), h.resolvedQualities.drop(requests).map { it.second })
    }
    @Test fun waveKeepsAlreadyPreparedAudioAndUsesStreamQualityForFutureFiles() {
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.STANDARD); h.audioQuality.setCache(AudioQuality.ECONOMY); player.playMyWave() }
        waitFor { player.state.value.playing && "yandex:2:7" in player.bufferedWaveAudioIds() }
        assertTrue(h.resolvedQualities.all { it.second == AudioQuality.STANDARD })
        val requests = h.resolvedQualities.count { it.first == "yandex:2:7" }
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.HIGH); player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 && "yandex:3:7" in player.bufferedWaveAudioIds() }
        assertEquals(requests, h.resolvedQualities.count { it.first == "yandex:2:7" })
        assertEquals(AudioQuality.HIGH, h.resolvedQualities.last { it.first == "yandex:3:7" }.second)
        assertTrue(player.state.value.wave)
    }
}
