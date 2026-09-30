package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlinePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(20000, condition)
    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle {
            player.stop(); player.switchProfile("owner"); player.clearQueue()
            player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false)
        }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.testTracks.size == 2 && fixture.online.state.value.signedIn }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun search() {
        compose.onNodeWithTag("nav_search").performClick(); compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_query"))
        compose.onNodeWithTag("online_query").performTextInput("fixture")
        try { waitFor { fixture.online.state.value.loaded && !fixture.online.state.value.loading } }
        catch (e: AssertionError) { throw AssertionError("Fixture search state: ${fixture.online.state.value}; requests=${fixture.requests}", e) }
        compose.onNodeWithTag("online_query").performImeAction()
        val label = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("m5Screenshot")
        if (label != null && label.matches(Regex("[a-z0-9-]+"))) {
            compose.waitForIdle()
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(compose.activity.getExternalFilesDir(null), "m5-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun action(tag: String) { compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).performClick() }
    @Test fun onlineQueueStreamsLazilyAndRecoversPausedWithoutSignedUrls() {
        search(); action("online_more"); waitFor { fixture.online.state.value.entries.size == 2 }
        assertTrue(fixture.resolved.isEmpty())
        action("online_play_all")
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertEquals(Source.YANDEX, player.state.value.current!!.source)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val before = player.state.value.positionSeconds; waitFor { player.state.value.positionSeconds >= before + 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { player.seek(8); player.skip(1) }
        waitFor { player.state.value.current!!.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.seek(9); player.toggle() }
        val checkpoint = fixture.checkpointText()
        assertFalse(checkpoint.contains("https://")); assertFalse(checkpoint.contains("content://")); assertFalse(checkpoint.contains("fixture-1"))
        compose.runOnIdle { fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:2:7" }
        assertEquals(9, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
        compose.runOnIdle { player.toggle() }; waitFor { player.state.value.positionSeconds >= 10 }
        assertTrue(fixture.resolved.all { it.first == "owner" })
    }
    @Test fun logoutRemovesOnlineQueueButRetainsLocalTracks() {
        search(); action("online_play_all"); waitFor { player.state.value.positionSeconds >= 1 }
        val local = fixture.library.testTracks.first().id
        compose.runOnIdle { player.enqueue(local); fixture.auth.signOut() }
        waitFor { !fixture.online.state.value.signedIn && player.state.value.queue.none { it.source == Source.YANDEX } }
        assertEquals(listOf(local), player.state.value.queue.map { it.id }); assertFalse(player.state.value.playing)
        assertFalse(fixture.checkpointText().contains("yandex:"))
    }
    @Test fun lateStreamCannotStartAfterProfileSwitch() {
        search(); compose.runOnIdle { fixture.streamDelayMillis = 1500 }; action("online_play_all")
        waitFor { fixture.resolved.isNotEmpty() }
        compose.runOnIdle { player.switchProfile("road") }
        waitFor { player.state.value.profileId == "road" && fixture.auth.state.value.profileId == "road" }
        compose.waitUntil(5000) { fixture.online.state.value.profileId == "road" }
        Thread.sleep(1800)
        assertFalse(player.state.value.playing); assertTrue(player.state.value.queue.none { it.source == Source.YANDEX })
    }
    @Test fun unavailableStreamShowsRetryableErrorAndNextAttemptPlays() {
        search(); compose.runOnIdle { fixture.streamFailure = MusicFailure.NETWORK }; action("online_play_all")
        waitFor { player.state.value.error != null }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { fixture.streamFailure = null; player.toggle() }
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.playing }
        assertNull(player.state.value.error)
    }
    @Test fun detailsBackAndRetryLeaveExistingAudioAlone() {
        val local = fixture.library.testTracks.first().id
        compose.runOnIdle { player.playQueue(listOf(local)) }; waitFor { player.state.value.positionSeconds >= 1 }
        search(); action("online_kind_ALBUMS"); waitFor { fixture.online.state.value.entries.firstOrNull()?.entity != null }
        action("online_entity_7"); waitFor { fixture.online.state.value.request.entity != null }
        // TV IME closes asynchronously; the short-window toolbar returns after its inset update.
        waitFor { compose.onAllNodesWithTag("navigate_up").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("navigate_up").performClick()
        waitFor { fixture.online.state.value.request.entity == null }
        assertEquals(MusicKind.ALBUMS, fixture.online.state.value.request.kind)
        compose.runOnIdle { fixture.failure = MusicFailure.NETWORK; fixture.online.search("other", MusicKind.TRACKS, false) }
        waitFor { fixture.online.state.value.issue != null }
        compose.runOnIdle { fixture.failure = null }; action("online_retry")
        waitFor { fixture.online.state.value.loaded && fixture.online.state.value.issue == null }
        assertEquals(local, player.state.value.current!!.id); assertTrue(player.state.value.playing)
    }
}
