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
    val rememberedManualFocus: Boolean = false,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    /** Freeze the running AWB solution in PRO, or use the selected preset. */
    val manualColor: Boolean = false,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val kelvin: Int = 5200,
    val tint: Int = 0,
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
            val desired = effectiveExposureTimeNs(caps)
            val iso = PreviewExposure.iso(effectiveIso(caps), desired, caps.isoMax ?: effectiveIso(caps))
            val time = PreviewExposure.time(desired)
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
        if (caps.supportsAwbLock) {
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_LOCK,
                (manualColor || whiteBalanceMode == WhiteBalanceMode.LOCK) && effectiveAwbMode(caps) == CameraMetadata.CONTROL_AWB_MODE_AUTO,
            )
        }

        manualWhiteBalance(caps)?.let { solution ->
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, solution.cameraGains())
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, solution.cameraTransform())
        }

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
        if (manualWhiteBalance(caps) != null) CameraMetadata.CONTROL_AWB_MODE_OFF
        else if (whiteBalanceMode == WhiteBalanceMode.LOCK) CameraMetadata.CONTROL_AWB_MODE_AUTO
        else if (caps.awbModes.contains(awbMode)) awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO

    fun manualWhiteBalance(caps: CameraCapabilities): CalibratedWhiteBalance.Solution? =
        if (whiteBalanceMode == WhiteBalanceMode.MANUAL && caps.supportsManualWhiteBalance)
            CalibratedWhiteBalance.resolve(caps.whiteBalanceCalibration, kelvin, tint) else null

    /** PRO starts from the sensor's latest AE reading, avoiding a visible exposure jump. */
    fun forPro(enabled: Boolean, caps: CameraCapabilities?, liveExposure: Pair<Int, Long>? = null): ManualSettings {
        if (!enabled) return copy(
            manualExposure = false,
            manualFocus = false,
            manualColor = false,
            awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
        )
        if (caps == null || !caps.hasManualSensor || !caps.hasManualColor) return this
        val exposure = liveExposure?.takeIf { it.first > 0 && it.second > 0L }
        val colorMode = if (caps.supportsAwbLock && CameraMetadata.CONTROL_AWB_MODE_AUTO in caps.awbModes)
            CameraMetadata.CONTROL_AWB_MODE_AUTO
            else awbMode.takeIf { it != CameraMetadata.CONTROL_AWB_MODE_AUTO && it in caps.awbModes }
                ?: caps.awbModes.first { it != CameraMetadata.CONTROL_AWB_MODE_AUTO }
        return copy(
            manualExposure = true,
            iso = exposure?.first ?: iso,
            exposureTimeNs = exposure?.second ?: exposureTimeNs,
            manualColor = true,
            awbMode = colorMode,
        )
    }

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
    fun shutterLabel(locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val seconds = exposureTimeNs / 1_000_000_000.0
        return if (seconds >= 0.4) {
            "%.1fs".format(locale, seconds)
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
