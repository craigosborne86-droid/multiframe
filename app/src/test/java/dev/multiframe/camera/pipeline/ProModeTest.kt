package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraMetadata
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProModeTest {
    private fun capabilities(awbLock: Boolean = true) = CameraCapabilities(
        hasManualSensor = true,
        isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000L, exposureMaxNs = 6_000_000_000L,
        minFocusDiopters = 10f,
        awbModes = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        afModes = listOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -12, evMax = 12, evStep = 1f / 6f,
        sensorOrientation = 90, supportsRaw = true, supportedOutputFormats = emptySet(),
        supportsAwbLock = awbLock,
    )

    @Test fun proEngagesManualExposureAtTheLiveReadingAndLocksAutoWhiteBalance() {
        val active = ManualSettings().forPro(true, capabilities(), 527 to 12_345_678L)
        assertThat(active.manualExposure).isTrue()
        assertThat(active.iso).isEqualTo(527)
        assertThat(active.exposureTimeNs).isEqualTo(12_345_678L)
        assertThat(active.manualColor).isTrue()
        assertThat(active.awbMode).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    @Test fun leavingProRestoresAeAfAndAwbEvenAfterManualAdjustments() {
        val adjusted = ManualSettings(manualExposure = true, manualFocus = true,
            manualColor = true, awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
        val auto = adjusted.forPro(false, capabilities())
        assertThat(auto.manualExposure).isFalse()
        assertThat(auto.manualFocus).isFalse()
        assertThat(auto.manualColor).isFalse()
        assertThat(auto.awbMode).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    @Test fun aCameraWithoutAwbLockUsesAnAdvertisedManualPreset() {
        val active = ManualSettings().forPro(true, capabilities(awbLock = false), 400 to 8_000_000L)
        assertThat(active.manualColor).isTrue()
        assertThat(active.awbMode).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
    }
}
