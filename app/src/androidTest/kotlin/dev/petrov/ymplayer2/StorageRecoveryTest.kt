package dev.petrov.ymplayer2

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.AudioService
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private val player get() = graph.playback
    private val library get() = graph.library
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000, condition)
    private fun provider(method: String, arg: String? = null) = graph.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, arg, null)
    private fun id(title: String) = library.testTracks.single { it.title == title }.id
    private fun restartService() {
        compose.runOnIdle { compose.activity.stopService(Intent(compose.activity, AudioService::class.java)) }
        waitFor { !player.state.value.connected }
        compose.runOnIdle { player.connect() }
        waitFor { player.state.value.connected }
    }
    @Before fun fixtures() {
        waitFor { library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.chooseSource(null); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        provider("fixtures")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.USB) }
        waitFor { player.state.value.queue.size == 2 }
    }
    @After fun stop() { compose.runOnIdle { player.stop() }; provider("unavailable", "false"); provider("missingSecond", "false") }

    @Test fun disconnectNotificationPreservesManualOrderPositionAndModesAcrossRestart() {
        val one = id("one"); val two = id("two")
        compose.runOnIdle { player.playQueue(listOf(two, one), two); player.seek(9); player.setRepeatMode(RepeatMode.ALL); player.setShuffle(true) }
        waitFor { player.state.value.positionSeconds >= 10 }
        provider("unavailable", "true"); provider("notify")
        waitFor { player.state.value.current?.available == false && !player.state.value.playing }
        val position = player.state.value.positionSeconds
        assertTrue(position >= 10)
        assertEquals(listOf(two, one), player.state.value.queue.map { it.id })
        restartService()
        assertEquals(two, player.state.value.current?.id)
        assertEquals(position, player.state.value.positionSeconds)
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
        provider("unavailable", "false"); provider("notify")
        waitFor { player.state.value.current?.available == true }
        assertEquals(listOf(two, one), player.state.value.queue.map { it.id })
        assertEquals(position, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds > position }
    }
    @Test fun partiallyMissingQueueDoesNotInterruptPresentTrackAndEditsRemainDurable() {
        val one = id("one"); val two = id("two")
        compose.runOnIdle { player.playQueue(listOf(two, one), one); player.seek(6) }
        waitFor { player.state.value.positionSeconds >= 7 }
        provider("missingSecond", "true"); runBlocking { library.refresh() }
        waitFor { player.state.value.queue.first().available == false && player.state.value.positionSeconds >= 8 }
        assertEquals(one, player.state.value.current?.id); assertTrue(player.state.value.playing)
        restartService()
        assertEquals(listOf(two, one), player.state.value.queue.map { it.id }); assertEquals("two", player.state.value.queue.first().title)
        compose.runOnIdle { player.removeFromQueue(two) }
        provider("missingSecond", "false"); runBlocking { library.refresh() }
        assertEquals(listOf(one), player.state.value.queue.map { it.id })
        provider("unavailable", "true"); runBlocking { library.refresh() }
        compose.runOnIdle { player.clearQueue() }
        restartService()
        provider("unavailable", "false"); runBlocking { library.refresh() }
        assertTrue(player.state.value.queue.isEmpty())
    }
    @Test fun embeddedArtworkIsBoundedRenderedAndRebuiltAfterCacheEviction() {
        provider("artwork"); runBlocking { library.refresh() }
        val coverTrack = library.testTracks.single { it.title == "Cover fixture" }
        val originalSize = coverTrack.sizeBytes
        val file = File(Uri.parse(coverTrack.artworkUri!!).path!!)
        assertTrue(file.isFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertTrue(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
        assertTrue(file.length() < 128 * 1024)
        compose.runOnIdle { player.select(coverTrack.id) }
        waitFor { player.state.value.playing }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("track_artwork").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("track_artwork").assertIsDisplayed()
        assertTrue(file.delete())
        runBlocking { library.refresh() }
        assertTrue(file.isFile)
        assertEquals(originalSize, library.testTracks.single { it.id == coverTrack.id }.sizeBytes)
        compose.runOnIdle { player.select(id("one")) }
        compose.onNodeWithTag("artwork_placeholder").assertIsDisplayed()
        assertNull(player.state.value.error)
    }
    @Test fun missingCurrentCanBeSkippedWithoutStartingItsSuccessor() {
        val one = id("one"); val two = id("two")
        compose.runOnIdle { player.playQueue(listOf(two, one), two); player.seek(5) }
        waitFor { player.state.value.positionSeconds >= 6 }
        provider("missingSecond", "true"); runBlocking { library.refresh() }
        waitFor { player.state.value.current?.available == false }
        compose.runOnIdle { player.skip(1) }
        assertEquals(one, player.state.value.current?.id); assertFalse(player.state.value.playing)
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        provider("missingSecond", "false"); runBlocking { library.refresh() }
        assertEquals(one, player.state.value.current?.id); assertTrue(player.state.value.playing)
        assertEquals(listOf(two, one), player.state.value.queue.map { it.id })
    }
}
