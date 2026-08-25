package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes colour fringes from high-contrast edges.
 *
 * ### What is being corrected, and what is not
 *
 * A lens does not focus every wavelength at the same scale, so the red, green
 * and blue images are very slightly different sizes. Along a high-contrast edge
 * that misregistration shows as a coloured rim -- purple on one side, green on
 * the other -- and it is worst at the corners of the frame, where the
 * displacement is largest. Phone lenses are small and fast and have plenty of
 * it; the ISP corrects it for its own JPEG output, so a raw pipeline inherits
 * the problem uncorrected.
 *
 * The honest fix is geometric: rescale the red and blue planes about the optical
 * centre by per-lens coefficients. Those coefficients are not published by the
 * camera, and estimating them reliably from an arbitrary photograph is a
 * research problem rather than an implementation one. So this treats the symptom
 * instead, which is what raw converters call defringing, and it is worth being
 * clear that is what it is.
 *
 * ### Distinguishing a fringe from a coloured object
 *
 * The distinction that makes this safe: a fringe exists *only* at the edge,
 * while a genuinely coloured object carries its colour away from its own edges
 * too. So the chroma at an edge pixel is limited to the range found among
 * nearby pixels that are not on an edge. A red sign keeps its red, because the
 * middle of the sign is red as well. A purple rim on a bare branch loses it,
 * because nothing near it is purple.
 *
 * That rule fails on a coloured object small enough to be all edge, which is
 * why the limit is a clamp toward the local range rather than a desaturation
 * toward grey: the worst case is a small object slightly less saturated, not a
 * grey one.
 */
object Defringe {

    data class Params(
        /**
         * Off by default, and deliberately so.
         *
         * The correction works -- it takes a fringe from 55 chroma to 13 in the
         * tests -- but its benefit has never been seen on a real photograph,
         * and its cost has been measured: about 400 ms added to a 910 ms
         * develop, and up to a third of the frame altered on the only scene
         * available to measure against. That scene is a black frame amplified
         * sixty-four times, which is pure noise and says nothing useful about
         * ordinary photographs, so neither number can be trusted as typical.
         *
         * Shipping a correction that might damage images and definitely costs
         * half a second, on evidence that thin, is not a trade worth making by
         * default. It is enabled by one flag once it can be judged against real
         * pictures.
         */
        val enabled: Boolean = false,
        /** Luma gradient above which a pixel counts as an edge, in 0..255. */
        val edgeThreshold: Float = 28f,
        /** How far chroma may exceed the local range before it is pulled back. */
        val tolerance: Float = 6f,
        /**
         * Chroma a pixel must carry before it is considered a fringe at all.
         *
         * Without this the rule fires on ordinary chroma noise: at high gain,
         * noise produces gradients everywhere and slightly-off chroma
         * everywhere, and the correction quietly becomes chroma noise reduction
         * applied to half the frame. Measured before it existed, 5.9 million
         * pixels of a 12.5 megapixel frame were being altered.
         *
         * A real fringe is a strongly coloured rim, so requiring real chroma
         * costs nothing and confines the correction to what it is for.
         */
        val minChroma: Float = 22f,
        /**
         * Fraction of the way to the corner before the correction applies.
         *
         * Lateral colour error is a radial phenomenon: the colour planes differ
         * in *scale*, so the displacement between them is zero at the optical
         * centre by definition and grows toward the corners. Correcting the
         * middle of the frame is therefore not merely wasteful, it is wrong --
         * whatever colour is there is not lateral aberration, and treating it
         * as such can only damage real detail.
         *
         * It also cuts the cost by about three quarters, since the excluded
         * region is most of the frame's area.
         */
        val innerRadius: Float = 0.45f,
        /** How much of the excess to remove. One removes all of it. */
        val strength: Float = 0.85f,
    ) {
        internal val active: Boolean get() = enabled && strength > 0f
    }

    /** Half-width of the window searched for non-edge neighbours. */
    private const val WINDOW = 2

    /**
     * How far in from the border the scan starts.
     *
     * One more than the window, because each neighbour in the window is itself
     * tested for being an edge, and that test needs its own neighbours. Using
     * the window alone reads a row above the first one.
     */
    private const val MARGIN = WINDOW + 1

    private fun luma(r: Int, g: Int, b: Int): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Suppresses fringes in packed ARGB pixels, in place.
     *
     * Returns how many pixels were altered, which is worth logging: a figure
     * far above a few percent means the edge threshold is catching texture
     * rather than fringes.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        params: Params = Params(),
    ): Int {
        if (!params.active || width <= MARGIN * 2 || height <= MARGIN * 2) return 0
        require(pixels.size >= width * height) { "pixel buffer is too small" }

        // Luma and the two chroma differences, computed once. The window needs
        // its neighbours' values, and recomputing them per neighbour would do
        // the work twenty-five times over.
        val n = width * height
        val lumaPlane = FloatArray(n)
        val crPlane = FloatArray(n)
        val cbPlane = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumaPlane[i] = luma(r, g, b)
            // Against green rather than against luma: green is where a Bayer
            // sensor has most of its samples and least of its own error, so it
            // is the most trustworthy reference for what the other two should be.
            crPlane[i] = (r - g).toFloat()
            cbPlane[i] = (b - g).toFloat()
        }

        val out = IntArray(n)
        System.arraycopy(pixels, 0, out, 0, n)
        var altered = 0

        // Squared radii, so the per-pixel test needs no square root.
        val centreX = (width - 1) * 0.5f
        val centreY = (height - 1) * 0.5f
        val cornerSquared = centreX * centreX + centreY * centreY
        val innerSquared = cornerSquared * params.innerRadius * params.innerRadius

        for (y in MARGIN until height - MARGIN) {
            val dyc = y - centreY
            for (x in MARGIN until width - MARGIN) {
                val dxc = x - centreX
                // Zero displacement at the optical centre, by definition.
                if (dxc * dxc + dyc * dyc < innerSquared) continue

                val index = y * width + x

                // Cheapest test first: most pixels carry no real chroma and
                // never need the window scan at all.
                val cr = crPlane[index]
                val cb = cbPlane[index]
                if (max(abs(cr), abs(cb)) < params.minChroma) continue

                val gx = abs(lumaPlane[index + 1] - lumaPlane[index - 1])
                val gy = abs(lumaPlane[index + width] - lumaPlane[index - width])
                if (max(gx, gy) < params.edgeThreshold) continue

                // The range of chroma among neighbours that are *not* on an
                // edge. A genuinely coloured object has some of those; a fringe
                // has none of its own colour anywhere nearby.
                var crLow = Float.MAX_VALUE
                var crHigh = -Float.MAX_VALUE
                var cbLow = Float.MAX_VALUE
                var cbHigh = -Float.MAX_VALUE
                var found = 0

                for (dy in -WINDOW..WINDOW) {
                    val row = index + dy * width
                    for (dx in -WINDOW..WINDOW) {
                        val at = row + dx
                        val ngx = abs(lumaPlane[at + 1] - lumaPlane[at - 1])
                        val ngy = abs(lumaPlane[at + width] - lumaPlane[at - width])
                        if (max(ngx, ngy) >= params.edgeThreshold) continue
                        crLow = min(crLow, crPlane[at]); crHigh = max(crHigh, crPlane[at])
                        cbLow = min(cbLow, cbPlane[at]); cbHigh = max(cbHigh, cbPlane[at])
                        found++
                    }
                }
                // Every neighbour is on an edge too, so there is no evidence
                // about what colour belongs here. Leaving it alone is the only
                // safe answer.
                if (found == 0) continue

                val newCr = clampToward(cr, crLow, crHigh, params)
                val newCb = clampToward(cb, cbLow, cbHigh, params)
                if (newCr == cr && newCb == cb) continue

                val p = pixels[index]
                val g = (p shr 8) and 0xFF
                out[index] = (p.toLong() and 0xFF000000L).toInt() or
                    (channel(g + newCr) shl 16) or
                    (g shl 8) or
                    channel(g + newCb)
                altered++
            }
        }
        System.arraycopy(out, 0, pixels, 0, n)
        return altered
    }

    /** Pulls a value back toward a range it has escaped, by [Params.strength]. */
    private fun clampToward(value: Float, low: Float, high: Float, params: Params): Float {
        val ceiling = high + params.tolerance
        val floor = low - params.tolerance
        return when {
            value > ceiling -> value - (value - ceiling) * params.strength
            value < floor -> value + (floor - value) * params.strength
            else -> value
        }
    }

    private fun channel(value: Float): Int = value.roundToInt().coerceIn(0, 255)
}
