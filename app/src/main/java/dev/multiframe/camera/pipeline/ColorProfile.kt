package dev.multiframe.camera.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

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
