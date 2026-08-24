package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

/**
 * User-facing manual camera settings.
 *
 * Values are clamped against [CameraCapabilities] before being sent, so a
 * setting the hardware cannot honour is never issued.
 */
data class ManualSettings(
    val manualExposure: Boolean = false,
    val iso: Int = 200,
    val exposureTimeNs: Long = 8_000_000L,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val evIndex: Int = 0,
    val suppressIspProcessing: Boolean = true,
) {

    /** Exposure compensation only applies while auto-exposure is running. */
    val evActive: Boolean get() = !manualExposure

    @OptIn(ExperimentalCamera2Interop::class)
    fun toCaptureRequestOptions(caps: CameraCapabilities): CaptureRequestOptions {
        val b = CaptureRequestOptions.Builder()

        if (suppressIspProcessing) {
            // Merging works better on data the ISP has not already denoised and
            // sharpened. Hardware that lacks these modes ignores the request.
            b.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            )
            b.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_OFF,
            )
        }

        if (manualExposure && caps.hasManualSensor) {
            val iso = caps.isoRange?.clamp(this.iso) ?: this.iso
            val time = caps.exposureTimeRange?.clamp(this.exposureTimeNs) ?: this.exposureTimeNs
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF,
            )
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, time)
        } else {
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON,
            )
        }

        if (manualFocus && caps.hasManualFocus) {
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            b.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDiopters.coerceIn(0f, caps.minFocusDiopters),
            )
        } else {
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        }

        if (caps.awbModes.contains(awbMode)) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        }

        return b.build()
    }

    /** Human-readable shutter speed, e.g. "1/125" or "0.5s". */
    fun shutterLabel(): String {
        val seconds = exposureTimeNs / 1_000_000_000.0
        return if (seconds >= 0.4) {
            "%.1fs".format(seconds)
        } else {
            "1/${(1.0 / seconds).toInt()}"
        }
    }

    fun focusLabel(): String =
        if (focusDiopters <= 0.01f) "INF" else "%.2fm".format(1f / focusDiopters)

    companion object {
        val AWB_LABELS = linkedMapOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "AUTO",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "TUNG",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "FLUO",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "SUN",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "CLOUD",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "SHADE",
        )
    }
}
