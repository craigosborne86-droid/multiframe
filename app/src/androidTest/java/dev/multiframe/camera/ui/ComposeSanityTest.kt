package dev.multiframe.camera.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Does Compose render at all in this environment?
 *
 * Every UI test rests on this, and on a locked device it is not a given: the
 * rule launches an activity, and an activity behind the keyguard may never
 * reach the resumed state that composition requires. A test that only renders
 * and never queries would pass regardless, proving nothing.
 */
class ComposeSanityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTrivialCompositionIsActuallyThere() {
        compose.setContent { Text("hello from compose") }
        compose.assumeRendered()

        compose.onNodeWithText("hello from compose").assertIsDisplayed()
    }
}
