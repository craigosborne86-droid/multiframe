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
            /**
             * A scene neutral (r/g, 1, b/g) in camera coordinates to use instead
             * of the camera's: the learned white balance's estimate (Labs,
             * [AwbContract]). The gains become its reciprocal and the matrix is
             * re-solved for it, exactly as for the camera's own.
             */
            neutralOverride: FloatArray? = null,
        ): ColorProfile {
            val reported = from(result)
            val fallback = if (neutralOverride == null) reported
                else reported.copy(gains = floatArrayOf(1f / neutralOverride[0], 1f, 1f, 1f / neutralOverride[2]))
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
            val neutral = neutralOverride ?: cameraNeutral(result, fallback.gains)

            // Interpolated by colour temperature where the device published the
            // illuminants its matrices were measured under, and by the
            // red-to-blue proxy where it did not. See `ColorScience.solveBlend`
            // for the circularity this resolves.
            val blend = ColorScience.solveBlend(calibration, neutral)
            // Demosaic applies the recorded gains once. Chapter 6's matrix
            // takes unbalanced camera coordinates, so compose it with the
            // inverse of those gains rather than replacing AsShotNeutral by 1.
            val matrix = ColorScience.balancedCameraToSrgb(
                calibration, neutral, blend, fallback.gains,
            )
            val kelvin = ColorScience.cctForBlend(calibration, neutral, blend)
            Log.i(
                TAG,
                "calibrated colour: blend %.2f toward daylight%s".format(
                    blend,
                    kelvin?.let { ", scene %dK".format(it) } ?: "",
                ),
            )
            return ColorProfile(gains = fallback.gains, matrix = matrix.m)
        }

        /** The selected scene white in camera coordinates, shared by JPEG and DNG. */
        fun cameraNeutral(result: TotalCaptureResult?, gains: FloatArray): FloatArray {
            val reported = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                ?.let { floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
            if (reported != null && reported.all { it > 0f }) {
                val green = reported[1]
                return floatArrayOf(reported[0] / green, 1f, reported[2] / green)
            }
            val green = (gains[1] + gains[2]) * 0.5f
            return floatArrayOf(green / gains[0], 1f, green / gains[3])
        }

        /**
         * The sensor's colorimetric characterisation on its own, for callers
         * that need the matrices rather than a rendered profile.
         *
         * The viewfinder's temperature gauge is the one that does: it has white
         * balance gains and needs to know what chromaticity they correspond to,
         * which is a question about the sensor and not about any one frame.
         * Same two routes and same order as [calibrated] — the documented keys
         * first, DngCreator second — so the gauge and the develop are reading
         * the same characterisation.
         *
         * Null on a device that publishes neither, where a temperature cannot
         * be computed and the gauge shows nothing.
         */
        fun calibrationFrom(
            characteristics: CameraCharacteristics,
        ): ColorScience.Calibration? =
            readCalibration(characteristics)
                ?: calibrationViaDng(characteristics, null)

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

            // The illuminants the two matrices were measured under, which is
            // what lets them be interpolated by temperature rather than by the
            // red-to-blue proxy. Optional in the specification; absent, the
            // pair still works and simply blends the older way.
            val illuminant1 = characteristics
                .get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
                ?.let { ColorScience.illuminantKelvin(it) }
            val illuminant2 = characteristics
                .get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
                ?.toInt()
                ?.let { ColorScience.illuminantKelvin(it) }

            return ColorScience.Calibration(
                colorMatrix1 = colour1 ?: colour2!!,
                colorMatrix2 = colour2 ?: colour1!!,
                calibration1 = transform(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
                    ?: Mat3.IDENTITY,
                calibration2 = transform(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
                    ?: Mat3.IDENTITY,
                forward1 = transform(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
                forward2 = transform(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
                illuminant1Kelvin = illuminant1,
                illuminant2Kelvin = illuminant2,
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

    /**
     * Whether the white balance is unity on every channel -- what a result
     * without gains falls back to, and what a lock taken before the camera
     * had converged reports. Raw developed at unity is green.
     */
    val isNeutralGains: Boolean get() = gains.all { kotlin.math.abs(it - 1f) < 1e-3f }

    /** Gain for the CFA colour index (0 red, 1 green, 2 blue) at a pixel position. */
    fun gainFor(colour: Int, y: Int): Float = when (colour) {
        0 -> gains[0]
        2 -> gains[3]
        else -> if (y and 1 == 0) gains[1] else gains[2]
    }
}
