package dev.multiframe.camera.ui

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import dev.multiframe.camera.pipeline.*
import org.junit.Assert.*
import org.junit.Test

/** Native Camera2 request objects; synthetic capabilities, no claim of HAL support. */
class Revision05RequestBuilderTest {
    private val caps = CameraCapabilities(true, 50, 6400, 1000L, 6_000_000_000L, 10f,
        listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_OFF),
        listOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
        emptyList(), emptyList(), -6, 6, .333f, 90, true, emptySet(),
        supportsAwbLock = true, manualPostProcessing = true,
        whiteBalanceCalibration = ColorScience.Calibration(Mat3.IDENTITY, Mat3.IDENTITY,
            illuminant1Kelvin = 2850, illuminant2Kelvin = 6500))
    private fun read(options: androidx.camera.camera2.interop.CaptureRequestOptions, key: CaptureRequest.Key<*>): Any? =
        options.listOptions().firstOrNull { it.token == key }?.let { options.retrieveOption(it) }
    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    @Test fun whiteBalanceOverrideDoesNotEngageManualExposureOrFocus() {
        val wb = ManualSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL, kelvin = 5000, tint = 15)
        val request = wb.toCaptureRequestOptions(caps)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, read(request, CaptureRequest.CONTROL_AE_MODE))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, read(request, CaptureRequest.CONTROL_AF_MODE))
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_OFF, read(request, CaptureRequest.CONTROL_AWB_MODE))
        assertNotNull(read(request, CaptureRequest.COLOR_CORRECTION_GAINS))
        assertNotNull(read(request, CaptureRequest.COLOR_CORRECTION_TRANSFORM))
        val manual = wb.copy(manualExposure = true, exposureTimeNs = 6_000_000_000, iso = 100).toCaptureRequestOptions(caps)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_OFF, read(manual, CaptureRequest.CONTROL_AE_MODE))
        assertEquals(PreviewExposure.FRAME_NS, read(manual, CaptureRequest.SENSOR_EXPOSURE_TIME))
        val auto = wb.copy(manualExposure = true).returnToAuto().toCaptureRequestOptions(caps)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, read(auto, CaptureRequest.CONTROL_AE_MODE))
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_OFF, read(auto, CaptureRequest.CONTROL_AWB_MODE))
    }
}
