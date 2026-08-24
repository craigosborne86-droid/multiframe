package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture

/**
 * What the attached camera can actually do, read at runtime.
 *
 * Every control the app offers is gated on something in here. A device without
 * MANUAL_SENSOR, without a focusable lens, or without the ability to switch off
 * the ISP's noise reduction gets a reduced UI rather than capture requests the
 * hardware will reject.
 */
data class CameraCapabilities(
    val hasManualSensor: Boolean,
    val isoMin: Int?,
    val isoMax: Int?,
    val exposureMinNs: Long?,
    val exposureMaxNs: Long?,
    val minFocusDiopters: Float,
    val awbModes: List<Int>,
    val afModes: List<Int>,
    val noiseReductionModes: List<Int>,
    val edgeModes: List<Int>,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    val sensorOrientation: Int,
    val supportsRaw: Boolean,
    /** Output formats CameraX can actually deliver on this camera. */
    val supportedOutputFormats: Set<Int>,
) {

    /**
     * DNG needs both a RAW-capable sensor and CameraX support for the combined
     * RAW+JPEG output on this specific camera.
     */
    val supportsDng: Boolean
        get() = supportsRaw &&
            supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
    /** A fixed-focus lens reports a minimum focus distance of zero dioptres. */
    val hasManualFocus: Boolean
        get() = minFocusDiopters > 0f && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)

    /**
     * Switching the ISP's denoiser off is optional in the Camera2 spec, so it
     * has to be checked rather than assumed.
     */
    fun canDisableNoiseReduction(): Boolean =
        noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)

    fun canDisableEdgeEnhancement(): Boolean =
        edgeModes.contains(CameraMetadata.EDGE_MODE_OFF)

    /** Best available autofocus mode, or null on a fixed-focus lens. */
    fun autoAfMode(): Int? = when {
        afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
            CameraMetadata.CONTROL_AF_MODE_AUTO
        else -> null
    }

    val supportsExposureCompensation: Boolean get() = evStep > 0f && evMax > evMin

    val hasIsoRange: Boolean get() = isoMin != null && isoMax != null && isoMax > isoMin

    val hasExposureRange: Boolean
        get() = exposureMinNs != null && exposureMaxNs != null && exposureMaxNs > exposureMinNs

    fun summary(): String = buildString {
        append(if (hasManualSensor) "manual" else "auto-only")
        if (isoMin != null && isoMax != null) append("  ISO $isoMin-$isoMax")
        if (exposureMinNs != null && exposureMaxNs != null) {
            append("  ${exposureMinNs / 1000}us-${exposureMaxNs / 1_000_000}ms")
        }
        if (hasManualFocus) append("  focus 0-%.1fD".format(minFocusDiopters))
        if (!canDisableNoiseReduction()) append("  NR:fixed")
        if (supportsDng) append("  DNG") else if (supportsRaw) append("  RAW(no DNG)")
    }

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun from(cameraInfo: CameraInfo): CameraCapabilities {
            val c2 = Camera2CameraInfo.from(cameraInfo)

            fun ints(key: CameraCharacteristics.Key<IntArray>): List<Int> =
                (c2.getCameraCharacteristic(key) ?: IntArray(0)).toList()

            val caps = c2.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: IntArray(0)

            val exposureState = cameraInfo.exposureState
            val supportsEv = exposureState.isExposureCompensationSupported

            val isoRange = c2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val exposureRange = c2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )

            val awb = ints(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                .ifEmpty { listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) }

            return CameraCapabilities(
                hasManualSensor = caps.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                ),
                isoMin = isoRange?.lower,
                isoMax = isoRange?.upper,
                exposureMinNs = exposureRange?.lower,
                exposureMaxNs = exposureRange?.upper,
                minFocusDiopters = c2.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                ) ?: 0f,
                awbModes = awb,
                afModes = ints(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                noiseReductionModes = ints(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
                ),
                edgeModes = ints(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                evMin = if (supportsEv) exposureState.exposureCompensationRange.lower else 0,
                evMax = if (supportsEv) exposureState.exposureCompensationRange.upper else 0,
                evStep = if (supportsEv) exposureState.exposureCompensationStep.toFloat() else 0f,
                sensorOrientation = c2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_ORIENTATION
                ) ?: 90,
                supportsRaw = caps.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
                ),
                supportedOutputFormats = runCatching {
                    ImageCapture.getImageCaptureCapabilities(cameraInfo)
                        .supportedOutputFormats
                }.getOrDefault(emptySet()),
            )
        }
    }
}
