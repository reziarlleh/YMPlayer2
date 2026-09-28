package dev.petrov.ymplayer2

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.core.CatalogFilter
import dev.petrov.ymplayer2.core.CatalogDimension
import dev.petrov.ymplayer2.core.RepeatMode
import dev.petrov.ymplayer2.playback.AudioService
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private val player get() = graph.playback
    private val library get() = graph.library
    private fun provider(method: String, arg: String? = null) = compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, arg, null)
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000, condition)

    @Before fun fixtures() {
        waitFor { library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle {
            player.stop()
            library.profiles.forEach { player.switchProfile(it.id); player.chooseSource(null); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
            player.switchProfile("owner")
        }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        provider("fixtures")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { library.state.value.tracks.size == 2 && player.state.value.queue.size == 2 }
    }
    @After fun stop() { compose.runOnIdle { player.stop() }; provider("unavailable", "false") }

    @Test fun diskIndexPaginatesGroupsAndRetainsUnavailableDocuments() = runBlocking {
        val first = library.pageTracks(CatalogFilter(), limit = 1)
        val second = library.pageTracks(CatalogFilter(), offset = 1, limit = 1)
        assertEquals(2, first.total)
        assertEquals(2, second.total)
        assertTrue(first.hasMore)
        assertNotEquals(first.items.single().id, second.items.single().id)
        assertEquals(1, library.pageTracks(CatalogFilter(query = "one")).total)
        assertEquals(0, library.pageTracks(CatalogFilter(source = Source.USB)).total)
        val groups = library.pageGroups(CatalogFilter(), CatalogDimension.FOLDERS)
        assertEquals(2, groups.items.sumOf { it.count })

        provider("unavailable", "true")
        library.refresh()
        val unavailable = library.pageTracks(CatalogFilter())
        assertEquals(first.items.map { it.id }.toSet() + second.items.map { it.id }, unavailable.items.map { it.id }.toSet())
        assertTrue(unavailable.items.none { it.available })
        assertEquals(0, library.pageTracks(CatalogFilter(availableOnly = true)).total)
        provider("unavailable", "false")
        library.refresh()
        assertEquals(2, library.pageTracks(CatalogFilter(availableOnly = true)).total)
    }

    @Test fun localSearchReadsDiskIndexAndOpensSelectedTrack() {
        val id = library.state.value.tracks.single { it.title == "one" }.id
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("search_input").performTextInput("one")
        waitFor { compose.onAllNodesWithTag("track_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("track_$id").assertIsDisplayed().performClick()
        waitFor { player.state.value.current?.id == id }
    }

    @Test fun safMetadataAndRealAudioContinueInBackground() {
        assertEquals(setOf("one", "two"), library.state.value.tracks.map { it.title }.toSet())
        assertTrue(library.state.value.tracks.all { it.durationSeconds == 30 && it.sizeBytes > 900000 })
        assertTrue(library.state.value.tracks.any { it.folder.contains("nested") })
        compose.onNodeWithTag("player_play").performClick()
        waitFor { player.state.value.positionSeconds >= 2 && player.state.value.playing && !player.state.value.buffering }
        waitFor {
            graph.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                it.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0
            }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val before = player.state.value.positionSeconds
        // This state comes from ExoPlayer's AudioTrack clock, not a demo timer.
        waitFor { player.state.value.positionSeconds >= before + 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { player.seek(10); player.skip(1) }
        waitFor { player.state.value.current?.title == "two" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.toggle() }
        waitFor { !player.state.value.playing }
        assertNull(player.state.value.error)
    }

    @Test fun transientExternalAudioFocusPausesAndRestoresPlayback() {
        val manager = compose.activity.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }
            .build()
        compose.runOnIdle { player.select(library.state.value.tracks.first().id) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request))
            waitFor { !player.state.value.playing }
        } finally {
            manager.abandonAudioFocusRequest(request)
        }
        waitFor { player.state.value.playing }
    }

    @Test fun profileCheckpointsAndServiceRestartStayPaused() {
        compose.runOnIdle { player.seek(7); player.switchProfile("guest") }
        waitFor { player.state.value.profileId == "guest" }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.seek(12); player.switchProfile("owner") }
        assertEquals(7, player.state.value.positionSeconds)
        compose.activityRule.scenario.recreate()
        assertEquals(7, player.state.value.positionSeconds)
        compose.runOnIdle { compose.activity.stopService(Intent(compose.activity, AudioService::class.java)) }
        waitFor { !player.state.value.connected }
        compose.runOnIdle { player.connect() }
        waitFor { player.state.value.connected && player.state.value.positionSeconds == 7 }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.switchProfile("guest") }
        waitFor { player.state.value.positionSeconds == 12 }
        assertFalse(player.state.value.playing)
    }

    @Test fun missingRootKeepsIndexAndForgetLeavesOriginalReadable() {
        val tracks = library.state.value.tracks
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.positionSeconds >= 1 }
        provider("unavailable", "true")
        runBlocking { library.refresh() }
        waitFor { !player.state.value.playing }
        assertEquals(tracks.map { it.id }, library.state.value.tracks.map { it.id })
        assertTrue(library.state.value.tracks.none { it.available })
        assertNotNull(library.state.value.roots.single().issue)
        provider("unavailable", "false")
        runBlocking { library.refresh() }
        waitFor { player.state.value.queue.size == 2 }
        assertTrue(library.state.value.tracks.all { it.available })
        runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
        waitFor { player.state.value.queue.isEmpty() }
        provider("grant")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.USB) }
        assertEquals(2, library.state.value.tracks.size)
        assertTrue(library.state.value.tracks.all { it.source == Source.USB })
    }

    @Test fun brokenAudioReportsErrorAndNextTrackStillPlays() {
        provider("corrupt")
        runBlocking { library.refresh() }
        val broken = library.state.value.tracks.single { it.title == "broken" }
        compose.runOnIdle { player.select(broken.id) }
        waitFor { player.state.value.error != null }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.select(library.state.value.tracks.single { it.title == "one" }.id) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertNull(player.state.value.error)
        compose.runOnIdle { player.select(broken.id) }
        waitFor { player.state.value.error != null }
        compose.runOnIdle { player.removeFromQueue(broken.id) }
        assertNull(player.state.value.error)
        assertFalse(player.state.value.playing)
    }
    @Test fun queueEditsKeepAudioClockAndPersistIntentionalEmptyQueue() {
        val one = library.state.value.tracks.single { it.title == "one" }.id
        val two = library.state.value.tracks.single { it.title == "two" }.id
        compose.runOnIdle { player.select(one); player.seek(7) }
        waitFor { player.state.value.positionSeconds >= 8 }
        compose.runOnIdle { player.moveInQueue(one, 1); player.removeFromQueue(two) }
        waitFor { player.state.value.queue.size == 1 && player.state.value.positionSeconds >= 9 }
        assertEquals(one, player.state.value.current?.id)
        assertTrue(player.state.value.playing)
        compose.runOnIdle { player.enqueue(two); player.enqueue(two); player.moveInQueue(two, -1) }
        assertEquals(listOf(one, two), player.state.value.queue.map { it.id })
        assertTrue(player.state.value.positionSeconds >= 9)
        compose.runOnIdle { player.removeFromQueue(one) }
        waitFor { player.state.value.current?.id == two && !player.state.value.playing }
        compose.runOnIdle { player.clearQueue() }
        runBlocking { library.refresh() }
        assertTrue(player.state.value.queue.isEmpty())
        compose.runOnIdle { compose.activity.stopService(Intent(compose.activity, AudioService::class.java)) }
        waitFor { !player.state.value.connected }
        compose.runOnIdle { player.connect() }
        waitFor { player.state.value.connected }
        assertTrue(player.state.value.queue.isEmpty())
        compose.runOnIdle { player.enqueue(two) }
        assertEquals(two, player.state.value.current?.id)
        assertFalse(player.state.value.playing)
    }
    @Test fun nativeRepeatAndShufflePersistPerProfileAndVisitEveryTrack() {
        provider("extra"); runBlocking { library.refresh() }
        waitFor { player.state.value.queue.size == 4 }
        val order = player.state.value.queue.map { it.id }
        val first = order.first()
        compose.runOnIdle { player.select(first); player.setRepeatMode(RepeatMode.ONE); player.seek(29) }
        waitFor { player.state.value.positionSeconds in 1..4 && player.state.value.playing }
        assertEquals(first, player.state.value.current?.id)
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == order[1] }
        compose.runOnIdle { player.select(order.last()); player.setRepeatMode(RepeatMode.ALL); player.seek(29) }
        waitFor { player.state.value.current?.id == first }
        compose.runOnIdle { player.setShuffle(true) }
        val visited = mutableListOf<String>()
        repeat(order.size) {
            val id = player.state.value.current!!.id
            visited += id
            compose.runOnIdle { player.skip(1) }
            waitFor { player.state.value.current?.id != id }
        }
        assertEquals(order.toSet(), visited.toSet())
        assertEquals(first, player.state.value.current?.id)
        assertEquals(order, player.state.value.queue.map { it.id })
        compose.runOnIdle { player.switchProfile("guest") }
        assertEquals(RepeatMode.OFF, player.state.value.repeatMode); assertFalse(player.state.value.shuffle)
        compose.runOnIdle { player.switchProfile("owner") }
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
        compose.runOnIdle { compose.activity.stopService(Intent(compose.activity, AudioService::class.java)) }
        waitFor { !player.state.value.connected }
        compose.runOnIdle { player.connect() }
        waitFor { player.state.value.connected }
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
        assertFalse(player.state.value.playing)
    }
    @Test fun modeAndQueueControlsOperateTheRealPlayer() {
        val one = library.state.value.tracks.single { it.title == "one" }.id
        val two = library.state.value.tracks.single { it.title == "two" }.id
        compose.onNodeWithTag("repeat_mode").performClick()
        compose.onNodeWithTag("shuffle_mode").performClick()
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
        compose.onNodeWithContentDescription("Очередь").performClick()
        compose.onNodeWithTag("queue_edit").performClick()
        compose.onNodeWithTag("queue_down_$one").performScrollTo().performClick()
        assertEquals(listOf(two, one), player.state.value.queue.map { it.id })
        compose.onNodeWithTag("queue_remove_$one").performScrollTo().performClick()
        assertEquals(listOf(two), player.state.value.queue.map { it.id })
        compose.onNodeWithTag("queue_clear").performClick()
        assertTrue(player.state.value.queue.isEmpty())
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("enqueue_$one"))
        compose.onNodeWithTag("enqueue_$one").performClick()
        assertEquals(listOf(one), player.state.value.queue.map { it.id }); assertFalse(player.state.value.playing)
    }
}
