package dev.multiframe.camera.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import org.junit.Rule
import org.junit.Test

/** Capability gating and settings behaviour through the active Titanium controls. */
class ControlsPanelTest {
    @get:Rule val compose = createComposeRule()

    private val fullyCapable = CameraCapabilities(
        hasManualSensor = true, isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000, exposureMaxNs = 500_000_000,
        minFocusDiopters = 10f,
        awbModes = ManualSettings.AWB_LABELS.keys.toList(),
        afModes = listOf(CameraMetadata.CONTROL_AF_MODE_OFF),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -12, evMax = 12, evStep = 1f / 6f,
        sensorOrientation = 90, supportsRaw = true,
        supportedOutputFormats = emptySet(),
    )
    private val basic = fullyCapable.copy(
        hasManualSensor = false, isoMin = null, isoMax = null,
        exposureMinNs = null, exposureMaxNs = null,
        minFocusDiopters = 0f, afModes = emptyList(),
        evMin = 0, evMax = 0, evStep = 0f,
    )

    private fun showDial(caps: CameraCapabilities?): () -> ManualSettings {
        val settings = mutableStateOf(ManualSettings())
        val tab = mutableStateOf(RangefinderTab.ISO)
        compose.setContent {
            Revision05ConsoleFixture(open = true, caps = caps, tab = tab.value,
                onTab = { tab.value = it }, settings = settings.value,
                onSettings = { settings.value = it })
        }
        return { settings.value }
    }

    private fun showService(caps: CameraCapabilities? = fullyCapable, guard: Boolean? = true):
        Pair<() -> Boolean, () -> Int> {
        val tone = mutableStateOf(false)
        val resets = mutableStateOf(0)
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                ServiceCaptureSettings(caps, guard, {}, tone.value,
                    { tone.value = it }, null, {}, onReset = { resets.value++ })
            }
        }
        return { tone.value } to { resets.value }
    }

    @Test fun resetAndDevelopmentControlsRemainReachableAndReportState() {
        val (tone, resets) = showService()
        compose.onNodeWithContentDescription("GUARD ON").assertIsDisplayed()
        compose.onNodeWithText("Protects bright highlights during capture.").assertIsDisplayed()
        compose.onNodeWithContentDescription("LOCAL TONE OFF").performClick()
        compose.onNodeWithText("Lifts dark areas while preserving contrast and texture.").assertIsDisplayed()
        assertThat(tone()).isTrue()
        compose.onNodeWithContentDescription("RESET ALL SETTINGS RESET").performClick()
        assertThat(resets()).isEqualTo(0)
        compose.onNodeWithTag("revision05-reset-cancel").performClick()
        assertThat(resets()).isEqualTo(0)
        compose.onNodeWithContentDescription("RESET ALL SETTINGS RESET").performClick()
        compose.onNodeWithTag("revision05-reset-confirm").performClick()
        assertThat(resets()).isEqualTo(1)
    }

    @Test fun guardIsAbsentWithoutACompatibleStream() {
        showService(guard = null)
        compose.onNodeWithContentDescription("GUARD ON").assertDoesNotExist()
        compose.onNodeWithContentDescription("GUARD OFF").assertDoesNotExist()
    }

    @Test fun actualControlsHaveNoDeveloperOnlySwitches() {
        showService()
        for (label in listOf("ISP ON", "A/B ON", "DCG ON", "MERGE ON", "ZSL ON", "2X ON")) {
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("GUARD ON").assertExists()
    }

    @Test fun manualTabsFollowSensorCapabilities() {
        showDial(fullyCapable)
        compose.onNodeWithTag("mechanical-tab-exp").assertIsEnabled()
        compose.onNodeWithTag("mechanical-tab-focus").assertIsEnabled()
        compose.onNodeWithTag("mechanical-tab-frames").assertIsEnabled()
    }

    // A control the camera cannot use is absent, not greyed (CLAUDE.md, Hardware Assumptions).
    @Test fun unsupportedManualActionsAreAbsentOnBasicCamera() {
        showDial(basic)
        compose.onNodeWithTag("mechanical-tab-exp").assertDoesNotExist()
        compose.onNodeWithTag("mechanical-subtab-iso").assertDoesNotExist()
        compose.onNodeWithTag("mechanical-subtab-shutter").assertDoesNotExist()
        compose.onNodeWithText("Manual exposure unavailable on this camera").assertDoesNotExist()
        compose.onNodeWithTag("revision05-manual").assertDoesNotExist()
        compose.onNodeWithTag("mechanical-tab-focus").assertDoesNotExist()
        compose.onNodeWithTag("revision05-focus-manual").assertDoesNotExist()
        compose.onNodeWithTag("mechanical-tab-frames").assertIsEnabled()
    }

    @Test fun movingIsoDetentUpdatesManualExposureState() {
        val settings = showDial(fullyCapable)
        assertThat(settings().manualExposure).isFalse()
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertThat(settings().manualExposure).isTrue()
        assertThat(settings().iso).isNotEqualTo(100)
    }

    @Test fun capabilitySummaryIsStillVisibleInTheServiceManual() {
        showService()
        compose.onNodeWithText(fullyCapable.summary()).assertIsDisplayed()
    }

    @Test fun unresolvedCapabilitiesShowNoDeadControls() {
        showDial(null)
        compose.onNodeWithTag("rangefinder-dial-ruler").assertDoesNotExist()
        compose.onNodeWithText("Camera controls unavailable").assertExists()
        compose.onNodeWithTag("revision05-manual").assertDoesNotExist()
    }
}
