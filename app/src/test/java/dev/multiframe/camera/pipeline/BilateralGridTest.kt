package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The local tone operator, held to the three things it promises.
 *
 * It compresses the range between neighbourhoods: a bright region comes down
 * and a dark one comes up. It preserves the contrast within a neighbourhood: a
 * pixel a little brighter than its surroundings stays exactly that much
 * brighter, because both get the same base. And it does not halo: a dark
 * pixel beside a bright edge gets the dark side's base, not a mixture,
 * because the grid's third axis keeps the two apart.
 */
class BilateralGridTest {

    /** A grid over a [w] x [h] image whose luminance is [f](x, y). */
    private fun gridOf(w: Int, h: Int, f: (Int, Int) -> Float): BilateralGrid {
        val g = BilateralGrid.forImage(w, h)
        for (y in 0 until h) for (x in 0 until w) g.splat(x, y, f(x, y))
        g.finish()
        return g
    }

    @Test
    fun aFlatFieldIsLeftAlone() {
        val g = gridOf(256, 128) { _, _ -> 0.3f }
        for (x in intArrayOf(3, 100, 250)) for (y in intArrayOf(2, 60, 120)) {
            assertThat(g.gain(x, y, 0.3f, 0.5f)).isWithin(1e-4f).of(1f)
        }
    }

    @Test
    fun strengthZeroIsOff() {
        val g = gridOf(256, 128) { x, _ -> if (x < 128) 0.05f else 0.8f }
        assertThat(g.gain(20, 20, 0.05f, 0f)).isEqualTo(1f)
        assertThat(g.gain(200, 20, 0.8f, 0f)).isEqualTo(1f)
    }

    @Test
    fun theBrightSideComesDownAndTheDarkSideComesUp() {
        val g = gridOf(256, 128) { x, _ -> if (x < 128) 0.05f else 0.8f }
        val dark = g.gain(30, 64, 0.05f, 0.5f)
        val bright = g.gain(220, 64, 0.8f, 0.5f)
        assertThat(dark).isGreaterThan(1.5f)
        assertThat(bright).isLessThan(0.7f)
        // At full strength both neighbourhoods land on the picture's mean
        // log luminance: four stops apart in, together out.
        val darkOut = 0.05f * g.gain(30, 64, 0.05f, 1f)
        val brightOut = 0.8f * g.gain(220, 64, 0.8f, 1f)
        assertThat(abs(BilateralGrid.logOf(darkOut) - BilateralGrid.logOf(brightOut))).isLessThan(0.1f)
    }

    @Test
    fun detailWithinANeighbourhoodIsCarriedThrough() {
        // A textured region: pixels a fifth of a stop above and below its level.
        val g = gridOf(256, 128) { x, y -> if ((x + y) % 2 == 0) 0.32f else 0.28f }
        val up = g.gain(100, 60, 0.32f, 0.7f)
        val down = g.gain(101, 60, 0.28f, 0.7f)
        // The same base for both, so the same gain: the ratio between them
        // after the operator is the ratio before it.
        assertThat(up).isWithin(0.01f).of(down)
        val ratioBefore = 0.32f / 0.28f
        val ratioAfter = (0.32f * up) / (0.28f * down)
        assertThat(ratioAfter).isWithin(0.02f).of(ratioBefore)
    }

    @Test
    fun aSharpEdgeDoesNotHalo() {
        // Dark wall on the left, bright window on the right, hard edge at 128.
        val g = gridOf(256, 128) { x, _ -> if (x < 128) 0.04f else 0.9f }
        // A wall pixel four pixels from the window gets the wall's gain, not a
        // blend of wall and window -- which is what a spatial blur would give
        // and what shows as a dark band along the edge.
        val nearEdge = g.gain(124, 64, 0.04f, 0.6f)
        val farFromEdge = g.gain(20, 64, 0.04f, 0.6f)
        assertThat(nearEdge / farFromEdge).isWithin(0.08f).of(1f)
        // And likewise on the window's side.
        val windowNear = g.gain(132, 64, 0.9f, 0.6f)
        val windowFar = g.gain(240, 64, 0.9f, 0.6f)
        assertThat(windowNear / windowFar).isWithin(0.08f).of(1f)
    }

    @Test
    fun theOperatorIsHuePreservingInTheDevelop() {
        // Through RawDeveloper: a frame half dark, half bright, developed with
        // and without the operator. Colour ratios within a pixel must not
        // move, since the whole pixel is scaled by one gain.
        val w = 96
        val h = 64
        val profile = SensorProfile(intArrayOf(64, 64, 64, 64), 1023, intArrayOf(0, 1, 1, 2))
        val data = ShortArray(w * h) { i ->
            val x = i % w
            val level = if (x < w / 2) 40 else 700
            (64 + level).toShort()
        }
        val frame = BayerFrame(w, h, data)
        val color = ColorProfile(floatArrayOf(1.4f, 1f, 1f, 1.2f), ColorProfile.NEUTRAL.matrix)
        val base = DevelopParams(
            exposureGain = 1f, shoulderKnee = 1f, contrast = 0f, highlightDesaturation = 0f,
            blackPoint = 0f, sharpen = Sharpen.Params(amount = 0f), hotPixelThreshold = 0f,
        )
        val plain = RawDeveloper.develop(frame, profile, color, base)
        val local = RawDeveloper.develop(frame, profile, color, base.copy(localTone = 0.6f))

        fun red(p: Int) = (p shr 16) and 0xFF
        fun green(p: Int) = (p shr 8) and 0xFF
        val darkPixel = h / 2 * w + w / 4
        val brightPixel = h / 2 * w + 3 * w / 4
        // Compression: the dark side came up, the bright side came down.
        assertThat(green(local[darkPixel])).isGreaterThan(green(plain[darkPixel]))
        assertThat(green(local[brightPixel])).isLessThan(green(plain[brightPixel]))
        // Hue: the red-to-green ratio is the same either way, to a code.
        val plainRatio = red(plain[darkPixel]).toFloat() / green(plain[darkPixel])
        val localRatio = red(local[darkPixel]).toFloat() / green(local[darkPixel])
        assertThat(localRatio).isWithin(0.03f).of(plainRatio)
    }

    // ------------------------------------------------------------------
    // The shadow lift.
    // ------------------------------------------------------------------

    /** Two levels, [dark] and [bright], the bright one over [brightShare] of the width. */
    private fun bimodal(dark: Float, bright: Float, brightShare: Float = 0.6f): BilateralGrid =
        gridOf(512, 256) { x, _ -> if (x < 512 * (1 - brightShare)) dark else bright }

    @Test
    fun theCellFollowsTheImage() {
        assertThat(BilateralGrid.cellFor(256, 128)).isEqualTo(BilateralGrid.CELL)
        assertThat(BilateralGrid.cellFor(4080, 3064)).isEqualTo(3064 / BilateralGrid.CELLS_ACROSS)
        // The bench's half-size develop of the same frame reads half the cell, so the same neighbourhood.
        assertThat(BilateralGrid.cellFor(2040, 1532)).isEqualTo(1532 / BilateralGrid.CELLS_ACROSS)
    }

    @Test
    fun aSceneOfOrdinaryRangeEarnsNoLift() {
        // Two and a half stops between the two levels: the display holds it,
        // and the operator renders exactly as the symmetric operator would.
        val g = bimodal(0.1f, 0.1f * 2f.pow(2.5f))
        assertThat(g.range).isWithin(0.3f).of(2.5f)
        assertThat(g.ceiling(1f, 3f)).isEqualTo(0f)
        val plain = g.gain(30, 64, 0.1f, 0.15f)
        val twoSided = g.gain(30, 64, 0.1f, 0.15f, 1f, 3f)
        assertThat(twoSided).isWithin(1e-5f).of(plain)
    }

    @Test
    fun aBacklitSceneEarnsTheCeilingAndSpendsItOnTheShadow() {
        // Six stops between the tower and the sky: the range beyond the held
        // range, a stop per stop, to the ceiling.
        val dark = 0.5f * 2f.pow(-6f)
        val g = bimodal(dark, 0.5f)
        assertThat(g.range).isWithin(0.3f).of(6f)
        val earned = g.ceiling(1f, 3f)
        assertThat(earned).isWithin(0.3f).of(minOf(3f, 6f - BilateralGrid.RANGE_HELD))
        // The sky is the bright end and does not move for the lift; the
        // tower, six stops under it, comes up by the whole ceiling.
        val sky = g.gain(400, 64, 0.5f, 0f, 1f, 3f)
        val tower = g.gain(30, 64, dark, 0f, 1f, 3f)
        assertThat(sky).isWithin(1e-4f).of(1f)
        assertThat(BilateralGrid.logOf(tower)).isWithin(0.05f).of(earned)
    }

    @Test
    fun theLiftKeepsTheOrderOfTwoNeighbourhoods() {
        // Along the whole ramp the lifted base is still increasing in the
        // base: a darker neighbourhood never comes out brighter than a
        // lighter one, and keeps at least a quarter of their separation.
        val ceiling = 3f
        var previous = Float.NEGATIVE_INFINITY
        var depth = 8f
        while (depth >= 0f) {
            val base = -depth
            val lifted = base + BilateralGrid.lift(0f, depth, 0f, ceiling)
            assertThat(lifted).isGreaterThan(previous)
            previous = lifted
            depth -= 0.05f
        }
        // A step of a stop in the base, on the steepest part of the ramp, is at least a quarter of a stop out.
        val steep = BilateralGrid.SHADOW_DEAD_ZONE + BilateralGrid.SHADOW_RAMP_PER_STOP * ceiling / 2
        val a = -steep + BilateralGrid.lift(0f, steep, 0f, ceiling)
        val b = -(steep + 1f) + BilateralGrid.lift(0f, steep + 1f, 0f, ceiling)
        assertThat(a - b).isAtLeast(0.24f)
        // And within the dead zone nothing moves.
        assertThat(BilateralGrid.lift(0f, BilateralGrid.SHADOW_DEAD_ZONE * 0.9f, 0f, ceiling)).isWithin(0f).of(0f)
    }

    @Test
    fun zeroStrengthDevelopsExactlyAsBefore() {
        val w = 64
        val h = 48
        val profile = SensorProfile(intArrayOf(64, 64, 64, 64), 1023, intArrayOf(0, 1, 1, 2))
        val frame = BayerFrame(w, h, ShortArray(w * h) { i -> (64 + (i * 37) % 800).toShort() })
        val a = RawDeveloper.develop(frame, profile, ColorProfile.NEUTRAL, DevelopParams(hotPixelThreshold = 0f))
        val b = RawDeveloper.develop(frame, profile, ColorProfile.NEUTRAL, DevelopParams(hotPixelThreshold = 0f, localTone = 0f))
        assertThat(b).isEqualTo(a)
    }
}
