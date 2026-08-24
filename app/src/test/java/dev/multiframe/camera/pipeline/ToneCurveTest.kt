package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * The rendering curve.
 *
 * A tone curve is easy to get subtly, permanently wrong: a curve that dips
 * anywhere turns smooth gradients into banding, and a curve that shifts hue
 * turns every sunset orange. These pin the properties rather than the numbers,
 * so the look can be retuned without rewriting the tests.
 */
class ToneCurveTest {

    private val params = DevelopParams(exposureGain = 1f)

    // ------------------------------------------------------------------
    // Monotonicity: the property that cannot be allowed to break.
    // ------------------------------------------------------------------

    @Test
    fun `the display curve never descends`() {
        var previous = -1f
        var x = 0f
        while (x <= 1f) {
            val v = ToneCurve.renderDisplay(x, params)
            assertThat(v).isAtLeast(previous)
            previous = v
            x += 0.001f
        }
    }

    @Test
    fun `the S-curve never descends at any strength`() {
        for (amount in listOf(0f, 0.15f, 0.3f, 0.6f, 1f)) {
            var previous = -1f
            var x = 0f
            while (x <= 1f) {
                val v = ToneCurve.sCurve(x, amount)
                assertThat(v).isAtLeast(previous)
                previous = v
                x += 0.002f
            }
        }
    }

    @Test
    fun `a brighter scene value never renders darker`() {
        val rgb = FloatArray(3)
        var previous = -1f
        var level = 0f
        while (level <= 4f) {
            rgb[0] = level; rgb[1] = level; rgb[2] = level
            ToneCurve.renderLinear(rgb, params)
            assertThat(rgb[0]).isAtLeast(previous - 1e-6f)
            previous = rgb[0]
            level += 0.01f
        }
    }

    // ------------------------------------------------------------------
    // Range.
    // ------------------------------------------------------------------

    @Test
    fun `black stays black and nothing exceeds white`() {
        assertThat(ToneCurve.sCurve(0f, 0.3f)).isEqualTo(0f)
        assertThat(ToneCurve.sCurve(1f, 0.3f)).isWithin(1e-6f).of(1f)

        val rgb = floatArrayOf(0f, 0f, 0f)
        ToneCurve.renderLinear(rgb, params)
        assertThat(rgb[0]).isEqualTo(0f)

        // Even an absurd overexposure has to stay in range.
        val hot = floatArrayOf(50f, 40f, 30f)
        ToneCurve.renderLinear(hot, params)
        for (v in hot) assertThat(v).isAtMost(1.0001f)
        assertThat(ToneCurve.renderDisplay(1f, params)).isAtMost(1f)
    }

    // ------------------------------------------------------------------
    // The look.
    // ------------------------------------------------------------------

    @Test
    fun `midtone contrast is increased, which is the whole point`() {
        // Without this the render is flat: technically faithful, visually dead.
        val delta = 0.01f
        val slope = (ToneCurve.renderDisplay(0.5f + delta, params) -
            ToneCurve.renderDisplay(0.5f - delta, params)) / (2 * delta)

        assertThat(slope).isGreaterThan(1.1f)
    }

    @Test
    fun `shadows are placed below the straight line and highlights above`() {
        // The definition of an S: separation is bought in the midtones by
        // spending it at both ends.
        val shadow = ToneCurve.renderDisplay(0.2f, params)
        val highlight = ToneCurve.renderDisplay(0.8f, params)

        assertThat(shadow).isLessThan(0.2f)
        assertThat(highlight).isGreaterThan(0.8f)
    }

    @Test
    fun `zero contrast leaves the tones alone`() {
        val flat = params.copy(contrast = 0f, blackPoint = 0f)
        for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            assertThat(ToneCurve.renderDisplay(x, flat)).isWithin(1e-6f).of(x)
        }
    }

    // ------------------------------------------------------------------
    // Hue.
    // ------------------------------------------------------------------

    @Test
    fun `the roll-off preserves hue instead of sliding red through orange`() {
        // Per-channel compression clips red first and walks a bright red
        // toward yellow. Compressing the peak and scaling all three together
        // is what stops that.
        val noDesat = params.copy(highlightDesaturation = 0f)
        val rgb = floatArrayOf(1.6f, 0.40f, 0.20f)
        val ratioBefore = rgb[1] / rgb[0] to rgb[2] / rgb[0]

        ToneCurve.renderLinear(rgb, noDesat)

        assertThat(rgb[1] / rgb[0]).isWithin(1e-4f).of(ratioBefore.first)
        assertThat(rgb[2] / rgb[0]).isWithin(1e-4f).of(ratioBefore.second)
        assertThat(rgb[0]).isLessThan(1f)
    }

    @Test
    fun `desaturation scales with how overexposed the scene actually was`() {
        // Ratio-preserving compression alone never reaches white, so bright
        // areas stay stubbornly coloured. How much colour is given up has to
        // track the scene value, not the compressed one.
        fun spreadAt(stops: Float): Float {
            val rgb = floatArrayOf(stops, stops * 0.4f, stops * 0.2f)
            ToneCurve.renderLinear(rgb, params)
            return rgb[0] - rgb[2]
        }

        val moderate = spreadAt(3f)
        val extreme = spreadAt(20f)

        val noDesat = floatArrayOf(3f, 1.2f, 0.6f)
        ToneCurve.renderLinear(noDesat, params.copy(highlightDesaturation = 0f))
        val uncorrected = noDesat[0] - noDesat[2]

        // A few stops over loses a good part of its colour...
        assertThat(moderate).isLessThan(uncorrected * 0.6f)
        // ...and something blindingly bright is essentially white.
        assertThat(extreme).isLessThan(0.15f)
        assertThat(extreme).isLessThan(moderate)
    }

    @Test
    fun `a colour just under white keeps its colour`() {
        // The failure this guards against is a washed-out sky: a bright blue
        // sitting below nominal white must not be desaturated at all.
        val rgb = floatArrayOf(0.55f, 0.72f, 0.95f)
        val before = rgb.copyOf()
        ToneCurve.renderLinear(rgb, params)

        // Compressed, since it is above the knee, but the ratios are intact.
        assertThat(rgb[2] / rgb[0]).isWithin(1e-4f).of(before[2] / before[0])
    }

    @Test
    fun `a midtone colour keeps its saturation`() {
        // Desaturation must stay out of the way below its threshold, or every
        // colour in the picture is quietly washed out.
        val rgb = floatArrayOf(0.45f, 0.20f, 0.10f)
        val before = rgb.copyOf()
        ToneCurve.renderLinear(rgb, params)

        for (i in 0 until 3) assertThat(rgb[i]).isWithin(1e-5f).of(before[i])
    }

    @Test
    fun `neutral input stays neutral`() {
        // Any asymmetry between channels would tint the whole picture.
        for (level in listOf(0.05f, 0.3f, 0.7f, 1.4f, 3f)) {
            val rgb = floatArrayOf(level, level, level)
            ToneCurve.renderLinear(rgb, params)
            assertThat(abs(rgb[0] - rgb[1])).isLessThan(1e-6f)
            assertThat(abs(rgb[1] - rgb[2])).isLessThan(1e-6f)
        }
    }

    @Test
    fun `the toe gives the deepest shadows some density`() {
        // Without a black point the darkest parts of the frame render as grey
        // haze rather than black.
        val withToe = ToneCurve.renderDisplay(0.004f, params)
        val without = ToneCurve.renderDisplay(0.004f, params.copy(blackPoint = 0f))

        assertThat(withToe).isLessThan(without)
        assertThat(withToe).isAtLeast(0f)
    }
}
