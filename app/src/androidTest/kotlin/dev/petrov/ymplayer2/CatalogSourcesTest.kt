package dev.petrov.ymplayer2


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
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "catalog-$device-${configuration.fontScale}-$name.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/YMPlayer2-QA")
        }
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
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
        val wave = compose.onNodeWithTag("my_wave")
        wave.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        wave.assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        section.assertIsFocused()
        val play = compose.onNodeWithTag("online_play_all")
        play.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        play.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) }
        section.assertIsFocused()
        assertEquals(MusicKind.TRACKS, fixture.online.state.value.request.kind)
        assertFalse(player.state.value.playing)
    }

    @Test fun selectedCatalogRowsRemainReachableAndFocusDoesNotChangeFilters() {
        compose.onNodeWithTag("nav_library").performClick()
        await { fixture.online.state.value.loaded }
        compose.onNodeWithTag("filter_available").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        fun fromTo(from: String, direction: androidx.compose.ui.input.key.Key, to: String) {
            compose.onNodeWithTag(from).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(from).assertIsFocused().performKeyInput { pressKey(direction) }
            compose.onNodeWithTag(to).assertIsFocused()
        }
        val down = androidx.compose.ui.input.key.Key.DirectionDown
        val up = androidx.compose.ui.input.key.Key.DirectionUp
        compose.onNodeWithTag("category_TRACKS").assertIsSelected()
        compose.onNodeWithTag("filter_all").assertIsSelected()
        compose.onNodeWithTag("filter_available").assertIsSelected()
        fromTo("manage_folders", down, "category_TRACKS")
        fromTo("category_TRACKS", down, "filter_all")
        fromTo("filter_all", down, "filter_available")
        fromTo("filter_available", up, "filter_all")
        fromTo("filter_all", up, "category_TRACKS")
        fromTo("category_TRACKS", up, "manage_folders")
        fromTo("manage_folders", up, "bulk_start")
        fromTo("bulk_start", up, "source_local")
        fromTo("source_local", androidx.compose.ui.input.key.Key.DirectionRight, "source_yandex")
        compose.onNodeWithTag("source_local").assertIsSelected()
        compose.onNodeWithTag("source_yandex").assertIsNotSelected()
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
            val wave = compose.onNodeWithTag("my_wave")
            wave.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            wave.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            compose.onNodeWithTag("online_kind_${kind.name}").assertIsFocused().assertIsSelected()
            assertEquals(kind, fixture.online.state.value.request.kind)
        }
        assertFalse(player.state.value.playing)
    }

    @Test fun onlineBulkSelectionSurvivesProviderPaginationAndQueuesBothTracks() {
        compose.onNodeWithTag("nav_library").performClick()
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.collection }
        action("bulk_start")
        action("select_yandex:1:7")
        action("catalog_online_more")
        await { fixture.online.state.value.entries.size == 2 }
        action("select_yandex:2:7")
        compose.onNodeWithTag("bulk_count").assertTextContains("2", substring = true)
        compose.onNodeWithTag("bulk_enqueue").performClick()
        await { player.state.value.queueCount == 2 }
        assertEquals(listOf("yandex:1:7", "yandex:2:7"), runBlocking { player.queuePage(0).items.map(Track::id) })
        assertFalse(player.state.value.playing)
    }

    @Test fun sharedTracksLoadEachSourceOnDemandAndPlayMixedReferences() {
        compose.onNodeWithTag("nav_library").performClick()
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.collection }
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
        compose.onNodeWithTag("search_input").performTextInput("Неизвестный")
        compose.onNodeWithTag("search_input").performImeAction()
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.query == "Неизвестный" }
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

    @Test fun albumDetailsReturnToSharedCatalogAndGuestCannotSeeAccountRows() {
        compose.onNodeWithTag("nav_library").performClick()
        action("category_ALBUMS")
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.kind == MusicKind.ALBUMS }
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasText("Без альбома"))
        capture("albums")
        action("catalog_entity_album:7")
        await { fixture.online.state.value.request.entity?.id == "7" && fixture.online.state.value.loaded }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_up"))
        compose.onNodeWithTag("online_up").performClick()
        action("catalog_entity_album:7") // Parent state/order remains available after returning.
        compose.onNodeWithTag("online_up").performScrollTo().performClick()
        action("category_TRACKS")
        await { fixture.online.state.value.loaded && fixture.online.state.value.request.kind == MusicKind.TRACKS }
        compose.runOnIdle { player.switchProfile("guest") }
        await { player.state.value.profileId == "guest" && fixture.online.state.value.profileId == "guest" }
        assertFalse(fixture.online.state.value.signedIn)
        showTrack(fixture.library.testTracks.first().id)
        compose.onNodeWithTag("track_yandex:1:7").assertDoesNotExist()
        action("filter_YANDEX")
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("catalog_sign_in"))
        compose.onNodeWithTag("catalog_sign_in").assertIsDisplayed()
    }

    @Test fun offlineFilterUsesOnlyDownloadedLikesAndStartsNoOnlineRequest() {
        compose.runOnIdle {
            fixture.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1"))
            fixture.taste.refresh(TasteKind.TRACK)
        }
        await { fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1") }
        compose.runOnIdle { fixture.offline.sync() }
        await { !fixture.offline.state.value.running && fixture.offline.state.value.tracks.size == 1 }
        compose.onNodeWithTag("nav_library").performClick()
        await { fixture.online.state.value.loaded }
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
        compose.onNodeWithTag("open_offline").performClick()
        compose.onNodeWithTag("track_${cached.id}").assertDoesNotExist()
        compose.onNodeWithTag("nav_search").assertIsSelected()
        compose.onNodeWithTag("offline_list").assertDoesNotExist()
        capture("offline-search")
    }

    @Test fun localGenreAndUsbFolderDoNotRequestInventedRemoteSections() {
        compose.onNodeWithTag("nav_library").performClick()
        await { fixture.online.state.value.loaded }
        val requests = fixture.requests
        action("category_GENRES")
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasText("Без жанра"))
        action("category_FOLDERS")
        action("filter_USB")
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasText("secondary"))
        assertEquals(requests, fixture.requests)
        compose.onNodeWithText("secondary").performClick()
        showTrack(fixture.library.testTracks.first { it.source == Source.USB }.id)
    }

    @Test fun tvDpadChoosesOnlineSourceAndStartsShownTrack() {
        if (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return
        compose.onNodeWithTag("nav_library").performClick()
        await { fixture.online.state.value.loaded }
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("filter_YANDEX"))
        compose.onNodeWithTag("filter_YANDEX").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("filter_YANDEX").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("catalog_play_all"))
        compose.onNodeWithTag("catalog_play_all").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("catalog_play_all").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        await { player.state.value.current?.source == Source.YANDEX && player.state.value.positionSeconds >= 1 }
        assertEquals(1, player.state.value.queueCount)
    }
}
