package dev.petrov.ymplayer2


import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real SAF + provider API fixture + production catalog UI and Media3. */
@RunWith(AndroidJUnit4::class)
class CatalogSourcesTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun await(condition: () -> Boolean) = compose.waitUntil(20000, condition)
    private fun capture(name: String) {
        val configuration = compose.activity.resources.configuration
        val device = if (configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "phone"
        val bitmap = if (Build.VERSION.SDK_INT >= 26) compose.onRoot().captureToImage().asAndroidBitmap()
            else androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        // QA screenshots are private test artifacts.
        java.io.File(compose.activity.getExternalFilesDir(null), "catalog-$device-${configuration.fontScale}-$name.png")
            .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun action(tag: String) {
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo()
        if (tag.startsWith("track_")) compose.onNodeWithTag(tag).performTouchInput {
            // Artist names have their own action. Hit the play icon at the row's right edge.
            click(androidx.compose.ui.geometry.Offset(width - 12f, height / 2f))
        } else if (tag.startsWith("filter_") && compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        } else compose.onNodeWithTag(tag).performClick()
    }
    private fun showTrack(id: String) {
        try { compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("track_$id")) }
        catch (failure: AssertionError) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(compose.activity.getExternalFilesDir(null), "catalog-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw AssertionError("Online=${fixture.online.state.value}; playback=${player.state.value}; UI=${compose.onRoot().printToString()}", failure)
        }
    }
    @Before fun prepare() {
        await { fixture.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        val resolver = compose.activity.contentResolver
        val control = android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control")
        resolver.call(control, "fixtures", null, null)
        resolver.call(control, "grantSecondary", null, null)
        runBlocking {
            fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
            fixture.library.addFolder(TestMusicProvider.secondaryTree.toString(), Source.USB)
        }
        await { fixture.library.testTracks.size == 3 && fixture.online.state.value.signedIn }
        await { fixture.offline.state.value.ready && fixture.offline.state.value.owner == OfflineOwner("owner", "1") }
        compose.runOnIdle { fixture.offline.clear() }
        await { fixture.offline.state.value.ready && fixture.offline.state.value.tracks.isEmpty() }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }

    @Test fun searchHandlesRealImeQueriesAndCompositionWhileAudioPlays() {
        compose.onNodeWithTag("nav_library").performClick()
        val firstLocal = fixture.library.testTracks.first { it.available }
        compose.runOnIdle { player.playQueue(listOf(firstLocal.id), firstLocal.id) }
        await { player.state.value.playing && player.state.value.positionSeconds > 0 }
        compose.onNodeWithTag("nav_search").performClick()
        action("search_input")
        compose.waitForIdle()
        // Exercise Android's InputConnection, not the Compose semantics text-injection shortcut.
        compose.runOnIdle {
            fun editor(view: android.view.View): android.view.View? {
                if (view.onCheckIsTextEditor()) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
                return null
            }
            val connection = editor(compose.activity.window.decorView)!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 4096; hintMaxLines = 10 }, 1)
            connection.getTextBeforeCursor(1024, 0)
            connection.getTextAfterCursor(1024, 0)
            connection.getSelectedText(0)
            connection.getCursorCapsMode(android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            if (android.os.Build.VERSION.SDK_INT >= 31) connection.getSurroundingText(1024, 1024, 0)
            connection.requestCursorUpdates(android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE or android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR)
            connection.beginBatchEdit()
            connection.setComposingText("тест", 1)
            connection.finishComposingText()
            connection.endBatchEdit()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("search_input").assertTextContains("тест")
        assertTrue(player.state.value.playing)
        compose.onNodeWithTag("search_input").performImeAction()
        assertFalse(compose.activity.isFinishing)
    }
    @Test fun selectedOnlineSectionCanReceiveVerticalFocusFromBothSides() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        await { fixture.online.state.value.loaded && fixture.taste.state.value.shelf(TasteKind.TRACK).ready }
        val section = compose.onNodeWithTag("online_kind_TRACKS")
        section.assertIsSelected()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        section.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        section.assertIsFocused()
        section.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.onNodeWithTag("online_kind_ALBUMS").assertIsFocused()
        section.assertIsSelected()
        assertEquals(MusicKind.TRACKS, fixture.online.state.value.request.kind)
        compose.onNodeWithTag("my_wave").assertDoesNotExist()
        val source = compose.onNodeWithTag("source_yandex")
        source.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        source.assertIsFocused()
        compose.runOnIdle {
            assertFalse("Native D-pad needs non-touch mode", compose.activity.window.decorView.isInTouchMode)
            assertTrue("The fixture window must receive native keys", compose.activity.window.decorView.hasWindowFocus())
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        try { section.assertIsFocused() }
        catch (failure: AssertionError) { throw AssertionError(compose.onRoot().printToString(), failure) }
        val play = compose.onNodeWithTag("online_play_all")
        play.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        play.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) }
        section.assertIsFocused()
        assertEquals(MusicKind.TRACKS, fixture.online.state.value.request.kind)
        assertFalse(player.state.value.playing)
    }

    @Test fun movingFocusBetweenLibrarySourcesAndTabsDoesNotChangeSelection() {
        compose.onNodeWithTag("nav_library").performClick()
        val requests = fixture.requests
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        for ((from, to) in listOf("source_local" to "source_yandex", "library_folders" to "category_PLAYLISTS")) {
            compose.onNodeWithTag(from).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(from).assertIsSelected().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
            compose.onNodeWithTag(to).assertIsFocused().assertIsNotSelected()
        }
        assertEquals(requests, fixture.requests)
        assertFalse(player.state.value.playing)
    }

    @Test fun verticalEntryReachesSelectedSectionAtEitherEndOfWideRow() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        for (kind in listOf(MusicKind.TRACKS, MusicKind.ALBUMS, MusicKind.ARTISTS, MusicKind.PLAYLISTS)) {
            compose.runOnIdle { fixture.online.collection(kind) }
            await { fixture.online.state.value.loaded && fixture.online.state.value.request.kind == kind }
            val source = compose.onNodeWithTag("source_yandex")
            source.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            source.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            compose.onNodeWithTag("online_kind_${kind.name}").assertIsFocused().assertIsSelected()
            assertEquals(kind, fixture.online.state.value.request.kind)
        }
        assertFalse(player.state.value.playing)
    }

    @Test fun onlineBulkSelectionSurvivesProviderPaginationAndQueuesBothTracks() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.collection }
        fun choose(tag: String) {
            compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
        }
        choose("bulk_start")
        choose("select_yandex:1:7")
        choose("online_more")
        await { fixture.online.state.value.entries.size == 2 }
        choose("select_yandex:2:7")
        compose.onNodeWithTag("bulk_count").assertTextContains("2", substring = true)
        compose.onNodeWithTag("bulk_enqueue").performClick()
        await { player.state.value.queueCount == 2 }
        assertEquals(listOf("yandex:1:7", "yandex:2:7"), runBlocking { player.queuePage(0).items.map(Track::id) })
        assertFalse(player.state.value.playing)
    }

    @Test fun globalSearchLoadsEachSourceOnDemandAndPlaysMixedReferences() {
        compose.onNodeWithTag("nav_search").performClick()
        action("search_input")
        compose.onNodeWithTag("search_input").performTextInput("o")
        compose.onNodeWithTag("search_input").performImeAction()
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.query == "o" }
        val local = fixture.library.testTracks.first { it.source == Source.LOCAL }.id
        val usb = fixture.library.testTracks.first { it.source == Source.USB }.id
        showTrack(local); showTrack(usb); showTrack("yandex:1:7")
        assertTrue(fixture.resolved.isEmpty())
        val requests = fixture.requests
        action("catalog_online_more")
        await { fixture.online.state.value.entries.size == 2 }
        assertEquals(requests + 1, fixture.requests)
        showTrack("yandex:2:7")
        capture("tracks")
        action("catalog_play_all")
        await { player.state.value.playing && player.state.value.queueCount == 5 && player.state.value.positionSeconds >= 1 }
        assertTrue(player.state.value.referenceQueue)
        assertTrue(player.state.value.queue.size <= 3)
        assertEquals((fixture.library.testTracks.map(Track::id) + listOf("yandex:1:7", "yandex:2:7")).toSet(), player.state.value.explicitQueueIds)
        repeat(3) {
            val before = player.state.value.current!!.id
            compose.runOnIdle { player.skip(1) }
            await { player.state.value.current?.id != before }
        }
        await { player.state.value.current?.source == Source.YANDEX && player.state.value.positionSeconds >= 1 }
        action("filter_USB"); showTrack(usb)
        compose.onNodeWithTag("track_$local").assertDoesNotExist()
        compose.onNodeWithTag("track_yandex:1:7").assertDoesNotExist()
        action("filter_YANDEX"); showTrack("yandex:1:7")
        compose.onNodeWithTag("track_$usb").assertDoesNotExist()
    }

    @Test fun commonSearchAndOnlineFailureKeepLocalResultsUsable() {
        compose.onNodeWithTag("nav_search").performClick()
        action("search_input")
        // Search real metadata, not the UI-only label for an absent artist tag.
        compose.onNodeWithTag("search_input").performTextInput("o")
        compose.onNodeWithTag("search_input").performImeAction()
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.query == "o" }
        val local = fixture.library.testTracks.first { it.title.contains("one", true) }
        showTrack(local.id); showTrack("yandex:1:7")
        compose.runOnIdle { fixture.failure = MusicFailure.NETWORK }
        action("search_input"); compose.onNodeWithTag("search_input").performTextReplacement("one")
        compose.onNodeWithTag("search_input").performImeAction()
        await { fixture.online.state.value.issue != null }
        showTrack(local.id)
        action("track_${local.id}")
        await { player.state.value.current?.id == local.id && player.state.value.positionSeconds >= 1 }
        action("catalog_online_issue")
        compose.onNodeWithTag("catalog_online_issue").assertIsDisplayed()
        action("filter_LOCAL"); showTrack(local.id)
        compose.onNodeWithTag("catalog_online_issue").assertDoesNotExist()
    }

    @Test fun collectionKeepsRecommendationsAndAlbumDetailsWhileLocalFilesStaySeparate() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        fun choose(tag: String) {
            compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
        }
        choose("online_recommendations")
        await { fixture.online.state.value.request.recommended && fixture.online.state.value.loaded }
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("local_file_list").assertIsDisplayed()
        compose.onNodeWithTag("open_offline").assertDoesNotExist()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("online_recommendations").assertIsSelected()
        choose("online_kind_ALBUMS")
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.kind == MusicKind.ALBUMS }
        choose("online_entity_7")
        await { fixture.online.state.value.request.entity?.id == "7" && fixture.online.state.value.loaded }
        choose("online_up")
        compose.onNodeWithTag("online_kind_ALBUMS").assertIsSelected()
        compose.runOnIdle { player.switchProfile("guest") }
        await { !fixture.online.state.value.signedIn && player.state.value.profileId == "guest" }
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("local_file_list").assertIsDisplayed()
        compose.onNodeWithTag("online_list").assertDoesNotExist()
    }

    @Test fun offlineFilterUsesOnlyDownloadedLikesAndStartsNoOnlineRequest() {
        compose.runOnIdle {
            fixture.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1"))
            fixture.taste.refresh(TasteKind.TRACK)
        }
        await { fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1") }
        compose.runOnIdle { fixture.offline.sync() }
        await { !fixture.offline.state.value.running && fixture.offline.state.value.tracks.size == 1 }
        compose.onNodeWithTag("nav_search").performClick()
        action("filter_offline")
        val requests = fixture.requests
        showTrack(fixture.library.testTracks.first().id); showTrack("yandex:1:7")
        compose.onNodeWithTag("track_yandex:2:7").assertDoesNotExist()
        action("filter_YANDEX"); showTrack("yandex:1:7")
        assertEquals(requests, fixture.requests)
        compose.runOnIdle { fixture.streamFailure = MusicFailure.NETWORK }
        val resolved = fixture.resolved.size
        action("track_yandex:1:7")
        try { await { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 } }
        catch (failure: AssertionError) { throw AssertionError("Playback=${player.state.value}; offline=${fixture.offline.state.value}; resolved=${fixture.resolved}", failure) }
        assertTrue(player.state.value.current!!.offline)
        assertEquals(resolved, fixture.resolved.size)
    }

    @Test fun offlineSourceSearchStaysInSearchAndFiltersOnlyProfileCache() {
        fun query(value: String) {
            action("search_input")
            compose.onNodeWithTag("search_input").performTextReplacement(value)
            compose.onNodeWithTag("search_input").performImeAction()
        }
        compose.runOnIdle {
            fixture.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1"))
            fixture.taste.refresh(TasteKind.TRACK)
        }
        await { fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1") }
        compose.runOnIdle { fixture.offline.sync() }
        await { !fixture.offline.state.value.running && fixture.offline.state.value.tracks.size == 1 }
        val cached = fixture.offline.state.value.tracks.single()
        compose.onNodeWithTag("nav_search").performClick()
        query("Онлайн")
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.query == "Онлайн" }
        compose.onNodeWithTag("open_offline").performClick()
        compose.onNodeWithTag("open_offline").assertIsSelected()
        compose.onNodeWithTag("nav_search").assertIsSelected()
        compose.onNodeWithTag("offline_list").assertDoesNotExist()
        compose.onNodeWithTag("search_input").assertTextContains("Онлайн")
        val requests = fixture.requests
        for (query in listOf(cached.title, cached.artist, cached.album).filter(String::isNotBlank)) {
            query(query.uppercase())
            showTrack(cached.id)
            compose.onNodeWithTag("track_yandex:2:7").assertDoesNotExist()
            fixture.library.testTracks.forEach { compose.onNodeWithTag("track_${it.id}").assertDoesNotExist() }
        }
        query("нет такого трека в кэше")
        compose.onNodeWithTag("track_${cached.id}").assertDoesNotExist()
        assertEquals("Offline search must not start provider requests", requests, fixture.requests)
        compose.onNodeWithTag("source_yandex").performClick()
        await { fixture.online.state.value.request.query == "нет такого трека в кэше" && fixture.online.state.value.loaded }
        compose.onNodeWithTag("online_query").assertTextContains("нет такого трека в кэше")
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("search_input").assertTextContains("нет такого трека в кэше")
        compose.onNodeWithTag("open_offline").performClick()
        query("")
        showTrack(cached.id)
        compose.runOnIdle { player.switchProfile("guest") }
        await { fixture.offline.state.value.owner == null && player.state.value.profileId == "guest" }
        // The profile change can refocus the search field on short Android 6 windows.
        compose.onNodeWithTag("search_input").performImeAction()
        await { compose.onAllNodesWithTag("open_offline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open_offline").performClick()
        compose.onNodeWithTag("track_${cached.id}").assertDoesNotExist()
        compose.onNodeWithTag("nav_search").assertIsSelected()
        compose.onNodeWithTag("offline_list").assertDoesNotExist()
        capture("offline-search")
    }

    @Test fun usbFolderPlaysRecursivelyWithoutAProviderRequest() {
        compose.onNodeWithTag("nav_library").performClick()
        val requests = fixture.requests
        val usb = fixture.library.testTracks.single { it.source == Source.USB }
        val tree = TestMusicProvider.secondaryTree
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val root = "file_$document"
        compose.onNodeWithTag("local_file_list").performScrollToNode(hasTestTag(root))
        compose.onNodeWithTag(root).performClick()
        val source = runBlocking { fixture.mediaFiles.roots().single { it.rootUri == tree.toString() } }
        val child = runBlocking { fixture.mediaFiles.children(source).single() }
        assertEquals(usb.id, child.trackId)
        assertEquals(listOf(usb.id), runBlocking { fixture.mediaFiles.tracks(listOf(source)).map(Track::id) })
        compose.onNodeWithTag("local_file_list").performScrollToNode(hasTestTag("file_${child.uri}"))
        compose.onNodeWithTag("play_folder").performScrollTo().assertIsEnabled().performClick()
        try { await { player.state.value.current?.id == usb.id && player.state.value.positionSeconds >= 1 } }
        catch (failure: Throwable) { throw AssertionError("Expected ${usb.id}; player=${player.state.value}; UI=${compose.onRoot().printToString()}", failure) }
        assertEquals(requests, fixture.requests)
        compose.onNodeWithTag("files_up").performClick()
        compose.onNodeWithTag("local_file_list").performScrollToNode(hasTestTag(root))
        compose.onNodeWithTag(root).assertIsDisplayed()
    }

    @Test fun tvDpadChoosesOnlineSourceAndStartsShownTrack() {
        if (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("source_yandex").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        await { fixture.online.state.value.loaded }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_play_all"))
        compose.onNodeWithTag("online_play_all").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("online_play_all").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        await { player.state.value.current?.source == Source.YANDEX && player.state.value.positionSeconds >= 1 }
        assertEquals(1, player.state.value.queueCount)
    }
}
