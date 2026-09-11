package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountScreenTest {
    @get:Rule val compose = createAndroidComposeRule<AuthTestActivity>()
    private val fixture get() = compose.activity.harness
    private fun openAccount() {
        compose.onNodeWithTag("profiles").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("account_open"))
        compose.onNodeWithTag("account_open").performScrollTo().performClick()
    }
    private fun action(tag: String) {
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun waitPhase(phase: AuthPhase) = compose.waitUntil(7000) { fixture.auth.state.value.phase == phase }
    @Test fun codeSurvivesRecreationAndBrowserBackgroundThenCancelRemovesIt() {
        openAccount(); action("auth_start"); waitPhase(AuthPhase.WAITING)
        compose.onNodeWithTag("auth_code").assertTextEquals("TEST1234")
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag("auth_code_hint"))
        compose.onNodeWithTag("auth_code_hint").assertTextContains("Запишите код перед переходом в браузер", substring = true)
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag("auth_code"))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("auth_code").assertTextEquals("TEST1234")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, fixture.requests)
        action("auth_cancel"); waitPhase(AuthPhase.SIGNED_OUT)
        compose.onNodeWithTag("auth_code").assertDoesNotExist()
        assertTrue(fixture.sessions.isEmpty())
    }
    @Test fun upCancelsLoginAndGuestRemainsLocal() {
        openAccount(); action("auth_start"); waitPhase(AuthPhase.WAITING)
        compose.onNodeWithTag("navigate_up").performClick()
        waitPhase(AuthPhase.SIGNED_OUT)
        compose.onNodeWithTag("account_screen").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("profile_guest"))
        compose.onNodeWithTag("profile_guest").performClick()
        openAccount(); waitPhase(AuthPhase.GUEST)
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag("auth_guest"))
        compose.onNodeWithTag("auth_guest").assertExists(); compose.onNodeWithTag("auth_start").assertDoesNotExist()
        assertEquals(1, fixture.requests)
    }
    @Test fun successfulLoginAndConfirmedLogoutLeaveQueueUntouched() {
        compose.runOnIdle { fixture.player.seek(8) }
        val before = fixture.player.state.value
        openAccount(); action("auth_start")
        compose.runOnIdle { fixture.result = TokenPoll.Granted(OAuthCredentials("test-access", "test-refresh", null)) }
        waitPhase(AuthPhase.SIGNED_IN)
        compose.onNodeWithTag("auth_connected").assertExists()
        compose.activityRule.scenario.recreate()
        assertEquals(AuthPhase.SIGNED_IN, fixture.auth.state.value.phase)
        action("auth_logout")
        compose.onNodeWithText("Отмена", useUnmergedTree = true).performClick()
        assertTrue(fixture.sessions.containsKey("owner"))
        action("auth_logout"); compose.onNodeWithTag("auth_logout_confirm").performClick()
        waitPhase(AuthPhase.SIGNED_OUT)
        assertTrue(fixture.sessions.isEmpty())
        assertEquals(before, fixture.player.state.value)
    }
    @Test fun expiredCodeShowsRetryWithoutKeepingOldCode() {
        compose.runOnIdle { fixture.lifetimeSeconds = 2 }
        openAccount(); action("auth_start"); waitPhase(AuthPhase.ERROR)
        compose.onNodeWithTag("auth_error").assertTextContains("Код истёк", substring = true)
        compose.onNodeWithTag("auth_code").assertDoesNotExist()
        action("auth_start"); waitPhase(AuthPhase.WAITING)
        assertEquals(2, fixture.requests)
        action("auth_cancel")
    }
    @Test fun backgroundNetworkFailureKeepsCodeAndCompletesAfterReturn() {
        compose.runOnIdle { fixture.failure = AuthFailure.NETWORK }
        openAccount(); action("auth_start"); waitPhase(AuthPhase.WAITING)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(7000) { fixture.auth.state.value.issue != null }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag("auth_network_wait"))
        compose.onNodeWithTag("auth_network_wait").assertTextContains("Код сохранён", substring = true)
        compose.onNodeWithTag("auth_start").assertDoesNotExist()
        assertEquals("TEST1234", fixture.auth.state.value.userCode)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { fixture.failure = null; fixture.result = TokenPoll.Granted(OAuthCredentials("test-access", null, null)) }
        waitPhase(AuthPhase.SIGNED_IN)
        assertEquals(1, fixture.requests); assertTrue(fixture.sessions.containsKey("owner"))
    }
    @Test fun accountRetryAfterRotationUsesGrantedTokenWithoutAnotherBrowserCode() {
        compose.runOnIdle { fixture.accountFailure = AuthFailure.NETWORK; fixture.result = TokenPoll.Granted(OAuthCredentials("test-access", null, null)) }
        openAccount(); action("auth_start"); waitPhase(AuthPhase.SIGNED_IN)
        compose.waitUntil(7000) { fixture.auth.state.value.issue != null }
        compose.onNodeWithTag("auth_start").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag("auth_account_retry_message"))
        compose.onNodeWithTag("auth_account_retry_message").assertTextContains("Вход сохранён", substring = true)
        compose.runOnIdle { fixture.accountFailure = null }
        action("auth_retry_account"); compose.waitUntil(7000) { fixture.auth.state.value.account != null }
        assertEquals(1, fixture.requests); assertEquals(1, fixture.polls); assertEquals(2, fixture.accounts)
    }
}
