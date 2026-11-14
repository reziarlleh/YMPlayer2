@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.shell.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountActionContinuationTest {
    @get:Rule val compose = createAndroidComposeRule<AccountActionTestActivity>()
    private val fixture get() = compose.activity.harness
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun action(tag: String) {
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun noSavedAccount() = runBlocking { withContext(Dispatchers.Main.immediate) {
        fixture.sessions.clear(); fixture.users.remove("family")
    } }
    @Test fun choosingAnAccountOpensClipsOnceAcrossProfileRootAndRotation() {
        compose.onNodeWithTag("nav_clips").performClick()
        compose.onNodeWithTag("account_choice_family").performClick()
        waitFor { fixture.clipsOpened == 1 }
        assertEquals("family", fixture.player.state.value.profileId)
        assertNull(fixture.model.accountContinuation.value)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(1, fixture.clipsOpened)
    }
    @Test fun choosingAnAccountStartsTheRequestedWaveWithoutASecondClick() {
        compose.onNodeWithTag("player_source_wave").performClick()
        compose.onNodeWithTag("account_choice_family").performClick()
        waitFor { fixture.waveCalls == 1 }
        assertEquals("family", fixture.player.state.value.profileId)
        assertEquals("player", fixture.currentRoute)
        assertEquals(0, fixture.clipsOpened)
    }
    @Test fun dpadAccountChoiceContinuesClipsImmediately() {
        compose.onNodeWithTag("nav_clips").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("account_choice_family").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("account_choice_family").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        waitFor { fixture.clipsOpened == 1 }
        assertEquals("family", fixture.player.state.value.profileId)
    }
    @Test fun choosingAnAccountContinuesRadioAndYandexSearch() {
        compose.onNodeWithTag("nav_radio").performClick()
        compose.onNodeWithTag("account_choice_family").performClick()
        waitFor { fixture.currentRoute == "radio" }
        compose.runOnIdle { fixture.player.switchProfile("local") }
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("account_choice_family").performClick()
        waitFor { fixture.player.state.value.profileId == "family" }
        compose.onNodeWithTag("source_yandex").assertIsSelected()
        assertEquals("search", fixture.currentRoute)
    }
    @Test fun ordinaryProfileChoiceNeverStartsWaveOrClips() {
        compose.onNodeWithTag("profiles").performClick()
        compose.onNodeWithTag("profile_family").performClick()
        waitFor { fixture.player.state.value.profileId == "family" }
        assertFalse(fixture.player.state.value.playing)
        assertEquals(0, fixture.waveCalls); assertEquals(0, fixture.clipsOpened)
    }
    @Test fun aNewConnectionPublishesOnlyAfterNameAndThenContinuesTheOriginalWave() {
        noSavedAccount()
        compose.runOnIdle { fixture.accountGate = CompletableDeferred() }
        compose.onNodeWithTag("player_source_wave").performClick()
        compose.onNodeWithTag("account_choice_connect").performClick()
        waitFor { fixture.users.state.value.any(Profile::provisional) }
        assertEquals(listOf("local"), fixture.users.state.value.filterNot(Profile::provisional).map(Profile::id))
        compose.runOnIdle { fixture.grant = true }
        action("auth_start")
        waitFor { fixture.auth.state.value.phase == AuthPhase.SIGNED_IN }
        assertEquals(0, fixture.waveCalls)
        assertEquals(listOf("local"), fixture.users.state.value.filterNot(Profile::provisional).map(Profile::id))
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { fixture.accountGate!!.complete(Unit) }
        waitFor { fixture.waveCalls == 1 }
        val profile = fixture.users.state.value.last()
        assertEquals("Анна", profile.name); assertFalse(profile.provisional)
        assertEquals(profile.id, fixture.player.state.value.profileId)
        assertEquals(1, fixture.codeRequests)
    }
    @Test fun cancellingConnectionRemovesTheUnfinishedEntryAndThePendingAction() {
        noSavedAccount()
        compose.onNodeWithTag("player_source_wave").performClick()
        compose.onNodeWithTag("account_choice_connect").performClick()
        waitFor { fixture.users.state.value.any(Profile::provisional) }
        action("auth_cancel")
        waitFor { fixture.users.state.value.size == 1 }
        assertEquals("local", fixture.player.state.value.profileId)
        assertNull(fixture.model.accountContinuation.value)
        assertEquals(0, fixture.waveCalls); assertEquals(0, fixture.codeRequests)
    }
    @Test fun cancellingOfferDoesNotCreateOrRunAnything() {
        noSavedAccount()
        compose.onNodeWithTag("nav_clips").performClick()
        compose.onNodeWithTag("account_choice_cancel").performClick()
        compose.activityRule.scenario.recreate()
        assertEquals(listOf("local"), fixture.users.state.value.map(Profile::id))
        assertNull(fixture.model.accountContinuation.value)
        assertEquals(0, fixture.clipsOpened); assertEquals(0, fixture.codeRequests)
    }
}
