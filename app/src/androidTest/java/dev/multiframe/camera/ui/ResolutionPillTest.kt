package dev.multiframe.camera.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.CameraCapabilities
import org.junit.Rule
import org.junit.Test

/**
 * The `12 MP | 50 MP` chip: the sensor's own numbers, selection by weight,
 * and -- the rule the whole hardware matrix rests on -- absent where the
 * sensor has no un-binned mode. The screen composes it behind
 * `CameraCapabilities.supportsFullResolution`; here the pill itself and
 * the gate are checked, with a capability set built by hand, since neither
 * development device has the mode.
 */
class ResolutionPillTest {

    @get:Rule
    val compose = createComposeRule()

    private val binned = CameraCapabilities(
        hasManualSensor = true, isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000, exposureMaxNs = 500_000_000, minFocusDiopters = 10f,
        awbModes = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO), afModes = emptyList(),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -12, evMax = 12, evStep = 1f / 6f, sensorOrientation = 90,
        supportsRaw = true, supportedOutputFormats = emptySet(),
        rawWidth = 4080, rawHeight = 3072,
    )

    @Test
    fun theChipShowsTheSensorsOwnSizesAndSwitchesByATap() {
        val full = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ResolutionPill(
                    full = full.value,
                    binnedMegapixels = 12.5f,
                    fullMegapixels = 50.1f,
                    enabled = true,
                    onFull = { full.value = it },
                )
            }
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("sensor readout 13 MP").assertIsDisplayed()
        compose.onNodeWithContentDescription("sensor readout 50 MP").assertIsDisplayed().performClick()
        assertThat(full.value).isTrue()
        compose.onNodeWithContentDescription("sensor readout 13 MP").performClick()
        assertThat(full.value).isFalse()
    }

    @Test
    fun aSensorWithoutTheModeOffersNoChip() {
        // The gate the screen composes the chip behind.
        assertThat(binned.supportsFullResolution).isFalse()
        // Advertising the capability without a raw size in the mode is not a mode.
        assertThat(binned.copy(ultraHighResolution = true).supportsFullResolution).isFalse()
        // With the capability, a size and the memory it is offered; the
        // labels are then the two raw sizes.
        val ultra = binned.copy(ultraHighResolution = true, fullResolutionRawWidth = 8160, fullResolutionRawHeight = 6144)
        assertThat(ultra.fullResolutionMegapixels).isWithin(0.1f).of(50.1f)
        assertThat(ultra.rawMegapixels).isWithin(0.1f).of(12.5f)
    }
}
