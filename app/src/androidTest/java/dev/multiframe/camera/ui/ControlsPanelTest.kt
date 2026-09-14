package dev.multiframe.camera.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * The manual drawer and the set-once panel.
 *
 * Every control is supposed to be gated on something the hardware actually
 * reports, so that a phone without a focusable lens or without manual exposure
 * gets a smaller drawer rather than controls that send requests the camera
 * will reject. That gating is the thing worth testing: it is invisible on the
 * development device, which supports everything. And nothing a developer
 * flips -- the ISP switch, the A/B pair, the bracket -- is anywhere in either.
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
                ManualPanel(
                    settings = state.value,
                    caps = caps,
                    onChange = { state.value = it },
                )
            }
        }
        compose.assumeRendered()
        return { state.value }
    }

    /** The set-once panel, with a guard to show and a reset to count. */
    private fun showSetOnce(caps: CameraCapabilities? = fullyCapable, guard: Boolean? = true): Pair<() -> Boolean, () -> Int> {
        val tone = mutableStateOf(false)
        val resets = mutableStateOf(0)
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                SetOncePanel(
                    caps = caps,
                    highlightGuard = guard,
                    localTone = tone.value,
                    onLocalTone = { tone.value = it },
                    onReset = { resets.value++ },
                )
            }
        }
        compose.assumeRendered()
        return { tone.value } to { resets.value }
    }

    // ------------------------------------------------------------------

    /**
     * A camera handed to someone else needs a way back. Without this the only
     * route out of a set of controls someone has been playing with is to
     * reinstall the app.
     */
    @Test
    fun thereIsAWayBackToTheDefaults() {
        val (_, resets) = showSetOnce()
        compose.onNodeWithContentDescription("Reset all settings").performClick()
        assertThat(resets()).isEqualTo(1)
    }

    /** The rendering choices are on the set-once panel and report back. */
    @Test
    fun theRenderingChoicesAreHereAndReportBack() {
        val (tone, _) = showSetOnce()
        compose.onNodeWithContentDescription("GUARD ON").assertIsDisplayed()
        compose.onNodeWithContentDescription("LOCAL TONE OFF").performClick()
        assertThat(tone()).isTrue()
    }

    /** No zero-shutter-lag stream, nothing to guard highlights for. */
    @Test
    fun theGuardIsAbsentWhereThereIsNoStreamToGuard() {
        showSetOnce(guard = null)
        compose.onNodeWithContentDescription("GUARD ON").assertDoesNotExist()
        compose.onNodeWithContentDescription("GUARD OFF").assertDoesNotExist()
    }

    /**
     * The developer's switches are gone from the user's app: the ISP switch
     * from the drawer, the A/B pair and the bracket from the set-once panel.
     */
    @Test
    fun noDeveloperSwitchIsDrawnAnywhere() {
        compose.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                ManualPanel(settings = ManualSettings(), caps = fullyCapable, onChange = {})
                SetOncePanel(caps = fullyCapable, highlightGuard = true, superRes = false, outputFormat = null)
            }
        }
        compose.assumeRendered()
        // Both panels are up, with their real controls...
        compose.onNodeWithContentDescription("AE AUTO").assertExists()
        compose.onNodeWithContentDescription("GUARD ON").assertExists()
        // ...and none of the switches a developer used to flip.
        for (label in listOf(
            "ISP ON", "ISP OFF", "A/B ON", "A/B OFF", "DCG ON", "DCG OFF",
            "MERGE ON", "MERGE OFF", "ZSL ON", "ZSL OFF",
        )) {
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }


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
        showSetOnce()

        compose.onNodeWithText(fullyCapable.summary()).assertIsDisplayed()
    }

    @Test
    fun noCapabilitiesYetDrawsNothingRatherThanCrashing() {
        // Either panel can be composed before the camera has finished binding.
        compose.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                ManualPanel(settings = ManualSettings(), caps = null, onChange = {})
                SetOncePanel(caps = null, onReset = {})
            }
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("AE AUTO").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reset all settings").assertDoesNotExist()
    }
}
