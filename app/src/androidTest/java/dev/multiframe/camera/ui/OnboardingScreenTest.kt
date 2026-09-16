package dev.multiframe.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** The first screen: the statement word for word, and the one button per step. */
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theStatementIsThePromiseAndContinueIsTheButton() {
        var pressed = 0
        compose.setContent { OnboardingScreen(OnboardingState.Step.INTRODUCE) { pressed++ } }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("mission statement").assertIsDisplayed()
            .assertTextContains("Multiframe merges a burst of raw frames to reduce noise and extend dynamic range. No generative AI. Every pixel is measured.")
        compose.onNodeWithContentDescription("Continue").assertIsDisplayed().performClick()
        assertThat(pressed).isEqualTo(1)
    }

    @Test
    fun aRefusalSaysWhyTheCameraMattersAndAsksAgain() {
        compose.setContent { OnboardingScreen(OnboardingState.Step.DENIED) {} }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("camera refused").assertIsDisplayed()
        compose.onNodeWithContentDescription("Allow camera").assertIsDisplayed()
    }

    @Test
    fun aPermanentRefusalOpensTheSettings() {
        compose.setContent { OnboardingScreen(OnboardingState.Step.DENIED_PERMANENTLY) {} }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("camera refused").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open settings").assertIsDisplayed()
    }
}
