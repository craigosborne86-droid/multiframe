package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.ln
import kotlin.math.exp
import kotlin.random.Random

/**
 * The fit that decides whether a sensor has an analog gain switch.
 *
 * Every case here is a curve with a known answer, which is the point of keeping
 * the arithmetic separate from the camera: a sensor that does have a switch and
 * one that does not are both a few lines to construct here and a trip to a lab
 * to obtain otherwise.
 *
 * The bar the tests hold it to is asymmetric on purpose. Claiming a switch that
 * is not there builds the bracket around a discontinuity the sensor does not
 * have; failing to find one that is there falls back to the software default
 * and loses nothing that was previously working. So the false-positive cases
 * outnumber the false-negative ones.
 */
class DcgCalibrationTest {

    /**
     * A sensor with no gain switch: one straight line in log-log.
     *
     * `var = g^2 * read^2 + g * shot`, which for a fixed read noise is a single
     * smooth curve however far the ISO travels.
     */
    private fun unbrokenCurve(
        isos: List<Int>,
        readNoise: Double = 2.0,
        shot: Double = 40.0,
        seed: Int = 1,
    ): List<DcgCalibration.Sample> {
        val rnd = Random(seed)
        return isos.map { iso ->
            val g = iso / 100.0
            val variance = g * g * readNoise * readNoise + g * shot
            // A percent of measurement scatter, which is generous: the real
            // thing averages 65536 pixels and is far steadier than this.
            DcgCalibration.Sample(iso, variance * (1.0 + rnd.nextDouble(-0.01, 0.01)))
        }
    }

    /**
     * A sensor that switches at [switchIso]: read noise drops there, so the
     * curve has a step down and continues on a lower line.
     */
    private fun brokenCurve(
        isos: List<Int>,
        switchIso: Int,
        lowGainRead: Double = 6.0,
        highGainRead: Double = 1.5,
        shot: Double = 40.0,
        seed: Int = 1,
    ): List<DcgCalibration.Sample> {
        val rnd = Random(seed)
        return isos.map { iso ->
            val g = iso / 100.0
            val read = if (iso < switchIso) highGainRead else lowGainRead
            val variance = g * g * read * read + g * shot
            DcgCalibration.Sample(iso, variance * (1.0 + rnd.nextDouble(-0.01, 0.01)))
        }
    }

    @Test
    fun theLadderIsLogarithmicSoTheBottomOfTheRangeIsNotThreePoints() {
        val ladder = DcgCalibration.isoLadder(50, 12800, 16)
        assertThat(ladder).isNotEmpty()
        assertThat(ladder.first()).isEqualTo(50)
        assertThat(ladder.last()).isAtMost(12800)

        // Half the samples below the geometric middle is what logarithmic
        // spacing means, and is the property linear spacing lacks: it would put
        // one sample below ISO 800 and fifteen above.
        val middle = exp((ln(50.0) + ln(12800.0)) / 2).toInt()
        val below = ladder.count { it < middle }
        assertThat(below).isAtLeast(ladder.size / 2 - 1)
        assertThat(below).isAtMost(ladder.size / 2 + 1)
    }

    @Test
    fun theLadderRefusesRangesItCannotDescribe() {
        assertThat(DcgCalibration.isoLadder(0, 3200, 16)).isEmpty()
        assertThat(DcgCalibration.isoLadder(100, 100, 16)).isEmpty()
        assertThat(DcgCalibration.isoLadder(100, 50, 16)).isEmpty()
        assertThat(DcgCalibration.isoLadder(100, 3200, 1)).isEmpty()
    }

    @Test
    fun anUnbrokenCurveReportsNoThreshold() {
        val isos = DcgCalibration.isoLadder(50, 12800, 16)
        val result = DcgCalibration.fit(unbrokenCurve(isos))

        assertThat(result.foundThreshold).isFalse()
        assertThat(result.thresholdIso).isNull()
        assertThat(result.fStatistic).isLessThan(DcgCalibration.F_THRESHOLD)
    }

    /**
     * The one that matters. A real gain switch has to be found, and found in
     * roughly the right place.
     */
    @Test
    fun aGainSwitchIsFoundNearWhereItWasPut() {
        val isos = DcgCalibration.isoLadder(50, 12800, 16)
        val switch = isos[9]
        val result = DcgCalibration.fit(brokenCurve(isos, switchIso = switch))

        assertThat(result.foundThreshold).isTrue()
        assertThat(result.fStatistic).isGreaterThan(DcgCalibration.F_THRESHOLD)

        // The break is located to within one rung. It cannot be exact: the
        // switch happens between two samples and the fit can only name one of
        // them.
        val found = isos.indexOf(result.thresholdIso!!)
        assertThat(found).isAtLeast(8)
        assertThat(found).isAtMost(10)
    }

    @Test
    fun theBrokenFitAlwaysExplainsMoreThanTheSingleLine() {
        val isos = DcgCalibration.isoLadder(50, 12800, 16)
        for (samples in listOf(unbrokenCurve(isos), brokenCurve(isos, isos[9]))) {
            val result = DcgCalibration.fit(samples)
            // Two extra parameters cannot fit worse. The question the F test
            // asks is whether they fit *enough* better to be worth having, and
            // this pins the arithmetic underneath that question.
            assertThat(result.brokenLineRss).isAtMost(result.singleLineRss)
        }
    }

    /**
     * The same curves at the magnitudes a real sensor produces.
     *
     * The synthetic cases above run to variances in the tens of thousands,
     * which is what you get from a normalised model. A 10-bit sensor measured
     * in raw counts squared reaches millions, and on the phone this reported
     * `F=0.0` for every camera -- not "no switch found" but the fit collapsing
     * and saying so as though it were an answer.
     *
     * The cause was conditioning: with weights of 1/v^2 and v in the millions,
     * the weighted moments underflow and the 3x3 determinant falls under the
     * guard, so every segment returned a residual of zero and the F statistic
     * was 0/0. Held here at the magnitude the device actually delivers.
     */
    @Test
    fun theFitSurvivesTheMagnitudesARealSensorProduces() {
        val isos = DcgCalibration.isoLadder(24, 6023, 16)

        // Raw counts squared, as a 10-bit sensor at base gain: a read noise of
        // about 40 counts and shot noise to match, so the curve runs from
        // thousands at base to millions at the top of the range.
        val unbroken = isos.map { iso ->
            val g = iso / 24.0
            DcgCalibration.Sample(iso, g * g * 1600.0 + g * 90_000.0)
        }
        val unbrokenResult = DcgCalibration.fit(unbroken)
        assertThat(unbrokenResult.foundThreshold).isFalse()
        // The finding is that the *fit ran*. A collapsed fit also reports no
        // threshold, which is why the statistic is asserted rather than only
        // the conclusion.
        assertThat(unbrokenResult.singleLineRss).isGreaterThan(0.0)

        val switch = isos[9]
        val broken = isos.map { iso ->
            val g = iso / 24.0
            val read = if (iso < switch) 900.0 else 14_400.0
            DcgCalibration.Sample(iso, g * g * read + g * 90_000.0)
        }
        val brokenResult = DcgCalibration.fit(broken)
        assertThat(brokenResult.fStatistic).isGreaterThan(DcgCalibration.F_THRESHOLD)
        assertThat(brokenResult.foundThreshold).isTrue()
    }

    @Test
    fun tooFewSamplesIsNotFittedAtAll() {
        val samples = (1..DcgCalibration.MIN_SAMPLES - 1).map {
            DcgCalibration.Sample(100 * it, 100.0 * it)
        }
        val result = DcgCalibration.fit(samples)
        assertThat(result.foundThreshold).isFalse()
        assertThat(result.sampleCount).isLessThan(DcgCalibration.MIN_SAMPLES)
    }

    /**
     * Nonsense in the input is discarded rather than fitted. A zero variance is
     * a failed measurement, and its logarithm would take the whole fit with it.
     */
    @Test
    fun unusableSamplesAreDropped() {
        val isos = DcgCalibration.isoLadder(50, 12800, 16)
        val poisoned = unbrokenCurve(isos) +
            DcgCalibration.Sample(0, 100.0) +
            DcgCalibration.Sample(400, 0.0) +
            DcgCalibration.Sample(-100, -5.0)

        val result = DcgCalibration.fit(poisoned)
        assertThat(result.sampleCount).isEqualTo(isos.size)
        assertThat(result.foundThreshold).isFalse()
    }

    @Test
    fun aRepeatedIsoIsCountedOnce() {
        val isos = DcgCalibration.isoLadder(50, 12800, 16)
        val doubled = unbrokenCurve(isos) + unbrokenCurve(isos, seed = 2)
        assertThat(DcgCalibration.fit(doubled).sampleCount).isEqualTo(isos.size)
    }

    /**
     * Scatter alone must not manufacture a switch.
     *
     * This is the false positive the F threshold exists to prevent, and it is
     * the failure mode with a real cost: a bracket built around a
     * discontinuity that is not there.
     */
    @Test
    fun noisyMeasurementsOfAnUnbrokenSensorDoNotInventASwitch() {
        val isos = DcgCalibration.isoLadder(50, 12800, 20)
        var falsePositives = 0
        for (seed in 1..40) {
            val rnd = Random(seed)
            val samples = isos.map { iso ->
                val g = iso / 100.0
                val variance = g * g * 4.0 + g * 40.0
                // Five percent scatter, several times worse than the real
                // measurement, and still no break.
                DcgCalibration.Sample(
                    iso, variance * (1.0 + rnd.nextDouble(-0.05, 0.05)),
                )
            }
            if (DcgCalibration.fit(samples).foundThreshold) falsePositives++
        }
        assertThat(falsePositives).isAtMost(2)
    }

    // --- temporal variance -------------------------------------------------

    /**
     * The measurement that makes the whole thing non-invasive: the scene
     * cancels, so no lens cap is needed.
     */
    @Test
    fun anIdenticalPairHasNoNoise() {
        val frame = ShortArray(4096) { (500 + it % 37).toShort() }
        assertThat(DcgCalibration.temporalVariance(frame, frame)).isEqualTo(0.0)
    }

    @Test
    fun theSceneCancelsAndOnlyTheNoiseIsMeasured() {
        val rnd = Random(7)
        val n = 65536
        val sigma = 12.0

        // A scene with enormous structure -- a gradient running the full width
        // of the range -- and the same small noise on both frames. If the scene
        // were not cancelling, its variance would dominate by orders of
        // magnitude.
        val a = ShortArray(n)
        val b = ShortArray(n)
        for (i in 0 until n) {
            val scene = 200.0 + (i % 256) * 3.0
            a[i] = (scene + gaussian(rnd) * sigma).toInt().coerceIn(0, 65535).toShort()
            b[i] = (scene + gaussian(rnd) * sigma).toInt().coerceIn(0, 65535).toShort()
        }

        val measured = DcgCalibration.temporalVariance(a, b)
        // Within 10% of sigma squared, which is what a sample of this size
        // supports.
        assertThat(measured).isGreaterThan(sigma * sigma * 0.9)
        assertThat(measured).isLessThan(sigma * sigma * 1.1)
    }

    /**
     * A brightness shift between the two frames is not noise.
     *
     * Auto-exposure drifting a little between one frame and the next moves
     * every pixel together. Counted as noise it would swamp the read noise
     * being measured, which is why the mean of the difference is subtracted
     * rather than assumed to be zero.
     */
    @Test
    fun aUniformExposureDriftIsNotCountedAsNoise() {
        val rnd = Random(3)
        val n = 65536
        val sigma = 8.0
        val drift = 40.0

        val a = ShortArray(n)
        val b = ShortArray(n)
        for (i in 0 until n) {
            val scene = 600.0
            a[i] = (scene + gaussian(rnd) * sigma).toInt().toShort()
            b[i] = (scene + drift + gaussian(rnd) * sigma).toInt().toShort()
        }

        val measured = DcgCalibration.temporalVariance(a, b)
        // The drift is five times the noise standard deviation. Without the
        // mean subtraction this would read about 800 instead of about 64.
        assertThat(measured).isLessThan(sigma * sigma * 1.2)
    }

    @Test
    fun mismatchedFramesAreRefusedRatherThanTruncated() {
        val a = ShortArray(100)
        val b = ShortArray(64)
        try {
            DcgCalibration.temporalVariance(a, b)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("differ in size")
        }
    }

    @Test
    fun anEmptyPairIsZeroRatherThanADivisionByZero() {
        assertThat(DcgCalibration.temporalVariance(ShortArray(0), ShortArray(0)))
            .isEqualTo(0.0)
    }

    /** Box-Muller, so the test's noise is actually Gaussian. */
    private fun gaussian(rnd: Random): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
