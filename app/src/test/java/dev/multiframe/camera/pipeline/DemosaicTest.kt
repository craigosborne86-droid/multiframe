package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Demosaic quality, measured against the full-colour image the CFA was sampled
 * from rather than asserted in the abstract.
 */
class DemosaicTest {

    private val profile = TestBayer.PROFILE          // GBRG, black 64, white 1023
    private val neutral = ColorProfile.NEUTRAL
    private val width = 96
    private val height = 72

    private fun mhc(frame: BayerFrame) = { x: Int, y: Int, out: FloatArray ->
        Demosaic.pixel(frame, profile, neutral, x, y, out)
    }

    private fun gather(frame: BayerFrame) = { x: Int, y: Int, out: FloatArray ->
        Demosaic.simpleGather(frame, profile, neutral, x, y, out)
    }

    // ------------------------------------------------------------------
    // The measurement that matters.
    // ------------------------------------------------------------------

    @Test
    fun `recovers the original image more accurately than a 3x3 gather`() {
        val truth = TestDemosaic.scene(width, height)
        val frame = TestDemosaic.mosaic(truth, profile)

        val errorMhc = TestDemosaic.rmse(truth, frame, profile, demosaic = mhc(frame))
        val errorGather = TestDemosaic.rmse(truth, frame, profile, demosaic = gather(frame))

        println("demosaic RMSE: gradient-corrected %.5f, 3x3 gather %.5f (%.2fx better)"
            .format(errorMhc, errorGather, errorGather / errorMhc))

        assertThat(errorMhc).isLessThan(errorGather)
    }

    /**
     * The specific failure being fixed.
     *
     * A green site sits at the centre of five greens in a 3x3 window, so the
     * gather returns a blur of all five instead of the value the sensor
     * actually measured. Half the pixels on the sensor are green, so this is
     * where most of the lost detail goes.
     */
    @Test
    fun `a measured sample is returned exactly, not averaged with its neighbours`() {
        val truth = TestDemosaic.scene(width, height)
        val frame = TestDemosaic.mosaic(truth, profile)
        val out = FloatArray(3)

        var checked = 0
        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                val colour = Demosaic.colourAt(profile, x, y)
                val measured = Demosaic.sample(frame, profile, neutral, x, y)
                Demosaic.pixel(frame, profile, neutral, x, y, out)
                assertThat(out[colour]).isWithin(1e-6f).of(measured)
                checked++
            }
        }
        assertThat(checked).isGreaterThan(1000)
    }

    @Test
    fun `the 3x3 gather does not preserve measured samples, which is the point`() {
        // Guards the comparison itself: if the baseline ever started preserving
        // samples, the test above would stop demonstrating anything.
        val truth = TestDemosaic.scene(width, height)
        val frame = TestDemosaic.mosaic(truth, profile)
        val out = FloatArray(3)

        var blurred = 0
        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                val colour = Demosaic.colourAt(profile, x, y)
                val measured = Demosaic.sample(frame, profile, neutral, x, y)
                Demosaic.simpleGather(frame, profile, neutral, x, y, out)
                if (kotlin.math.abs(out[colour] - measured) > 1e-4f) blurred++
            }
        }
        assertThat(blurred).isGreaterThan(500)
    }

    // ------------------------------------------------------------------
    // Correctness properties.
    // ------------------------------------------------------------------

    @Test
    fun `a flat field demosaics to that exact colour`() {
        // Every kernel sums to 8/8, so a constant field must come back
        // unchanged. A coefficient typo shows up here immediately.
        val flat = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            flat[i * 3] = 0.40f; flat[i * 3 + 1] = 0.55f; flat[i * 3 + 2] = 0.25f
        }
        val truth = TestDemosaic.Rgb(width, height, flat)
        val frame = TestDemosaic.mosaic(truth, profile)
        val out = FloatArray(3)

        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                Demosaic.pixel(frame, profile, neutral, x, y, out)
                assertThat(out[0]).isWithin(2e-3f).of(0.40f)
                assertThat(out[1]).isWithin(2e-3f).of(0.55f)
                assertThat(out[2]).isWithin(2e-3f).of(0.25f)
            }
        }
    }

    @Test
    fun `a neutral edge stays neutral instead of fringing`() {
        // Independent per-plane interpolation disagrees about where an edge is,
        // which is what puts coloured fringes on a grey step. The gradient
        // correction is there to stop that, so this is its direct test.
        val data = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 2) 0.15f else 0.75f
                val i = (y * width + x) * 3
                data[i] = v; data[i + 1] = v; data[i + 2] = v
            }
        }
        val frame = TestDemosaic.mosaic(TestDemosaic.Rgb(width, height, data), profile)
        val out = FloatArray(3)

        var worstMhc = 0f
        var worstGather = 0f
        for (y in 4 until height - 4) {
            for (x in width / 2 - 3..width / 2 + 3) {
                Demosaic.pixel(frame, profile, neutral, x, y, out)
                worstMhc = maxOf(
                    worstMhc,
                    kotlin.math.abs(out[0] - out[1]),
                    kotlin.math.abs(out[2] - out[1]),
                )
                Demosaic.simpleGather(frame, profile, neutral, x, y, out)
                worstGather = maxOf(
                    worstGather,
                    kotlin.math.abs(out[0] - out[1]),
                    kotlin.math.abs(out[2] - out[1]),
                )
            }
        }
        println("worst colour error across a neutral edge: gradient-corrected " +
            "%.4f, 3x3 gather %.4f".format(worstMhc, worstGather))

        assertThat(worstMhc).isLessThan(worstGather)
    }

    @Test
    fun `holds a fine detail pattern with more contrast than the gather`() {
        // One-pixel-period detail is beyond what a CFA can resolve, but two-pixel
        // detail should survive. Modulation depth is what "sharp" means here.
        val data = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if ((x / 2) % 2 == 0) 0.70f else 0.20f
                val i = (y * width + x) * 3
                data[i] = v; data[i + 1] = v; data[i + 2] = v
            }
        }
        val frame = TestDemosaic.mosaic(TestDemosaic.Rgb(width, height, data), profile)

        fun modulation(f: (Int, Int, FloatArray) -> Unit): Float {
            val out = FloatArray(3)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (y in 6 until height - 6) {
                for (x in 6 until width - 6) {
                    f(x, y, out)
                    val luma = 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2]
                    if (luma < lo) lo = luma
                    if (luma > hi) hi = luma
                }
            }
            return hi - lo
        }

        val depthMhc = modulation(mhc(frame))
        val depthGather = modulation(gather(frame))
        println("two-pixel detail modulation: gradient-corrected %.4f, gather %.4f"
            .format(depthMhc, depthGather))

        assertThat(depthMhc).isGreaterThan(depthGather)
    }

    @Test
    fun `never returns negative light`() {
        // The correction is an extrapolation and overshoots on hard edges.
        val truth = TestDemosaic.scene(width, height)
        val frame = TestDemosaic.mosaic(truth, profile)
        val out = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                Demosaic.pixel(frame, profile, neutral, x, y, out)
                assertThat(out[0]).isAtLeast(0f)
                assertThat(out[1]).isAtLeast(0f)
                assertThat(out[2]).isAtLeast(0f)
            }
        }
    }

    @Test
    fun `works for every CFA arrangement, not just this sensor`() {
        // The kernels are selected from the pattern at runtime, so a device
        // with RGGB must demosaic as correctly as this one's GBRG.
        val patterns = mapOf(
            "RGGB" to intArrayOf(0, 1, 1, 2),
            "GRBG" to intArrayOf(1, 0, 2, 1),
            "GBRG" to intArrayOf(1, 2, 0, 1),
            "BGGR" to intArrayOf(2, 1, 1, 0),
        )
        val flat = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            flat[i * 3] = 0.30f; flat[i * 3 + 1] = 0.60f; flat[i * 3 + 2] = 0.45f
        }
        val truth = TestDemosaic.Rgb(width, height, flat)
        val out = FloatArray(3)

        for ((name, cfa) in patterns) {
            val sensor = SensorProfile(intArrayOf(64, 64, 64, 64), 1023, cfa)
            val frame = TestDemosaic.mosaic(truth, sensor)
            for (y in 6 until height - 6 step 3) {
                for (x in 6 until width - 6 step 3) {
                    Demosaic.pixel(frame, sensor, neutral, x, y, out)
                    assertThat(out[0]).isWithin(3e-3f).of(0.30f)
                    assertThat(out[1]).isWithin(3e-3f).of(0.60f)
                    assertThat(out[2]).isWithin(3e-3f).of(0.45f)
                }
            }
            println("$name demosaics a flat field correctly")
        }
    }

    @Test
    fun `white balance gains are applied before interpolation`() {
        // Carrying a correction measured on one channel across to another is
        // only sound when the channels share a scale. If gains were applied
        // afterwards this would return the raw ratio instead of the balanced one.
        val data = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            data[i * 3] = 0.25f; data[i * 3 + 1] = 0.50f; data[i * 3 + 2] = 0.20f
        }
        val frame = TestDemosaic.mosaic(TestDemosaic.Rgb(width, height, data), profile)
        val warm = ColorProfile(
            gains = floatArrayOf(2f, 1f, 1f, 1.5f),
            matrix = ColorProfile.NEUTRAL.matrix,
        )
        val out = FloatArray(3)

        Demosaic.pixel(frame, profile, warm, 20, 20, out)

        assertThat(out[0]).isWithin(3e-3f).of(0.25f * 2f)
        assertThat(out[1]).isWithin(3e-3f).of(0.50f)
        assertThat(out[2]).isWithin(3e-3f).of(0.20f * 1.5f)
    }
}
