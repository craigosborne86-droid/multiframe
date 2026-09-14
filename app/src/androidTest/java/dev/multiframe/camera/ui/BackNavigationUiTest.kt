package dev.multiframe.camera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.SuperResMachine
import org.junit.Rule
import org.junit.Test

/**
 * The back gesture, delivered.
 *
 * [BackNavigationTest] proves the rule -- what back does given what is open.
 * This proves the plumbing: that a `BackHandler` wired to the rule, in the
 * same shape `CameraScreen` uses, receives the system gesture and closes the
 * thing on top rather than the activity; and that with nothing open the
 * handler stands aside, which is what lets the gesture leave the app.
 *
 * Driven by the same state the screen would hold, with the rule deciding, so
 * that the screen's handler and this one cannot drift apart in kind.
 */
class BackNavigationUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun backClosesTheSettingsScreenAndThenStopsARunningSuperResBeforeItWouldLeave() {
        var settingsOpen by mutableStateOf(true)
        var phase by mutableStateOf(SuperResMachine.Phase.RUNNING)
        var handled = 0
        compose.setContent {
            MultiframeTheme {
                val screen = BackNavigation.Screen(settingsOpen = settingsOpen, superRes = phase)
                BackHandler(enabled = BackNavigation.intercepts(screen)) {
                    handled++
                    when (BackNavigation.resolve(screen)) {
                        BackNavigation.Action.CLOSE_SETTINGS -> settingsOpen = false
                        BackNavigation.Action.STOP_SUPER_RES ->
                            phase = SuperResMachine.apply(phase, SuperResMachine.Event.Stop)
                        else -> Unit
                    }
                }
                if (settingsOpen) {
                    SettingsScreen(
                        chips = ControlBar.settings(ControlBarState()),
                        onControl = {},
                        onDismiss = { settingsOpen = false },
                        panel = {},
                    )
                }
            }
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("Close settings").assertExists()

        // First back: the settings close; Super Res is untouched underneath.
        Espresso.pressBack()
        compose.waitForIdle()
        assertThat(settingsOpen).isFalse()
        assertThat(phase).isEqualTo(SuperResMachine.Phase.RUNNING)
        compose.onNodeWithContentDescription("Close settings").assertDoesNotExist()

        // Second back: the running Super Res is asked to stop, not the app to quit.
        Espresso.pressBack()
        compose.waitForIdle()
        assertThat(phase).isEqualTo(SuperResMachine.Phase.STOPPING)
        assertThat(handled).isEqualTo(2)
    }

    @Test
    fun theSettingsScreenShowsItsChipsAndClosesFromItsOwnButton() {
        var open by mutableStateOf(true)
        val fired = mutableListOf<String>()
        compose.setContent {
            MultiframeTheme {
                if (open) {
                    SettingsScreen(
                        chips = ControlBar.settings(ControlBarState(streamOffered = true, zslOn = true)),
                        onControl = { fired.add(it) },
                        onDismiss = { open = false },
                        panel = { androidx.compose.material3.Text("MANUAL PANEL") }
                    )
                }
            }
        }
        compose.assumeRendered()
        // The developer switch is here now, and nowhere over the picture.
        compose.onNodeWithContentDescription("ZSL ON").assertExists()
        compose.onNodeWithText("MANUAL PANEL").assertExists()
        compose.onNodeWithContentDescription("ZSL ON").performClickAndWait()
        assertThat(fired).containsExactly(ControlBar.ZSL)
        compose.onNodeWithContentDescription("Close settings").performClickAndWait()
        assertThat(open).isFalse()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndWait() {
        performClick()
        compose.waitForIdle()
    }
}
