package dev.multiframe.camera.pipeline

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
        /** Matches the Pixel 9 Pro XL: GBRG, 10-bit. Used as a fallback only. */
        val DEFAULT = SensorProfile(
            blackLevel = intArrayOf(64, 64, 64, 64),
            whiteLevel = 1023,
            cfaPattern = intArrayOf(1, 2, 0, 1),
        )
    }
}
