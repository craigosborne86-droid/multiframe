package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A [DngRecord] from the camera: the values `DngCreator` read for the
 * September files, from the same keys, in the same encodings.
 *
 * Null where the writer cannot represent the camera, and `DngCreator`
 * writes the file instead (RawBurstCapture.writeDng):
 *
 * - a sensor that is not a 2x2 Bayer mosaic (monochrome, RGB-IR, quad
 *   layouts reported as something else);
 * - a camera that publishes no colour matrix through the characteristics.
 *   `DngCreator` reaches the calibration on such a device by a route an
 *   application does not have (see [DngMetadata]); its file is right and
 *   ours could not be.
 */
object DngCamera2 {

    fun record(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        width: Int,
        height: Int,
        orientation: Int,
        /** The develop's camera neutral; null keeps the HAL's. */
        neutral: FloatArray? = null,
        baselineExposureStops: Float = 0f,
        gainTables: Pair<ByteArray, ByteArray>? = null,
    ): DngRecord? {
        val cfa = when (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(0, 1, 1, 2)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)
            else -> return null
        }
        val colorMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null

        // The black and white this frame was read at, where the sensor
        // reports them per frame; the static pattern otherwise.
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                ?.let { p -> FloatArray(4) { p.getOffsetForIndex(it % 2, it / 2).toFloat() } }
            ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null

        val shading = ShadingMap.from(result, SensorProfile(IntArray(4), white, cfa))
        return DngRecord(
            width = width,
            height = height,
            cfaPattern = cfa,
            blackLevel = LongArray(8).also { out -> for (i in 0..3) DngRecord.hundredths(black[i]).copyInto(out, i * 2) },
            whiteLevel = white.toLong(),
            make = Build.MANUFACTURER,
            model = Build.MODEL,
            uniqueCameraModel = "${Build.MODEL}-${Build.MANUFACTURER}-${Build.BRAND}",
            software = Build.FINGERPRINT,
            dateTime = dateTime(characteristics, result),
            orientation = orientation,
            exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { longArrayOf(it, 1_000_000_000L) },
            fNumber = result.get(CaptureResult.LENS_APERTURE)?.let { DngRecord.hundredths(it) },
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { DngRecord.hundredths(it) },
            colorMatrix1 = pairs(colorMatrix1),
            colorMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let(::pairs),
            cameraCalibration1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let(::pairs),
            cameraCalibration2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let(::pairs),
            forwardMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let(::pairs),
            forwardMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let(::pairs),
            // Camera2's illuminant enumeration is EXIF LightSource's.
            illuminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            illuminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            asShotNeutral = neutral?.let(::neutralPairs)
                ?: result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { LongArray(6).also { out -> it.forEachIndexed { i, r -> out[i * 2] = r.numerator.toLong(); out[i * 2 + 1] = r.denominator.toLong() } } },
            baselineExposure = DngRecord.hundredths(baselineExposureStops),
            noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { p ->
                noisePerPlane(DoubleArray(p.size * 2) { if (it % 2 == 0) p[it / 2].first else p[it / 2].second }, cfa)
            },
            opcodeList2 = shading?.let { DngOpcodes.gainMaps(it, cfa, width, height) },
            opcodeList3 = warp(characteristics, result, width, height),
            profileGainTableMap = gainTables?.first,
            profileGainTableMap2 = gainTables?.second,
        )
    }

    /** A colour transform's nine rationals, row-major, as interleaved pairs. */
    private fun pairs(t: ColorSpaceTransform): LongArray {
        val r = arrayOfNulls<Rational>(9)
        t.copyElements(r, 0)
        return LongArray(18) { i -> r[i / 2]!!.let { if (i % 2 == 0) it.numerator.toLong() else it.denominator.toLong() } }
    }

    /** The develop's neutral in millionths: what the patch wrote before the writer was ours. */
    fun neutralPairs(neutral: FloatArray): LongArray {
        require(neutral.size >= 3 && neutral.take(3).all { it > 0f })
        return LongArray(6) { i -> if (i % 2 == 0) Math.round(neutral[i / 2] * DngMetadata.NEUTRAL_DENOMINATOR.toDouble()) else DngMetadata.NEUTRAL_DENOMINATOR.toLong() }
    }

    /**
     * `SENSOR_NOISE_PROFILE` -- an (S, O) pair per CFA site -- as DNG's
     * NoiseProfile, a pair per colour plane: each plane takes its first site.
     */
    fun noisePerPlane(perSite: DoubleArray, cfa: IntArray): DoubleArray? {
        if (perSite.size < 8) return null
        val out = DoubleArray(6)
        for (plane in 0..2) {
            val site = cfa.indexOf(plane).takeIf { it >= 0 } ?: return null
            out[plane * 2] = perSite[site * 2]
            out[plane * 2 + 1] = perSite[site * 2 + 1]
        }
        return out
    }

    private fun warp(characteristics: CameraCharacteristics, result: CaptureResult, width: Int, height: Int): ByteArray? {
        val k = result.get(CaptureResult.LENS_DISTORTION) ?: characteristics.get(CameraCharacteristics.LENS_DISTORTION) ?: return null
        if (k.all { it == 0f }) return null
        val intrinsics = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            ?: characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return null
        if (intrinsics.size < 5 || intrinsics[0] <= 0f) return null
        // The intrinsics are in the pre-correction array's pixels; the raw is
        // read from that array, or scaled from it.
        val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        var cx = intrinsics[2]; var cy = intrinsics[3]; var fx = intrinsics[0]
        if (array != null && array.width() > 0) {
            if (array.width() == width && array.height() == height) {
                cx -= array.left; cy -= array.top
            } else {
                val s = width.toFloat() / array.width()
                cx = (cx - array.left) * s; cy = (cy - array.top) * s; fx *= s
            }
        }
        return DngOpcodes.warpCoefficients(k, fx, cx, cy, width, height)?.let(DngOpcodes::warpRectilinear)
    }

    /**
     * When the frame was exposed, in local time: now, less the frame's age
     * where its timestamp is on the elapsed-realtime clock.
     */
    private fun dateTime(characteristics: CameraCharacteristics, result: CaptureResult): String {
        var millis = System.currentTimeMillis()
        val realtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val stamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (realtime && stamp != null && stamp > 0) {
            val age = (SystemClock.elapsedRealtimeNanos() - stamp) / 1_000_000L
            if (age in 0..MAX_FRAME_AGE_MS) millis -= age
        }
        return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(millis)
    }

    /** A ZSL frame is seconds old at most; anything older is a clock that is not the one assumed. */
    private const val MAX_FRAME_AGE_MS = 60_000L
}
