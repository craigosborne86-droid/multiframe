package dev.multiframe.camera.pipeline

/**
 * Lateral chromatic aberration: the three colours focused at different sizes.
 *
 * ### What the defect is
 *
 * A lens refracts short wavelengths more than long ones, so blue comes to focus
 * at a slightly smaller image scale than red. The three colour planes are
 * therefore near-identical pictures at marginally different magnifications, and
 * the mismatch grows with distance from the optical axis -- zero at the centre,
 * worst in the corners. At a high-contrast edge the planes disagree about where
 * the edge is, and the eye reads that disagreement as a coloured fringe.
 *
 * ### Why this is not [Defringe]
 *
 * [Defringe] finds pixels whose colour is an outlier at an edge and pulls them
 * toward neutral. That treats the symptom, and it cannot do otherwise: by the
 * time it runs, the detail that should have been there has already been
 * replaced by the wrong colour, and the best available move is to desaturate.
 *
 * This treats the cause. Rescaling the red and blue planes to agree with green
 * puts the edges back on top of each other, and the fringe stops existing rather
 * than being painted over. What is recovered is real: the samples were correct
 * all along, just in the wrong place.
 *
 * Defringe stays, for the residual -- longitudinal aberration, where the planes
 * differ in *focus* rather than in scale, is not a geometric error and no
 * rescale removes it.
 *
 * ### Why the scale is measured rather than looked up
 *
 * Camera2 publishes no chromatic aberration coefficients. `LENS_DISTORTION`
 * describes the geometry the three channels share and says nothing about how
 * they differ. So the correction is estimated from the picture: the radial
 * scale that best aligns each channel against green is searched for directly.
 */
object ChromaticAberration {

    /**
     * Radial scale factors that bring red and blue onto green.
     *
     * A value of 1.0 means the channel already agrees. Real lenses sit within a
     * few parts per thousand of that -- a 0.002 scale error is about four
     * pixels of fringe at the corner of a 4000-pixel frame, which is plainly
     * visible, and the numbers here are correspondingly small.
     */
    data class Scale(val red: Float, val blue: Float) {
        val isIdentity: Boolean get() = red == 1f && blue == 1f

        override fun toString(): String =
            "CA red %.5f blue %.5f".format(red, blue)
    }

    val NONE = Scale(1f, 1f)

    /**
     * How far from 1.0 a scale is allowed to be.
     *
     * Half a percent is already several times worse than any phone lens
     * measured, so a search that wants to go outside this has locked onto
     * something that is not chromatic aberration -- most likely a scene with
     * genuine colour structure at the scale of the search. Clamping keeps a bad
     * estimate from becoming a visible warp.
     */
    const val MAX_SCALE_DEVIATION = 0.005f

    /** Steps either side of 1.0 in the search. Odd, so 1.0 itself is tried. */
    private const val SEARCH_STEPS = 41

    /**
     * Only pixels with a gradient at least this fraction of the frame's peak
     * take part. Chromatic aberration is invisible in flat areas, and including
     * them buries the signal in pixels that carry no information about it.
     */
    private const val EDGE_FRACTION = 0.25f

    /** Below this many usable edge pixels the estimate is not worth making. */
    private const val MIN_SAMPLES = 500

    /**
     * Estimates the radial scale of red and blue against green.
     *
     * Takes a packed ARGB image that has already been white balanced, which
     * matters: the cost below compares a channel against green directly, and
     * that comparison is only meaningful once a neutral surface produces equal
     * values in all three.
     *
     * Returns [NONE] when the image gives too little to measure -- a flat scene,
     * or one whose edges are all genuinely coloured.
     */
    fun estimate(pixels: IntArray, width: Int, height: Int): Scale {
        require(pixels.size >= width * height) { "image smaller than $width x $height" }
        if (width < 32 || height < 32) return NONE

        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f

        val edges = edgePixels(pixels, width, height)
        if (edges.size < MIN_SAMPLES) return NONE

        return Scale(
            red = bestScale(pixels, width, height, cx, cy, edges, SHIFT_RED),
            blue = bestScale(pixels, width, height, cx, cy, edges, SHIFT_BLUE),
        )
    }

    private const val SHIFT_RED = 16
    private const val SHIFT_BLUE = 0
    private const val SHIFT_GREEN = 8

    /**
     * Indices of pixels sitting on a strong green-channel edge.
     *
     * Green is the reference because it is the channel the other two are being
     * aligned to, and because a Bayer sensor measures twice as much of it, so
     * its edges are the least noisy in the frame.
     *
     * Sampled on a stride rather than exhaustively: a few thousand well-chosen
     * edge pixels determine a single scalar to far more precision than it needs,
     * and a full sweep of twelve megapixels at forty-one candidate scales is
     * half a billion samples for no gain.
     */
    private fun edgePixels(pixels: IntArray, width: Int, height: Int): IntArray {
        val stride = maxOf(1, minOf(width, height) / 400)
        val margin = 2

        var peak = 0
        val found = ArrayList<Int>(4096)
        val gradients = ArrayList<Int>(4096)

        var y = margin
        while (y < height - margin) {
            var x = margin
            val row = y * width
            while (x < width - margin) {
                val i = row + x
                val gx = green(pixels[i + 1]) - green(pixels[i - 1])
                val gy = green(pixels[i + width]) - green(pixels[i - width])
                val g = kotlin.math.abs(gx) + kotlin.math.abs(gy)
                if (g > 0) {
                    found.add(i)
                    gradients.add(g)
                    if (g > peak) peak = g
                }
                x += stride
            }
            y += stride
        }

        if (peak == 0) return IntArray(0)
        val threshold = (peak * EDGE_FRACTION).toInt()
        val kept = ArrayList<Int>(found.size / 2)
        for (n in found.indices) {
            if (gradients[n] >= threshold) kept.add(found[n])
        }
        return kept.toIntArray()
    }

    /**
     * The radial scale minimising disagreement between [shift]'s channel and
     * green across the sampled edges.
     *
     * A direct sweep rather than a gradient method. The cost surface is smooth
     * but not guaranteed convex -- a scene with periodic structure can put a
     * local minimum at the period -- and forty-one evaluations of a few thousand
     * samples is cheap enough that the robustness is worth more than the speed.
     */
    private fun bestScale(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        edges: IntArray,
        shift: Int,
    ): Float {
        var bestScale = 1f
        var bestCost = Float.MAX_VALUE

        for (step in 0 until SEARCH_STEPS) {
            val t = step.toFloat() / (SEARCH_STEPS - 1) * 2f - 1f
            val scale = 1f + t * MAX_SCALE_DEVIATION
            val cost = costAt(pixels, width, height, cx, cy, edges, shift, scale)
            if (cost < bestCost) {
                bestCost = cost
                bestScale = scale
            }
        }
        return bestScale
    }

    /**
     * Squared disagreement between the rescaled channel and green.
     *
     * For each edge pixel, the channel is read from where it would have come
     * from had the lens magnified it by [scale], and compared against green
     * where it actually is. At the correct scale the two edges coincide and the
     * difference collapses; away from it, the channel is sampled off the edge
     * and the difference grows.
     */
    private fun costAt(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        edges: IntArray,
        shift: Int,
        scale: Float,
    ): Float {
        var cost = 0f
        val maxX = width - 1
        val maxY = height - 1

        for (i in edges) {
            val x = (i % width).toFloat()
            val y = (i / width).toFloat()

            val sx = cx + (x - cx) * scale
            val sy = cy + (y - cy) * scale
            if (sx < 0f || sy < 0f || sx > maxX || sy > maxY) continue

            val sampled = sampleChannel(pixels, width, maxX, maxY, sx, sy, shift)
            val reference = green(pixels[i])
            val d = sampled - reference
            cost += d * d
        }
        return cost
    }

    private fun green(argb: Int): Int = (argb ushr SHIFT_GREEN) and 0xFF

    /** Bilinear sample of one channel, clamped at the border. */
    private fun sampleChannel(
        pixels: IntArray,
        width: Int,
        maxX: Int,
        maxY: Int,
        fx: Float,
        fy: Float,
        shift: Int,
    ): Float {
        // Clamped before the split into integer and fraction, not after: taking
        // the fraction of an out-of-range coordinate and then clamping the
        // integer part leaves the two describing different points.
        val cx = fx.coerceIn(0f, maxX.toFloat())
        val cy = fy.coerceIn(0f, maxY.toFloat())

        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0
        val tx = cx - x0
        val ty = cy - y0

        val v00 = ((pixels[y0 * width + x0] ushr shift) and 0xFF).toFloat()
        val v10 = ((pixels[y0 * width + x1] ushr shift) and 0xFF).toFloat()
        val v01 = ((pixels[y1 * width + x0] ushr shift) and 0xFF).toFloat()
        val v11 = ((pixels[y1 * width + x1] ushr shift) and 0xFF).toFloat()

        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }

    /**
     * Rescales the red and blue planes in place, leaving green alone.
     *
     * Green is the reference and is never resampled, which is deliberate: it
     * carries most of the luminance detail, and putting it through an
     * interpolation would soften the picture to correct a defect it does not
     * have.
     */
    fun apply(pixels: IntArray, width: Int, height: Int, scale: Scale) {
        require(pixels.size >= width * height) { "image smaller than $width x $height" }
        if (scale.isIdentity) return

        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val maxX = width - 1
        val maxY = height - 1

        // Both channels are read from the original and written to a copy, so a
        // pixel already rewritten is never used as a source for the next one.
        val source = pixels.copyOf(width * height)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val px = x.toFloat()
                val py = y.toFloat()

                val rx = cx + (px - cx) * scale.red
                val ry = cy + (py - cy) * scale.red
                val bx = cx + (px - cx) * scale.blue
                val by = cy + (py - cy) * scale.blue

                val r = kotlin.math.round(
                    sampleChannel(source, width, maxX, maxY, rx, ry, SHIFT_RED)
                ).toInt().coerceIn(0, 255)
                val b = kotlin.math.round(
                    sampleChannel(source, width, maxX, maxY, bx, by, SHIFT_BLUE)
                ).toInt().coerceIn(0, 255)

                val original = source[row + x]
                pixels[row + x] = (original and 0xFF00FF00.toInt()) or
                    (r shl SHIFT_RED) or (b shl SHIFT_BLUE)
            }
        }
    }
}
