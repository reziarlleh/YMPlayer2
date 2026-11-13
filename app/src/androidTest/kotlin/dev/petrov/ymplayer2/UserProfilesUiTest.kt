@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.AppLanguages
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserProfilesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private var original = ""
    private val added = mutableListOf<String>()
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(25_000, test)
    private fun userMutation(action: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Main.immediate) { action() } }
    private fun row(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("profiles_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    @Before fun prepare() {
        waitFor { graph.library.state.value.ready && !graph.library.state.value.scanning && graph.playback.state.value.connected }
        original = graph.playback.state.value.profileId
        compose.runOnIdle { graph.playback.stop() }
    }
    @After fun restore() {
        userMutation {
            if (graph.library.profiles.any { it.id == original }) graph.playback.switchProfile(original)
            added.filter { id -> graph.library.profiles.any { it.id == id } }.forEach { graph.users.remove(it) }
        }
    }

    @Test fun createRenameAndDeleteThroughTheActualUserInterface() {
        compose.onNodeWithTag("profiles").performClick()
        row("profile_add").performClick()
        compose.onNodeWithTag("profile_name").performTextInput("Семья UI280")
        compose.onNodeWithTag("profile_save").performClick()
        waitFor { graph.library.profiles.any { it.name == "Семья UI280" } }
        val id = graph.library.profiles.first { it.name == "Семья UI280" }.id
        added += id
        waitFor { graph.playback.state.value.profileId == id }
        assertFalse(graph.playback.state.value.playing)
        compose.onNodeWithTag("profiles").performClick()
        row("profile_menu_$id").performClick()
        compose.onNodeWithTag("profile_rename_$id").performClick()
        compose.onNodeWithTag("profile_name").performTextReplacement("Основной")
        compose.onNodeWithTag("profile_save").performClick()
        waitFor { graph.library.profiles.first { it.id == id }.name == "Основной" }
        compose.runOnIdle { AppLanguages.select("en") }
        row("profile_$id").assertTextContains("Основной")
        row("profile_$original").performClick()
        waitFor { graph.playback.state.value.profileId == original }
        compose.onNodeWithTag("profiles").performClick()
        row("profile_menu_$id").performClick()
        compose.onNodeWithTag("profile_delete_$id").performClick()
        compose.onNodeWithTag("profile_delete_confirm").performClick()
        waitFor { graph.library.profiles.none { it.id == id } }
    }

    @Test fun dpadCanOpenTheAddUserDialogWithoutATouch() {
        compose.onNodeWithTag("profiles").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        row("profile_add").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("profile_add").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("profile_name").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test fun rapidRepeatedSaveCreatesOnlyOneUser() {
        compose.onNodeWithTag("profiles").performClick()
        row("profile_add").performClick()
        compose.onNodeWithTag("profile_name").performTextInput("Rapid UI280")
        val click = compose.onNodeWithTag("profile_save").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        waitFor { graph.library.profiles.any { it.name == "Rapid UI280" } && !graph.users.busy.value }
        compose.waitForIdle()
        val created = graph.library.profiles.filter { it.name == "Rapid UI280" }
        added += created.map(Profile::id)
        assertEquals("Repeated activation before the next frame is one save", 1, created.size)
    }

    @Test fun switchingUsersRestoresTheirOwnTrackAndPositionButNeverStartsPlayback() {
        userMutation {
            graph.library.state.value.roots.forEach { graph.library.forgetFolder(it.uri) }
            compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
            graph.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
        }
        waitFor { graph.library.testTracks.size == 2 }
        val tracks = graph.library.testTracks.sortedBy(Track::id)
        val other = runBlocking { graph.users.create("Водитель UI280") }.also { added += it.id }
        compose.runOnIdle { graph.playback.playQueue(listOf(tracks[0].id)) }
        waitFor { graph.playback.state.value.playing }
        compose.runOnIdle { graph.playback.seek(4); graph.playback.switchProfile(other.id) }
        waitFor { graph.playback.state.value.profileId == other.id && graph.playback.state.value.connected }
        assertFalse(graph.playback.state.value.playing)
        compose.runOnIdle { graph.playback.playQueue(listOf(tracks[1].id)) }
        waitFor { graph.playback.state.value.playing && graph.playback.state.value.current?.id == tracks[1].id }
        compose.runOnIdle { graph.playback.seek(9); graph.playback.switchProfile(original) }
        waitFor { graph.playback.state.value.current?.id == tracks[0].id && graph.playback.state.value.positionSeconds == 4 }
        assertFalse(graph.playback.state.value.playing)
        compose.runOnIdle { graph.playback.switchProfile(other.id) }
        waitFor { graph.playback.state.value.current?.id == tracks[1].id && graph.playback.state.value.positionSeconds == 9 }
        assertFalse(graph.playback.state.value.playing)
    }

    @Test fun deletingTheActiveUserSelectsAnotherPausedUserAndKeepsTheirPreferences() {
        val other = runBlocking { graph.users.create("Удаление UI280") }.also { added += it.id }
        val appearance = compose.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        appearance.edit().putString("mode:$original", "system").putString("mode:${other.id}", "light").commit()
        compose.runOnIdle { graph.playback.switchProfile(other.id) }
        waitFor { graph.playback.state.value.profileId == other.id }
        userMutation { graph.users.remove(other.id) }
        waitFor { graph.library.profiles.none { it.id == other.id } && graph.playback.state.value.profileId != other.id }
        assertFalse(graph.playback.state.value.playing)
        assertFalse(appearance.contains("mode:${other.id}"))
        assertEquals("system", appearance.getString("mode:$original", null))
    }
}
