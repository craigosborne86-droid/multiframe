package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

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
