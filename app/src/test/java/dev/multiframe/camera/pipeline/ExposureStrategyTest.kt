package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    fun `an ordinary scene carries the standing bias and nothing more`() {
        // A stop in hand before any clipping is seen: the sky the shutter
        // would take is the sky the guard has to protect, and once the
        // histogram shows it clipping it is already gone. The clipping guard
        // alone -- bias zero -- still leaves an ordinary scene as it is.
        val ordinary = ExposureStrategy.analyse(histogram(peakBin = 20))

        assertThat(ordinary.clippedFraction).isLessThan(0.01f)
        assertThat(ExposureStrategy.recommendedPullStops(ordinary, frameCount = 8))
            .isWithin(1e-6f).of(-ExposureStrategy.HIGHLIGHT_BIAS_STOPS)
        assertThat(ExposureStrategy.recommendedPullStops(ordinary, frameCount = 8, bias = 0f))
            .isEqualTo(0f)
        assertThat(ExposureStrategy.HIGHLIGHT_BIAS_STOPS).isEqualTo(1.0f)
    }

    @Test
    fun `the bias is paid for by the burst, and a dark scene is not biased`() {
        val ordinary = ExposureStrategy.analyse(histogram(peakBin = 20))
        // One frame affords nothing; two afford half a stop; eight the whole stop.
        assertThat(ExposureStrategy.recommendedPullStops(ordinary, frameCount = 1)).isEqualTo(0f)
        assertThat(ExposureStrategy.recommendedPullStops(ordinary, frameCount = 2)).isWithin(1e-6f).of(-0.5f)
        assertThat(ExposureStrategy.recommendedPullStops(ordinary, frameCount = 8)).isWithin(1e-6f).of(-1f)
        // A blown sky is pulled past the bias, to what eight frames afford.
        val blown = ExposureStrategy.analyse(histogram(clippedFraction = 0.15f, peakBin = 45))
        assertThat(ExposureStrategy.recommendedPullStops(blown, frameCount = 8)).isWithin(1e-6f).of(-1.5f)
        // Sixteen frames afford two stops, and a blown scene takes them.
        assertThat(ExposureStrategy.recommendedPullStops(blown, frameCount = 16)).isWithin(1e-6f).of(-2f)
        // A dark scene -- its bright end under a tenth of white -- has no
        // highlights to hold, and is left to gather its light.
        val dark = ExposureStrategy.analyse(IntArray(64).also { it[1] = 900; it[3] = 100 })
        assertThat(dark.highlightLevel).isLessThan(ExposureStrategy.BIAS_FLOOR_LEVEL)
        assertThat(ExposureStrategy.recommendedPullStops(dark, frameCount = 8)).isEqualTo(0f)
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

        assertThat(ExposureStrategy.recommendedPullStops(speculars, frameCount = 16, bias = 0f))
            .isEqualTo(0f)
        // With the standing bias, the bias and no more.
        assertThat(ExposureStrategy.recommendedPullStops(speculars, frameCount = 16))
            .isWithin(1e-6f).of(-ExposureStrategy.HIGHLIGHT_BIAS_STOPS)
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

    @Test
    fun `the histogram reads green sites on every colour filter arrangement`() {
        // Green at 900, red and blue at 100: a histogram of green sites is
        // all in the top bins, whatever phase the pattern puts green at. A
        // version sampled every fourth column from zero and tested the
        // phase, which on RGGB and BGGR -- green at (0, 1) and (1, 0) --
        // never landed on a green site and read an empty scene.
        val w = 64
        val h = 48
        for ((name, cfa) in listOf(
            "RGGB" to intArrayOf(0, 1, 1, 2), "GRBG" to intArrayOf(1, 0, 2, 1),
            "GBRG" to intArrayOf(1, 2, 0, 1), "BGGR" to intArrayOf(2, 1, 1, 0),
        )) {
            val sensor = SensorProfile(intArrayOf(64, 64, 64, 64), 1023, cfa)
            val data = ShortArray(w * h) { i ->
                val x = i % w
                val y = i / w
                if (cfa[(y and 1) * 2 + (x and 1)] == 1) 900 else 100
            }
            val histogram = ExposureStrategy.histogramOf(BayerFrame(w, h, data), sensor)
            val total = histogram.sum()
            assertWithMessage(name).that(total).isGreaterThan(0)
            // Every sample is a green site: nothing in the bottom half.
            assertWithMessage(name).that(histogram.take(32).sum()).isEqualTo(0)
            // And the stride visits the same number of sites on each pattern.
            assertWithMessage(name).that(total).isEqualTo((w / 4) * (h / 4))
        }
    }

    // --- The guard with a memory ---

    private fun scene(highlight: Float, clipped: Float = 0f) = SceneAnalysis(clipped, highlight, highlight / 8f, 3f)

    /** The meter's response to a pull: the frame the guard sees next is the scene darkened by it. */
    private fun asSeen(sceneHighlight: Float, guard: ExposureStrategy.Guard, clipped: Float = 0f) =
        scene((sceneHighlight * Math.pow(2.0, guard.pullStops.toDouble()).toFloat()).coerceAtMost(1f), clipped)

    @Test
    fun `a dim room with a window does not switch the bias on and off`() {
        // The eighth field test: bright end at 0.15 -- over the floor -- so
        // the bias went on; the frame then read 0.075, under it, so the
        // bias came off, and so on every 700 ms. The scene is the same
        // scene, and the decision has to be the same decision.
        val sceneHighlight = 0.15f
        var guard = ExposureStrategy.Guard.REST
        val decisions = ArrayList<Float>()
        repeat(12) {
            guard = ExposureStrategy.nextGuard(asSeen(sceneHighlight, guard), frameCount = 10, current = guard)
            decisions.add(guard.pullStops)
        }
        assertThat(decisions.first()).isWithin(1e-6f).of(-1f)
        assertThat(decisions.distinct()).containsExactly(-1f)
    }

    @Test
    fun `the bias comes off only when the scene itself has gone dark, with hysteresis`() {
        var guard = ExposureStrategy.nextGuard(scene(0.5f), 10)
        assertThat(guard.standing).isTrue()
        // The scene's bright end falls to 0.08: under the floor but not under
        // the release fraction of it. The bias stays.
        guard = ExposureStrategy.nextGuard(asSeen(0.08f, guard), 10, guard)
        assertThat(guard.standing).isTrue()
        // To 0.05: released. And a frame at 0.05 with no pull is under the
        // floor, so it stays released.
        guard = ExposureStrategy.nextGuard(asSeen(0.05f, guard), 10, guard)
        assertThat(guard.standing).isFalse()
        guard = ExposureStrategy.nextGuard(asSeen(0.05f, guard), 10, guard)
        assertThat(guard.standing).isFalse()
        assertThat(guard.pullStops).isEqualTo(0f)
    }

    @Test
    fun `from rest a clipped scene is pulled as the stateless rule says, then held rather than chased`() {
        val blown = scene(1f, clipped = 0.05f)
        val guard = ExposureStrategy.nextGuard(blown, 16)
        assertThat(guard.pullStops).isWithin(1e-5f).of(
            ExposureStrategy.recommendedPullStops(blown, 16),
        )
        // The pull in force; the next frame has stopped clipping but its
        // bright end sits at the safety line. Nothing changes.
        val settled = scene(0.9f)
        val held = ExposureStrategy.nextGuard(settled, 16, guard)
        assertThat(held).isEqualTo(guard)
        // With half a stop of headroom under the line the risk relaxes a
        // step, and no further while the frame stays there.
        val roomy = scene(0.6f)
        val relaxed = ExposureStrategy.nextGuard(roomy, 16, held)
        assertThat(relaxed.riskStops).isWithin(1e-5f).of((held.riskStops - ExposureStrategy.RISK_RELAX_STEP).coerceAtLeast(0f))
        assertThat(relaxed.standing).isTrue()
    }

    @Test
    fun `the risk grows while clipping survives the pull and never past what the burst affords`() {
        var guard = ExposureStrategy.nextGuard(scene(1f, clipped = 0.2f), 4)
        repeat(6) { guard = ExposureStrategy.nextGuard(scene(1f, clipped = 0.2f), 4, guard) }
        assertThat(-guard.pullStops).isWithin(1e-5f).of(ExposureStrategy.shadowRecoveryStops(4))
        // A single frame affords nothing, and the guard asks for nothing.
        assertThat(ExposureStrategy.nextGuard(scene(1f, clipped = 0.2f), 1, guard).pullStops).isEqualTo(0f)
    }

    @Test
    fun `a lamp in the frame does not walk the guard to the burst's whole allowance`() {
        // A light source clips whatever the pull, so the bright end reads
        // 0.98 tick after tick. The pull it implies is the same each time,
        // and that is where the guard stays.
        val lamp = scene(0.98f, clipped = 0.004f)
        var guard = ExposureStrategy.nextGuard(lamp, 28)
        // The second tick reads the lamp as clipped under the first pull --
        // which it is -- and settles; every tick after that is the same.
        guard = ExposureStrategy.nextGuard(lamp, 28, guard)
        val settled = guard.pullStops
        repeat(20) { guard = ExposureStrategy.nextGuard(lamp, 28, guard) }
        assertThat(guard.pullStops).isWithin(1e-5f).of(settled)
        assertThat(-settled).isLessThan(1.5f)
        assertThat(-settled).isGreaterThan(1f)
    }

    @Test
    fun `a clipped frame reads as clipped whatever the pull in force`() {
        // The bright end cannot be read back above one: a frame clipped
        // under a two-stop pull is a scene at least four times over, and the
        // bias stays on however the arithmetic would have it.
        val deep = ExposureStrategy.Guard(standing = true, riskStops = 1f)
        val next = ExposureStrategy.nextGuard(scene(1f, clipped = 0.01f), 16, deep)
        assertThat(next.standing).isTrue()
        assertThat(next.riskStops).isAtLeast(deep.riskStops)
    }

    @Test
    fun `the EV index quantises the pull to the camera's steps`() {
        assertThat(ExposureStrategy.evIndexFor(-1f, caps)).isEqualTo(-6)
        assertThat(ExposureStrategy.evIndexFor(-1.6f, caps)).isEqualTo(-9)
        assertThat(ExposureStrategy.evIndexFor(0f, caps)).isEqualTo(0)
        val none = caps.copy(evMin = 0, evMax = 0, evStep = 0f)
        assertThat(ExposureStrategy.evIndexFor(-1f, none)).isEqualTo(0)
    }
}
