@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.core.CatalogFilter
import dev.petrov.ymplayer2.core.CatalogDimension
import dev.petrov.ymplayer2.core.RepeatMode
import dev.petrov.ymplayer2.core.IndexedLocalLibrary
import dev.petrov.ymplayer2.core.Track
import dev.petrov.ymplayer2.core.LocalPlaybackWindow
import dev.petrov.ymplayer2.playback.AndroidPlayback
import dev.petrov.ymplayer2.playback.AudioService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
        waitFor { library.testTracks.size == 2 && player.state.value.queue.size == 2 }
    }
    @After fun stop() { compose.runOnIdle { player.stop() }; provider("unavailable", "false") }

    @Test fun diskIndexPaginatesGroupsAndRetainsUnavailableDocuments() = runBlocking {
        val first = library.pageTracks(CatalogFilter(), limit = 1)
        val second = library.pageTracks(CatalogFilter(), offset = 1, limit = 1)
        assertEquals(2, first.total)
        assertEquals(2, second.total)
        assertTrue(first.hasMore)
        assertNotEquals(first.items.single().id, second.items.single().id)
        assertEquals(second.items.single().id, library.adjacentTrack(first.items.single().id, 1)?.id)
        val initialWindow = requireNotNull(library.playbackWindow(first.items.single().id, Source.LOCAL))
        assertEquals(0, initialWindow.index)
        assertEquals(2, initialWindow.visible.total)
        assertEquals(listOf(first.items.single().id, second.items.single().id), initialWindow.media.map { it.id })
        assertNull(library.playbackWindow(first.items.single().id, Source.USB))
        assertEquals(first.items.single().id, library.adjacentTrack(second.items.single().id, -1)?.id)
        assertNull(library.adjacentTrack(second.items.single().id, 1))
        assertEquals(first.items.single().id, library.adjacentTrack(second.items.single().id, 1, wrap = true)?.id)
        assertNull(library.adjacentTrack(first.items.single().id, 1, Source.USB))
        assertEquals(1, library.pageTracks(CatalogFilter(query = "one")).total)
        assertEquals(0, library.pageTracks(CatalogFilter(source = Source.USB)).total)
        val groups = library.pageGroups(CatalogFilter(), CatalogDimension.FOLDERS)
        assertEquals(2, groups.items.sumOf { it.count })

        provider("unavailable", "true")
        library.refresh()
        val unavailable = library.pageTracks(CatalogFilter())
        assertEquals(first.items.map { it.id }.toSet() + second.items.map { it.id }, unavailable.items.map { it.id }.toSet())
        assertTrue(unavailable.items.none { it.available })
        assertTrue(requireNotNull(library.playbackWindow(first.items.single().id)).media.isEmpty())
        assertNull(library.adjacentTrack(first.items.single().id, 1))
        assertEquals(0, library.pageTracks(CatalogFilter(availableOnly = true)).total)
        provider("unavailable", "false")
        library.refresh()
        assertEquals(2, library.pageTracks(CatalogFilter(availableOnly = true)).total)
    }

    @Test fun localSearchReadsDiskIndexAndOpensSelectedTrack() {
        val id = library.testTracks.single { it.title == "one" }.id
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("search_input").performTextInput("one")
        waitFor { compose.onAllNodesWithTag("track_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("track_$id").assertIsDisplayed().performClick()
        waitFor { player.state.value.current?.id == id }
    }

    @Test fun indexedSelectionsRestoreAndQueuedCommandsNeverEnumerateSnapshot() {
        provider("bulk", "60")
        runBlocking { library.refresh() }
        val order = runBlocking { library.pageTracks(CatalogFilter(), limit = 100).items }
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("cursor-test-$name", mode)
        }
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        var gate: CompletableDeferred<Unit>? = null
        val indexed = object : IndexedLocalLibrary by library {
            override fun tracks(profileId: String): List<Track> = error("Full catalog snapshot must not be enumerated")
            override suspend fun playbackWindow(currentId: String?, source: Source?): LocalPlaybackWindow? {
                gate?.await()
                return library.playbackWindow(currentId, source)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var cursor: AndroidPlayback
        lateinit var engine: ExoPlayer
        compose.runOnIdle {
            cursor = AndroidPlayback(context, indexed, scope)
            engine = ExoPlayer.Builder(context).build()
            cursor.attach(engine)
        }
        try {
            waitFor { cursor.state.value.connected && cursor.state.value.queueCount == 62 }
            assertEquals(order.first().id, cursor.state.value.current?.id)
            gate = CompletableDeferred()
            compose.runOnIdle { cursor.select(order[50].id); cursor.seek(7); cursor.toggle() }
            assertFalse(cursor.state.value.playing)
            gate.complete(Unit); gate = null
            waitFor { cursor.state.value.current?.id == order[50].id && cursor.state.value.positionSeconds == 7 && !cursor.state.value.playing }
            assertFalse(cursor.state.value.playing)
            assertFalse(compose.runOnIdle { engine.playWhenReady })
            assertEquals(46, cursor.state.value.queueOffset)
            assertTrue(compose.runOnIdle { engine.mediaItemCount } <= 29)
            compose.runOnIdle {
                engine.setMediaItems(listOf(engine.currentMediaItem!!), 0, 7_000)
                cursor.skip(1)
            }
            waitFor { cursor.state.value.current?.id == order[51].id && !cursor.state.value.playing }
            compose.runOnIdle { cursor.select(order[50].id); cursor.seek(7); cursor.toggle() }
            waitFor { cursor.state.value.current?.id == order[50].id && cursor.state.value.positionSeconds == 7 && !cursor.state.value.playing }
            compose.runOnIdle { cursor.detach(); engine.release() }
            compose.waitForIdle()
            compose.runOnIdle { engine = ExoPlayer.Builder(context).build(); cursor.attach(engine) }
            waitFor { cursor.state.value.connected && cursor.state.value.current?.id == order[50].id }
            assertEquals(7, cursor.state.value.positionSeconds)
            assertFalse(cursor.state.value.playing)
            compose.runOnIdle { engine.clearMediaItems(); cursor.setRepeatMode(RepeatMode.ALL) }
            waitFor { compose.runOnIdle { engine.mediaItemCount > 0 } && cursor.state.value.current?.id == order[50].id }
            compose.runOnIdle { cursor.setRepeatMode(RepeatMode.ONE); cursor.setRepeatMode(RepeatMode.OFF) }
            provider("unavailable", "true"); runBlocking { library.refresh() }
            waitFor { cursor.state.value.current?.available == false }
            assertEquals(7, cursor.state.value.positionSeconds)
            provider("unavailable", "false"); runBlocking { library.refresh() }
            waitFor { cursor.state.value.current?.available == true }
            assertEquals(7, cursor.state.value.positionSeconds)
            assertFalse(cursor.state.value.playing)
            compose.runOnIdle { cursor.chooseSource(Source.USB) }
            waitFor { cursor.state.value.queueCount == 0 }
            compose.runOnIdle { cursor.select(order[50].id); cursor.toggle() }
            waitFor { cursor.state.value.current?.id == order[50].id && !cursor.state.value.playing }
            assertNull(cursor.state.value.automaticSource)
            compose.runOnIdle { cursor.chooseSource(Source.LOCAL) }
            waitFor { cursor.state.value.current?.id == order.first().id }
            compose.runOnIdle { cursor.switchProfile("guest"); cursor.chooseSource(Source.LOCAL) }
            waitFor { cursor.state.value.profileId == "guest" && cursor.state.value.queueCount == 62 }
            assertFalse(cursor.state.value.playing)
            compose.runOnIdle { cursor.setRepeatMode(RepeatMode.ALL); cursor.select(order.last().id); cursor.seek(29) }
            waitFor { cursor.state.value.current?.id == order.first().id }
            assertTrue(compose.runOnIdle { engine.mediaItemCount } <= 29)
            compose.runOnIdle { cursor.setShuffle(true); cursor.toggle() }
            waitFor { cursor.state.value.shuffle && !cursor.state.value.playing &&
                compose.runOnIdle { engine.mediaItemCount <= 3 } }
            val shuffledCurrent = cursor.state.value.current!!.id
            compose.runOnIdle { cursor.skip(1) }
            waitFor { cursor.state.value.current?.id != shuffledCurrent }
            val restoredId = cursor.state.value.current!!.id
            compose.runOnIdle { cursor.detach(); engine.release() }
            compose.waitForIdle()
            compose.runOnIdle { engine = ExoPlayer.Builder(context).build(); cursor.attach(engine) }
            waitFor { cursor.state.value.connected && cursor.state.value.current?.id == restoredId &&
                cursor.state.value.shuffle && cursor.state.value.repeatMode == RepeatMode.ALL }
            assertFalse(cursor.state.value.playing)
            assertTrue(compose.runOnIdle { engine.mediaItemCount } <= 3)
        } finally {
            compose.runOnIdle { cursor.detach(); engine.release(); scope.cancel() }
        }
    }

    @Test fun independentRootsKeepTheirRowsAndCountsWhenOneScanFailsOrIsForgotten() {
        provider("duplicateFirst", "true")
        provider("grantSecondary")
        runBlocking { library.addFolder(TestMusicProvider.secondaryTree.toString(), Source.USB) }
        assertEquals(listOf(2, 1), library.state.value.roots.map { it.trackCount })
        assertEquals(3, library.testTracks.size)
        assertTrue(library.state.value.tracks.isEmpty())
        provider("secondaryUnavailable", "true"); runBlocking { library.refresh() }
        assertEquals(2, library.testTracks.count { it.available })
        assertFalse(library.testTracks.single { it.source == Source.USB }.available)
        assertNotNull(library.state.value.roots.last().issue)
        assertEquals(listOf(2, 1), library.state.value.roots.map { it.trackCount })
        runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
        assertEquals(1, library.testTracks.size)
        assertEquals(TestMusicProvider.secondaryTree.toString(), library.state.value.roots.single().uri)
        provider("secondaryUnavailable", "false"); runBlocking { library.refresh() }
        assertTrue(library.testTracks.single().available)
        runBlocking { library.forgetFolder(TestMusicProvider.secondaryTree.toString()) }
        assertTrue(library.testTracks.isEmpty())
        assertTrue(library.state.value.roots.isEmpty())
        provider("grant")
        compose.activity.contentResolver.openFileDescriptor(android.provider.DocumentsContract.buildDocumentUriUsingTree(TestMusicProvider.tree, "one.wav"), "r")!!.use {
            assertTrue(it.statSize > 0)
        }
    }

    @Test fun manualReferencesUseSelectedIdsAndKeepCommandOrderDuringIo() {
        provider("bulk", "60"); runBlocking { library.refresh() }
        val order = library.testTracks
        assertEquals(62, library.state.value.roots.single().trackCount)
        assertTrue(library.state.value.tracks.isEmpty())
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("references-test-$name", mode)
        }
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        val requested = mutableListOf<Set<String>>()
        var gate: CompletableDeferred<Unit>? = null
        val indexed = object : IndexedLocalLibrary by library {
            override fun tracks(profileId: String): List<Track> = error("A manual list must not read the entire catalog")
            override suspend fun tracksByIds(ids: Collection<String>): Map<String, Track> {
                requested += ids.toSet()
                gate?.await()
                return library.tracksByIds(ids)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var cursor: AndroidPlayback
        lateinit var engine: ExoPlayer
        compose.runOnIdle { cursor = AndroidPlayback(context, indexed, scope); engine = ExoPlayer.Builder(context).build(); cursor.attach(engine) }
        try {
            waitFor { cursor.state.value.connected && cursor.state.value.queueCount == 62 }
            val selected = listOf(order[50].id, order[4].id, order[20].id)
            gate = CompletableDeferred()
            compose.runOnIdle { cursor.playQueue(selected, order[4].id); cursor.seek(7); cursor.toggle() }
            assertFalse(cursor.state.value.playing)
            gate.complete(Unit); gate = null
            waitFor { cursor.state.value.current?.id == order[4].id && cursor.state.value.positionSeconds == 7 && !cursor.state.value.playing }
            assertEquals(selected, cursor.state.value.queue.map(Track::id))
            compose.runOnIdle { cursor.moveInQueue(order[20].id, 0); cursor.removeFromQueue(order[50].id); cursor.enqueue(order[33].id) }
            val edited = listOf(order[20].id, order[4].id, order[33].id)
            waitFor { cursor.state.value.queue.map(Track::id) == edited }
            assertEquals(order[4].id, cursor.state.value.current?.id)
            assertEquals(7, cursor.state.value.positionSeconds)
            compose.runOnIdle { cursor.setRepeatMode(RepeatMode.ALL); cursor.setShuffle(true); cursor.detach(); engine.release() }
            compose.waitForIdle()
            compose.runOnIdle { engine = ExoPlayer.Builder(context).build(); cursor.attach(engine) }
            waitFor { cursor.state.value.connected && cursor.state.value.queue.map(Track::id) == edited }
            assertEquals(7, cursor.state.value.positionSeconds)
            assertTrue(cursor.state.value.shuffle); assertEquals(RepeatMode.ALL, cursor.state.value.repeatMode)
            assertFalse(cursor.state.value.playing)
            provider("unavailable", "true"); runBlocking { library.refresh() }
            waitFor { cursor.state.value.current?.available == false }
            assertEquals(7, cursor.state.value.positionSeconds)
            provider("unavailable", "false"); runBlocking { library.refresh() }
            waitFor { cursor.state.value.current?.available == true }
            assertEquals(edited, cursor.state.value.queue.map(Track::id))
            assertFalse(cursor.state.value.playing)
            assertTrue(requested.isNotEmpty() && requested.all { it.size <= 3 })
        } finally { compose.runOnIdle { cursor.detach(); engine.release(); scope.cancel() } }
    }

    @Test fun followedLibraryCheckpointIsCompactAndRestoresItsOrder() {
        provider("extra")
        runBlocking { library.refresh() }
        waitFor { player.state.value.queue.size == 4 }
        val order = player.state.value.queue.map { it.id }
        val selected = order[1]
        compose.runOnIdle { player.select(selected); player.seek(7) }
        val prefs = graph.getSharedPreferences("playback", 0)
        waitFor { player.state.value.current?.id == selected && player.state.value.positionSeconds >= 7 &&
            JSONObject(prefs.getString("queue:owner", "{}")!!).optString("current") == selected }
        val saved = JSONObject(prefs.getString("queue:owner", "{}")!!)
        assertTrue(saved.getBoolean("followLibrary"))
        assertEquals(0, saved.getJSONArray("ids").length())
        assertEquals(1, saved.getJSONArray("tracks").length())
        assertEquals(selected, saved.getJSONArray("tracks").getJSONObject(0).getString("id"))
        compose.runOnIdle { compose.activity.stopService(Intent(compose.activity, AudioService::class.java)) }
        waitFor { !player.state.value.connected }
        compose.runOnIdle { player.connect() }
        waitFor { player.state.value.connected && player.state.value.queue.map { it.id } == order }
        assertEquals(selected, player.state.value.current?.id)
        assertTrue(player.state.value.positionSeconds >= 7)
        assertFalse(player.state.value.playing)
    }

    @Test fun followedLibraryKeepsMedia3WindowBoundedAcrossSelectionsAndModes() {
        provider("bulk", "60")
        runBlocking { library.refresh() }
        waitFor { player.state.value.queueCount == 62 }
        val order = runBlocking { player.queuePage(0, 62).items.map { it.id } }
        val indexedWindow = runBlocking { requireNotNull(library.playbackWindow(order[25])) }
        assertEquals(25, indexedWindow.index)
        assertEquals(62, indexedWindow.visible.total)
        assertEquals(21, indexedWindow.visible.offset)
        assertEquals(order.subList(21, 50), indexedWindow.visible.items.map { it.id })
        assertEquals(order.subList(21, 50), indexedWindow.media.map { it.id })
        assertTrue(player.state.value.automaticLocal)
        assertTrue(player.state.value.queue.size <= 29)
        val browser = MediaBrowser.Builder(compose.activity,
            SessionToken(compose.activity, ComponentName(compose.activity, AudioService::class.java)))
            .buildAsync().get(20, TimeUnit.SECONDS)
        try {
            assertTrue(compose.runOnIdle { browser.mediaItemCount } <= 29)
            compose.runOnIdle { player.select(order[24]); player.seek(29) }
            waitFor { player.state.value.current?.id == order[25] && player.state.value.queueOffset == 21 }
            assertTrue(compose.runOnIdle { browser.mediaItemCount } <= 29)
            compose.runOnIdle { player.skip(1) }
            waitFor { player.state.value.current?.id == order[26] && player.state.value.queueOffset == 22 }
            compose.runOnIdle { player.select(order[50]) }
            waitFor { player.state.value.current?.id == order[50] }
            assertTrue(compose.runOnIdle { browser.mediaItemCount } <= 29)
            compose.runOnIdle { player.setRepeatMode(RepeatMode.ONE); player.select(order[24]); player.skip(1) }
            waitFor { player.state.value.current?.id == order[25] }
            compose.runOnIdle { player.setRepeatMode(RepeatMode.OFF) }
            compose.runOnIdle { player.select(order[2]); player.skip(-1) }
            waitFor { player.state.value.current?.id == order[1] }
            compose.runOnIdle { player.setShuffle(true) }
            waitFor { compose.runOnIdle { browser.shuffleModeEnabled && browser.mediaItemCount <= 3 } }
            compose.runOnIdle { player.setShuffle(false); player.setRepeatMode(RepeatMode.ALL) }
            waitFor { compose.runOnIdle { !browser.shuffleModeEnabled && browser.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL && browser.mediaItemCount <= 29 } }
            compose.runOnIdle { player.setRepeatMode(RepeatMode.OFF) }
            waitFor { compose.runOnIdle { browser.mediaItemCount } <= 29 }
            assertEquals(order, runBlocking { player.queuePage(0, 62).items.map { it.id } })
            assertTrue(player.state.value.queue.size <= 29)
        } finally {
            compose.runOnIdle { browser.release() }
        }
    }

    @Test fun automaticShuffleVisitsAllIdsAndRepeatsWithoutFullMetadataQueue() {
        provider("bulk", "60"); runBlocking { library.refresh() }
        waitFor { player.state.value.queueCount == 62 }
        val ids = runBlocking { library.playableTrackIds(Source.LOCAL) }.toSet()
        val browser = MediaBrowser.Builder(compose.activity,
            SessionToken(compose.activity, ComponentName(compose.activity, AudioService::class.java)))
            .buildAsync().get(20, TimeUnit.SECONDS)
        try {
            compose.runOnIdle { browser.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL; browser.shuffleModeEnabled = true }
            waitFor { player.state.value.shuffle && player.state.value.repeatMode == RepeatMode.ALL &&
                compose.runOnIdle { browser.mediaItemCount <= 3 && browser.shuffleModeEnabled } }
            val first = player.state.value.current!!.id
            val visited = mutableSetOf<String>()
            repeat(ids.size) {
                val current = player.state.value.current!!.id
                assertTrue("Repeated ID before completing shuffle pass", visited.add(current))
                compose.runOnIdle { browser.seekToNextMediaItem() }
                waitFor { player.state.value.current?.id != current }
                assertTrue(compose.runOnIdle { browser.mediaItemCount } <= 3)
            }
            assertEquals(ids, visited)
            assertEquals(first, player.state.value.current?.id)
            compose.runOnIdle { browser.seekToPreviousMediaItem() }
            waitFor { player.state.value.current?.id != first }
            val previous = player.state.value.current!!.id
            compose.runOnIdle { browser.seekToNextMediaItem() }
            waitFor { player.state.value.current?.id == first }
            assertNotEquals(previous, first)
            compose.runOnIdle { browser.shuffleModeEnabled = false; browser.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF }
            waitFor { !player.state.value.shuffle && player.state.value.repeatMode == RepeatMode.OFF }
        } finally { compose.runOnIdle { browser.release() } }
    }

    @Test fun safMetadataAndRealAudioContinueInBackground() {
        assertEquals(setOf("one", "two"), library.testTracks.map { it.title }.toSet())
        assertTrue(library.testTracks.all { it.durationSeconds == 30 && it.sizeBytes > 900000 })
        assertTrue(library.testTracks.any { it.folder.contains("nested") })
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
        compose.runOnIdle { player.select(library.testTracks.first().id) }
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
        waitFor { player.state.value.profileId == "owner" && player.state.value.positionSeconds == 7 }
        assertEquals(7, player.state.value.positionSeconds)
        compose.activityRule.scenario.recreate()
        waitFor { player.state.value.positionSeconds == 7 }
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
        val tracks = library.testTracks
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.positionSeconds >= 1 }
        provider("unavailable", "true")
        runBlocking { library.refresh() }
        waitFor { !player.state.value.playing }
        assertEquals(tracks.map { it.id }, library.testTracks.map { it.id })
        assertTrue(library.testTracks.none { it.available })
        assertNotNull(library.state.value.roots.single().issue)
        provider("unavailable", "false")
        runBlocking { library.refresh() }
        waitFor { player.state.value.queue.size == 2 }
        assertTrue(library.testTracks.all { it.available })
        runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
        waitFor { player.state.value.queue.isEmpty() }
        provider("grant")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.USB) }
        assertEquals(2, library.testTracks.size)
        assertTrue(library.testTracks.all { it.source == Source.USB })
    }

    @Test fun brokenAudioReportsErrorAndNextTrackStillPlays() {
        provider("corrupt")
        runBlocking { library.refresh() }
        val broken = library.testTracks.single { it.title == "broken" }
        compose.runOnIdle { player.select(broken.id) }
        waitFor { player.state.value.error != null }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.select(library.testTracks.single { it.title == "one" }.id) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertNull(player.state.value.error)
        compose.runOnIdle { player.select(broken.id) }
        waitFor { player.state.value.error != null }
        compose.runOnIdle { player.removeFromQueue(broken.id) }
        waitFor { player.state.value.queue.none { it.id == broken.id } && player.state.value.error == null }
        assertNull(player.state.value.error)
        assertFalse(player.state.value.playing)
    }
    @Test fun queueEditsKeepAudioClockAndPersistIntentionalEmptyQueue() {
        val one = library.testTracks.single { it.title == "one" }.id
        val two = library.testTracks.single { it.title == "two" }.id
        compose.runOnIdle { player.select(one); player.seek(7) }
        waitFor { player.state.value.positionSeconds >= 8 }
        compose.runOnIdle { player.moveInQueue(one, 1); player.removeFromQueue(two) }
        waitFor { player.state.value.queue.size == 1 && player.state.value.positionSeconds >= 9 }
        assertEquals(one, player.state.value.current?.id)
        assertTrue(player.state.value.playing)
        compose.runOnIdle { player.enqueue(two); player.enqueue(two); player.moveInQueue(two, -1) }
        waitFor { player.state.value.queue.map(Track::id) == listOf(one, two) }
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
        waitFor { player.state.value.current?.id == two }
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
        val one = library.testTracks.single { it.title == "one" }.id
        val two = library.testTracks.single { it.title == "two" }.id
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

    @Test fun queueLoadsFarRowsWithoutLosingDirectSelection() {
        provider("bulk", "100")
        runBlocking { library.refresh() }
        waitFor { player.state.value.queueCount == 102 }
        assertTrue(player.state.value.automaticLocal)
        assertTrue(player.state.value.queue.size <= 29)
        val diskPage = runBlocking { player.queuePage(80, 22) }
        val target = diskPage.items[15]
        assertEquals(102, diskPage.total)
        assertEquals(library.testTracks.subList(80, 102).map { it.id }, diskPage.items.map { it.id })
        compose.onNodeWithTag("player_queue").performClick()
        compose.onNodeWithTag("queue_list").performScrollToIndex(95)
        waitFor { compose.onAllNodesWithTag("track_card_${target.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("track_${target.id}").performClick()
        waitFor { player.state.value.current?.id == target.id && player.state.value.playing }
        assertTrue(player.state.value.queue.size <= 29)
        assertTrue(player.state.value.index in player.state.value.queueOffset until
            player.state.value.queueOffset + player.state.value.queue.size)
        compose.runOnIdle { player.moveInQueue(target.id, 94) }
        waitFor { !player.state.value.automaticLocal && player.state.value.queue.size == 102 }
        assertEquals(target.id, player.state.value.current?.id)
    }
}
