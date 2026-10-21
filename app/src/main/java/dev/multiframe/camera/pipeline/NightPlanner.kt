package dev.multiframe.camera.pipeline

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What a NIGHT press shoots: the still burst's exposure, gain and count. */
data class NightPlan(
    val motion: MotionClass,
    val exposureNs: Long,
    val iso: Int,
    val frames: Int,
    /** The reference frame's blur at the measured rate, in sensor pixels. */
    val blurPixels: Float,
    /**
     * Stops of light the plan gathers over the meter's own exposure. Zero
     * but for the ISO's rounding; see [NightPlanner.solve].
     */
    val lightStops: Float,
) {
    /** The console's reading of the plan: `HANDHELD · 1/15 · ISO 3200 · 28×`. */
    fun reading(locale: java.util.Locale = java.util.Locale.getDefault()): String =
        "${motion.label} · ${NightPlanner.shutterLabel(exposureNs, locale)} · ISO $iso · $frames×"
}

/**
 * NIGHT's exposure, solved from the hand.
 *
 * ### What it replaces
 *
 * NIGHT took every frame the ring held at the meter's own exposure, with a
 * nominal 250 ms cap ([CaptureModes.NIGHT_EXPOSURE_NS]) the repeating
 * request never reached: the meter does not expose a preview frame past the
 * frame interval. So a phone on a tripod shot the same thirtieth of a second,
 * at the same gain, as a phone in a hand.
 *
 * ### What it solves
 *
 * The shutter from the hand, and the gain from the light. The class
 * ([MotionClassifier]) gives the range of exposures; within it the exposure is
 * the longest that keeps the reference frame's blur under
 * [MotionClassifier.BLUR_BUDGET_PX] at the measured rate, `budget / (f_px *
 * omega)`, held to the class's range:
 *
 * | class    | exposure         | frames                     |
 * |----------|------------------|----------------------------|
 * | HANDHELD | 1/15 -- 1/8 s    | 2 s of burst, 16 -- 28     |
 * | BRACED   | 1/4 -- 1/2 s     | 4 s of burst, 8 -- 16      |
 * | TRIPOD   | 1/2 -- 1 s       | the ring's ceiling         |
 *
 * Every count is held to the ring's ceiling. A handheld hand that cannot hold
 * 1/15 s at the budget still gets 1/15 s; [NightPlan.blurPixels] says what
 * that costs. The burst's length is bounded for the hand, not the exposure:
 * two seconds is about what a hand holds a framing for.
 *
 * ### The light is the meter's
 *
 * The plan keeps the meter's exposure product, `ISO * t`, and only re-splits
 * it: the longer shutter is paid for with a lower gain. That is what keeps the
 * develop where the meter put it -- the develop's automatic gain is held
 * within half a stop under and three quarters over the meter's
 * ([DevelopParams.meteredGain], [RawDeveloper.boundedGain]), and a plan that
 * gathered more light than the meter asked for would render a night scene as
 * a dusk. The meter in NIGHT runs with no highlight pull
 * ([CapturePlan.protectHighlights] is false), and whatever pull is in force
 * when the press reads the meter is in its exposure already, and is undone
 * by the develop and the DNG's `BaselineExposure` as for any shot. Where the
 * class's longest exposure at the sensor's highest gain cannot carry the
 * meter's light, or its shortest at the lowest gain carries too much, the
 * shutter leaves the class's range rather than the light leaving the meter's
 * -- toward the light within the class's longest exposure, past it never. The
 * meter's own frame is at most a frame interval at the sensor's range, so in
 * practice the light is always kept; [lightStops][NightPlan.lightStops] would
 * say otherwise.
 *
 * Pure: the screen reads the meter, the gyroscope and the lens and calls this.
 */
object NightPlanner {

    /** How often the screen re-reads the hand and the meter. */
    const val POLL_MS = 100L

    /** The handheld burst's length: two seconds. */
    const val HANDHELD_BURST_NS = 2_000_000_000L
    const val HANDHELD_MIN_FRAMES = 16
    const val HANDHELD_MAX_FRAMES = 28

    /** The braced burst's length: four seconds. */
    const val BRACED_BURST_NS = 4_000_000_000L
    const val BRACED_MIN_FRAMES = 8
    const val BRACED_MAX_FRAMES = 16

    /**
     * Whether a NIGHT plan can be shot at all: the gyroscope to classify the
     * hand, a manual sensor and its ranges to pin the exposure. Without them
     * NIGHT is the fixed plan.
     */
    fun supported(hasGyro: Boolean, caps: CameraCapabilities): Boolean =
        hasGyro && caps.hasManualSensor && caps.hasIsoRange && caps.hasExposureRange

    fun solve(
        motion: MotionClass,
        /** The measured RMS rate in radians per second; NaN where unknown. */
        rate: Float,
        pixelsPerRadian: Float,
        meterIso: Int,
        meterExposureNs: Long,
        isoMin: Int,
        isoMax: Int,
        exposureMinNs: Long,
        exposureMaxNs: Long,
        ceiling: Int,
    ): NightPlan {
        // The longest exposure the hand holds at the budget.
        val permitted = if (rate > 0f && pixelsPerRadian > 0f)
            (MotionClassifier.BLUR_BUDGET_PX / (pixelsPerRadian * rate) * 1e9).coerceAtMost(1e10)
        else motion.maxExposureNs.toDouble()
        var t = permitted.coerceIn(motion.minExposureNs.toDouble(), motion.maxExposureNs.toDouble())

        // The light is the meter's; the gain pays for the shutter.
        val light = meterIso.toDouble() * meterExposureNs.coerceAtLeast(1L)
        // A scene too dark for the gain at this shutter takes the class's
        // longer end first...
        t = maxOf(t, minOf(light / isoMax, motion.maxExposureNs.toDouble()))
        // ...and one too bright for the lowest gain takes less time.
        t = minOf(t, light / isoMin)
        val exposureNs = t.roundToLong().coerceIn(exposureMinNs, exposureMaxNs)
        val iso = (light / exposureNs).roundToInt().coerceIn(isoMin, isoMax)

        return NightPlan(
            motion = motion,
            exposureNs = exposureNs,
            iso = iso,
            frames = framesFor(motion, exposureNs, ceiling),
            blurPixels = if (rate.isNaN()) Float.NaN else StabilityGate.blurPixels(rate, exposureNs, pixelsPerRadian),
            lightStops = (ln(iso.toDouble() * exposureNs / light) / ln(2.0)).toFloat(),
        )
    }

    /** The burst's count for a class at an exposure, held to [ceiling]. */
    fun framesFor(motion: MotionClass, exposureNs: Long, ceiling: Int): Int {
        val wanted = when (motion) {
            MotionClass.HANDHELD -> (HANDHELD_BURST_NS.toDouble() / exposureNs).roundToInt()
                .coerceIn(HANDHELD_MIN_FRAMES, HANDHELD_MAX_FRAMES)
            MotionClass.BRACED -> (BRACED_BURST_NS.toDouble() / exposureNs).roundToInt()
                .coerceIn(BRACED_MIN_FRAMES, BRACED_MAX_FRAMES)
            MotionClass.TRIPOD -> ceiling
        }
        return minOf(wanted, ceiling).coerceAtLeast(1)
    }

    /** Whether [plan]'s light sits inside the develop's bound on the meter's gain. */
    fun withinMeteredBound(plan: NightPlan): Boolean =
        plan.lightStops >= -DevelopParams.METERED_GAIN_BELOW_STOPS &&
            plan.lightStops <= DevelopParams.METERED_GAIN_ABOVE_STOPS

    /** `1/15`, `1/4`, `0.5 s`, `1.0 s`: the nearest whole reciprocal, not the floor. */
    fun shutterLabel(exposureNs: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val seconds = exposureNs / 1e9
        return if (seconds >= 0.4) "%.1f s".format(locale, seconds) else "1/${(1.0 / seconds).roundToInt()}"
    }
}
