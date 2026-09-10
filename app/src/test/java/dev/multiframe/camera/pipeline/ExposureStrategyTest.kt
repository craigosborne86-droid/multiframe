package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exposure decisions.
 *
 * The asymmetry being exploited: a clipped highlight is gone forever, while a
 * noisy shadow still contains its detail and averaging digs it out. These pin
 * the amount of that trade to the sqrt(N) law rather than to taste.
 */
class ExposureStrategyTest {

    private val caps = CameraCapabilities(
        hasManualSensor = true,
        isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000, exposureMaxNs = 500_000_000,
        minFocusDiopters = 10f,
        awbModes = listOf(1), afModes = listOf(1),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -24, evMax = 24, evStep = 1f / 6f,
        sensorOrientation = 90,
        supportsRaw = true,
        supportedOutputFormats = emptySet(),
    )

    /** A histogram with a given fraction of pixels in the top bin. */
    private fun histogram(
        bins: Int = 64,
        clippedFraction: Float = 0f,
        peakBin: Int = 20,
    ): IntArray {
        val out = IntArray(bins)
        val total = 10_000
        val clipped = (total * clippedFraction).toInt()
        out[bins - 1] = clipped
        var remaining = total - clipped
        // A rough bell around the peak.
        for (offset in -8..8) {
            val bin = (peakBin + offset).coerceIn(0, bins - 2)
            val weight = 9 - kotlin.math.abs(offset)
            val share = remaining * weight / 81
            out[bin] += share
        }
        return out
    }

    // ------------------------------------------------------------------
    // The law the strategy turns on.
    // ------------------------------------------------------------------

    @Test
    fun `shadow recovery follows the square root of the frame count`() {
        // Averaging N frames improves signal to noise by sqrt(N), which is
        // 0.5 * log2(N) stops. This is the entire argument for burst capture
        // expressed as one number.
        assertThat(ExposureStrategy.shadowRecoveryStops(1)).isEqualTo(0f)
        assertThat(ExposureStrategy.shadowRecoveryStops(4)).isWithin(0.01f).of(1f)
        assertThat(ExposureStrategy.shadowRecoveryStops(8)).isWithin(0.01f).of(1.5f)
        assertThat(ExposureStrategy.shadowRecoveryStops(16)).isWithin(0.01f).of(2f)
        assertThat(ExposureStrategy.shadowRecoveryStops(32)).isWithin(0.01f).of(2.5f)
    }

    @Test
    fun `DCG adds extra shadow recovery on top of the sqrt-N base`() {
        val base8 = ExposureStrategy.shadowRecoveryStops(8)
        val dcg8 = ExposureStrategy.shadowRecoveryStopsDcg(8, dcgRatio = 4)

        // 4x ratio = 2 stops of extra sensitivity, halved = 1 extra stop.
        assertThat(dcg8).isWithin(0.01f).of(base8 + 1f)
        println("8 frames: base %.2f stops, with DCG×4 %.2f stops".format(base8, dcg8))
    }

    @Test
    fun `DCG ratio of 1 is identical to the base`() {
        for (n in listOf(1, 4, 8, 16)) {
            assertThat(ExposureStrategy.shadowRecoveryStopsDcg(n, dcgRatio = 1))
                .isEqualTo(ExposureStrategy.shadowRecoveryStops(n))
        }
    }

    @Test
    fun `DCG lets the highlight guard pull further`() {
        val bright = ExposureStrategy.analyse(histogram(clippedFraction = 0.25f))

        val noDcg = ExposureStrategy.recommendedPullStops(bright, frameCount = 8)
        val withDcg = ExposureStrategy.recommendedPullStops(bright, frameCount = 8, dcgRatio = 4)

        assertThat(withDcg).isLessThan(noDcg)
        println("blown scene at 8 frames: no DCG %.2f, DCG×4 %.2f".format(noDcg, withDcg))
    }

    @Test
    fun `a single frame can afford no underexposure at all`() {
        // With nothing to merge, pulling exposure just makes a darker, noisier
        // picture. The strategy has to know that.
        val bright = ExposureStrategy.analyse(histogram(clippedFraction = 0.2f))

        assertThat(ExposureStrategy.recommendedPullStops(bright, frameCount = 1)).isEqualTo(0f)
    }

    @Test
    fun `more frames buy more highlight protection`() {
        val bright = ExposureStrategy.analyse(histogram(clippedFraction = 0.25f))

        val four = ExposureStrategy.recommendedPullStops(bright, frameCount = 4)
        val sixteen = ExposureStrategy.recommendedPullStops(bright, frameCount = 16)

        assertThat(sixteen).isLessThan(four)
        println("blown scene: 4 frames pull %.2f stops, 16 frames pull %.2f"
            .format(four, sixteen))
    }

    @Test
    fun `recovery gain exactly undoes the pull`() {
        // The picture must land at the brightness it would have had, with the
        // highlights it would not have had.
        for (stops in listOf(-0.5f, -1f, -1.5f, -2f)) {
            val gain = ExposureStrategy.shadowRecoveryGain(stops)
            assertThat(gain).isGreaterThan(1f)
        }
        assertThat(ExposureStrategy.shadowRecoveryGain(-1f)).isWithin(1e-4f).of(2f)
        assertThat(ExposureStrategy.shadowRecoveryGain(-2f)).isWithin(1e-4f).of(4f)
        assertThat(ExposureStrategy.shadowRecoveryGain(0f)).isWithin(1e-4f).of(1f)
    }

    // ------------------------------------------------------------------
    // Reading the scene.
    // ------------------------------------------------------------------

    @Test
    fun `an ordinary scene is left alone`() {
        // Most photographs need no protection, and pulling them down would
        // just add noise for nothing.
        val ordinary = ExposureStrategy.analyse(histogram(peakBin = 20))

        assertThat(ordinary.clippedFraction).isLessThan(0.01f)
        assertThat(ExposureStrategy.recommendedPullStops(ordinary, frameCount = 8))
            .isEqualTo(0f)
    }

    @Test
    fun `a blown sky is pulled down`() {
        val blown = ExposureStrategy.analyse(histogram(clippedFraction = 0.15f, peakBin = 45))

        val pull = ExposureStrategy.recommendedPullStops(blown, frameCount = 8)

        println("blown sky: clipped %.3f, pull %.2f stops".format(blown.clippedFraction, pull))
        assertThat(pull).isLessThan(-0.5f)
    }

    @Test
    fun `the pull is never more than the burst can pay back`() {
        // Trading a blown highlight for a noisy shadow is not obviously a
        // better picture, so the strategy refuses to spend what it cannot earn.
        val terrible = ExposureStrategy.analyse(histogram(clippedFraction = 0.6f, peakBin = 60))

        for (frames in listOf(2, 4, 8, 16, 32)) {
            val pull = ExposureStrategy.recommendedPullStops(terrible, frames)
            assertThat(-pull).isAtMost(ExposureStrategy.shadowRecoveryStops(frames) + 1e-3f)
        }
    }

    @Test
    fun `a few specular highlights are allowed to clip`() {
        // A glint off chrome or a visible light source carries no detail worth
        // protecting, and darkening the whole picture for it would be wrong.
        val speculars = ExposureStrategy.analyse(histogram(clippedFraction = 0.002f, peakBin = 20))

        assertThat(ExposureStrategy.recommendedPullStops(speculars, frameCount = 16))
            .isEqualTo(0f)
    }

    @Test
    fun `dynamic range is measured in stops`() {
        val wide = ExposureStrategy.analyse(
            IntArray(64).also { it[2] = 500; it[60] = 500 }
        )
        val narrow = ExposureStrategy.analyse(
            IntArray(64).also { it[30] = 500; it[34] = 500 }
        )

        assertThat(wide.dynamicRangeStops).isGreaterThan(narrow.dynamicRangeStops)
        println("dynamic range: wide %.1f stops, narrow %.1f"
            .format(wide.dynamicRangeStops, narrow.dynamicRangeStops))
    }

    @Test
    fun `an empty or black histogram does not produce nonsense`() {
        val empty = ExposureStrategy.analyse(IntArray(0))
        val black = ExposureStrategy.analyse(IntArray(64).also { it[0] = 1000 })

        assertThat(empty.dynamicRangeStops).isEqualTo(0f)
        assertThat(black.dynamicRangeStops).isAtLeast(0f)
        assertThat(ExposureStrategy.recommendedPullStops(empty, 8)).isEqualTo(0f)
        assertThat(ExposureStrategy.recommendedPullStops(black, 8)).isEqualTo(0f)
    }

    // ------------------------------------------------------------------
    // Talking to the camera.
    // ------------------------------------------------------------------

    @Test
    fun `the decision converts to an exposure compensation index`() {
        val blown = ExposureStrategy.analyse(histogram(clippedFraction = 0.2f, peakBin = 50))

        val index = ExposureStrategy.recommendedEvIndex(blown, frameCount = 16, caps)

        // Sixth-of-a-stop steps, so a pull of about two stops is around -12.
        assertThat(index).isLessThan(0)
        assertThat(index).isAtLeast(caps.evMin)
        println("EV index for a blown scene at 16 frames: $index")
    }

    @Test
    fun `a camera without exposure compensation is not asked for it`() {
        val none = caps.copy(evMin = 0, evMax = 0, evStep = 0f)
        val blown = ExposureStrategy.analyse(histogram(clippedFraction = 0.3f))

        assertThat(ExposureStrategy.recommendedEvIndex(blown, 16, none)).isEqualTo(0)
    }

    @Test
    fun `the index stays inside what the device offers`() {
        val narrow = caps.copy(evMin = -3, evMax = 3, evStep = 1f)
        val terrible = ExposureStrategy.analyse(histogram(clippedFraction = 0.7f, peakBin = 62))

        val index = ExposureStrategy.recommendedEvIndex(terrible, 32, narrow)

        assertThat(index).isAtLeast(-3)
        assertThat(index).isAtMost(3)
    }

    // ------------------------------------------------------------------

    @Test
    fun `a histogram can be read straight from a raw frame`() {
        val sensor = SensorProfile.DEFAULT
        val dark = BayerFrame(64, 48, ShortArray(64 * 48) { 100.toShort() })
        // At the sensor's white level, so it is genuinely clipped rather than
        // merely bright: 1000 of 1023 lands a bin short of the top.
        val bright = BayerFrame(64, 48, ShortArray(64 * 48) { 1023.toShort() })

        val darkAnalysis = ExposureStrategy.analyse(
            ExposureStrategy.histogramOf(dark, sensor)
        )
        val brightAnalysis = ExposureStrategy.analyse(
            ExposureStrategy.histogramOf(bright, sensor)
        )

        assertThat(brightAnalysis.highlightLevel).isGreaterThan(darkAnalysis.highlightLevel)
        assertThat(brightAnalysis.clippedFraction).isGreaterThan(0.5f)
    }
}
