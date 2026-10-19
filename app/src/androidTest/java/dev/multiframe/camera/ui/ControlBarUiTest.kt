package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.SuperResMachine.Phase
import dev.multiframe.camera.pipeline.CaptureMode
import org.junit.Rule
import org.junit.Test

/** Active Revision 05 controls. */
class ControlBarUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun revision05PhotoNightChangesOnlyWhenEnabled() {
        var selected by mutableStateOf(CaptureMode.PHOTO)
        var enabled by mutableStateOf(true)
        val selections = mutableListOf<CaptureMode>()
        compose.setContent {
            Revision05ConsoleFixture(mode = selected, enabled = enabled, onMode = {
                selections += it; selected = it
            })
        }
        compose.assumeRendered()
        compose.onNodeWithTag("rangefinder-mode-photo").assertIsSelected()
        compose.onNodeWithTag("rangefinder-mode-night").performTouchInput { click() }
        compose.onNodeWithTag("rangefinder-mode-night").assertIsSelected()
        compose.onNodeWithTag("mechanical-mode-2").assertDoesNotExist()
        assertThat(selections).containsExactly(CaptureMode.NIGHT)

        enabled = false
        compose.waitForIdle()
        compose.onNodeWithTag("rangefinder-mode-photo").assertIsNotEnabled()
        compose.onNodeWithTag("rangefinder-mode-photo").performTouchInput { click() }
        assertThat(selected).isEqualTo(CaptureMode.NIGHT)
        assertThat(selections).containsExactly(CaptureMode.NIGHT)
    }

    @Test fun mechanicalUtilityHonoursBusyAndReportsItsState() {
        var active by mutableStateOf(false)
        var enabled by mutableStateOf(false)
        var taps = 0
        compose.setContent {
            Revision05ConsoleFixture(torch = active, enabled = enabled,
                onTorch = { taps++; active = !active })
        }
        compose.assumeRendered()
        val torch = compose.onNodeWithTag("rangefinder-torch")
        torch.assertIsNotEnabled().performTouchInput { click() }
        assertThat(taps).isEqualTo(0)
        enabled = true
        compose.waitForIdle()
        torch.assertIsEnabled().performTouchInput { click() }
        torch.assertIsSelected().assertContentDescriptionEquals("Torch: Light")
        assertThat(taps).isEqualTo(1)
    }

    @Test fun mechanicalProSwitchReportsItsEngagedState() {
        var engaged by mutableStateOf(false)
        var taps = 0
        compose.setContent {
            Revision05ConsoleFixture(open = engaged, onPro = { taps++; engaged = !engaged })
        }
        compose.assumeRendered()
        val pro = compose.onNodeWithTag("rangefinder-pro")
        pro.assertIsNotSelected().assertContentDescriptionEquals("Pro controls: Pro")
        pro.performTouchInput { click() }
        pro.assertIsSelected().assertContentDescriptionEquals("Pro controls: Hide Pro")
        compose.onNodeWithTag("persistent-shutter").assertIsDisplayed()
        assertThat(taps).isEqualTo(1)
    }

    @Test fun activeServiceManualDispatchesDngGuidesAndHistogram() {
        val fired = mutableListOf<String>()
        val state = ControlBarState(streamOffered = true, manualOffered = true,
            superResOffered = true)
        compose.setContent {
            SettingsScreen(chips = ControlBar.settings(state), onControl = { fired += it },
                onDismiss = {}, panel = {})
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("DNG ON").performScrollTo().performTouchInput { click() }
        compose.onNodeWithContentDescription("Composition grid OFF").performScrollTo().performTouchInput { click() }
        compose.onNodeWithContentDescription("Preview RGB histogram OFF").performScrollTo().performTouchInput { click() }
        assertThat(fired).containsExactly(ControlBar.DNG, ControlBar.GUIDES,
            ControlBar.HISTOGRAM).inOrder()
    }

    @Test fun busyServiceManualBlocksCaptureSettingsButKeepsGuidesLive() {
        val fired = mutableListOf<String>()
        val state = ControlBarState(streamOffered = true, manualOffered = true,
            superResOffered = true, busy = true)
        compose.setContent {
            SettingsScreen(chips = ControlBar.settings(state), onControl = { fired += it },
                onDismiss = {}, panel = {})
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("DNG ON").performScrollTo().performTouchInput { click() }
        assertThat(fired).isEmpty()
        compose.onNodeWithContentDescription("Composition grid OFF").performScrollTo().performTouchInput { click() }
        assertThat(fired).containsExactly(ControlBar.GUIDES)
    }

    @Test fun revision05SafeTopCornerOpensSettings() {
        var opened = 0
        compose.setContent {
            Revision05ConsoleFixture(onSettingsOpen = { opened++ })
        }
        compose.assumeRendered()
        compose.onNodeWithTag("titanium-labs-button").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Settings: Settings")
            .assertIsDisplayed().performTouchInput { click() }
        assertThat(opened).isEqualTo(1)
    }
}
