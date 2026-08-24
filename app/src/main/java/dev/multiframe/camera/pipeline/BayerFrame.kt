package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.core.ImageProxy
import java.nio.ByteOrder

/**
 * One raw sensor frame: a single-channel colour filter array image.
 *
 * Values are the sensor's own linear codes, not gamma encoded, which is what
 * makes averaging photometrically correct without any transfer-function
 * guesswork. On the Pixel 9 Pro XL these are 10-bit (white level 1023) stored
 * in 16-bit words.
 */
class BayerFrame(
    val width: Int,
    val height: Int,
    /** Row-major CFA samples, one per pixel. */
    val data: ShortArray,
    val timestampNs: Long = 0L,
) {
    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFFFF

    companion object {
        fun allocate(width: Int, height: Int) =
            BayerFrame(width, height, ShortArray(width * height))

        /**
         * Extracts 16-bit CFA samples from a RAW_SENSOR image, honouring row
         * stride. The buffer is little-endian on every Android device we can
         * target, but the order is set explicitly rather than assumed.
         */
        fun copyFrom(image: ImageProxy): BayerFrame {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val rowStride = plane.rowStride
            val out = ShortArray(w * h)

            if (rowStride == w * 2) {
                buffer.asShortBuffer().get(out, 0, minOf(out.size, buffer.remaining() / 2))
            } else {
                val row = ShortArray(rowStride / 2)
                for (y in 0 until h) {
                    if (buffer.remaining() < rowStride) break
                    buffer.position(y * rowStride)
                    val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    slice.get(row, 0, minOf(row.size, slice.remaining()))
                    System.arraycopy(row, 0, out, y * w, minOf(w, row.size))
                }
            }
            return BayerFrame(w, h, out, image.imageInfo.timestamp)
        }
    }
}

/**
 * Sensor characteristics needed to interpret and re-emit the CFA data.
 *
 * [cfaPattern] follows the DNG CFAPattern tag: 0=red, 1=green, 2=blue, read
 * left-to-right, top-to-bottom over the 2x2 repeating cell. GBRG is [1,2,0,1].
 */
data class SensorProfile(
    val blackLevel: IntArray,
    val whiteLevel: Int,
    val cfaPattern: IntArray,
) {
    /** Black level for the CFA position of this pixel. */
    fun blackAt(x: Int, y: Int): Int {
        if (blackLevel.isEmpty()) return 0
        val idx = (y and 1) * 2 + (x and 1)
        return blackLevel[idx % blackLevel.size]
    }

    val range: Int get() = (whiteLevel - (blackLevel.minOrNull() ?: 0)).coerceAtLeast(1)

    override fun equals(other: Any?): Boolean =
        other is SensorProfile &&
            blackLevel.contentEquals(other.blackLevel) &&
            whiteLevel == other.whiteLevel &&
            cfaPattern.contentEquals(other.cfaPattern)

    override fun hashCode(): Int =
        blackLevel.contentHashCode() * 31 + whiteLevel * 31 + cfaPattern.contentHashCode()

    companion object {
        /**
         * Reads the real sensor profile. Black level is per-CFA-position and
         * white level bounds the usable range; both are needed for a correct
         * merge and a valid DNG.
         */
        fun from(characteristics: CameraCharacteristics): SensorProfile {
            val pattern = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            )
            val cfa = when (pattern) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
                    intArrayOf(0, 1, 1, 2)
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
                    intArrayOf(1, 0, 2, 1)
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
                    intArrayOf(1, 2, 0, 1)
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
                    intArrayOf(2, 1, 1, 0)
                else -> DEFAULT.cfaPattern
            }
            val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
                ?: DEFAULT.whiteLevel
            val blackPattern = characteristics.get(
                CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN
            )
            val black = if (blackPattern != null) {
                IntArray(4).also { blackPattern.copyTo(it, 0) }
            } else {
                DEFAULT.blackLevel
            }
            return SensorProfile(black, white, cfa)
        }

        /** Matches the Pixel 9 Pro XL: GBRG, 10-bit. Used as a fallback only. */
        val DEFAULT = SensorProfile(
            blackLevel = intArrayOf(64, 64, 64, 64),
            whiteLevel = 1023,
            cfaPattern = intArrayOf(1, 2, 0, 1),
        )
    }
}
