package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.designsystem.ThemePreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearancePersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun systemChoiceIsDurableBeforeStopAndSurvivesActivityRecreation() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("theme_system").performScrollTo().performClick()
        assertEquals("system", ThemePreferences(compose.activity).read())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("theme_system").assertIsSelected()
        assertEquals("system", ThemePreferences(compose.activity).read())
        compose.onNodeWithTag("theme_light").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("theme_light").assertIsSelected()
        assertEquals("light", ThemePreferences(compose.activity).read())
        compose.onNodeWithTag("theme_system").performClick()
    }
}
