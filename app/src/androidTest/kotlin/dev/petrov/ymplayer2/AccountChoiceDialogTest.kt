@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.collectAsState
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.PrismTheme
import dev.petrov.ymplayer2.shell.AccountChoiceDialog
import kotlinx.coroutines.delay
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountChoiceDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun noAccountsOnlyOffersConnectionAndCancelCreatesNothing() {
        val users = UserProfiles(listOf(UserProfiles.localProfile()), {})
        var dismissed = false
        var created = false
        compose.setContent { val profiles = users.state.collectAsState().value
            PrismTheme { AccountChoiceDialog(profiles, users,
            { dismissed = true }, { error("No account to select") }, { created = true }) } }
        compose.onNodeWithTag("account_choice_connect").assertExists()
        assertEquals(1, users.state.value.size)
        assertFalse(created)
        compose.onNodeWithTag("account_choice_cancel").performClick()
        assertTrue(dismissed)
        assertFalse(created)
        assertEquals(listOf(UserProfiles.localProfile()), users.state.value)
    }
    @Test fun existingAccountsCanBeChosenWithoutCreatingAProfile() {
        val wife = Profile("wife", "Мария", "", accountConnected = true)
        val users = UserProfiles(listOf(UserProfiles.localProfile(), wife), {})
        var selected: String? = null
        compose.setContent { val profiles = users.state.collectAsState().value
            PrismTheme { AccountChoiceDialog(profiles, users,
            {}, { selected = it }, { error("Must select instead of creating") }) } }
        compose.onNodeWithTag("account_choice_local").assertDoesNotExist()
        compose.onNodeWithTag("account_choice_wife").assertTextContains("Мария").performClick()
        assertEquals("wife", selected)
        assertEquals(2, users.state.value.size)
    }
    @Test fun connectionRequiresConfirmationAndRepeatedClickCreatesOnlyOneProfile() {
        val users = UserProfiles(listOf(UserProfiles.localProfile()), { delay(100) })
        var created = 0
        compose.setContent { val profiles = users.state.collectAsState().value
            PrismTheme { AccountChoiceDialog(profiles, users, {}, {}, { created++ }) } }
        assertEquals(1, users.state.value.size)
        val click = compose.onNodeWithTag("account_choice_connect").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.waitUntil(5_000) { created == 1 && !users.busy.value }
        assertEquals(2, users.state.value.size)
        assertEquals(UserProfiles.localProfile(), users.state.value.first())
        assertTrue(users.state.value.last().provisional)
        assertEquals(listOf(UserProfiles.localProfile()), users.state.value.filterNot(Profile::provisional))
    }
}
