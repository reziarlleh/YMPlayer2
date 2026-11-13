@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OfflinePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val cache get() = h.offline
    private val owner = OfflineOwner("owner", "1")
    private fun waitFor(block: () -> Boolean) = compose.waitUntil(30000, block)
    private lateinit var sourceCover: File
    @Before fun setup() {
        waitFor { h.library.state.value.ready && h.player.state.value.connected }
        compose.runOnIdle { h.player.stop(); h.player.switchProfile("owner"); h.player.clearQueue(); cache.setEnabled(true) }
        waitFor { cache.state.value.owner == owner && cache.state.value.ready }
        compose.runOnIdle { cache.clear() }
        waitFor { cache.state.value.ready && cache.state.value.tracks.isEmpty() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 }
        sourceCover = File(compose.activity.filesDir, "offline-source.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        sourceCover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.runOnIdle {
            h.offlineArtwork = Uri.fromFile(sourceCover).toString()
            h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2"))
            h.taste.refresh(TasteKind.TRACK)
        }
        waitFor { h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1", "2") && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
    }
    @After fun cleanup() { compose.runOnIdle { cache.cancel(); h.player.stop(); h.internet.available.value = true }; if (::sourceCover.isInitialized) sourceCover.delete() }
    private fun sync() { compose.runOnIdle { cache.sync() }; waitFor { !cache.state.value.running } }
    private fun audit() { compose.runOnIdle { cache.verifyAndRepair() }; waitFor { !cache.state.value.running } }
    @Test fun aThousandCompletedTracksCannotBlockTwoNewDownloads() {
        val resolver = compose.activity.contentResolver
        val control = Uri.parse("content://dev.petrov.ymplayer2.test.control")
        resolver.call(control, "storage-reset", null, null)
        runBlocking { h.offlineStore.move(TestStorageProvider.tree.toString()) { _, _ -> } }
        try {
            resolver.call(control, "storage-seed-catalog", null, android.os.Bundle().apply {
                putString("root", android.provider.DocumentsContract.getDocumentId(Uri.parse(
                    h.context.getSharedPreferences("offline-location", 0).getString("uri", null)!!)))
                putString("profile", owner.profileId); putString("account", owner.accountId)
                putInt("count", 1000); putBoolean("completedAudio", true)
            })
            val existing = runBlocking { h.offlineStore.catalog(owner) }
            assertEquals(1000, existing.size)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(compose.activity, Uri.parse(existing.first().uri), null)
                assertTrue("Large-fixture files are real audio", (0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                })
            } finally { extractor.release() }
            val rows = existing.map { it.copy(offline = false, uri = null) } +
                listOf(h.onlineTrack(1), h.onlineTrack(2)).map { it.copy(artworkUri = h.offlineArtwork) }
            val keys = rows.mapTo(linkedSetOf()) { it.tasteTarget().key }
            compose.runOnIdle {
                h.offlineSnapshotOverride = LikedSnapshot(keys, rows)
                h.tasteLists["owner" to TasteKind.TRACK] = TasteList(keys)
                h.taste.refresh(TasteKind.TRACK); cache.reloadCache()
            }
            waitFor { cache.state.value.ready && cache.state.value.tracks.size == 1000 && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
            // These files are genuine PCM WAVs. Any attempt to reread their bodies
            // is held by the provider, while two new downloads remain writable.
            resolver.call(control, "storage-guard-existing-audio", "closed", null)
            val resolved = h.resolved.size
            val started = android.os.SystemClock.elapsedRealtime()
            sync()
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val stats = resolver.call(control, "storage-audio-stats", null, null)!!
            assertEquals("No saved audio body may be opened during incremental sync", 0, stats.getInt("guardedOpens"))
            assertEquals(1002, cache.state.value.tracks.size)
            assertEquals(1002, cache.state.value.checked)
            assertEquals(setOf("yandex:1:7", "yandex:2:7"), h.resolved.drop(resolved).map { it.second }.toSet())
            assertEquals(0, cache.state.value.audioFailures)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("offlineSyncMetric", "cached=1000 new=2 elapsedMs=$elapsed oldAudioOpens=${stats.getInt("guardedOpens")}")
            })
        } finally {
            resolver.call(control, "storage-guard-existing-audio", "open", null)
            compose.runOnIdle { cache.cancel(); h.player.stop(); h.offlineSnapshotOverride = null }
            runBlocking { h.offlineStore.retain(owner, setOf("1", "2")) }
            compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
            runBlocking { h.offlineStore.move(null) { _, _ -> } }
            compose.runOnIdle { cache.reloadCache() }
        }
    }
    @Test fun repairedSafPictureRefreshesAtTheSameUriAndCoarseTimestamp() {
        sync()
        val resolver = compose.activity.contentResolver
        val control = Uri.parse("content://dev.petrov.ymplayer2.test.control")
        resolver.call(control, "storage-reset", null, null)
        runBlocking { h.offlineStore.move(TestStorageProvider.tree.toString()) { _, _ -> } }
        try {
            resolver.call(control, "storage-catalog-constant-mtime", "true", null)
            compose.runOnIdle { cache.reloadCache() }
            waitFor { cache.state.value.ready && cache.state.value.tracks.size == 2 }
            val track = cache.state.value.tracks.first { it.tasteTarget().key == "1" }
            val picture = Uri.parse(track.artworkUri)
            val data = resolver.openInputStream(picture)!!.use { it.readBytes() }
            data[0] = (data[0].toInt() xor 0x55).toByte()
            resolver.openOutputStream(picture, "wt")!!.use { it.write(data) }
            compose.onNodeWithTag("player_source_offline").performClick()
            waitFor { h.player.state.value.current?.tasteTarget()?.key == "1" && h.player.state.value.playing }
            compose.onNodeWithTag("artwork_placeholder").assertIsDisplayed()
            val position = h.player.state.value.positionSeconds
            audit()
            assertEquals(track.artworkUri, cache.state.value.tracks.first { it.tasteTarget().key == "1" }.artworkUri)
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("track_artwork").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("track_artwork").assertIsDisplayed()
            assertTrue(h.player.state.value.playing)
            assertTrue("Artwork repair must not rewind the playing audio", h.player.state.value.positionSeconds >= position)
            val firstRevision = cache.state.value.tracks.first { it.tasteTarget().key == "1" }.artworkRevision
            resolver.openOutputStream(picture, "wt")!!.use { it.write(data) }
            audit()
            assertNotEquals("Repeated repairs of identical artwork need a fresh persisted revision even with coarse mtime",
                firstRevision, cache.state.value.tracks.first { it.tasteTarget().key == "1" }.artworkRevision)
            assertTrue(h.player.state.value.playing)
        } finally {
            resolver.call(control, "storage-catalog-constant-mtime", "false", null)
            compose.runOnIdle { cache.cancel(); h.player.stop() }
            runBlocking { h.offlineStore.move(null) { _, _ -> } }
            compose.runOnIdle { cache.reloadCache() }
        }
    }
    @Test fun safArtworkIsDisplayedAfterMovingTheOfflineCache() {
        sync()
        val resolver = compose.activity.contentResolver
        val control = Uri.parse("content://dev.petrov.ymplayer2.test.control")
        resolver.call(control, "storage-reset", null, null)
        runBlocking { h.offlineStore.move(TestStorageProvider.tree.toString()) { _, _ -> } }
        try {
            compose.runOnIdle { cache.reloadCache() }
            waitFor { cache.state.value.ready && cache.state.value.tracks.size == 2 }
            val uri = Uri.parse(cache.state.value.tracks.first().artworkUri)
            assertEquals("content", uri.scheme)
            resolver.openInputStream(uri)!!.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                assertNotNull("The saved PNG is readable through SAF independently of the UI", bitmap)
                bitmap!!.recycle()
            }
            compose.onNodeWithTag("player_source_offline").performClick()
            waitFor { h.player.state.value.origin.source == PlaybackSource.OFFLINE && h.player.state.value.current != null }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("track_artwork").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("track_artwork").assertIsDisplayed()
        } finally {
            compose.runOnIdle { h.player.stop(); cache.cancel() }
            runBlocking { h.offlineStore.move(null) { _, _ -> } }
            compose.runOnIdle { cache.reloadCache() }
        }
    }
    @Test fun ordinarySyncOfReadySafCacheDoesNotReadSavedAudioAgain() {
        sync()
        val resolver = compose.activity.contentResolver
        val control = Uri.parse("content://dev.petrov.ymplayer2.test.control")
        resolver.call(control, "storage-reset", null, null)
        runBlocking { h.offlineStore.move(TestStorageProvider.tree.toString()) { _, _ -> } }
        try {
            compose.runOnIdle { cache.reloadCache() }
            waitFor { cache.state.value.ready && cache.state.value.tracks.size == 2 }
            val before = resolver.call(control, "storage-audio-stats", null, null)!!.getInt("opens")
            sync()
            assertEquals(2, cache.state.value.tracks.size)
            assertEquals("Checking for new downloads must not reread unchanged audio bodies", 0,
                resolver.call(control, "storage-audio-stats", null, null)!!.getInt("opens") - before)
        } finally {
            compose.runOnIdle { h.player.stop(); cache.cancel() }
            runBlocking { h.offlineStore.move(null) { _, _ -> } }
            compose.runOnIdle { cache.reloadCache() }
        }
    }
    @Test fun fastCatalogDoesNotAuditAllBytesButSelectedDamagedAudioIsRejected() {
        sync()
        val damaged = audio("1")
        File(damaged.path + ".sha256").writeText("0".repeat(64))
        assertEquals(2, runBlocking { h.offlineStore.catalog(owner) }.size)
        assertNull("Playback still performs full verification", runBlocking { h.offlineStore.audio(owner, "yandex:1:7") })
        assertEquals(listOf("2"), runBlocking { h.offlineStore.load(owner) }.map { it.tasteTarget().key })
    }
    @Test fun emptyOfflineSourceRemainsSelectableWithoutNetwork() {
        compose.runOnIdle { h.internet.available.value = false }
        compose.onNodeWithTag("player_source_offline").assertIsEnabled().performClick()
        waitFor { h.player.state.value.origin.source == PlaybackSource.OFFLINE }
        assertTrue(h.player.state.value.queue.isEmpty())
        compose.runOnIdle { h.internet.available.value = true }
    }
    private fun audio(id: String) = File(Uri.parse(cache.state.value.tracks.first { it.tasteTarget().key == id }.uri).path!!)
    private fun cover(id: String) = File(Uri.parse(cache.state.value.tracks.first { it.tasteTarget().key == id }.artworkUri).path!!)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun openScreen() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_offline"))
        compose.onNodeWithTag("settings_offline").performClick()
    }
    private fun clickOffline(tag: String) {
        compose.onNodeWithTag("offline_settings_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun openCollection() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("open_offline").performClick()
        compose.onNodeWithTag("offline_list").assertIsDisplayed()
    }
    private fun capture(label: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("offlineSplitScreenshots") != "true") return
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(compose.activity.getExternalFilesDir(null), "offline-split-$label.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    @Test fun settingsAndCollectionAreSeparateAndReturnToTheirOwnParents() {
        sync()
        val first = cache.state.value.tracks.first().id
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_offline"))
        compose.onNodeWithTag("settings_offline").assertTextEquals("Настройки офлайн-кэша")
        val row = compose.onNodeWithTag("settings_offline").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("settings_list").fetchSemanticsNode().boundsInRoot
        assertEquals(list.width - 32 * compose.activity.resources.displayMetrics.density, row.width, 1f)
        compose.onNodeWithTag("manage_folders").assertDoesNotExist()
        capture("settings")
        compose.onNodeWithTag("settings_offline").performClick()
        compose.onNodeWithTag("offline_settings_list").assertIsDisplayed()
        compose.onNodeWithTag("nav_library").assertIsNotSelected()
        compose.onNodeWithTag("offline_list").assertDoesNotExist()
        compose.onNodeWithTag("offline_track_$first").assertDoesNotExist()
        compose.onNodeWithTag("offline_play_all").assertDoesNotExist()
        capture("preferences")
        clickOffline("offline_wifi")
        assertFalse(cache.state.value.wifiOnly)
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.onNodeWithTag("catalog_list").assertDoesNotExist()
        openCollection()
        compose.onNodeWithTag("nav_library").assertIsSelected()
        for (tag in listOf("offline_settings_list", "offline_enabled", "offline_wifi", "offline_sync", "offline_cancel", "offline_clear"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("offline_list").performScrollToNode(hasTestTag("offline_track_$first"))
        compose.onNodeWithTag("offline_track_$first").assertIsDisplayed()
        capture("collection")
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("catalog_list").assertIsDisplayed()
        compose.onNodeWithTag("settings_list").assertDoesNotExist()
    }
    @Test fun guestHasAnEmptyReadOnlyListAndIndependentDeviceSettings() {
        sync()
        val guest = h.library.profiles.first { it.guest }.id
        compose.runOnIdle { h.player.switchProfile(guest) }
        waitFor { cache.state.value.owner == null }
        openCollection()
        compose.onNodeWithTag("offline_empty").assertIsDisplayed()
        compose.onNodeWithTag("offline_play_all").assertDoesNotExist()
        compose.onNodeWithTag("offline_enabled").assertDoesNotExist()
        assertTrue(cache.state.value.tracks.isEmpty())
        openScreen()
        clickOffline("offline_enabled")
        waitFor { !cache.state.value.enabled }
        compose.onNodeWithTag("offline_list").assertDoesNotExist()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
    }
    @Test fun remoteCanOpenCacheSettingsAndChooseCachedTrackFromLibrary() {
        sync()
        val first = cache.state.value.tracks.first().id
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_offline"))
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        fun activate(tag: String) {
            val node = compose.onNodeWithTag(tag)
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            node.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        }
        activate("settings_offline")
        compose.onNodeWithTag("offline_settings_list").assertIsDisplayed()
        activate("offline_enabled")
        waitFor { !cache.state.value.enabled }
        activate("offline_enabled")
        waitFor { cache.state.value.ready && cache.state.value.tracks.size == 2 }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        activate("nav_library"); activate("open_offline")
        compose.onNodeWithTag("offline_list").performScrollToNode(hasTestTag("offline_track_$first"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        activate("offline_track_$first")
        waitFor { h.player.state.value.current?.id == first }
    }
    @Test fun disablingCacheStopsItsUseWithoutDeletingDownloadsAndCanBeReenabled() {
        sync(); assertEquals(2, cache.state.value.tracks.size)
        val savedAudio = audio("1")
        openScreen(); clickOffline("offline_enabled")
        waitFor { !cache.state.value.enabled }
        compose.onNodeWithTag("offline_settings_list").performScrollToNode(hasTestTag("offline_sync"))
        compose.onNodeWithTag("offline_sync").assertIsNotEnabled()
        assertTrue(savedAudio.isFile)
        assertTrue(cache.tracks("owner").isEmpty())
        assertNull(runBlocking { cache.audio("owner", "yandex:1:10") })
        openCollection()
        compose.onNodeWithTag("offline_empty").assertIsDisplayed()
        compose.onNodeWithTag("offline_enabled").assertDoesNotExist()
        openScreen()
        clickOffline("offline_enabled")
        waitFor { cache.state.value.ready && cache.state.value.tracks.size == 2 }
        assertTrue(savedAudio.isFile)
    }
    @Test fun syncScreenThenPlayWithNetworkDisabledAndRestorePaused() {
        runBlocking { h.history.clear("owner") }
        openScreen(); clickOffline("offline_sync")
        waitFor { !cache.state.value.running && cache.state.value.tracks.size == 2 }
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(compose.activity.getExternalFilesDir(null), "offline-ready.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
        }
        assertTrue(cache.state.value.tracks.all { it.offline && File(Uri.parse(it.uri).path!!).isFile && File(Uri.parse(it.artworkUri).path!!).isFile })
        val resolved = h.resolved.size
        compose.runOnIdle { h.streamFailure = MusicFailure.NETWORK; h.failure = MusicFailure.NETWORK; h.offlineNetwork = false }
        openCollection()
        compose.onNodeWithTag("offline_list").performScrollToNode(hasTestTag("offline_play_all"))
        compose.onNodeWithTag("offline_play_all").performClick()
        waitFor { h.player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { h.player.skip(1) }
        waitFor { h.player.state.value.current?.id == "yandex:2:7" && h.player.state.value.positionSeconds >= 1 }
        val history = runBlocking { h.history.page("owner").items }
        assertEquals(listOf("yandex:2:7", "yandex:1:7"), history.map { it.track.id })
        assertTrue(history.all { it.track.uri == null })
        compose.runOnIdle { h.player.toggle(); h.restartEngine() }
        waitFor { h.player.state.value.connected && h.player.state.value.current?.id == "yandex:2:7" }
        assertFalse(h.player.state.value.playing)
        assertEquals(history, runBlocking { h.history.page("owner").items })
        compose.runOnIdle { h.player.toggle() }
        waitFor { h.player.state.value.playing && h.player.state.value.positionSeconds >= 1 }
        assertEquals("Offline playback must not resolve a network URL", resolved, h.resolved.size)
    }
    @Test fun damagedAudioAndCoverAreRepairedIndependently() {
        sync(); assertEquals(2, cache.state.value.tracks.size)
        val audio1 = audio("1"); val audio2 = audio("2"); val cover1 = cover("1"); val cover2 = cover("2")
        val originalAudio = hash(audio1); val untouchedAudio = hash(audio2); val untouchedCover = hash(cover1); val originalCover = hash(cover2)
        RandomAccessFile(audio1, "rw").use { it.seek(it.length() / 2); val byte = it.read(); it.seek(it.length() / 2); it.write(byte xor 0x55) }
        RandomAccessFile(cover2, "rw").use { it.setLength(it.length() / 2) }
        val resolved = h.resolved.size
        audit()
        assertEquals(2, cache.state.value.tracks.size); assertEquals(0, cache.state.value.audioFailures); assertEquals(0, cache.state.value.coverFailures)
        assertEquals(originalAudio, hash(audio1)); assertEquals(originalCover, hash(cover2))
        assertEquals(untouchedAudio, hash(audio2)); assertEquals(untouchedCover, hash(cover1))
        assertEquals("Good audio is not downloaded to repair its cover", resolved + 1, h.resolved.size)
    }
    @Test fun badAudioDoesNotDeleteHealthyCoverAndRetryRepairsOnlyAudio() {
        sync(); val file = audio("1"); val artwork = cover("1"); val before = hash(artwork)
        file.writeBytes(byteArrayOf(1, 2, 3))
        compose.runOnIdle { h.brokenStreamTrackIds = setOf("yandex:1:7") }
        sync(); assertEquals(1, cache.state.value.audioFailures); assertEquals(before, hash(artwork))
        compose.runOnIdle { h.brokenStreamTrackIds = emptySet() }
        sync(); assertEquals(2, cache.state.value.tracks.size); assertEquals(before, hash(artwork))
    }
    @Test fun missingCoverKeepsAudioAndNeverStoresPlaceholderArt() {
        compose.runOnIdle { h.offlineArtwork = null }; sync()
        assertEquals(2, cache.state.value.tracks.size); assertEquals(2, cache.state.value.noCover)
        assertTrue(cache.state.value.tracks.all { it.artworkUri == null })
        assertTrue(audio("1").parentFile!!.listFiles()!!.none { it.name.endsWith(".cover") })
    }
    @Test fun unlikeInFlightCannotLeaveAnyFilesForRemovedTrack() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:1:7" to gate); cache.sync() }
        waitFor { h.resolved.any { it.second == "yandex:1:7" } }
        compose.runOnIdle { h.taste.react(TasteTarget(TasteKind.TRACK, "1:7", "First"), TasteAction.UNLIKE) }
        waitFor { "1" !in h.taste.state.value.shelf(TasteKind.TRACK).list.liked }
        gate.complete(Unit)
        waitFor { !cache.state.value.running }
        assertEquals(listOf("2"), cache.state.value.tracks.map { it.tasteTarget().key })
        assertEquals(listOf("2"), runBlocking { h.offlineStore.load(owner) }.map { it.tasteTarget().key })
    }
    @Test fun cancelKeepsCompletedFileAndNextSyncContinues() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:2:7" to gate); cache.sync() }
        waitFor { h.resolved.any { it.second == "yandex:2:7" } && cache.state.value.tracks.size == 1 }
        val saved = hash(audio("1"))
        compose.runOnIdle { cache.cancel() }; gate.complete(Unit)
        assertFalse(cache.state.value.running)
        compose.runOnIdle { h.streamGates = emptyMap() }; sync()
        assertEquals(2, cache.state.value.tracks.size); assertEquals(saved, hash(audio("1")))
    }
    @Test fun profileSwitchCancelsDownloadAndHidesOutgoingCache() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:2:7" to gate); cache.sync() }
        waitFor { h.resolved.any { it.second == "yandex:2:7" } && cache.state.value.tracks.size == 1 }
        compose.runOnIdle { h.player.switchProfile("road") }; gate.complete(Unit)
        waitFor { cache.state.value.owner?.profileId == "road" && cache.state.value.ready }
        assertTrue(cache.state.value.tracks.isEmpty()); assertFalse(cache.state.value.running)
        compose.runOnIdle { h.player.switchProfile("owner") }
        waitFor { cache.state.value.owner == owner && cache.state.value.ready }
        assertEquals(listOf("1"), cache.state.value.tracks.map { it.tasteTarget().key })
        assertFalse(h.player.state.value.playing)
    }
    @Test fun clearOfflineFilesLeavesServerLikesUntouched() {
        sync(); val root = audio("1").parentFile!!
        openScreen(); clickOffline("offline_clear")
        compose.onNodeWithTag("offline_clear_confirm").performClick()
        waitFor { cache.state.value.ready && cache.state.value.tracks.isEmpty() }
        assertEquals(setOf("1", "2"), h.tasteLists["owner" to TasteKind.TRACK]?.liked)
        assertTrue(!root.exists() || root.listFiles().orEmpty().isEmpty())
    }
    @Test fun checksumlessValidFilesAreVerifiedWithoutDownloadingAgain() {
        sync(); val root = audio("1").parentFile!!; val resolved = h.resolved.size
        root.listFiles()!!.filter { it.name.endsWith(".sha256") }.forEach { assertTrue(it.delete()) }
        sync(); assertEquals(2, cache.state.value.tracks.size); assertEquals(resolved, h.resolved.size)
        assertEquals(4, root.listFiles()!!.count { it.name.endsWith(".sha256") })
    }
    @Test fun foregroundSyncCancelsWithoutStoppingTheAudioPlayer() {
        val gate = CompletableDeferred<Unit>()
        val intent = android.content.Intent(compose.activity, OfflineSyncTestService::class.java)
        compose.runOnIdle {
            h.player.playQueue(h.library.testTracks.map(Track::id))
            h.streamGates = mapOf("yandex:2:7" to gate)
            OfflineSyncTestService.controller = cache
            androidx.core.content.ContextCompat.startForegroundService(compose.activity, intent)
        }
        try {
            waitFor { OfflineSyncTestService.active != null && cache.state.value.running && cache.state.value.tracks.size == 1 && h.player.state.value.positionSeconds >= 1 }
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys activity services dev.petrov.ymplayer2.dev")
            val services = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertTrue(services.contains("OfflineSyncTestService")); assertTrue(services.contains("isForeground=true"))
            File(compose.activity.getExternalFilesDir(null), "offline-service.txt").writeText(services)
            compose.runOnIdle { cache.cancel() }; gate.complete(Unit)
            waitFor { OfflineSyncTestService.active == null }
            assertTrue(h.player.state.value.playing); assertEquals(1, cache.state.value.tracks.size)
        } finally {
            compose.runOnIdle { cache.cancel(); compose.activity.stopService(intent) }
            gate.complete(Unit)
        }
    }
    @Test fun foregroundFullCheckCancelsAndNextSyncRepairsKnownCorruption() {
        sync()
        val damaged = audio("2")
        val original = hash(damaged)
        RandomAccessFile(damaged, "rw").use {
            val offset = it.length() / 2
            it.seek(offset); val byte = it.read(); it.seek(offset); it.write(byte xor 0x55)
        }
        val gate = CompletableDeferred<Unit>()
        val intent = android.content.Intent(compose.activity, OfflineSyncTestService::class.java)
            .putExtra(OfflineSyncService.VERIFY, true)
        compose.runOnIdle {
            h.player.playQueue(h.library.testTracks.map(Track::id))
            h.streamGates = mapOf("yandex:2:7" to gate)
            OfflineSyncTestService.controller = cache
            androidx.core.content.ContextCompat.startForegroundService(compose.activity, intent)
        }
        try {
            waitFor { OfflineSyncTestService.active != null && cache.state.value.running &&
                h.resolved.count { it.second == "yandex:2:7" } == 2 && h.player.state.value.positionSeconds >= 1 }
            compose.runOnIdle { cache.cancel() }; gate.complete(Unit)
            waitFor { OfflineSyncTestService.active == null }
            assertTrue("Cancelling a full cache check must preserve unrelated playback", h.player.state.value.playing)
            compose.runOnIdle { h.streamGates = emptyMap() }
            sync()
            assertEquals("Known corrupt audio must not be accepted by the next fast pass", original, hash(damaged))
            assertEquals(2, cache.state.value.tracks.size)
        } finally {
            compose.runOnIdle { cache.cancel(); compose.activity.stopService(intent) }
            gate.complete(Unit)
        }
    }
}
