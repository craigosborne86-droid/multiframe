package dev.multiframe.camera.pipeline

import kotlin.math.sqrt

/**
 * How the phone is being held, as NIGHT needs to know it: in a hand, braced
 * against something, or on a tripod. Ordered from least to most stable.
 *
 * Each class is the longest exposure the hand allows, not a guess at a
 * posture. A phone resting on a wall is TRIPOD if it reads as still as one,
 * and a tripod in the wind is HANDHELD.
 */
enum class MotionClass(
    /** The shortest exposure this class plans, in nanoseconds. */
    val minExposureNs: Long,
    /** The longest. */
    val maxExposureNs: Long,
) {
    HANDHELD(66_666_667L, 125_000_000L),
    BRACED(250_000_000L, 500_000_000L),
    TRIPOD(500_000_000L, 1_000_000_000L);

    val label: String get() = name
}

/**
 * The gyroscope's samples over a window, on the sensor clock, in radians per
 * second about the device's three axes. Plain arrays so that a synthetic trace
 * is as easy to build on the JVM as a recorded one.
 */
class GyroTrace(
    val timestampsNs: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
) {
    init {
        require(x.size == timestampsNs.size && y.size == timestampsNs.size && z.size == timestampsNs.size)
    }

    val size: Int get() = timestampsNs.size
}

/**
 * The hand's angular rate, measured the way blur is made.
 *
 * ### What is measured
 *
 * The RMS angular rate over the last [WINDOW_NS], from the gyroscope at the
 * fastest rate the platform delivers. Not the peak, as [StabilityGate] takes
 * for a sweep: a sweep's tile is one frame and one twitch smears it, but a
 * night exposure is a sixth of a second to a second and integrates the whole
 * tremor, which is what the RMS describes.
 *
 * ### Why the samples are binned first
 *
 * The gyroscope's own white noise, at a few hundred samples a second, reads
 * as a tremor of several hundredths of a degree a second -- more than a
 * tripod's real motion and as much as the braced threshold on the main lens.
 * The image does not see it: noise integrates to a random walk, a few
 * thousandths of a degree over a second. So the vector rate is averaged in
 * [BIN_NS] bins first (an equivalent noise bandwidth of 25 Hz, which leaves
 * the hand's 8--12 Hz physiological tremor nearly whole: a 20 ms box passes
 * 10 Hz at 94%), the RMS is taken over the bins, and the noise that is left,
 * [GYRO_NOISE_RAD_S], is removed in quadrature.
 *
 * Pure: the screen hands it a [GyroTrace] from [MotionSensor].
 */
object MotionMeter {

    /** The window the rate is measured over: half a second. */
    const val WINDOW_NS = 500_000_000L

    /** The bin the vector rate is averaged in before it is squared. */
    const val BIN_NS = 20_000_000L

    /**
     * Bins that must hold a sample before the window says anything: 200 ms of
     * the 500. Fewer is a sensor still starting, or one that stopped.
     */
    const val MIN_BINS = 10

    /**
     * The gyroscope's noise in a bin's mean, as a vector magnitude: a typical
     * MEMS density of 0.005 degree/s/sqrt(Hz) per axis over 25 Hz, times
     * sqrt(3), is 0.043 degree/s. An estimate from datasheets, not a
     * measurement of either development phone.
     */
    const val GYRO_NOISE_RAD_S = 0.0008f

    /**
     * The RMS angular rate, in radians per second, of [trace]'s samples in
     * the [WINDOW_NS] ending at [nowNs], binned and with the noise floor
     * removed. NaN when fewer than [MIN_BINS] bins hold a sample.
     */
    fun rmsRate(trace: GyroTrace, nowNs: Long): Float {
        val start = nowNs - WINDOW_NS
        val bins = (WINDOW_NS / BIN_NS).toInt()
        val sx = DoubleArray(bins)
        val sy = DoubleArray(bins)
        val sz = DoubleArray(bins)
        val n = IntArray(bins)
        for (i in 0 until trace.size) {
            val t = trace.timestampsNs[i]
            if (t < start || t > nowNs) continue
            val b = ((t - start) / BIN_NS).toInt().coerceAtMost(bins - 1)
            sx[b] += trace.x[i].toDouble()
            sy[b] += trace.y[i].toDouble()
            sz[b] += trace.z[i].toDouble()
            n[b]++
        }
        var sum = 0.0
        var filled = 0
        for (b in 0 until bins) {
            if (n[b] == 0) continue
            val mx = sx[b] / n[b]
            val my = sy[b] / n[b]
            val mz = sz[b] / n[b]
            sum += mx * mx + my * my + mz * mz
            filled++
        }
        if (filled < MIN_BINS) return Float.NaN
        val meanSquare = sum / filled
        val noise = GYRO_NOISE_RAD_S.toDouble()
        return sqrt((meanSquare - noise * noise).coerceAtLeast(0.0)).toFloat()
    }
}

/**
 * The class, from the rate, held steady.
 *
 * ### The thresholds are blur
 *
 * The newest frame of the burst is the merge's reference, so it is the frame
 * whose blur the photograph shows. In an exposure of `t` at a rate of `omega`
 * the image moves `f_px * omega * t` pixels, `f_px` the focal length in pixels
 * ([StabilityGate.blurPixels]); it is held under [BLUR_BUDGET_PX]. A class is
 * the rate at which the hand can hold that class's *shortest* exposure:
 *
 *     braced  below  omega_b = budget / (f_px * 1/4 s)
 *     tripod  below  omega_t = budget / (f_px * 1/2 s)
 *
 * and anything faster is HANDHELD. On a 3000-pixel main lens that is 0.06 and
 * 0.03 degree a second: tight, because 0.75 px over a quarter second is tight.
 * A longer lens has proportionally tighter thresholds -- the same hand is
 * less still on a telephoto, which is the point.
 *
 * ### Hysteresis
 *
 * A rate near a boundary would flip the class, and with it the planned
 * shutter, every poll. So a class is left for a less stable one only when the
 * rate passes the boundary by [EXIT_RATIO], and entered from a less stable
 * one only when the rate has been under the boundary by [ENTER_RATIO] for
 * [ENTER_DWELL_NS]. Falling is immediate -- a hand that has started moving is
 * moving now -- and rising waits, because a hand that has paused may not
 * have stopped. It starts HANDHELD, the class that cannot over-expose a
 * hand. A NaN rate (the sensor not yet delivering) changes nothing.
 */
class MotionClassifier(pixelsPerRadian: Float) {

    /** The rate under which the hand holds the braced class's shortest exposure. */
    val bracedRate: Float = rateFor(MotionClass.BRACED.minExposureNs, pixelsPerRadian)

    /** The rate under which it holds the tripod class's shortest. */
    val tripodRate: Float = rateFor(MotionClass.TRIPOD.minExposureNs, pixelsPerRadian)

    var current: MotionClass = MotionClass.HANDHELD
        private set

    private var pending: MotionClass? = null
    private var pendingSinceNs = 0L

    fun update(rate: Float, nowNs: Long): MotionClass {
        if (rate.isNaN()) return current
        val lenient = classOf(rate / EXIT_RATIO)
        val strict = classOf(rate / ENTER_RATIO)
        when {
            lenient < current -> {
                current = lenient
                pending = null
            }
            strict > current -> {
                val p = pending
                if (p == null) {
                    pending = strict
                    pendingSinceNs = nowNs
                } else {
                    // Rising to the least stable class the dwell has seen.
                    val held = minOf(p, strict)
                    pending = held
                    if (nowNs - pendingSinceNs >= ENTER_DWELL_NS) {
                        current = held
                        pending = null
                    }
                }
            }
            else -> pending = null
        }
        return current
    }

    /** The class with no hysteresis. */
    fun classOf(rate: Float): MotionClass = when {
        bracedRate <= 0f -> MotionClass.HANDHELD
        rate <= tripodRate -> MotionClass.TRIPOD
        rate <= bracedRate -> MotionClass.BRACED
        else -> MotionClass.HANDHELD
    }

    companion object {
        /** The reference frame's blur budget, in sensor pixels. */
        const val BLUR_BUDGET_PX = 0.75f

        /** A class is left when the rate is this far over its boundary. */
        const val EXIT_RATIO = 1.25f

        /** And entered when this far under. */
        const val ENTER_RATIO = 0.8f

        /** For this long, before a more stable class is taken. */
        const val ENTER_DWELL_NS = 300_000_000L

        /**
         * The rate, in radians per second, at which an exposure of
         * [exposureNs] carries [BLUR_BUDGET_PX] of blur. Zero where the
         * lens's scale is unknown, which leaves every reading HANDHELD.
         */
        fun rateFor(exposureNs: Long, pixelsPerRadian: Float): Float {
            if (exposureNs <= 0L || pixelsPerRadian <= 0f) return 0f
            return BLUR_BUDGET_PX / (pixelsPerRadian * (exposureNs / 1e9f))
        }
    }
}
