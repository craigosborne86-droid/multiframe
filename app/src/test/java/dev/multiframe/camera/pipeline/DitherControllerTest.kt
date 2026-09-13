package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The dither schedule, held to the one rule it must not break: the motor
 * never runs while the sensor is integrating.
 *
 * Every test here is about the window. A pulse that lands inside an exposure
 * is motion blur, and the arithmetic that keeps it out has to be right in
 * every case -- including the ones where the right answer is no pulse.
 */
class DitherControllerTest {

    private val ms = 1_000_000L
    private val period30 = 33_333_333L

    /** A fast actuator: 3 ms drive, 8 ms settle. What a good LRA does. */
    private fun fast() = DitherController(pulseNs = 3 * ms, settleNs = 8 * ms)

    private val mounted = TripodDetector.State.MOUNTED

    // --- fitting -----------------------------------------------------------

    /**
     * At 30 fps with an 8 ms exposure there is 25 ms of dead time. A fast
     * actuator needs 3 + 8 + 4 (margins) = 15 ms. It fits.
     */
    @Test
    fun aShortExposureLeavesRoomForAFastActuator() {
        assertThat(fast().fits(exposureNs = 8 * ms, periodNs = period30)).isTrue()
    }

    /**
     * A 25 ms exposure at 30 fps leaves 8 ms. Nothing fits, and the answer is
     * no pulse -- not a shorter margin.
     */
    @Test
    fun aLongExposureLeavesNoRoom() {
        assertThat(fast().fits(exposureNs = 25 * ms, periodNs = period30)).isFalse()
    }

    /**
     * The Pixel 11 Pro's motor, as measured, does not fit a 30 fps frame at
     * any exposure a burst would use. Not close: the onset latency alone is
     * longer than the window. This is the measured finding, and the controller
     * built on it has to decline.
     */
    @Test
    fun theMeasuredPixel11ProMotorDeclinesEveryFrameAtThirtyFps() {
        val c = DitherController.measuredOnPixel11Pro()
        for (exposureMs in listOf(1L, 4L, 8L, 16L)) {
            assertThat(c.fits(exposureMs * ms, period30)).isFalse()
        }
        // Even a one-nanosecond exposure: 32.3 + 65 + 4 = 101.3 ms > 33.3.
        assertThat(c.fits(1L, period30)).isFalse()
    }

    /**
     * What period the measured motor *would* need. 8 + 32.3 + 65 + 4 =
     * 109.3 ms, which is a burst at nine frames a second. The PRD lists
     * lengthening the period as a fallback; this is what it would cost.
     */
    @Test
    fun theMeasuredMotorNeedsANineFpsBurst() {
        val c = DitherController.measuredOnPixel11Pro()
        val needed = c.periodNeeded(exposureNs = 8 * ms)
        assertThat(needed).isEqualTo(8 * ms + DitherController.PIXEL_11_PRO_PULSE_NS +
            DitherController.PIXEL_11_PRO_SETTLE_NS + 2 * DitherController.DEFAULT_MARGIN_NS)
        assertThat(needed / 1e6).isWithin(0.1).of(109.3)
        assertThat(c.fits(8 * ms, needed)).isTrue()
        assertThat(c.fits(8 * ms, needed - 1)).isFalse()
    }

    /** An unmeasured device gets the measured device's caution. */
    @Test
    fun anUnmeasuredDeviceIsAsCautiousAsTheMeasuredOne() {
        assertThat(DitherController.unmeasured().fits(8 * ms, period30)).isFalse()
    }

    // --- the window --------------------------------------------------------

    /** The pulse starts one margin after the exposure ends. Not before. */
    @Test
    fun thePulseStartsAfterTheExposureAndAMargin() {
        val start = 1_000_000_000L
        val exposure = 8 * ms
        val d = fast().decide(start, exposure, period30, mounted)
        assertThat(d).isInstanceOf(DitherController.Decision.Pulse::class.java)
        val p = d as DitherController.Decision.Pulse
        assertThat(p.atNs).isEqualTo(start + exposure + DitherController.DEFAULT_MARGIN_NS)
        // Strictly after the exposure ends.
        assertThat(p.atNs).isGreaterThan(start + exposure)
    }

    /**
     * The whole pulse, plus its settle, plus the trailing margin, ends before
     * the next exposure begins. This is the assertion that encodes the rule.
     */
    @Test
    fun thePulseHasSettledBeforeTheNextExposure() {
        val start = 1_000_000_000L
        val exposure = 8 * ms
        val c = fast()
        val p = c.decide(start, exposure, period30, mounted) as DitherController.Decision.Pulse

        val pulseEnd = p.atNs + 3 * ms
        val settled = pulseEnd + 8 * ms
        val nextExposure = start + period30

        assertThat(settled + DitherController.DEFAULT_MARGIN_NS).isAtMost(nextExposure)
        assertThat(p.windowEndNs).isEqualTo(nextExposure - DitherController.DEFAULT_MARGIN_NS)
    }

    /**
     * Right at the boundary: the exposure that leaves exactly enough room
     * fires, and one nanosecond longer does not.
     */
    @Test
    fun theBoundaryIsExact() {
        val c = fast()
        // period - pulse - settle - 2*margin = 33.33 - 3 - 8 - 4 = 18.33 ms
        val exact = period30 - 3 * ms - 8 * ms - 2 * DitherController.DEFAULT_MARGIN_NS
        assertThat(c.decide(0L, exact, period30, mounted))
            .isInstanceOf(DitherController.Decision.Pulse::class.java)
        assertThat(c.decide(0L, exact + 1, period30, mounted))
            .isInstanceOf(DitherController.Decision.Skip::class.java)
    }

    // --- the detector's veto -----------------------------------------------

    @Test
    fun aHandheldPhoneIsNeverDithered() {
        val d = fast().decide(0L, 8 * ms, period30, TripodDetector.State.HANDHELD)
        assertThat(d).isInstanceOf(DitherController.Decision.Skip::class.java)
        assertThat((d as DitherController.Decision.Skip).reason).contains("handheld")
    }

    /** Unknown is not mounted. A verdict on noise does not earn a pulse. */
    @Test
    fun anUnknownStateIsNotDithered() {
        val d = fast().decide(0L, 8 * ms, period30, TripodDetector.State.UNKNOWN)
        assertThat(d).isInstanceOf(DitherController.Decision.Skip::class.java)
    }

    /**
     * The mid-burst pick-up. The decision is made per frame from the state
     * *now*, so a burst that started mounted stops dithering the moment the
     * detector says otherwise -- there is no per-burst latch to unwind.
     */
    @Test
    fun theDecisionFollowsTheStateFrameByFrame() {
        val c = fast()
        val states = listOf(
            TripodDetector.State.MOUNTED,
            TripodDetector.State.MOUNTED,
            TripodDetector.State.HANDHELD,
            TripodDetector.State.HANDHELD,
        )
        val decisions = states.mapIndexed { i, s ->
            c.decide(i * period30, 8 * ms, period30, s)
        }
        assertThat(decisions[0]).isInstanceOf(DitherController.Decision.Pulse::class.java)
        assertThat(decisions[1]).isInstanceOf(DitherController.Decision.Pulse::class.java)
        assertThat(decisions[2]).isInstanceOf(DitherController.Decision.Skip::class.java)
        assertThat(decisions[3]).isInstanceOf(DitherController.Decision.Skip::class.java)
    }

    @Test
    fun missingTimingIsARefusalNotACrash() {
        val c = fast()
        assertThat(c.decide(0L, 0L, period30, mounted))
            .isInstanceOf(DitherController.Decision.Skip::class.java)
        assertThat(c.decide(0L, 8 * ms, 0L, mounted))
            .isInstanceOf(DitherController.Decision.Skip::class.java)
    }

    /** The reason for a skip names the numbers, so a field log is actionable. */
    @Test
    fun aSkipForRoomSaysHowMuchWasMissing() {
        val d = fast().decide(0L, 25 * ms, period30, mounted) as DitherController.Decision.Skip
        assertThat(d.reason).contains("no room")
        assertThat(d.reason).contains("25.0ms")
        assertThat(d.reason).contains("33.3ms")
    }
}
