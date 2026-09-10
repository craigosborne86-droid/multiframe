package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Lateral chromatic aberration, tested by injecting a known amount and asking
 * for it back.
 *
 * This is the shape of test the defect deserves. There is no ground truth to be
 * had from a real photograph -- nobody knows what its true scale error was --
 * but a synthetic scene can be built with the aberration put in deliberately,
 * and then the estimator either recovers the number or it does not.
 */
class ChromaticAberrationTest {

    private val width = 256
    private val height = 256

    /**
     * A scene of concentric high-contrast rings.
     *
     * Rings rather than a grid because lateral aberration is radial: a pattern
     * of edges at every radius and every angle exercises the whole field, and
     * the misalignment it produces is in the direction the estimator searches.
     */
    private fun rings(
        redScale: Float = 1f,
        blueScale: Float = 1f,
    ): IntArray {
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val out = IntArray(width * height)

        // Value of the scene at radius r, as a channel would see it if the lens
        // magnified that channel by `scale`.
        fun channelAt(x: Float, y: Float, scale: Float): Int {
            val r = kotlin.math.hypot((x - cx) / scale, (y - cy) / scale)
            // Square rings, so every crossing is a hard edge.
            return if (((r / 12f).toInt()) % 2 == 0) 220 else 40
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = x.toFloat()
                val fy = y.toFloat()
                val r = channelAt(fx, fy, redScale)
                val g = channelAt(fx, fy, 1f)
                val b = channelAt(fx, fy, blueScale)
                out[y * width + x] =
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    // --- estimation --------------------------------------------------------

    /** A scene with no aberration must not have any invented for it. */
    @Test
    fun anAlignedImageEstimatesNoCorrection() {
        val scale = ChromaticAberration.estimate(rings(), width, height)
        assertThat(scale.red).isWithin(3e-4f).of(1f)
        assertThat(scale.blue).isWithin(3e-4f).of(1f)
    }

    /**
     * The one that matters: a known scale error is recovered.
     *
     * The sign convention is what this pins. Red magnified by 1.002 means red's
     * edges sit further out than green's, and the correction must sample red
     * from further out to bring it back — so the estimate should come back
     * near 1.002, not 0.998. Getting this backwards doubles the fringe instead
     * of removing it, and looks plausible until measured.
     */
    @Test
    fun aKnownRedScaleErrorIsRecovered() {
        val injected = 1.002f
        val scale = ChromaticAberration.estimate(
            rings(redScale = injected), width, height,
        )
        assertThat(scale.red).isWithin(5e-4f).of(injected)
        assertThat(scale.blue).isWithin(5e-4f).of(1f)
    }

    @Test
    fun aKnownBlueScaleErrorIsRecovered() {
        val injected = 0.9975f
        val scale = ChromaticAberration.estimate(
            rings(blueScale = injected), width, height,
        )
        assertThat(scale.blue).isWithin(5e-4f).of(injected)
        assertThat(scale.red).isWithin(5e-4f).of(1f)
    }

    /** Real lenses spread the channels in opposite directions. */
    @Test
    fun redAndBlueAreEstimatedIndependently() {
        val scale = ChromaticAberration.estimate(
            rings(redScale = 1.0025f, blueScale = 0.9975f), width, height,
        )
        assertThat(scale.red).isGreaterThan(1f)
        assertThat(scale.blue).isLessThan(1f)
    }

    // --- refusals ----------------------------------------------------------

    /**
     * A flat scene carries no information about the defect. Estimating from one
     * would be reading noise, so it declines.
     */
    @Test
    fun aFlatImageIsNotMeasured() {
        val flat = IntArray(width * height) { (0xFF shl 24) or 0x808080 }
        assertThat(ChromaticAberration.estimate(flat, width, height))
            .isEqualTo(ChromaticAberration.NONE)
    }

    @Test
    fun anImageTooSmallToMeasureIsDeclined() {
        val tiny = IntArray(16 * 16) { (0xFF shl 24) or 0x808080 }
        assertThat(ChromaticAberration.estimate(tiny, 16, 16))
            .isEqualTo(ChromaticAberration.NONE)
    }

    /**
     * The estimate cannot wander outside what a lens can physically do. A
     * search that wants to is looking at scene colour, not aberration, and an
     * unclamped result would warp the picture visibly.
     */
    @Test
    fun theEstimateStaysWithinPhysicalBounds() {
        val scale = ChromaticAberration.estimate(
            rings(redScale = 1.05f, blueScale = 0.95f), width, height,
        )
        val bound = ChromaticAberration.MAX_SCALE_DEVIATION
        assertThat(scale.red).isAtMost(1f + bound)
        assertThat(scale.red).isAtLeast(1f - bound)
        assertThat(scale.blue).isAtMost(1f + bound)
        assertThat(scale.blue).isAtLeast(1f - bound)
    }

    @Test
    fun anUndersizedBufferIsRefused() {
        try {
            ChromaticAberration.estimate(IntArray(10), width, height)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("smaller")
        }
    }

    // --- correction --------------------------------------------------------

    /**
     * Estimate and correct should close the loop: after applying what was
     * measured, a second estimate finds nothing left to do.
     */
    @Test
    fun correctingAnImageLeavesNothingToCorrect() {
        val pixels = rings(redScale = 1.002f, blueScale = 0.998f)
        val first = ChromaticAberration.estimate(pixels, width, height)

        ChromaticAberration.apply(pixels, width, height, first)

        val residual = ChromaticAberration.estimate(pixels, width, height)
        assertThat(residual.red).isWithin(6e-4f).of(1f)
        assertThat(residual.blue).isWithin(6e-4f).of(1f)
    }

    /**
     * Green carries most of the luminance detail and has no scale error to fix,
     * so it is never resampled. Interpolating it would soften the picture to
     * correct a defect it does not have.
     */
    @Test
    fun greenIsNeverTouched() {
        val pixels = rings(redScale = 1.003f, blueScale = 0.997f)
        val before = pixels.map { (it ushr 8) and 0xFF }

        ChromaticAberration.apply(
            pixels, width, height, ChromaticAberration.Scale(1.003f, 0.997f),
        )

        val after = pixels.map { (it ushr 8) and 0xFF }
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun anIdentityScaleIsANoOp() {
        val pixels = rings(redScale = 1.002f)
        val before = pixels.copyOf()
        ChromaticAberration.apply(pixels, width, height, ChromaticAberration.NONE)
        assertThat(pixels).isEqualTo(before)
    }

    @Test
    fun theAlphaChannelStaysOpaque() {
        val pixels = rings(redScale = 1.003f)
        ChromaticAberration.apply(
            pixels, width, height, ChromaticAberration.Scale(1.003f, 0.998f),
        )
        assertThat(pixels.all { (it ushr 24) and 0xFF == 0xFF }).isTrue()
    }

    /**
     * The correction reads from an untouched copy. Resampling in place would
     * let a pixel already rewritten become the source for its neighbour, which
     * smears the correction outward across the frame.
     */
    @Test
    fun theCorrectionDoesNotFeedOnItsOwnOutput() {
        val strong = ChromaticAberration.Scale(1.005f, 0.995f)

        val once = rings()
        ChromaticAberration.apply(once, width, height, strong)

        // Applying the inverse must return close to the original, which is only
        // true if each pass read a clean source.
        val back = once.copyOf()
        ChromaticAberration.apply(
            back, width, height, ChromaticAberration.Scale(1f / 1.005f, 1f / 0.995f),
        )

        val original = rings()
        var worst = 0
        for (i in original.indices) {
            val a = (original[i] ushr 16) and 0xFF
            val b = (back[i] ushr 16) and 0xFF
            worst = maxOf(worst, kotlin.math.abs(a - b))
        }
        // Two resamples of a hard-edged synthetic target cannot be lossless;
        // what this rules out is the runaway smear, which reaches full scale.
        assertThat(worst).isLessThan(120)
    }
}
