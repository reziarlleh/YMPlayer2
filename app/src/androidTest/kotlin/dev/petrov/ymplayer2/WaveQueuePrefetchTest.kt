@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real ExoPlayer and complete local fixture audio; no Yandex account or network. */
@RunWith(AndroidJUnit4::class)
class WaveQueuePrefetchTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private fun waitFor(condition: () -> Boolean) {
        try { compose.waitUntil(25_000, condition) }
        catch (e: Throwable) { throw AssertionError("state=${player.state.value}; buffered=${player.bufferedWaveAudioIds()}; requests=${h.waveRequests}; streams=${h.resolvedQualities}", e) }
    }
    @Before fun prepare() {
        waitFor { h.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 && h.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }

    private fun fullWindow() = player.state.value.queue.size == 10 && !player.state.value.waveLoading &&
        player.bufferedWaveAudioIds() == player.state.value.queue.mapTo(hashSetOf(), Track::id)

    @Test fun explicitStopClearsTheWholeWaveWindowAndNextPlayStartsANewSession() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && fullWindow() }
        val audio = player.state.value.queue.map { File(Uri.parse(player.resolveStream("owner", it.id)).path!!) }
        val sessions = h.waveSelections.size
        // Explicit app Stop resets the wave. A generic MediaSession Stop also
        // handles notification dismissal and is covered by the retention tests.
        compose.runOnIdle { player.stop() }
        assertTrue(player.state.value.wave)
        assertTrue("Explicit Stop clears the wave queue", player.state.value.queue.isEmpty())
        assertNull(player.state.value.current)
        assertEquals(0, player.state.value.positionSeconds)
        assertFalse(player.state.value.playing)
        assertTrue(player.bufferedWaveAudioIds().isEmpty()); assertTrue(audio.none(File::exists))
        compose.runOnIdle { h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave }
        Thread.sleep(1_000)
        assertTrue(player.state.value.queue.isEmpty()); assertFalse(player.state.value.playing)
        assertEquals(sessions, h.waveSelections.size)
        compose.runOnIdle { h.systemPlayer.play() }
        waitFor { player.state.value.playing && fullWindow() }
        assertEquals("Stop must discard the old session/cursor", sessions + 1, h.waveSelections.size)
        compose.runOnIdle { player.stop(); player.waveSettings!!.load("ru") }
        waitFor { player.waveSettings!!.state.value.options != null }
        compose.runOnIdle {
            player.waveSettings!!.select("moodEnergy", "settingMoodEnergy:calm")
            assertFalse(player.state.value.playing)
            assertTrue(player.state.value.queue.isEmpty())
            player.toggle()
        }
        waitFor { player.state.value.playing && fullWindow() }
        assertEquals(sessions + 2, h.waveSelections.size)
        assertTrue("Touch Play after Stop uses the newly chosen settings", "settingMoodEnergy:calm" in h.waveSelections.last().settings)
    }

    @Test fun durableStopCannotResurrectAnOlderPreparedQueueCheckpoint() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.playing }
        compose.runOnIdle {
            player.seek(7); h.systemPlayer.pause(); player.saveForExit()
            dev.petrov.ymplayer2.playback.LaunchStateStore(h.context)
                .write("owner", dev.petrov.ymplayer2.playback.PlaybackOutput.MUSIC, false, stopped = true)
            h.restartEngine()
        }
        waitFor { player.state.value.connected && player.state.value.wave }
        assertNull(player.state.value.current)
        assertTrue(player.state.value.queue.isEmpty()); assertTrue(player.bufferedWaveAudioIds().isEmpty())
        assertEquals(0, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
    }

    @Test fun zeroPositionPauseKeepsTheWholePreparedWindowAcrossRestart() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.playing }
        val ids = player.state.value.queue.map(Track::id)
        compose.runOnIdle { player.seek(0); h.systemPlayer.pause(); player.saveForExit(); h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == ids.first() }
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals(ids.toSet(), player.bufferedWaveAudioIds())
        assertEquals(0, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
        assertFalse(dev.petrov.ymplayer2.playback.LaunchStateStore(h.context).read("owner").stopped)
    }

    @Test fun applyingNewSettingsImmediatelyStartsNewWaveAndDiscardsLateOldTail() {
        val late = CompletableDeferred<Unit>()
        compose.runOnIdle { h.waveRequestGates = mapOf(11 to late); player.playMyWave() }
        waitFor { fullWindow() && player.state.value.playing }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:3:7" && 11 in h.waveRequests }
        val obsolete = player.state.value.queue.drop(player.state.value.index + 1)
            .map { File(Uri.parse(player.resolveStream("owner", it.id)).path!!) }
        compose.runOnIdle { player.waveSettings!!.load("ru") }
        waitFor { player.waveSettings!!.state.value.options != null }
        compose.runOnIdle {
            player.seek(7)
            player.waveSettings!!.select("moodEnergy", "settingMoodEnergy:calm")
            player.playMyWave()
        }
        assertNull("Applying settings immediately ends the old song", player.state.value.current)
        assertEquals(0, player.state.value.positionSeconds)
        assertTrue("Discard the old prepared future", obsolete.none(File::exists))
        assertTrue("settingMoodEnergy:calm" in h.waveSelections.last().settings)
        compose.runOnIdle { late.complete(Unit) }
        waitFor { fullWindow() && player.state.value.playing }
        assertNotEquals("yandex:3:7", player.state.value.current?.id)
        assertFalse("Previous-wave Back history is cleared too", player.state.value.queue.any { it.id == "yandex:2:7" || it.id == "yandex:3:7" })
        assertTrue(org.json.JSONObject(h.checkpointText()).getJSONObject("waveSelection")
            .getJSONArray("settings").toString().contains("settingMoodEnergy:calm"))
    }

    @Test fun pausedFullWindowSurvivesRestartWithoutReplacingQueueOrDownloadingAgain() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && fullWindow() }
        compose.runOnIdle { player.seek(7); h.systemPlayer.pause(); player.saveForExit() }
        val ids = player.state.value.queue.map(Track::id)
        val audio = ids.associateWith { id -> File(Uri.parse(player.resolveStream("owner", id)).path!!) }
        val bytes = audio.mapValues { it.value.readBytes() }
        val persisted = dev.petrov.ymplayer2.playback.WaveAudioBuffer(h.context) { "1" }
        assertEquals(ids.toSet(), persisted.ids())
        val otherAccount = dev.petrov.ymplayer2.playback.WaveAudioBuffer(h.context) { "different-account" }
        assertTrue("Persisted audio must remain account scoped", otherAccount.ids().isEmpty())
        val requests = h.waveRequests.size
        val streams = h.resolved.size
        compose.runOnIdle {
            h.internet.available.value = false
            h.waveFailure = MusicFailure.NETWORK; h.streamFailure = MusicFailure.NETWORK
            h.restartEngine()
        }
        waitFor { player.state.value.connected && player.state.value.current?.id == ids.first() }
        Thread.sleep(1_200)
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals(7, player.state.value.positionSeconds)
        assertFalse(player.state.value.playing)
        assertEquals(ids.toSet(), player.bufferedWaveAudioIds())
        ids.forEach { id ->
            assertEquals(audio.getValue(id).toURI().path, Uri.parse(player.resolveStream("owner", id)).path)
            assertArrayEquals(bytes.getValue(id), audio.getValue(id).readBytes())
        }
        assertEquals(requests, h.waveRequests.size); assertEquals(streams, h.resolved.size)
        compose.runOnIdle { h.systemPlayer.play() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 8 }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == ids[1] && player.state.value.playing }
        assertEquals(requests, h.waveRequests.size); assertEquals(streams, h.resolved.size)
        compose.runOnIdle {
            h.waveFailure = null; h.streamFailure = null; h.internet.available.value = true
            player.skip(1)
        }
        waitFor { player.state.value.current?.id == ids[2] && fullWindow() }
        assertEquals("New tracks are requested only as the retained window advances", "yandex:11:7", player.state.value.queue.last().id)
        ids.drop(1).forEach { id -> assertArrayEquals(bytes.getValue(id), audio.getValue(id).readBytes()) }
        assertFalse("Only audio older than the one-step Back history is removed", audio.getValue(ids.first()).exists())
    }

    @Test fun allTenEntriesContainRealAudioAndAdvancingReplenishesWithoutGrowingStorage() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && fullWindow() }
        assertEquals((1..10).map { "yandex:$it:7" }, player.state.value.queue.map(Track::id))
        val original = player.state.value.queue.associate { track ->
            val uri = Uri.parse(player.resolveStream("owner", track.id))
            assertEquals("A URL or ID is not a complete audio cache", "file", uri.scheme)
            val file = File(uri.path!!)
            val source = h.library.testTracks[(track.id.removePrefix("yandex:").substringBefore(':').toInt() - 1) % 2]
            val expected = compose.activity.contentResolver.openInputStream(Uri.parse(source.uri))!!.use { it.readBytes() }
            assertArrayEquals("The whole fixture audio, not a preview, must be cached", expected, file.readBytes())
            track.id to file
        }
        assertTrue(h.tasteWrites.isEmpty())
        assertTrue(h.offline.state.value.tracks.isEmpty())
        for (id in 2..7) {
            compose.runOnIdle { player.skip(1) }
            waitFor { player.state.value.current?.id == "yandex:$id:7" && player.state.value.playing && fullWindow() }
            assertTrue(player.state.value.queue.size <= WaveQueueWindow.MAX_ITEMS)
        }
        assertFalse(original.getValue("yandex:1:7").exists())
        val root = original.values.first().parentFile!!
        assertEquals(10, root.listFiles()!!.count { it.extension == "audio" })
        assertEquals(0, root.listFiles()!!.count { it.extension == "part" })
        compose.runOnIdle { player.stop() }
        waitFor { player.bufferedWaveAudioIds().isEmpty() }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun slowTailCannotDelayStartOrPreparedNextAndEarlyNextWaitsForOneExistingDownload() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:3:7" to gate); player.playMyWave() }
        waitFor { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 &&
            h.resolved.any { it.second == "yandex:3:7" } }
        assertEquals(setOf("yandex:1:7", "yandex:2:7"), player.bufferedWaveAudioIds())
        assertFalse(gate.isCompleted)
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.skip(1) }
        assertEquals("yandex:2:7", player.state.value.current?.id)
        compose.runOnIdle { gate.complete(Unit) }
        waitFor { player.state.value.current?.id == "yandex:3:7" && player.state.value.positionSeconds >= 1 }
        assertEquals(1, h.resolved.count { it.second == "yandex:3:7" })
        assertTrue(player.state.value.wave)
    }

    @Test fun sourceChangeCancelsTailAndCannotPublishItsLateFile() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:3:7" to gate); player.playMyWave() }
        waitFor { player.state.value.playing && h.resolved.any { it.second == "yandex:3:7" } }
        val cached = File(Uri.parse(player.resolveStream("owner", "yandex:2:7")).path!!)
        val prepared = player.bufferedWaveAudioIds()
        assertEquals(setOf("yandex:1:7", "yandex:2:7"), prepared)
        compose.runOnIdle { player.playQueue(listOf(h.library.testTracks.first().id)); gate.complete(Unit) }
        waitFor { !player.state.value.wave && player.state.value.playing && player.state.value.positionSeconds >= 1 &&
            cached.parentFile!!.listFiles()!!.none { it.extension == "part" } }
        assertEquals("Retain completed audio for returning to the parked wave", prepared, player.bufferedWaveAudioIds())
        assertTrue(cached.exists())
        assertFalse("The cancelled old tail must not publish after changing sources", "yandex:3:7" in player.bufferedWaveAudioIds())
        assertEquals(prepared.size, cached.parentFile!!.listFiles()!!.count { it.extension == "audio" })
    }

    @Test fun realStorageWriteFailureKeepsPlayingAndWaitsForExplicitRetry() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:3:7" to gate); player.playMyWave() }
        waitFor { player.state.value.positionSeconds >= 1 && "yandex:2:7" in player.bufferedWaveAudioIds() &&
            h.resolved.any { it.second == "yandex:3:7" } }
        val root = File(Uri.parse(player.resolveStream("owner", "yandex:2:7")).path!!).parentFile!!
        // Fail the real FileOutputStream after URL resolution, without deleting current/next audio.
        val part = root.listFiles()!!.single { it.extension == "part" }
        assertTrue(part.delete())
        assertTrue(part.mkdir())
        compose.runOnIdle { gate.complete(Unit) }
        waitFor { !player.state.value.waveLoading && player.state.value.waveIssue != null }
        val issue = player.state.value.waveIssue!!
        assertTrue(issue, issue.contains("Не удалось сохранить временное аудио"))
        assertFalse(issue, issue.contains("Проверьте сеть"))
        assertFalse(issue, issue.contains("Повторим автоматически"))
        assertTrue(player.state.value.playing)
        val requests = h.resolved.count { it.second == "yandex:3:7" }
        val position = player.state.value.positionSeconds
        waitFor { player.state.value.positionSeconds >= position + 4 }
        assertEquals("Storage errors must not trigger network retries", requests,
            h.resolved.count { it.second == "yandex:3:7" })
        compose.runOnIdle { player.retryWave() }
        waitFor { fullWindow() }
        assertNull(player.state.value.waveIssue)
        assertTrue(player.state.value.playing)
    }

    @Test fun rapidNextRetainsPreviousAudioDownloadAndPreviousUsesItsCompleteFile() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:1:7" to gate); player.playMyWave() }
        waitFor { "yandex:2:7" in player.bufferedWaveAudioIds() }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { gate.complete(Unit) }
        waitFor { fullWindow() }
        val previous = File(Uri.parse(player.resolveStream("owner", "yandex:1:7")).path!!)
        assertTrue(previous.isFile)
        val requests = h.resolved.count { it.second == "yandex:1:7" }
        compose.runOnIdle { player.skip(-1) }
        waitFor { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 }
        assertEquals("Previous must use its retained complete audio", requests, h.resolved.count { it.second == "yandex:1:7" })
        assertTrue(previous.isFile)
    }

    @Test fun changedQualityDiscardsInflightTailButKeepsAlreadyPreparedAudio() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            h.audioQuality.setStream(AudioQuality.STANDARD)
            h.streamGates = mapOf("yandex:3:7" to gate)
            player.playMyWave()
        }
        waitFor { player.state.value.playing && h.resolvedQualities.any { it.first == "yandex:3:7" } }
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.HIGH); gate.complete(Unit) }
        waitFor { fullWindow() }
        assertEquals(listOf(AudioQuality.STANDARD, AudioQuality.HIGH), h.resolvedQualities.filter { it.first == "yandex:3:7" }.map { it.second })
        assertEquals(listOf(AudioQuality.STANDARD), h.resolvedQualities.filter { it.first == "yandex:2:7" }.map { it.second })
        assertTrue(h.resolvedQualities.filter { it.first.removePrefix("yandex:").substringBefore(':').toInt() >= 4 }.all { it.second == AudioQuality.HIGH })
    }

    @Test fun ordinaryLongPlaylistRetainsItsTracksWithoutDownloadingTheWholeList() {
        compose.runOnIdle { h.onlineTrackCount = 20; h.online.search("long list", MusicKind.TRACKS, false) }
        waitFor { h.online.state.value.loaded && !h.online.state.value.loading }
        compose.runOnIdle { h.online.more() }
        waitFor { h.online.state.value.entries.size == 20 && !h.online.state.value.loading }
        val ids = h.online.state.value.entries.map { it.id }
        compose.runOnIdle { player.playList(ids, origin = PlaybackOrigin(PlaybackSource.LIST, "Twenty tracks")) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 2 }
        assertFalse(player.state.value.wave)
        assertEquals(20, player.state.value.queueCount)
        assertEquals(ids, runBlocking { player.queuePage(0, 50).items.map(Track::id) })
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        assertTrue("Ordinary lists must not resolve and download every track", h.resolved.map { it.second }.distinct().size < 20)
        assertTrue(h.offline.state.value.tracks.isEmpty())
    }

    @Test fun restartRetainsAllTenCandidatesAndTheirCompleteFiles() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.positionSeconds >= 1 }
        val ids = player.state.value.queue.map(Track::id)
        val requests = h.waveRequests.toList()
        val streams = h.resolved.size
        compose.runOnIdle { player.sessionPause(); h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == ids.first() }
        assertFalse(player.state.value.playing)
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals(ids.toSet(), player.bufferedWaveAudioIds())
        compose.runOnIdle { player.sessionPlay() }
        waitFor { fullWindow() && player.state.value.playing }
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals("Restored queued candidates must not be skipped as already heard", requests, h.waveRequests.toList())
        assertEquals("Complete audio survives without downloading again", streams, h.resolved.size)
    }

    @Test fun missingAndTruncatedFutureFilesAreRepairedWithoutReplacingTheSavedQueue() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.playing }
        compose.runOnIdle { player.seek(7); h.systemPlayer.pause() }
        val ids = player.state.value.queue.map(Track::id)
        val missing = File(Uri.parse(player.resolveStream("owner", ids[4])).path!!)
        val truncated = File(Uri.parse(player.resolveStream("owner", ids[7])).path!!)
        assertTrue(missing.delete()); truncated.writeBytes(byteArrayOf(1, 2, 3))
        val requests = h.waveRequests.size
        val streams = h.resolved.size
        compose.runOnIdle { h.waveFailure = MusicFailure.NETWORK; h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == ids.first() }
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals(7, player.state.value.positionSeconds)
        assertEquals(8, player.bufferedWaveAudioIds().size)
        compose.runOnIdle { h.systemPlayer.play() }
        waitFor { fullWindow() && player.state.value.playing && player.state.value.positionSeconds >= 8 }
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals(requests, h.waveRequests.size)
        assertEquals(streams + 2, h.resolved.size)
        compose.runOnIdle { h.waveFailure = null }
    }

    @Test fun restartingWaveDoesNotTreatItsUnplayedCachedTailAsHeard() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.saveForExit() }
        val saved = org.json.JSONArray(h.context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
            .getString("myWaveSeen:owner:1", "[]"))
        val heard = (0 until saved.length()).map { saved.getString(it) }.toSet()
        assertTrue("1" in heard)
        assertTrue("Downloading the next nine songs must not mark them as heard", (2..10).none { it.toString() in heard })
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.current?.id == "yandex:2:7" && player.state.value.playing }
        assertTrue(player.state.value.queue.any { it.id == "yandex:3:7" })
    }
}
