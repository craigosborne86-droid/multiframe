package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Multiframe"

/**
 * Colour information needed to turn sensor CFA data into a viewable image.
 *
 * Taken from the capture the frames came from, so the render matches what the
 * camera's own auto white balance decided rather than a guess. Both fields have
 * neutral fallbacks, so a device that reports neither still produces a sane
 * (if less accurate) image instead of failing.
 */
data class ColorProfile(
    /** Per-channel white balance multipliers, ordered red, greenEven, greenOdd, blue. */
    val gains: FloatArray,
    /** Row-major 3x3 taking white-balanced sensor RGB to linear sRGB. */
    val matrix: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ColorProfile &&
            gains.contentEquals(other.gains) && matrix.contentEquals(other.matrix)

    override fun hashCode(): Int = gains.contentHashCode() * 31 + matrix.contentHashCode()

    companion object {
        val NEUTRAL = ColorProfile(
            gains = floatArrayOf(1f, 1f, 1f, 1f),
            matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )

        /**
         * Colour from the sensor's own characterisation rather than the ISP's
         * rendering matrix.
         *
         * `COLOR_CORRECTION_TRANSFORM` is the matrix the camera's processor
         * chose, tuned to produce the manufacturer's look -- the one the stock
         * app ships. Using it means inheriting that look, which is the opposite
         * of the intent here. The sensor also carries the colorimetric data a
         * DNG carries: colour matrices measured under two illuminants, and
         * forward matrices taking white-balanced camera space to XYZ. That
         * describes what the sensor sees rather than what the vendor wants it
         * to look like.
         *
         * Falls back to the ISP's matrix where a device reports no calibration,
         * which is optional in the Camera2 spec.
         */
        fun calibrated(
            characteristics: CameraCharacteristics?,
            result: TotalCaptureResult?,
        ): ColorProfile {
            val fallback = from(result)
            if (characteristics == null) return fallback

            val calibration = readCalibration(characteristics)
                ?: calibrationViaDng(characteristics, result)
                ?: run {
                    Log.i(TAG, "no sensor colour calibration; using the ISP matrix")
                    return fallback
                }

            // The blend is chosen from the light the scene was actually under.
            // The neutral colour point is the sensor's own answer; the white
            // balance gains are the fallback, since their reciprocal is the
            // same quantity by a different route.
            val neutral = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                ?.let { floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
                ?: floatArrayOf(
                    1f / fallback.gains[0].coerceAtLeast(1e-3f),
                    1f,
                    1f / fallback.gains[3].coerceAtLeast(1e-3f),
                )

            val blend = ColorScience.blendFor(neutral)
            // The demosaic has already applied the white balance gains, so by
            // the time this matrix is used a neutral is (1, 1, 1). Normalising
            // against the raw neutral instead would balance the picture twice.
            val matrix = ColorScience.cameraToSrgb(
                calibration, floatArrayOf(1f, 1f, 1f), blend,
            )
            Log.i(TAG, "calibrated colour: blend %.2f toward daylight".format(blend))
            return ColorProfile(gains = fallback.gains, matrix = matrix.m)
        }

        /** Per-camera, and unchanging, so it is worked out once. */
        private val dngCalibrationCache =
            ConcurrentHashMap<String, ColorScience.Calibration>()

        private val dngCalibrationMisses = ConcurrentHashMap<String, Boolean>()

        /**
         * Recovers the calibration by having DngCreator write it down.
         *
         * Only reached when the documented route gives nothing. The keys are
         * optional in the Camera2 specification, and a device can advertise RAW
         * capability without publishing them, yet still write correct DNGs --
         * because the framework reaches the data by a route an application does
         * not have. Asking DngCreator to serialise a sixteen-pixel-square image
         * and reading the camera profile tags out of the result gets to the
         * same numbers.
         *
         * Note that the keys also read as absent to an app without the CAMERA
         * permission, which looks identical to hardware that withholds them.
         * That is a privacy measure rather than a gap, and it is why this is a
         * fallback rather than the main path.
         */
        private fun calibrationViaDng(
            characteristics: CameraCharacteristics,
            result: TotalCaptureResult?,
        ): ColorScience.Calibration? {
            if (result == null) return null
            val key = cacheKey(characteristics)
            dngCalibrationCache[key]?.let { return it }
            if (dngCalibrationMisses.containsKey(key)) return null

            val calibration = runCatching {
                val side = 16
                val pixels = ByteBuffer
                    .allocateDirect(side * side * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val bytes = ByteArrayOutputStream(64 * 1024)
                DngCreator(characteristics, result).use { dng ->
                    dng.writeByteBuffer(bytes, Size(side, side), pixels, 0)
                }
                DngMetadata.readCalibration(bytes.toByteArray())
            }.onFailure {
                Log.i(TAG, "could not read calibration through a DNG", it)
            }.getOrNull()

            if (calibration == null) {
                dngCalibrationMisses[key] = true
            } else {
                dngCalibrationCache[key] = calibration
                Log.i(TAG, "recovered sensor colour calibration from a written DNG")
            }
            return calibration
        }

        /** Stable enough to identify one camera, without needing its id. */
        private fun cacheKey(characteristics: CameraCharacteristics): String =
            buildString {
                append(characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
                append('|')
                append(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
                append('|')
                append(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull())
            }

        private fun readCalibration(
            characteristics: CameraCharacteristics
        ): ColorScience.Calibration? {
            fun transform(key: CameraCharacteristics.Key<ColorSpaceTransform>): Mat3? =
                characteristics.get(key)?.let { t ->
                    Mat3(
                        FloatArray(9) { i ->
                            val r = t.getElement(i % 3, i / 3)
                            if (r.denominator == 0) 0f
                            else r.numerator.toFloat() / r.denominator.toFloat()
                        }
                    )
                }

            val colour1 = transform(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            val colour2 = transform(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            if (colour1 == null && colour2 == null) return null

            return ColorScience.Calibration(
                colorMatrix1 = colour1 ?: colour2!!,
                colorMatrix2 = colour2 ?: colour1!!,
                calibration1 = transform(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
                    ?: Mat3.IDENTITY,
                calibration2 = transform(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
                    ?: Mat3.IDENTITY,
                forward1 = transform(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
                forward2 = transform(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
            )
        }

        fun from(result: TotalCaptureResult?): ColorProfile {
            if (result == null) return NEUTRAL

            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue)
            } ?: NEUTRAL.gains

            val matrix = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                FloatArray(9) { i ->
                    t.getElement(i % 3, i / 3).let { r ->
                        if (r.denominator == 0) 0f
                        else r.numerator.toFloat() / r.denominator.toFloat()
                    }
                }
            } ?: NEUTRAL.matrix

            return ColorProfile(gains, matrix)
        }
    }

    /** Gain for the CFA colour index (0 red, 1 green, 2 blue) at a pixel position. */
    fun gainFor(colour: Int, y: Int): Float = when (colour) {
        0 -> gains[0]
        2 -> gains[3]
        else -> if (y and 1 == 0) gains[1] else gains[2]
    }
}
