package com.smartcess.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.theme.SmartCessTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented accessibility stub: key components expose text to TalkBack.
 * Extend per spec UI/a11y plan (TalkBack-only bill flow, live regions, contrast).
 */
class AccessibilitySmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun offlineBanner_exposesText() {
        composeRule.setContent {
            SmartCessTheme {
                SmartCessBanner(message = "Offline mode. Please review.")
            }
        }
        composeRule.onNodeWithText("Offline mode. Please review.").assertIsDisplayed()
    }
}
