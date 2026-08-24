package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Capture sharpening.
 *
 * ### Why a raw pipeline has to do this
 *
 * A Bayer sensor measures one colour per site and a demosaic reconstructs the
 * other two, so even a perfect demosaic delivers less acutance than the lens
 * projected. On top of that the sensor's anti-aliasing behaviour and the
 * demosaic's own interpolation each cost a little. Every raw converter applies
 * capture sharpening by default for this reason -- it is not an effect, it is
 * restoring what the sampling took away. Leaving it off does not produce a
 * neutral image, it produces a soft one.
 *
 * ### Why it is safer here than on a single frame
 *
 * Sharpening amplifies whatever is there, including noise, which is why phone
 * cameras that sharpen aggressively look crunchy. A merged burst has already
 * had its noise reduced by the square root of the frame count, so the same
 * amount of sharpening lands on a much cleaner signal. The merge is what earns
 * the right to sharpen at all.
 *
 * ### The restraint
 *
 * Unsharp masking with a threshold, applied to luminance only. Three decisions,
 * each avoiding a specific way this goes wrong:
 *
 *  * **Luminance only.** Sharpening the colour channels independently produces
 *    coloured speckle along every edge, which is the single most recognisable
 *    artefact of over-processed phone output.
 *  * **A threshold.** Differences smaller than it are left alone, so flat
 *    areas -- skies, skin, shadow -- keep whatever smoothness the merge bought
 *    instead of having their remaining noise amplified.
 *  * **A small radius.** A wide radius produces halos, the bright rim along
 *    high-contrast edges that reads instantly as processing.
 */
object Sharpen {

    /** A radius of one pixel restores acutance without producing visible halos. */
    private const val RADIUS = 1

    data class Params(
        /** How much of the detail difference to add back. */
        val amount: Float = 0.55f,
        /** Luminance differences below this, in 0..255, are left alone. */
        val threshold: Float = 3.5f,
        /** Cap on how far any pixel may move, to stop edges from ringing. */
        val maxShift: Float = 28f,
    ) {
        val enabled: Boolean get() = amount > 0f
    }

    /** Rec. 709 luma, matching the colour space the pipeline outputs in. */
    fun luma(r: Int, g: Int, b: Int): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Sharpens packed ARGB pixels in place.
     *
     * The blur is a 3x3 box, which at this radius is indistinguishable from a
     * Gaussian and considerably cheaper.
     */
    fun apply(pixels: IntArray, width: Int, height: Int, params: Params = Params()) {
        if (!params.enabled || width < 3 || height < 3) return

        // Luminance is computed once for the whole image: the mask needs each
        // pixel's neighbours, and recomputing luma per neighbour would do the
        // work nine times over.
        val lumaPlane = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            lumaPlane[i] = luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }

        val out = IntArray(pixels.size)
        System.arraycopy(pixels, 0, out, 0, pixels.size)

        for (y in RADIUS until height - RADIUS) {
            for (x in RADIUS until width - RADIUS) {
                val index = y * width + x
                var sum = 0f
                for (dy in -RADIUS..RADIUS) {
                    val row = (y + dy) * width
                    for (dx in -RADIUS..RADIUS) {
                        sum += lumaPlane[row + x + dx]
                    }
                }
                val blurred = sum / ((RADIUS * 2 + 1) * (RADIUS * 2 + 1))
                val detail = lumaPlane[index] - blurred

                // Below the threshold this is noise or texture the merge just
                // finished cleaning up. Leave it alone.
                if (abs(detail) < params.threshold) continue

                var shift = detail * params.amount
                if (shift > params.maxShift) shift = params.maxShift
                if (shift < -params.maxShift) shift = -params.maxShift

                val p = pixels[index]
                out[index] = (p.toLong() and 0xFF000000L).toInt() or
                    (shiftChannel((p shr 16) and 0xFF, shift) shl 16) or
                    (shiftChannel((p shr 8) and 0xFF, shift) shl 8) or
                    shiftChannel(p and 0xFF, shift)
            }
        }
        System.arraycopy(out, 0, pixels, 0, pixels.size)
    }

    /**
     * Moves one channel by a luminance shift.
     *
     * Added equally to all three rather than scaled per channel, which is what
     * keeps the hue fixed: an equal move in R, G and B changes brightness
     * without changing the ratios between them by more than the clamp forces.
     */
    private fun shiftChannel(value: Int, shift: Float): Int =
        (value + shift).roundToInt().coerceIn(0, 255)
}
