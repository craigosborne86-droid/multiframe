package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two decisions the profiler makes about each rung of its ladder: how long
 * to expose it for, and whether the frame that came back is the one that was
 * asked for.
 *
 * Both were found on the phone rather than here, and both produced a
 * measurement that looked like an answer. They are pinned now.
 */
class DcgProfilerTest {

    private fun caps(
        exposureMinNs: Long? = 1_000L,
        exposureMaxNs: Long? = 16_000_000_000L,
    ) = CameraCapabilities(
        hasManualSensor = true,
        isoMin = 24,
        isoMax = 6023,
        exposureMinNs = exposureMinNs,
        exposureMaxNs = exposureMaxNs,
        minFocusDiopters = 9f,
        awbModes = emptyList(),
        afModes = emptyList(),
        noiseReductionModes = emptyList(),
        edgeModes = emptyList(),
        evMin = -24,
        evMax = 24,
        evStep = 1f / 6f,
        sensorOrientation = 90,
        supportsRaw = true,
        supportedOutputFormats = setOf(32),
    )

    // --- exposure ----------------------------------------------------------

    /**
     * The whole point of the trade: every rung is the same brightness, so
     * analog gain is the only thing that varies across the ladder.
     */
    @Test
    fun theExposureProductIsHeldConstantAcrossTheLadder() {
        val reference = 376L * 33_333_333L
        val c = caps()

        for (iso in listOf(376, 752, 1504, 3008, 6016)) {
            val exposure = DcgProfiler.exposureFor(reference, iso, c)!!
            // Integer division loses at most one nanosecond per unit of ISO.
            assertThat(iso * exposure).isWithin(iso.toLong()).of(reference)
        }
    }

    @Test
    fun aHigherSensitivityGetsAShorterExposure() {
        val reference = 376L * 33_333_333L
        val c = caps()
        val low = DcgProfiler.exposureFor(reference, 376, c)!!
        val high = DcgProfiler.exposureFor(reference, 6023, c)!!
        assertThat(high).isLessThan(low)
    }

    /**
     * A rung whose exposure the sensor cannot deliver is refused, not clamped.
     *
     * Clamping would keep the rung at a different brightness from every other
     * one, which is precisely the confound the constant product exists to
     * remove -- a rung two stops darker than its neighbours reads as a break in
     * the noise curve that is not there.
     */
    @Test
    fun aRungTheSensorCannotExposeIsDroppedRatherThanClamped() {
        val c = caps(exposureMinNs = 1_000_000L, exposureMaxNs = 100_000_000L)
        val reference = 376L * 33_333_333L

        // Needs 1/6 of a second at low ISO -- inside the ceiling.
        assertThat(DcgProfiler.exposureFor(reference, 200, c)).isNotNull()
        // Would need well over the ceiling.
        assertThat(DcgProfiler.exposureFor(reference, 50, c)).isNull()
        // Would need under the floor.
        assertThat(DcgProfiler.exposureFor(reference, 100_000, c)).isNull()
    }

    @Test
    fun nonsensicalInputsProduceNoExposure() {
        val c = caps()
        assertThat(DcgProfiler.exposureFor(1000L, 0, c)).isNull()
        assertThat(DcgProfiler.exposureFor(1000L, -100, c)).isNull()
        assertThat(DcgProfiler.exposureFor(0L, 400, c)).isNull()
        assertThat(DcgProfiler.exposureFor(-5L, 400, c)).isNull()
    }

    // --- sensitivity verification -----------------------------------------

    /**
     * A sensor rounds a sensitivity request to its own steps.
     *
     * Asked for ISO 316 this phone delivers 315 and reports it honestly. Exact
     * equality rejected every rung of the ladder, and the run that followed
     * collected zero samples.
     */
    @Test
    fun aSensorRoundingItsOwnStepsStillCounts() {
        assertThat(DcgProfiler.isoMatches(reported = 315, requested = 316)).isTrue()
        assertThat(DcgProfiler.isoMatches(reported = 316, requested = 316)).isTrue()
        assertThat(DcgProfiler.isoMatches(reported = 6000, requested = 6023)).isTrue()
    }

    /**
     * But a frame from a neighbouring rung does not.
     *
     * The ladder is geometric over about four stops, so neighbours differ by
     * tens of percent. The tolerance has to be far tighter than that or a frame
     * left over from the previous rung passes as this one -- which is the
     * contamination the verification was added to catch.
     */
    @Test
    fun aFrameFromTheWrongRungIsRejected() {
        assertThat(DcgProfiler.isoMatches(reported = 376, requested = 452)).isFalse()
        assertThat(DcgProfiler.isoMatches(reported = 452, requested = 376)).isFalse()
        // The case actually seen: auto-exposure frames still in the ring while
        // the ladder had moved on.
        assertThat(DcgProfiler.isoMatches(reported = 376, requested = 24)).isFalse()
    }

    @Test
    fun anImpossibleRequestMatchesNothing() {
        assertThat(DcgProfiler.isoMatches(reported = 100, requested = 0)).isFalse()
        assertThat(DcgProfiler.isoMatches(reported = 100, requested = -1)).isFalse()
    }

    /**
     * The tolerance is narrower than the gap between rungs, stated as a
     * property rather than as examples.
     */
    @Test
    fun theToleranceCannotSpanTwoRungs() {
        val ladder = DcgCalibration.isoLadder(376, 6023, 16)
        for (i in 1 until ladder.size) {
            assertThat(DcgProfiler.isoMatches(ladder[i - 1], ladder[i])).isFalse()
            assertThat(DcgProfiler.isoMatches(ladder[i], ladder[i - 1])).isFalse()
        }
    }
}
