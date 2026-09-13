package dev.multiframe.camera.pipeline

/**
 * When, and whether, to nudge the sensor between frames.
 *
 * ### What this is for
 *
 * On a tripod every frame of a burst samples the scene on the same grid, and
 * sub-pixel accumulation has nothing to work with. A brief pulse from the
 * vibration motor between exposures displaces the sensor by a fraction of a
 * photosite, so successive frames sample on offset grids -- the diversity a
 * hand provides for free, supplied deliberately where there is no hand.
 *
 * ### The rule this must never break
 *
 * **The motor never runs while the sensor is integrating.** That is the
 * project's no-haptics-during-capture rule read precisely: it exists because
 * vibration during an exposure is motion blur. A pulse that starts after one
 * exposure ends and has fully settled before the next begins is not during
 * capture, and is the only kind this schedules.
 *
 * ### The arithmetic
 *
 * Frame N's exposure starts at T and lasts E. The next starts at T + P, where
 * P is the frame period. The sensor is idle on [T + E, T + P). A pulse of
 * duration D that rings down over S must fit inside that with a margin M on
 * either side:
 *
 *     T + E + M  <=  pulse start
 *     pulse start + D + S + M  <=  T + P
 *
 * so it is possible only when `E + D + S + 2M <= P`. Where it is not, the
 * answer is not a shorter pulse or a smaller margin; it is no pulse. A dither
 * that has not settled is exactly the failure the rule exists to prevent.
 *
 * D and S are properties of the actuator and are **measured**, not assumed:
 * `DitherTimingDeviceTest` reads them off the accelerometer. The PRD is
 * explicit that this feature is not built on an assumed settle time, and the
 * defaults here are placeholders for a device that has not been measured,
 * chosen to be conservative enough to decline rather than blur.
 *
 * ### Pure, on purpose
 *
 * This decides. Something else vibrates. That split is what lets the timing
 * be tested without a motor, and what keeps the vibrate call in one place
 * where its precondition -- the detector says mounted, the window is open --
 * is visible.
 */
class DitherController(
    /** How long the actuator is driven, nanoseconds. Measured. */
    private val pulseNs: Long,
    /** How long after the drive ends before motion has stopped. Measured. */
    private val settleNs: Long,
    /** Safety either side of the pulse, so timing jitter does not put it in an exposure. */
    private val marginNs: Long = DEFAULT_MARGIN_NS,
) {

    /** What to do about the frame that just started. */
    sealed class Decision {
        /** Fire at [atNs] on the sensor's clock. The window has room. */
        data class Pulse(val atNs: Long, val windowEndNs: Long) : Decision()

        /** Do nothing, and this is why. */
        data class Skip(val reason: String) : Decision()
    }

    /**
     * Whether a pulse can fit at all for this exposure and period.
     *
     * Answered up front, before any frame, so the capture can be planned: a
     * long exposure on a tripod can lengthen its frame period to make room --
     * on a tripod a slower burst costs nothing -- or can decline dither and
     * shoot a plain merge.
     */
    fun fits(exposureNs: Long, periodNs: Long): Boolean =
        exposureNs + pulseNs + settleNs + 2 * marginNs <= periodNs

    /** The shortest frame period at which a pulse fits this exposure. */
    fun periodNeeded(exposureNs: Long): Long =
        exposureNs + pulseNs + settleNs + 2 * marginNs

    /**
     * Decides for a frame whose exposure began at [exposureStartNs].
     *
     * [state] is the detector's verdict *now*. It is re-read on every frame
     * rather than once per burst, because the failure the PRD names is the
     * phone being picked up mid-burst: the first window that reads as
     * handheld cancels dithering for the rest of the burst, and a pulse added
     * to a hand's motion is blur.
     */
    fun decide(
        exposureStartNs: Long,
        exposureNs: Long,
        periodNs: Long,
        state: TripodDetector.State,
    ): Decision {
        if (state != TripodDetector.State.MOUNTED) {
            return Decision.Skip(
                if (state == TripodDetector.State.UNKNOWN) "mount not yet established"
                else "handheld: the hand supplies the diversity",
            )
        }
        if (exposureNs <= 0L || periodNs <= 0L) {
            return Decision.Skip("no exposure timing")
        }
        if (!fits(exposureNs, periodNs)) {
            return Decision.Skip(
                "no room: exposure %.1fms + pulse %.1fms + settle %.1fms + margins %.1fms > period %.1fms".format(
                    exposureNs / 1e6, pulseNs / 1e6, settleNs / 1e6,
                    2 * marginNs / 1e6, periodNs / 1e6,
                ),
            )
        }
        val at = exposureStartNs + exposureNs + marginNs
        val windowEnd = exposureStartNs + periodNs - marginNs
        return Decision.Pulse(at, windowEnd)
    }

    companion object {
        /**
         * Two milliseconds either side. The sensor timestamp is accurate to
         * well under a millisecond and the vibrator's start latency is the
         * larger uncertainty; this covers both with room.
         */
        const val DEFAULT_MARGIN_NS = 2_000_000L

        /**
         * What the Pixel 11 Pro's motor actually does, read off the
         * accelerometer at 371 Hz by `DitherTimingDeviceTest`, phone resting
         * on a surface, five traces:
         *
         *     onset latency        30.3 ms median (27.9-47.2)
         *     active, amp 10       64.9 ms   peak 0.20 m/s^2
         *     active, amp 25       72.4 ms   peak 0.27 m/s^2
         *     active, amp 255     134.9 ms   peak 2.88 m/s^2
         *
         * The onset alone -- the time from `vibrate()` to the first sample
         * above baseline -- is longer than the entire 25 ms of dead time a
         * 30 fps frame has after an 8 ms exposure. With the ring-down it needs
         * 115 ms of a 33 ms period. This is not a near miss.
         *
         * The PRD anticipated it: "a linear resonant actuator typically rings
         * down over 10-30 ms, which may not fit." It rings down over 65, and
         * takes 30 to start. Of the PRD's three fallbacks, the OIS actuator is
         * not exposed by Camera2 on this device, lengthening the period would
         * mean a burst at 8.7 fps, and so the controller built on these numbers
         * declines every frame at 30 fps -- which is the right answer, and the
         * reason the measurement was made before the feature.
         *
         * The onset is folded into the pulse figure, since the controller
         * schedules the `vibrate()` call and the motor's own delay is part of
         * what has to fit.
         */
        const val PIXEL_11_PRO_PULSE_NS = 30_300_000L + 2_000_000L
        const val PIXEL_11_PRO_SETTLE_NS = 65_000_000L

        fun measuredOnPixel11Pro() =
            DitherController(PIXEL_11_PRO_PULSE_NS, PIXEL_11_PRO_SETTLE_NS)

        /**
         * For a device that has not been measured: the Pixel 11 Pro's figures,
         * which decline at any frame rate a burst would use. A phone whose
         * motor is faster gains nothing from this default and loses nothing
         * either; one whose motor is slower is protected by it.
         */
        fun unmeasured() = measuredOnPixel11Pro()
    }
}
