package dev.multiframe.camera.pipeline

import kotlin.math.sqrt

/**
 * Whether the phone is rigidly mounted, from what the IMU says.
 *
 * ### Why it matters to a merge
 *
 * Multi-frame super-resolution does not extract detail by averaging. It works
 * because each frame samples the scene at a slightly different sub-pixel
 * offset -- hand tremor moves the sensor a fraction of a photosite between
 * frames, and the burst collectively samples more densely than any one frame.
 * On a tripod that stops: every frame lands on the same grid, and there is
 * nothing to recover. The result is worse than handheld in the one situation
 * the user reasonably expects to be best.
 *
 * So the merge needs to know which regime it is in, and this tells it.
 *
 * ### The two signals, and why both
 *
 * Gyroscope RMS over a window separates a hand from a mount by an order of
 * magnitude either side of the threshold: handheld reads 0.01-0.05 rad/s,
 * a mount reads the sensor's own noise floor near 0.001. Accelerometer RMS
 * does the same for linear motion. Both are required because a phone resting
 * flat on a table is still against gravity but not against a tap on the
 * surface -- the gyro sees nothing and the accelerometer sees the tap.
 *
 * ### Pure, on purpose
 *
 * This holds no sensor and knows nothing about Android. Samples are pushed in
 * with their timestamps; a verdict comes out. That is what lets the thresholds
 * be tested against synthetic motion on the JVM, and what lets the same logic
 * be fed from a recorded trace as from a live sensor.
 */
class TripodDetector(
    /** Window over which RMS is computed, in nanoseconds. */
    private val windowNs: Long = DEFAULT_WINDOW_NS,
    private val gyroThreshold: Float = GYRO_RMS_THRESHOLD,
    private val accelThreshold: Float = ACCEL_RMS_THRESHOLD,
) {

    enum class State {
        /** Not enough samples yet to say. */
        UNKNOWN,

        /** Moving as a hand moves. The normal case. */
        HANDHELD,

        /** Below both thresholds for a full window. */
        MOUNTED,
    }

    private class Sample(val timestampNs: Long, val x: Float, val y: Float, val z: Float)

    private val gyro = ArrayDeque<Sample>()
    private val accel = ArrayDeque<Sample>()

    /** Gravity, tracked so the accelerometer reading can have it removed. */
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityPrimed = false

    /**
     * A gyroscope reading, in rad/s on three axes.
     *
     * Rotation rate has no static component to remove: a stationary phone
     * reads zero on every axis up to sensor noise.
     */
    fun gyroscope(timestampNs: Long, x: Float, y: Float, z: Float) {
        gyro.addLast(Sample(timestampNs, x, y, z))
        trim(gyro, timestampNs)
    }

    /**
     * An accelerometer reading, in m/s^2 on three axes, gravity included.
     *
     * Gravity is removed by a slow low-pass before the sample is kept: the
     * quantity that separates a mount from a hand is the *change* in
     * acceleration, and a constant 9.8 on one axis would swamp it. The filter
     * constant is slow enough that a hand's tremor passes through and fast
     * enough to settle within the first window.
     */
    fun accelerometer(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (!gravityPrimed) {
            gravityX = x
            gravityY = y
            gravityZ = z
            gravityPrimed = true
        } else {
            gravityX += (x - gravityX) * GRAVITY_ALPHA
            gravityY += (y - gravityY) * GRAVITY_ALPHA
            gravityZ += (z - gravityZ) * GRAVITY_ALPHA
        }
        accel.addLast(Sample(timestampNs, x - gravityX, y - gravityY, z - gravityZ))
        trim(accel, timestampNs)
    }

    /** Drops samples older than the window behind [now]. */
    private fun trim(queue: ArrayDeque<Sample>, now: Long) {
        val cutoff = now - windowNs
        while (queue.isNotEmpty() && queue.first().timestampNs < cutoff) queue.removeFirst()
    }

    /** Root-mean-square magnitude across the window. */
    private fun rms(queue: ArrayDeque<Sample>): Float {
        if (queue.isEmpty()) return Float.MAX_VALUE
        var sum = 0.0
        for (s in queue) sum += (s.x * s.x + s.y * s.y + s.z * s.z).toDouble()
        return sqrt(sum / queue.size).toFloat()
    }

    /** Whether a queue spans the full window, so its RMS is a fair reading. */
    private fun spansWindow(queue: ArrayDeque<Sample>): Boolean =
        queue.size >= MIN_SAMPLES &&
            queue.last().timestampNs - queue.first().timestampNs >= windowNs * SPAN_FRACTION

    val gyroRms: Float get() = rms(gyro)
    val accelRms: Float get() = rms(accel)

    /**
     * The verdict now.
     *
     * [UNKNOWN] until both sensors have filled a window, because a verdict on
     * a fraction of one is a verdict on noise. Both thresholds have to hold at
     * once for [MOUNTED]; either failing is [HANDHELD].
     */
    val state: State
        get() {
            if (!spansWindow(gyro) || !spansWindow(accel)) return State.UNKNOWN
            val still = rms(gyro) < gyroThreshold && rms(accel) < accelThreshold
            return if (still) State.MOUNTED else State.HANDHELD
        }

    /** Forgets everything. For a fresh capture, or a test. */
    fun reset() {
        gyro.clear()
        accel.clear()
        gravityPrimed = false
    }

    companion object {
        /** Half a second: long enough to average tremor, short enough to react. */
        const val DEFAULT_WINDOW_NS = 500_000_000L

        /**
         * An order of magnitude above a gyroscope's noise floor and an order
         * below the gentlest hand. From docs/PRD.md §2.3.6.
         */
        const val GYRO_RMS_THRESHOLD = 0.005f

        /** The same, for linear acceleration with gravity removed. */
        const val ACCEL_RMS_THRESHOLD = 0.02f

        /**
         * Gravity tracker constant. At 200 Hz this is a time constant of about
         * a second -- slow against tremor, fast against the window.
         */
        private const val GRAVITY_ALPHA = 0.005f

        /** Fewer than this and the RMS is not a statistic. */
        private const val MIN_SAMPLES = 20

        /** A queue must cover this fraction of the window to count as full. */
        private const val SPAN_FRACTION = 0.8f
    }
}
