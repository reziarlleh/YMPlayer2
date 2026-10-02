@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TasteIconsTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(25000, condition)
    private fun icon(kind: TasteKind, id: String, action: String = "like", location: String = "player") =
        compose.onNodeWithTag("${location}_taste_${kind.name}_${id}_$action")
    private fun unknown(node: SemanticsNodeInteraction) = node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)).assertIsNotEnabled()
    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && fixture.player.state.value.connected }
        compose.runOnIdle { fixture.player.stop(); fixture.player.switchProfile("owner"); fixture.player.clearQueue() }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.testTracks.size == 2 && fixture.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle { fixture.tasteReadGate?.complete(Unit); fixture.player.stop() } }
    private fun loadTrack(collaborators: Boolean = false) {
        compose.runOnIdle { fixture.collaborators = collaborators; fixture.online.collection() }
        waitFor { fixture.online.state.value.loaded }
        compose.runOnIdle { fixture.player.playQueue(fixture.online.state.value.entries.map { it.id }) }
        waitFor { fixture.player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { fixture.player.toggle() }
    }
    private fun refresh() {
        compose.runOnIdle { TasteKind.entries.forEach(fixture.taste::refresh) }
        waitFor { TasteKind.entries.all { fixture.taste.state.value.shelf(it).let { s -> s.ready && !s.busy } } }
    }
    private fun screenshot(label: String) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("tasteScreenshot") != "true") return
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "taste-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun existingMarksAreVisibleAndCollaboratingArtistsStayIndependent() {
        loadTrack(collaborators = true)
        compose.runOnIdle {
            fixture.tasteLists["owner" to TasteKind.TRACK] = TasteList(liked = setOf("1"))
            fixture.tasteLists["owner" to TasteKind.ARTIST] = TasteList(blocked = setOf("5"))
        }
        refresh()
        icon(TasteKind.TRACK, "1").assertIsDisplayed().assertIsOn()
        icon(TasteKind.TRACK, "1", "block").assertIsOff()
        icon(TasteKind.ARTIST, "5", "block").assertDoesNotExist()
        screenshot("player")
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("taste_ARTIST_5_block").performScrollTo().assertTextContains("Снова предлагать этого исполнителя")
        compose.onNodeWithTag("taste_ARTIST_8_like").performScrollTo().performClick()
        waitFor { "8" in fixture.taste.state.value.shelf(TasteKind.ARTIST).list.liked }
        compose.onNodeWithTag("taste_ARTIST_8_like").assertTextContains("Убрать из любимых исполнителей")
        compose.onNodeWithTag("taste_ARTIST_5_block").performScrollTo().performClick()
        waitFor { fixture.taste.state.value.shelf(TasteKind.ARTIST).list.blocked.isEmpty() }
        compose.onNodeWithTag("taste_ARTIST_5_block").assertTextContains("Никогда не предлагать этого исполнителя")
        assertEquals(setOf("1"), fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked)
        assertEquals(listOf("8" to TasteAction.LIKE, "5" to TasteAction.UNBLOCK), fixture.tasteWrites.map { it.first.id to it.second })
        assertFalse(fixture.player.state.value.playing)
    }
    @Test fun unconfirmedWriteShowsUnknownAndCanReloadActualServerMark() {
        loadTrack()
        compose.runOnIdle { fixture.tasteReadGate = CompletableDeferred(); fixture.tasteReadFailure = MusicFailure.NETWORK }
        icon(TasteKind.TRACK, "1").assertIsOff().performClick()
        icon(TasteKind.TRACK, "1").assertIsNotEnabled().assertIsOff()
        assertEquals(1, fixture.tasteWrites.size)
        compose.runOnIdle { fixture.tasteReadGate!!.complete(Unit) }
        waitFor { fixture.taste.state.value.shelf(TasteKind.TRACK).issue != null }
        unknown(icon(TasteKind.TRACK, "1"))
        unknown(icon(TasteKind.TRACK, "1", "block"))
        screenshot("unknown")
        compose.runOnIdle { fixture.tasteReadFailure = null }
        compose.onNodeWithTag("player_taste_TRACK_1_retry").performClick()
        waitFor { fixture.taste.state.value.shelf(TasteKind.TRACK).ready }
        icon(TasteKind.TRACK, "1").assertIsOn().assertIsEnabled()
        icon(TasteKind.TRACK, "1", "block").assertIsOff()
        assertEquals(1, fixture.tasteWrites.size)
    }
    @Test fun catalogAndArtistIconsUseSharedStateWithoutStartingPlaybackOrOpeningDetails() {
        loadTrack()
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_query"))
        compose.onNodeWithTag("online_query").performTextInput("Онлайн")
        waitFor { fixture.online.state.value.loaded && !fixture.online.state.value.loading }
        compose.onNodeWithTag("online_query").performImeAction()
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("catalog_taste_TRACK_1_block"))
        icon(TasteKind.TRACK, "1", "block", "catalog").performClick()
        waitFor { "1" in fixture.taste.state.value.shelf(TasteKind.TRACK).list.blocked }
        icon(TasteKind.TRACK, "1", "block", "catalog").assertIsOn()
        icon(TasteKind.ARTIST, "5", "block", "catalog_yandex:1:7").assertDoesNotExist()
        screenshot("catalog")
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_kind_ARTISTS"))
        compose.onNodeWithTag("online_kind_ARTISTS").performClick()
        waitFor { fixture.online.state.value.entries.singleOrNull()?.entity?.kind == MusicKind.ARTISTS }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("catalog_taste_ARTIST_5_like"))
        icon(TasteKind.ARTIST, "5", location = "catalog").performClick()
        waitFor { "5" in fixture.taste.state.value.shelf(TasteKind.ARTIST).list.liked }
        icon(TasteKind.ARTIST, "5", location = "catalog").assertIsOn()
        assertNull(fixture.online.state.value.request.entity)
        assertFalse(fixture.player.state.value.playing)
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_entity_5"))
        compose.onNodeWithTag("online_entity_5").performClick()
        waitFor { fixture.online.state.value.request.entity != null }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("detail_taste_ARTIST_5_like"))
        icon(TasteKind.ARTIST, "5", location = "detail").assertIsOn()
    }
    @Test fun resumeRefreshesMarksChangedOutsideApp() {
        loadTrack()
        val harness = fixture
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        harness.tasteLists["owner" to TasteKind.TRACK] = TasteList(liked = setOf("1"))
        harness.tasteLists["owner" to TasteKind.ARTIST] = TasteList(blocked = setOf("5"))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { "1" in harness.taste.state.value.shelf(TasteKind.TRACK).list.liked && "5" in harness.taste.state.value.shelf(TasteKind.ARTIST).list.blocked }
        icon(TasteKind.TRACK, "1").assertIsOn()
        icon(TasteKind.ARTIST, "5", "block").assertDoesNotExist()
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("taste_ARTIST_5_block").performScrollTo().assertTextContains("Снова предлагать этого исполнителя")
        assertTrue(harness.tasteWrites.isEmpty())
    }
}
