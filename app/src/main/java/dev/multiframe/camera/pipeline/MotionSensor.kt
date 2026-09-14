package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * The inertial sensors, read for the mosaic: the gyroscope for the stability
 * gate and the rotation vector for the guidance.
 *
 * ### The gyroscope
 *
 * Keeps the angular speed -- the magnitude of the rotation rate -- of the last
 * second of samples, with their timestamps, on the sensor's own clock. The
 * guidance asks one question of it every poll: how fast was the phone
 * turning, at its fastest, since the last poll? A hand that is holding still
 * answers a degree or two a second; a hand that has just arrived on the
 * target and is still settling answers several. See [StabilityGate] for what
 * the answer is compared against. The peak and not the mean, because a burst
 * is eight frames and one frame smeared by a twitch is one tile smeared.
 *
 * ### The rotation vector
 *
 * The device's attitude in the world, fused by the platform. The *game*
 * rotation vector is preferred: it leaves the magnetometer out, so it never
 * jumps when the phone passes a steel beam, at the cost of a yaw that drifts
 * slowly -- which the guidance corrects from the tiles it registers. Kept as
 * the platform's rotation matrix, device axes to world, which is what
 * [ImuGuidance] multiplies by; and kept for the last half second, so that a
 * frame the camera exposed a hundred milliseconds ago can be paired with the
 * attitude the phone had *then*, not the one it has now.
 *
 * Runs on its own handler thread at the fastest rate the platform offers, and
 * does nothing on the main thread: a listener there would put a sensor
 * callback between every frame the viewfinder draws. Listens only between
 * [start] and [stop] -- a mosaic's duration -- and is otherwise idle; the
 * screen holds one for its lifetime and [close]s it when it goes.
 */
class MotionSensor(context: Context) : AutoCloseable {

    private class Sample(val timestampNs: Long, val speed: Float)

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val attitudeSensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val thread = HandlerThread("imu").apply { start() }
    private val samples = ArrayDeque<Sample>()
    private val lock = Any()
    private var started = false

    // The latest rotation matrix and when it arrived, written on the sensor
    // thread and read on the runner's, and the recent history behind it.
    private val rotation = FloatArray(9)
    private var rotationStampNs = -1L
    private class Attitude(val timestampNs: Long, val matrix: FloatArray)
    private val attitudes = ArrayDeque<Attitude>()

    /** Whether the device has a gyroscope; without one the gate is the attitude's drift alone. */
    val isAvailable: Boolean get() = gyro != null

    /** Whether the device reports its attitude; without it the guidance cannot run. */
    val hasAttitude: Boolean get() = attitudeSensor != null

    private val attitudeListener = object : SensorEventListener {
        private val scratch = FloatArray(9)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(scratch, e.values)
            synchronized(lock) {
                System.arraycopy(scratch, 0, rotation, 0, 9)
                rotationStampNs = e.timestamp
                attitudes.addLast(Attitude(e.timestamp, scratch.copyOf()))
                val horizon = e.timestamp - ATTITUDE_KEEP_NS
                while (attitudes.isNotEmpty() && attitudes.first().timestampNs < horizon) attitudes.removeFirst()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * The device's current rotation matrix, device axes to world, row-major,
     * or null before the first sample. A fresh copy each call.
     */
    fun rotation(): FloatArray? = synchronized(lock) {
        if (rotationStampNs < 0) null else rotation.copyOf()
    }

    /**
     * Whether the attitude has settled: the fusion behind the rotation vector
     * starts from nowhere when the listener registers and converges over the
     * first second or so, and while it converges the attitude swings through
     * tens of degrees on a phone lying still. Anchoring on that laid the
     * canvas 120 degrees from where the phone pointed. Settled means the
     * attitude turned, over the last [SETTLE_WINDOW_NS], no more than the
     * gyroscope says the phone did, plus a small slack -- the two sensors
     * agreeing is what convergence looks like. False before the history spans
     * the window.
     */
    fun attitudeSettled(): Boolean {
        val latest: Attitude
        val earliest: Attitude
        synchronized(lock) {
            if (attitudes.size < 2) return false
            latest = attitudes.last()
            val horizon = latest.timestampNs - SETTLE_WINDOW_NS
            earliest = attitudes.firstOrNull { it.timestampNs <= horizon } ?: return false
        }
        val turned = angleBetween(earliest.matrix, latest.matrix)
        val seconds = (latest.timestampNs - earliest.timestampNs) / 1e9f
        val gyroPeak = peakSpeedSince(earliest.timestampNs)
        val allowed = (if (gyroPeak.isNaN()) 0f else gyroPeak * seconds) + SETTLE_SLACK_RAD
        return turned <= allowed
    }

    /**
     * The rotation matrix nearest in time to [timestampNs] on the sensor
     * clock, for pairing a camera frame with the attitude it was exposed at;
     * the latest where the history does not reach back that far, and null
     * before the first sample.
     */
    fun rotationAt(timestampNs: Long): FloatArray? = synchronized(lock) {
        if (rotationStampNs < 0) return null
        var best: Attitude? = null
        var bestGap = Long.MAX_VALUE
        for (a in attitudes) {
            val gap = kotlin.math.abs(a.timestampNs - timestampNs)
            if (gap < bestGap) { bestGap = gap; best = a }
        }
        (best?.matrix ?: rotation).copyOf()
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val speed = StabilityGate.speedOf(e.values[0], e.values[1], e.values[2])
            synchronized(lock) {
                samples.addLast(Sample(e.timestamp, speed))
                val horizon = e.timestamp - KEEP_NS
                while (samples.isNotEmpty() && samples.first().timestampNs < horizon) samples.removeFirst()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        val handler = Handler(thread.looper)
        gyro?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        attitudeSensor?.let { manager.registerListener(attitudeListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
    }

    /**
     * The largest angular speed, in radians per second, of any sample at or
     * after [sinceNs] on the sensor clock ([SystemClock.elapsedRealtimeNanos]).
     * NaN when there is no gyroscope, and zero when there are no samples in
     * the window -- which after a poll interval means the sensor has stopped,
     * and a stopped sensor reads as still rather than as moving.
     */
    fun peakSpeedSince(sinceNs: Long): Float {
        if (gyro == null) return Float.NaN
        var peak = 0f
        synchronized(lock) {
            for (s in samples) if (s.timestampNs >= sinceNs && s.speed > peak) peak = s.speed
        }
        return peak
    }

    /** The current sensor clock, for the caller's window bookkeeping. */
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    /** How many samples arrived in the last window; zero says the sensor is not delivering. */
    fun sampleCount(): Int = synchronized(lock) { samples.size }

    /**
     * Stops listening, and forgets what was heard. The sensor can be started
     * again: one instance serves every mosaic the screen runs, so the thread
     * is kept.
     */
    fun stop() {
        if (started) {
            manager.unregisterListener(listener)
            manager.unregisterListener(attitudeListener)
        }
        started = false
        synchronized(lock) {
            samples.clear()
            attitudes.clear()
            rotationStampNs = -1L
        }
    }

    /** [stop], and the thread with it. Not restartable. */
    override fun close() {
        stop()
        thread.quitSafely()
    }

    companion object {
        private const val KEEP_NS = 1_000_000_000L
        private const val ATTITUDE_KEEP_NS = 1_000_000_000L

        /** The attitude has to have held, against the gyroscope, for this long. */
        const val SETTLE_WINDOW_NS = 400_000_000L

        /** Half a degree of disagreement between the fused attitude and the gyroscope is noise, not convergence. */
        const val SETTLE_SLACK_RAD = 0.0087f

        /** The angle, in radians, of the rotation that takes [a] to [b]: `acos((tr(aᵀb) − 1) / 2)`. */
        fun angleBetween(a: FloatArray, b: FloatArray): Float {
            // trace(aᵀ b) = sum over all elements of a[i] * b[i].
            var trace = 0f
            for (i in 0 until 9) trace += a[i] * b[i]
            val c = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
            return kotlin.math.acos(c)
        }
    }
}
