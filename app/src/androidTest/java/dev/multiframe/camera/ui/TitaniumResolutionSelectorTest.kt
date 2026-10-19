package dev.multiframe.camera.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.CameraCapabilities
import org.junit.Rule
import org.junit.Test

/** The active resolution control mounted in the R05 top deck; declared sensor capabilities. */
class TitaniumResolutionSelectorTest {
    @get:Rule val compose = createComposeRule()

    private val binned = CameraCapabilities(
        hasManualSensor = true, isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000, exposureMaxNs = 500_000_000, minFocusDiopters = 10f,
        awbModes = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO), afModes = emptyList(),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -12, evMax = 12, evStep = 1f / 6f, sensorOrientation = 90,
        supportsRaw = true, supportedOutputFormats = emptySet(),
        rawWidth = 4080, rawHeight = 3072,
    )
    private val fullSensor = binned.copy(
        ultraHighResolution = true,
        fullResolutionRawWidth = 8160,
        fullResolutionRawHeight = 6144,
    )

    @Test fun sensorSizesAndBothSelectionRequestsReachTheActiveTopDeck() {
        var full by mutableStateOf(false)
        val requests = mutableListOf<Boolean>()
        compose.setContent {
            Revision05ConsoleFixture(resolutionControl = {
                    TitaniumResolutionSelector(
                        full = full,
                        binnedMegapixels = fullSensor.rawMegapixels,
                        fullMegapixels = fullSensor.fullResolutionMegapixels,
                        enabled = true,
                        onFull = { requests += it; full = it },
                    )
                },
            )
        }
        compose.assumeRendered()
        compose.onNodeWithTag("titanium-labs-button").assertIsDisplayed()
        compose.onNodeWithTag("titanium-resolution-selector").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resolution 13MP").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resolution 50MP").assertIsDisplayed()
            .performTouchInput { click() }
        assertThat(full).isTrue()
        compose.onNodeWithContentDescription("Resolution 13MP").performTouchInput { click() }
        assertThat(full).isFalse()
        assertThat(requests).containsExactly(true, false).inOrder()
    }

    @Test fun disabledSelectorCannotRequestAStreamChange() {
        val requests = mutableListOf<Boolean>()
        compose.setContent {
            TitaniumResolutionSelector(
                full = false,
                binnedMegapixels = fullSensor.rawMegapixels,
                fullMegapixels = fullSensor.fullResolutionMegapixels,
                enabled = false,
                onFull = { requests += it },
            )
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("Resolution 50MP")
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("Resolution 13MP")
            .assertIsNotEnabled().performTouchInput { click() }
        assertThat(requests).isEmpty()
    }

    @Test fun aSensorWithoutAnUnbinnedRawSizeHasNoSelector() {
        assertThat(binned.supportsFullResolution).isFalse()
        assertThat(binned.copy(ultraHighResolution = true).supportsFullResolution).isFalse()
        assertThat(fullSensor.rawMegapixels).isWithin(.1f).of(12.5f)
        assertThat(fullSensor.fullResolutionMegapixels).isWithin(.1f).of(50.1f)
        compose.setContent {
            Revision05ConsoleFixture(resolutionControl = {
                    if (binned.supportsFullResolution) {
                        TitaniumResolutionSelector(false, binned.rawMegapixels,
                            binned.fullResolutionMegapixels, true, onFull = {})
                    }
                },
            )
        }
        compose.assumeRendered()
        compose.onNodeWithTag("titanium-labs-button").assertIsDisplayed()
        compose.onNodeWithTag("titanium-resolution-selector").assertDoesNotExist()
    }
}
