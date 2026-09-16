package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture
import kotlin.math.roundToInt

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
    /**
     * The active array, in pixels, or zero where the camera did not say. What
     * a raw frame's size will be, and so what a 2x canvas of it would cost.
     */
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    /**
     * The highest sensitivity the sensor reaches with analog gain alone
     * (`SENSOR_MAX_ANALOG_SENSITIVITY`), or null where the camera did not
     * say. Above it the gain is digital, and a read-noise curve measured
     * there says nothing about a conversion gain switch -- see
     * [DcgProfiler].
     */
    val maxAnalogIso: Int? = null,
    /**
     * Whether the sensor advertises `ULTRA_HIGH_RESOLUTION_SENSOR`: a binned
     * default mode and an un-binned maximum-resolution mode, switched per
     * session with `SENSOR_PIXEL_MODE`. Neither development device does --
     * their 50 MP sensors are 12 MP to Camera2, with the un-binned centre
     * exposed as a further physical camera -- so every use of this is gated,
     * and the control for it is absent where it is false (CLAUDE.md,
     * "Verify, never assume").
     */
    val ultraHighResolution: Boolean = false,
    /** The largest RAW_SENSOR size in the maximum-resolution stream map, or zero where there is no such mode. */
    val fullResolutionRawWidth: Int = 0,
    val fullResolutionRawHeight: Int = 0,
) {

    /**
     * Whether a full-resolution raw stream can be asked for at all: the mode,
     * a raw size in it, and the memory to ring and merge it
     * ([MemoryBudget.canHoldFullResolution]).
     */
    val supportsFullResolution: Boolean
        get() = ultraHighResolution && fullResolutionRawWidth > 0 && fullResolutionRawHeight > 0 &&
            MemoryBudget.canHoldFullResolution(fullResolutionRawWidth, fullResolutionRawHeight)

    /** Megapixels of the default (binned) raw and of the full-resolution raw, for the chip. */
    val rawMegapixels: Float get() = rawWidth.toFloat() * rawHeight / 1_000_000f
    val fullResolutionMegapixels: Float get() = fullResolutionRawWidth.toFloat() * fullResolutionRawHeight / 1_000_000f

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

    val supportsBracketing: Boolean
        get() = hasManualSensor && hasIsoRange && supportsRaw

    /**
     * Whether the 2x sub-pixel canvas can be offered.
     *
     * Raw frames, because the accumulation is in the sensor's own domain; the
     * native library, because the canvas and its develop exist only there;
     * and the memory for a canvas of this sensor at twice its resolution, six
     * bytes a cell, judged against physical memory the way the allocation
     * itself will judge it. A device that fails any of these never sees the
     * control.
     */
    val supportsSuperRes: Boolean
        get() = supportsRaw && NativeMerge.isAvailable() &&
            MemoryBudget.canHoldCanvas(rawWidth, rawHeight)

    fun summary(): String = buildString {
        append(if (hasManualSensor) "Manual" else "Auto only")
        if (isoMin != null && isoMax != null) append("  \u00b7  ISO $isoMin\u2013$isoMax")
        if (exposureMinNs != null && exposureMaxNs != null) {
            append("  \u00b7  ${shutterLabel(exposureMinNs)}\u2013${shutterLabel(exposureMaxNs)}")
        }
        if (hasManualFocus && minFocusDiopters > 0f) {
            append("  \u00b7  focus to ${closestFocusLabel()}")
        }
        if (!canDisableNoiseReduction()) append("  \u00b7  fixed noise reduction")
        if (supportsDng) append("  \u00b7  DNG") else if (supportsRaw) append("  \u00b7  raw, no DNG")
    }

    /**
     * An exposure time the way a photographer says it.
     *
     * The Camera2 keys are nanoseconds and this used to print them as given --
     * "1us-300ms" -- which is the sensor's vocabulary rather than the reader's.
     * A shutter speed is a fraction of a second.
     */
    private fun shutterLabel(nanoseconds: Long): String {
        val seconds = nanoseconds / 1_000_000_000.0
        return if (seconds >= 0.4) "%.1fs".format(seconds)
        else "1/${(1.0 / seconds).roundToInt()}"
    }

    /**
     * Closest focus as a distance rather than in dioptres.
     *
     * "0-20.0D" is exactly right and means nothing at a glance; five
     * centimetres is the same fact in a form that answers "can I get close to
     * this?".
     */
    private fun closestFocusLabel(): String {
        val metres = 1f / minFocusDiopters
        return if (metres < 1f) "%.0f cm".format(metres * 100) else "%.1f m".format(metres)
    }

    companion object {
        /**
         * The largest RAW_SENSOR size of the maximum-resolution stream map,
         * where the camera has one. Read in a try, because a HAL that does
         * not implement the mode may throw rather than return null for the
         * key.
         */
        @OptIn(ExperimentalCamera2Interop::class)
        @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
        private fun fullResolutionRaw(c2: Camera2CameraInfo): android.util.Size? = runCatching {
            val map = c2.getCameraCharacteristic(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
            ) ?: return null
            map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height }
        }.getOrNull()

        // See the note in ManualSettings: the compiler reads one of these and
        // AndroidX lint reads the other.
        @OptIn(ExperimentalCamera2Interop::class)
        @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
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

            val activeArray = c2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )

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
                rawWidth = activeArray?.width() ?: 0,
                rawHeight = activeArray?.height() ?: 0,
                maxAnalogIso = c2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY
                ),
                ultraHighResolution = caps.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
                ),
                fullResolutionRawWidth = fullResolutionRaw(c2)?.width ?: 0,
                fullResolutionRawHeight = fullResolutionRaw(c2)?.height ?: 0,
            )
        }
    }
}
