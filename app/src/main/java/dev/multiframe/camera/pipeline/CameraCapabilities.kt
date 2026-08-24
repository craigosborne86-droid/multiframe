package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/**
 * What the attached camera can actually do, read at runtime.
 *
 * Nothing here is assumed: a device without MANUAL_SENSOR simply reports
 * [hasManualSensor] false and the UI hides those controls rather than sending
 * capture requests the hardware will reject.
 */
data class CameraCapabilities(
    val hasManualSensor: Boolean,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val minFocusDiopters: Float,
    val hasManualFocus: Boolean,
    val awbModes: List<Int>,
    val evRange: Range<Int>,
    val evStep: Float,
    val sensorOrientation: Int,
    val supportsRaw: Boolean,
) {
    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun from(cameraInfo: CameraInfo): CameraCapabilities {
            val c2 = Camera2CameraInfo.from(cameraInfo)

            val caps = c2.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: IntArray(0)

            val manual = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            )
            val raw = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )

            val minFocus = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            val exposureState = cameraInfo.exposureState

            return CameraCapabilities(
                hasManualSensor = manual,
                isoRange = c2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
                ),
                exposureTimeRange = c2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
                ),
                minFocusDiopters = minFocus,
                // A minimum focus distance of 0 diopters means a fixed-focus lens.
                hasManualFocus = minFocus > 0f,
                awbModes = (
                    c2.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
                    ) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    ).toList(),
                evRange = if (exposureState.isExposureCompensationSupported) {
                    exposureState.exposureCompensationRange
                } else {
                    Range(0, 0)
                },
                evStep = if (exposureState.isExposureCompensationSupported) {
                    exposureState.exposureCompensationStep.toFloat()
                } else {
                    0f
                },
                sensorOrientation = c2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_ORIENTATION
                ) ?: 90,
                supportsRaw = raw,
            )
        }
    }

    fun summary(): String = buildString {
        append(if (hasManualSensor) "manual" else "auto-only")
        isoRange?.let { append("  ISO ${it.lower}-${it.upper}") }
        exposureTimeRange?.let {
            append("  ${it.lower / 1000}us-${it.upper / 1_000_000}ms")
        }
        if (hasManualFocus) append("  focus 0-%.1fD".format(minFocusDiopters))
        if (supportsRaw) append("  RAW")
    }
}
