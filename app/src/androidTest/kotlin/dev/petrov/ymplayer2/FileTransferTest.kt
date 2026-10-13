@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FileTransferTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val resolver get() = compose.activity.contentResolver
    private val owner = OfflineOwner("owner", "1")
    private fun waitFor(block: () -> Boolean) = compose.waitUntil(120_000, block)
    private fun control(method: String, arg: String? = null) { resolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, arg, null) }
    @Before fun setup() {
        waitFor { h.library.state.value.ready && h.player.state.value.connected }
        control("storage-reset")
        compose.runOnIdle { h.player.stop(); h.player.switchProfile("owner"); h.offline.setEnabled(true); h.audioExporter.cancel(); h.cacheLocation.move(null) }
        waitFor { !h.cacheLocation.state.value.moving && h.offline.state.value.ready && h.offline.state.value.owner == owner }
        compose.runOnIdle { h.offline.clear() }; waitFor { h.offline.state.value.ready && h.offline.state.value.tracks.isEmpty() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        control("fixtures")
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 }
        compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { !h.taste.state.value.shelf(TasteKind.TRACK).busy }
        compose.runOnIdle { h.offline.sync() }
        waitFor { !h.offline.state.value.running && h.offline.state.value.tracks.size == 2 }
    }
    @After fun cleanup() {
        control("storage-fail-write", "false"); control("storage-space", (2L * 1024 * 1024 * 1024).toString())
        compose.runOnIdle { h.audioExporter.cancel(); h.cacheLocation.cancel(); h.player.stop() }
        waitFor { !h.audioExporter.state.value.running && !h.cacheLocation.state.value.moving }
        compose.runOnIdle { h.cacheLocation.move(null) }; waitFor { !h.cacheLocation.state.value.moving }
    }
    private fun move() {
        compose.runOnIdle { h.cacheLocation.move(TestStorageProvider.tree.toString()) }
        waitFor { !h.cacheLocation.state.value.moving && h.offline.state.value.ready }
    }
    private fun hash(uri: String) = resolver.openInputStream(Uri.parse(uri))!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    private fun entries(tree: Uri): List<Pair<String, Uri>> {
        val doc = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))
        val children = Docs.buildChildDocumentsUriUsingTree(doc, Docs.getDocumentId(doc))
        return resolver.query(children, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to Docs.buildDocumentUriUsingTree(doc, c.getString(0))) }
        }
    }
    @Test fun movePreservesBytesAndCatalogAndOfflinePlaybackThenReturnsInternal() {
        val otherOwner = OfflineOwner("other-profile", "other-account")
        val original = h.library.testTracks.first().copy(id = "yandex:other-upload:7", source = Source.YANDEX)
        runBlocking { h.offlineStore.sync(otherOwner, original, { original.uri!! }, { true }, {}) }
        val otherHash = hash(runBlocking { h.offlineStore.audio(otherOwner, original.id) }!!)
        val old = h.offline.state.value.tracks.associate { it.id to it.uri!! }
        val hashes = old.mapValues { hash(it.value) }
        move()
        assertTrue(h.cacheLocation.state.value.toString(), h.cacheLocation.state.value.external)
        assertEquals(hashes, h.offline.state.value.tracks.associate { it.id to hash(it.uri!!) })
        assertTrue(old.values.none { File(Uri.parse(it).path!!).exists() })
        assertEquals(2, runBlocking { dev.petrov.ymplayer2.offline.LikedFileStore(h.context).load(owner) }.size)
        assertEquals(otherHash, hash(runBlocking { h.offlineStore.audio(otherOwner, original.id) }!!))
        h.offlineNetwork = false
        compose.runOnIdle { h.player.playOffline() }
        waitFor { h.player.state.value.playing && h.player.state.value.positionSeconds > 1 }
        assertEquals(PlaybackSource.OFFLINE, h.player.state.value.origin.source)
        compose.runOnIdle { h.player.stop(); h.cacheLocation.move(null) }
        waitFor { !h.cacheLocation.state.value.moving && h.offline.state.value.ready }
        assertFalse(h.cacheLocation.state.value.external)
        assertEquals(hashes, h.offline.state.value.tracks.associate { it.id to hash(it.uri!!) })
    }
    @Test fun insufficientCapacityAndProviderFailureKeepOriginalCache() {
        val old = h.offline.state.value.tracks.associate { it.id to it.uri!! }
        control("storage-space", "1"); move()
        assertFalse(h.cacheLocation.state.value.external)
        assertTrue(h.cacheLocation.state.value.message!!.contains("Недостаточно"))
        assertTrue(entries(TestStorageProvider.tree).isEmpty())
        control("storage-space", (2L * 1024 * 1024 * 1024).toString()); control("storage-fail-write", "true"); move()
        assertFalse(h.cacheLocation.state.value.external)
        assertEquals(old, h.offline.state.value.tracks.associate { it.id to it.uri!! })
        assertEquals(2, runBlocking { h.offlineStore.load(owner) }.size)
    }
    @Test fun interruptedCopyAndRestartRecoveryPreserveSourceAndRemovePartialFolder() {
        val old = h.offline.state.value.tracks.associate { it.id to hash(it.uri!!) }
        try {
            runBlocking { h.offlineStore.move(TestStorageProvider.tree.toString()) { done, _ -> if (done > 0) throw CancellationException("fixture cancellation") } }
            fail("Expected interrupted copy")
        } catch (_: CancellationException) { }
        assertTrue(entries(TestStorageProvider.tree).isEmpty())
        assertEquals(old, runBlocking { h.offlineStore.load(owner) }.associate { it.id to hash(it.uri!!) })
        val parent = Docs.buildDocumentUriUsingTree(TestStorageProvider.tree, "store")
        val partial = Docs.createDocument(resolver, parent, Docs.Document.MIME_TYPE_DIR, "YMPlayer2-offline-interrupted")!!
        h.context.getSharedPreferences("offline-location", 0).edit().putString("pending", partial.toString()).commit()
        assertEquals(2, runBlocking { dev.petrov.ymplayer2.offline.LikedFileStore(h.context).catalog(owner) }.size)
        assertTrue(entries(TestStorageProvider.tree).isEmpty())
        assertFalse(h.context.getSharedPreferences("offline-location", 0).contains("pending"))
    }
    @Test fun exportCancellationRetainsReadyMp3AndRemovesTemporaryFiles() {
        val tracks = List(50) { h.offline.state.value.tracks.first() }
        compose.runOnIdle { h.audioExporter.ask(AudioExportRequest("owner", "Cancel", tracks = tracks)); h.audioExporter.template("cancel-{number}"); h.audioExporter.start(TestStorageProvider.exportTree.toString()) }
        waitFor { h.audioExporter.state.value.completed > 0 }
        compose.runOnIdle { h.audioExporter.cancel() }; waitFor { !h.audioExporter.state.value.running }
        val rows = entries(TestStorageProvider.exportTree)
        assertEquals(h.audioExporter.state.value.completed, rows.size)
        assertTrue(rows.all { it.first.endsWith(".mp3") })
        assertTrue(rows.isNotEmpty()); rows.forEach { assertMp3(it.second) }
    }
    @Test fun blockedSyncResolutionDoesNotBlockAnAlreadyCachedTrack() {
        val damaged = h.offline.state.value.tracks.first { it.tasteTarget().key == "1" }
        File(Uri.parse(damaged.uri).path!!).writeBytes(byteArrayOf(1, 2, 3))
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<String>()
        runBlocking {
            val sync = launch { h.offlineStore.sync(owner, damaged, { entered.complete(Unit); gate.await() }, { true }, {}) }
            try {
                withTimeout(5000) { entered.await() }
                assertNotNull("A stalled network resolver must not block cached playback", withTimeout(5000) { h.offlineStore.audio(owner, "yandex:2:7") })
            } finally { sync.cancelAndJoin() }
        }
    }
    private fun export(request: AudioExportRequest, template: String) {
        compose.runOnIdle { h.audioExporter.ask(request); h.audioExporter.template(template); h.audioExporter.start(TestStorageProvider.exportTree.toString()) }
        waitFor { !h.audioExporter.state.value.running }
        assertEquals(h.audioExporter.state.value.toString(), 0, h.audioExporter.state.value.failed)
        assertEquals(h.audioExporter.state.value.toString(), 2, h.audioExporter.state.value.completed)
    }
    private fun assertMp3(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(compose.activity, uri, null)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mpeg", format.getString(MediaFormat.KEY_MIME))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 20_000_000)
            extractor.selectTrack(0); assertTrue(extractor.sampleSize > 0)
            assertTrue(resolver.openInputStream(uri)!!.use { val bytes = ByteArray(3); it.read(bytes) == 3 && bytes.contentEquals("ID3".toByteArray()) })
        } finally { extractor.release() }
    }
    @Test fun offlineWavExportsRealMp3WithoutNetworkAndNeverOverwritesCollision() {
        val calls = h.resolvedQualities.size; h.offlineNetwork = false
        val request = AudioExportRequest("owner", "Offline", tracks = h.offline.state.value.tracks)
        export(request, "{number} - {title}")
        val first = entries(TestStorageProvider.exportTree)
        assertEquals(2, first.size); first.forEach { assertMp3(it.second) }
        val hashes = first.associate { it.first to hash(it.second.toString()) }
        export(request, "{number} - {title}")
        val all = entries(TestStorageProvider.exportTree)
        assertEquals(4, all.size); assertEquals(2, all.count { it.first.endsWith(" (2).mp3") })
        assertEquals(hashes, all.filter { it.first in hashes }.associate { it.first to hash(it.second.toString()) })
        assertEquals(calls, h.resolvedQualities.size)
    }
    @Test fun newExportCleansInterruptedPartsButKeepsPublishedFilesWithStableDocumentIds() {
        val parent = Docs.buildDocumentUriUsingTree(TestStorageProvider.exportTree, "export")
        val partName = ".YMPlayer2-export-recovery.part"
        val part = Docs.createDocument(resolver, parent, "application/octet-stream", partName)!!
        resolver.openOutputStream(part)!!.use { it.write(byteArrayOf(1, 2, 3)) }
        val ready = Docs.createDocument(resolver, parent, "audio/mpeg", "kept.mp3")!!
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("cover.mp3").use { input -> resolver.openOutputStream(ready)!!.use(input::copyTo) }
        val before = hash(ready.toString())
        h.context.getSharedPreferences("mp3-export", 0).edit().putString("temporary", org.json.JSONObject()
            .put(part.toString(), partName).put(ready.toString(), ".YMPlayer2-export-already-renamed.part").toString()).commit()
        export(AudioExportRequest("owner", "Recovery", tracks = h.offline.state.value.tracks), "recovered-{number}")
        assertEquals(3, entries(TestStorageProvider.exportTree).size)
        assertEquals(before, hash(ready.toString()))
        assertFalse(entries(TestStorageProvider.exportTree).any { it.first.endsWith(".part") })
        assertEquals("{}", h.context.getSharedPreferences("mp3-export", 0).getString("temporary", null))
    }
    @Test fun anExportDialogFromThePreviousAccountCannotStartAfterSignOut() {
        compose.runOnIdle { h.audioExporter.ask(AudioExportRequest("owner", "Old account", tracks = h.offline.state.value.tracks)); h.auth.signOut() }
        waitFor { h.auth.state.value.phase == AuthPhase.SIGNED_OUT }
        compose.runOnIdle { h.audioExporter.start(TestStorageProvider.exportTree.toString()) }
        assertFalse(h.audioExporter.state.value.running)
        assertTrue(entries(TestStorageProvider.exportTree).isEmpty())
    }
    @Test fun remoteSourceIsDownloadedToDestinationWithTheWorkingMusicUserAgent() {
        val source = resolver.openInputStream(Uri.parse(h.library.testTracks.first().uri))!!.use { it.readBytes() }
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val headers = java.util.concurrent.atomic.AtomicReference<List<String>>()
        val worker = Thread {
          runCatching { server.accept().use { client ->
                val input = client.getInputStream().bufferedReader(); val lines = mutableListOf<String>()
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                headers.set(lines)
                client.getOutputStream().use { output ->
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${source.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(source)
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            val track = h.library.testTracks.first().copy(uri = "http://127.0.0.1:${server.localPort}/fixture.wav")
            compose.runOnIdle { h.audioExporter.ask(AudioExportRequest("owner", "Network", tracks = listOf(track))); h.audioExporter.template("network-{number}"); h.audioExporter.start(TestStorageProvider.exportTree.toString()) }
            waitFor { !h.audioExporter.state.value.running }
            assertEquals(h.audioExporter.state.value.toString(), 1, h.audioExporter.state.value.completed)
            assertEquals(0, h.audioExporter.state.value.failed)
            assertTrue(headers.get().any { it.equals("User-Agent: Yandex-Music-API", true) })
            val rows = entries(TestStorageProvider.exportTree); assertEquals(1, rows.size); assertMp3(rows.single().second)
            assertEquals("{}", h.context.getSharedPreferences("mp3-export", 0).getString("temporary", null))
        } finally { server.close(); worker.join(2000) }
    }
    @Test fun albumAndPlaylistExportFetchEveryPage() {
        export(AudioExportRequest("owner", "Album", entity = MusicEntity("7", "Album", MusicKind.ALBUMS)), "album-{number}")
        export(AudioExportRequest("owner", "Playlist", entity = MusicEntity("1:7", "Playlist", MusicKind.PLAYLISTS)), "list-{number}")
        assertEquals(setOf("album-01.mp3", "album-02.mp3", "list-01.mp3", "list-02.mp3"), entries(TestStorageProvider.exportTree).map { it.first }.toSet())
    }
    @Test fun templateHelpPreviewValidationAndPersistence() {
        compose.runOnIdle { h.audioExporter.ask(AudioExportRequest("owner", "Offline", tracks = h.offline.state.value.tracks)) }
        compose.onNodeWithTag("export_template").performTextReplacement("{number}_{title}.MP3")
        compose.onNodeWithTag("export_name_preview").assertTextContains("01_", substring = true)
        compose.onNodeWithTag("export_template_help").performClick()
        compose.onNodeWithText("Переменные шаблона").assertIsDisplayed()
        compose.onNodeWithTag("export_help_close").performClick()
        compose.onNodeWithTag("export_template").performTextReplacement("{unknown}")
        compose.onNodeWithTag("export_choose_folder").assertIsNotEnabled()
        compose.onNodeWithTag("export_template").performTextReplacement("{artist} - {title}")
        compose.onNodeWithTag("export_choose_folder").assertIsEnabled()
        assertEquals("{artist} - {title}", h.context.getSharedPreferences("mp3-export", 0).getString("template", null))
    }
    @Test fun exportDialogAndHelpFollowAllEightLanguagesWithoutTranslatingTrackNames() {
        val track = h.offline.state.value.tracks.first().copy(title = "My Original Title", artist = "Original Artist")
        compose.runOnIdle { h.audioExporter.ask(AudioExportRequest("owner", "User collection", tracks = listOf(track))); h.audioExporter.template(ExportFileName.DEFAULT) }
        try {
            for (language in AppLanguages.available) {
                compose.runOnIdle { AppLanguages.select(language.tag) }
                compose.onNodeWithText(trMessage("Экспорт MP3")).assertIsDisplayed()
                compose.onNodeWithTag("export_name_preview").assertTextContains("Original Artist - My Original Title", substring = true)
                compose.onNodeWithTag("export_template_help").performClick()
                compose.onNodeWithText(trMessage("Переменные шаблона")).assertIsDisplayed()
                val help = compose.onAllNodes(hasText("{number}", substring = true)).fetchSemanticsNodes()
                assertTrue(language.tag, help.isNotEmpty())
                compose.onNodeWithTag("export_help_close").performClick()
                if (language.tag in listOf("ru", "en")) {
                    compose.waitForIdle()
                    Thread.sleep(150) // Wait for the platform window to draw the already-verified Compose state.
                    val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(compose.activity.getExternalFilesDir(null), "export-${language.tag}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                }
            }
        } finally { compose.runOnIdle { AppLanguages.select("ru") } }
    }
}
