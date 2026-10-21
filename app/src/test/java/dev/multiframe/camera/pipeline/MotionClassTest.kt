package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MotionClassTest {
    // A 12 MP main lens: about 3000 pixels per radian.
    private val ppr = 3000f
    private val rateHz = 400

    /**
     * A gyroscope's own noise per axis per sample: a datasheet density of
     * 0.005 degree/s/sqrt(Hz) over the 200 Hz a 400 Hz stream carries.
     */
    private val sensorNoise = 0.0012

    /**
     * A synthetic gyroscope at [rateHz]: a 10 Hz tremor about x whose RMS is
     * [rms], plus deterministic pseudo-white noise of [noise] per axis.
     */
    private fun trace(startNs: Long, endNs: Long, rms: (Long) -> Double, noise: Double = sensorNoise): GyroTrace {
        val step = 1_000_000_000L / rateHz
        val n = ((endNs - startNs) / step).toInt()
        val rng = java.util.Random(7)
        val t = LongArray(n) { startNs + it * step }
        val x = FloatArray(n) { (rms(t[it]) * sqrt(2.0) * sin(2 * PI * 10 * t[it] / 1e9) + noise * rng.nextGaussian()).toFloat() }
        val y = FloatArray(n) { (noise * rng.nextGaussian()).toFloat() }
        val z = FloatArray(n) { (noise * rng.nextGaussian()).toFloat() }
        return GyroTrace(t, x, y, z)
    }

    /** Polls a classifier every 100 ms over a trace, returning the class at each poll. */
    private fun run(c: MotionClassifier, endNs: Long, rms: (Long) -> Double, noise: Double = sensorNoise): List<MotionClass> {
        val tr = trace(0L, endNs, rms, noise)
        return (1..(endNs / 100_000_000L)).map { k ->
            val now = k * 100_000_000L
            c.update(MotionMeter.rmsRate(tr, now), now)
        }
    }

    @Test fun thresholdsAreTheBlurBudgetAtEachClassesShortestExposure() {
        val c = MotionClassifier(ppr)
        assertThat(StabilityGate.blurPixels(c.bracedRate, MotionClass.BRACED.minExposureNs, ppr))
            .isWithin(1e-4f).of(MotionClassifier.BLUR_BUDGET_PX)
        assertThat(StabilityGate.blurPixels(c.tripodRate, MotionClass.TRIPOD.minExposureNs, ppr))
            .isWithin(1e-4f).of(MotionClassifier.BLUR_BUDGET_PX)
        // A longer lens is less forgiving of the same hand.
        assertThat(MotionClassifier(5 * ppr).bracedRate).isWithin(1e-7f).of(c.bracedRate / 5)
    }

    @Test fun rmsOfATremorIsMeasuredAndTheNoiseFloorRemoved() {
        val tr = trace(0L, 600_000_000L, { 0.01 })
        // The 20 ms bin passes a 10 Hz tremor at 94%.
        assertThat(MotionMeter.rmsRate(tr, 600_000_000L)).isWithin(0.001f).of(0.01f)
        // The gyroscope's own noise does not read as a hand.
        val still = trace(0L, 600_000_000L, { 0.0 })
        assertThat(MotionMeter.rmsRate(still, 600_000_000L)).isLessThan(MotionClassifier(ppr).tripodRate)
    }

    @Test fun tooFewSamplesSayNothing() {
        val tr = trace(0L, 100_000_000L, { 0.0 })
        assertThat(MotionMeter.rmsRate(tr, 100_000_000L)).isNaN()
        val c = MotionClassifier(ppr)
        assertThat(c.update(Float.NaN, 0L)).isEqualTo(MotionClass.HANDHELD)
    }

    @Test fun aStillPhoneRisesToTripodOnlyAfterTheDwell() {
        val c = MotionClassifier(ppr)
        val classes = run(c, 1_500_000_000L, { 0.0 })
        // The window needs 200 ms of bins; the dwell another 300 ms.
        assertThat(classes.take(4)).containsExactly(MotionClass.HANDHELD, MotionClass.HANDHELD,
            MotionClass.HANDHELD, MotionClass.HANDHELD).inOrder()
        assertThat(classes.last()).isEqualTo(MotionClass.TRIPOD)
    }

    // The wander tests use an ultra-wide's scale, where the braced boundary
    // (0.003 rad/s) stands well clear of the gyroscope's noise: on the main
    // lens the boundary is within a factor of two of it, and the window's
    // estimate of the rate scatters by a third poll to poll.
    private val wide = 1000f

    @Test fun aHandAtTheBracedBoundaryDoesNotFlicker() {
        val c = MotionClassifier(wide)
        val b = c.bracedRate.toDouble()
        // Settle braced, then wander ±20% around the boundary for five seconds.
        run(c, 1_000_000_000L, { 0.7 * b })
        assertThat(c.current).isEqualTo(MotionClass.BRACED)
        val tr = trace(0L, 6_000_000_000L, { t -> b * (1.0 + 0.2 * sin(2 * PI * 0.7 * t / 1e9)) })
        var changes = 0
        var last = c.current
        for (k in 10..60) {
            val now = k * 100_000_000L
            val next = c.update(MotionMeter.rmsRate(tr, now), now)
            if (next != last) changes++
            last = next
        }
        assertThat(changes).isEqualTo(0)
        assertThat(c.current).isEqualTo(MotionClass.BRACED)
    }

    @Test fun withoutHysteresisTheSameTraceWouldFlicker() {
        val c = MotionClassifier(wide)
        val b = c.bracedRate.toDouble()
        val tr = trace(0L, 6_000_000_000L, { t -> b * (1.0 + 0.2 * sin(2 * PI * 0.7 * t / 1e9)) })
        val raw = (10..60).map { c.classOf(MotionMeter.rmsRate(tr, it * 100_000_000L)) }
        assertThat(raw.zipWithNext().count { (a, b2) -> a != b2 }).isAtLeast(4)
    }

    @Test fun aJoltDropsToHandheldAtOnce() {
        val c = MotionClassifier(ppr)
        run(c, 1_500_000_000L, { 0.0 })
        assertThat(c.current).isEqualTo(MotionClass.TRIPOD)
        val jolt = trace(0L, 2_000_000_000L, { t -> if (t >= 1_500_000_000L) 0.05 else 0.0 })
        // Half the window is enough to pass the braced boundary by far.
        assertThat(c.update(MotionMeter.rmsRate(jolt, 1_700_000_000L), 1_700_000_000L)).isEqualTo(MotionClass.HANDHELD)
    }

    @Test fun unknownLensIsAlwaysHandheld() {
        val c = MotionClassifier(0f)
        assertThat(c.classOf(0f)).isEqualTo(MotionClass.HANDHELD)
    }
}
