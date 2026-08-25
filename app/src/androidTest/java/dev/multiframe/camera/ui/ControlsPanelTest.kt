package dev.multiframe.camera.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import org.junit.Rule
import org.junit.Test

/**
 * The pro controls.
 *
 * Every control is supposed to be gated on something the hardware actually
 * reports, so that a phone without a focusable lens or without manual exposure
 * gets a smaller panel rather than controls that send requests the camera will
 * reject. That gating is the thing worth testing: it is invisible on the
 * development device, which supports everything.
 */
class ControlsPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val fullyCapable = CameraCapabilities(
        hasManualSensor = true,
        isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000, exposureMaxNs = 500_000_000,
        minFocusDiopters = 10f,
        awbModes = ManualSettings.AWB_LABELS.keys.toList(),
        afModes = listOf(CameraMetadata.CONTROL_AF_MODE_OFF),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -12, evMax = 12, evStep = 1f / 6f,
        sensorOrientation = 90, supportsRaw = true,
        supportedOutputFormats = emptySet(),
    )

    /** A phone with a fixed-focus lens and no manual exposure. */
    private val basic = fullyCapable.copy(
        hasManualSensor = false,
        isoMin = null, isoMax = null,
        exposureMinNs = null, exposureMaxNs = null,
        minFocusDiopters = 0f,
        afModes = emptyList(),
        evMin = 0, evMax = 0, evStep = 0f,
    )

    private fun show(caps: CameraCapabilities?, initial: ManualSettings = ManualSettings()):
        () -> ManualSettings {
        val state = mutableStateOf(initial)
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                ControlsPanel(
                    settings = state.value,
                    caps = caps,
                    onChange = { state.value = it },
                )
            }
        }
        compose.assumeRendered()
        return { state.value }
    }

    // ------------------------------------------------------------------

    @Test
    fun aCapableCameraGetsTheManualControls() {
        show(fullyCapable)

        compose.onNodeWithContentDescription("AE AUTO").assertIsDisplayed()
    }

    @Test
    fun manualExposureIsNotOfferedWhereTheSensorCannotDoIt() {
        // Sending SENSOR_SENSITIVITY to a camera without MANUAL_SENSOR has the
        // whole request rejected, taking every other setting with it.
        show(basic)

        compose.onNodeWithContentDescription("AE AUTO").assertDoesNotExist()
    }

    @Test
    fun manualFocusIsNotOfferedOnAFixedLens() {
        // A fixed-focus lens reports a minimum focus distance of zero, and a
        // focus slider there would do nothing at all.
        show(basic)

        compose.onNodeWithContentDescription("FOCUS AUTO").assertDoesNotExist()
    }

    @Test
    fun togglingManualExposureReportsItBack() {
        // The panel owns no state; it reports changes and is redrawn. If that
        // wiring is wrong the control appears to work and nothing reaches the
        // camera.
        val settings = show(fullyCapable)
        assertThat(settings().manualExposure).isFalse()

        compose.onNodeWithContentDescription("AE AUTO").performClick()
        compose.waitForIdle()

        assertThat(settings().manualExposure).isTrue()
        compose.onNodeWithContentDescription("AE MAN").assertIsDisplayed()
    }

    @Test
    fun theCapabilitySummaryIsShown() {
        // So a user can see what the hardware admits to rather than guessing
        // why a control is missing.
        show(fullyCapable)

        compose.onNodeWithText(fullyCapable.summary()).assertIsDisplayed()
    }

    @Test
    fun noCapabilitiesYetDrawsNothingRatherThanCrashing() {
        // The panel can be composed before the camera has finished binding.
        show(caps = null)

        compose.onNodeWithContentDescription("AE AUTO").assertDoesNotExist()
    }
}
