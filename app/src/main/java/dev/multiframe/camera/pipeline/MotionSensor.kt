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
 * The gyroscope, read for the mosaic's stability gate.
 *
 * ### What it keeps
 *
 * The angular speed -- the magnitude of the rotation rate -- of the last
 * second of samples, with their timestamps, on the sensor's own clock. The
 * guidance asks one question of it ten times a second: how fast was the phone
 * turning, at its fastest, since the last poll? A hand that is holding still
 * answers under a degree a second; a hand that has just arrived on the target
 * and is still settling answers several. See [StabilityGate] for what the
 * answer is compared against.
 *
 * ### Why the peak and not the mean
 *
 * A burst is eight frames, and one frame smeared by a twitch is one tile
 * smeared. The mean over a window forgives a twitch; the peak does not.
 *
 * Runs on its own handler thread at the fastest rate the platform offers, and
 * does nothing on the main thread: a listener there would put a sensor
 * callback between every frame the viewfinder draws.
 */
class MotionSensor(context: Context) : AutoCloseable {

    private class Sample(val timestampNs: Long, val speed: Float)

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val thread = HandlerThread("gyro").apply { start() }
    private val samples = ArrayDeque<Sample>()
    private val lock = Any()
    private var started = false

    /** Whether the device has a gyroscope; without one the gate is registration alone. */
    val isAvailable: Boolean get() = gyro != null

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
        val g = gyro ?: return
        if (started) return
        started = true
        manager.registerListener(listener, g, SensorManager.SENSOR_DELAY_FASTEST, Handler(thread.looper))
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

    override fun close() {
        if (started) manager.unregisterListener(listener)
        started = false
        thread.quitSafely()
    }

    private companion object {
        const val KEEP_NS = 1_000_000_000L
    }
}
