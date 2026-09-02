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

    // Kotlin's opt-in satisfies the compiler; AndroidX's is what its own lint
    // check reads. With only the first, every interop call in this function was
    // reported as an UnsafeOptInUsageError.
    @OptIn(ExperimentalCamera2Interop::class)
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun toCaptureRequestOptions(caps: CameraCapabilities): CaptureRequestOptions {
        val b = CaptureRequestOptions.Builder()

        if (suppressIspProcessing) {
            // Merging works better on data the ISP has not already denoised and
            // sharpened, but OFF is an optional mode in the Camera2 spec, so it
            // is only requested where the device advertises it.
            if (caps.canDisableNoiseReduction()) {
                b.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                )
            }
            if (caps.canDisableEdgeEnhancement()) {
                b.setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CameraMetadata.EDGE_MODE_OFF,
                )
            }
        }

        if (manualExposure && caps.hasManualSensor) {
            val iso = effectiveIso(caps)
            val time = effectiveExposureTimeNs(caps)
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
                effectiveFocusDiopters(caps),
            )
        } else {
            // A fixed-focus lens advertises no AF mode at all; sending
            // CONTINUOUS_PICTURE there would be rejected.
            caps.autoAfMode()?.let {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, it)
            }
        }

        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, effectiveAwbMode(caps))

        // The lens shading map is only reported when it is asked for, and raw
        // is uncorrected by definition, so without this every frame keeps its
        // corner falloff and nothing downstream can put it right.
        b.setCaptureRequestOption(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON,
        )

        return b.build()
    }

    /** Values below are pure so they can be unit tested without a camera. */

    fun effectiveIso(caps: CameraCapabilities): Int =
        if (caps.isoMin != null && caps.isoMax != null) {
            iso.coerceIn(caps.isoMin, caps.isoMax)
        } else iso

    fun effectiveExposureTimeNs(caps: CameraCapabilities): Long =
        if (caps.exposureMinNs != null && caps.exposureMaxNs != null) {
            exposureTimeNs.coerceIn(caps.exposureMinNs, caps.exposureMaxNs)
        } else exposureTimeNs

    fun effectiveFocusDiopters(caps: CameraCapabilities): Float =
        focusDiopters.coerceIn(0f, caps.minFocusDiopters)

    fun effectiveAwbMode(caps: CameraCapabilities): Int =
        if (caps.awbModes.contains(awbMode)) awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO

    fun effectiveEvIndex(caps: CameraCapabilities): Int =
        if (caps.supportsExposureCompensation) {
            evIndex.coerceIn(caps.evMin, caps.evMax)
        } else {
            0
        }

    /** Manual exposure is only offered where the sensor supports it. */
    fun manualExposureActive(caps: CameraCapabilities): Boolean =
        manualExposure && caps.hasManualSensor

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
        /**
         * White balance presets, named the way a photographer names them.
         *
         * These were abbreviated to fit a chip -- "TUNG", "FLUO", "CLOUD" --
         * which let a layout constraint decide the vocabulary. The row scrolls,
         * so it never needed to.
         */
        val AWB_LABELS = linkedMapOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "Shade",
        )
    }
}
