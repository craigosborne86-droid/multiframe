package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** How the phone is being held, relative to level. */
data class Attitude(
    /** Rotation about the viewing axis. Zero is a level horizon. */
    val rollDegrees: Float,
    /** Tilt toward or away from the subject. Zero is straight ahead. */
    val pitchDegrees: Float,
    /** True when the phone is pointed near enough straight up or down. */
    val facingUpOrDown: Boolean,
) {
    /** Within this, a horizon reads as level to the eye. */
    val isLevel: Boolean get() = abs(rollDegrees) <= LEVEL_TOLERANCE

    companion object {
        const val LEVEL_TOLERANCE = 1.0f
    }
}

/**
 * A spirit level, from the accelerometer.
 *
 * A crooked horizon is the single most common fault in a photograph and the
 * hardest to see while taking it. Every serious camera offers this, and it costs
 * nothing to compute.
 *
 * The maths is separated from the sensor so it can be tested against known
 * gravity vectors, which is the only way to be sure the signs and axes are
 * right -- a level that reads backwards is worse than none.
 */
object LevelGauge {

    /**
     * Beyond this tilt the phone is pointing up or down and roll stops meaning
     * anything: at the limit, gravity lies along the viewing axis and the
     * horizon is not in frame at all. Reporting a confident roll there would
     * have the indicator spin wildly as the phone is moved.
     */
    const val GIMBAL_LIMIT_DEGREES = 70f

    /**
     * Reads attitude from a gravity vector in device coordinates.
     *
     * Android's convention with the phone held upright in portrait: x is to the
     * right, y is up the screen, z is out of the screen toward the user. At
     * rest in that pose gravity reads about (0, -9.81, 0) after the sign
     * convention of the accelerometer, so the vector passed here points *down*.
     */
    fun attitudeFrom(x: Float, y: Float, z: Float, deviceRotationDegrees: Int = 0): Attitude {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < 1e-3f) return Attitude(0f, 0f, facingUpOrDown = false)

        // Tilt of the viewing axis away from the horizontal.
        val horizontal = hypot(x, y)
        val pitch = Math.toDegrees(atan2(z.toDouble(), horizontal.toDouble())).toFloat()

        // Roll about the viewing axis, from where gravity sits in the screen
        // plane. Corrected for how the phone is being held, so a level horizon
        // reads as level in landscape as well as portrait.
        var roll = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        roll -= deviceRotationDegrees
        roll = normalise(roll)

        val facingUpOrDown = abs(pitch) >= GIMBAL_LIMIT_DEGREES
        return Attitude(
            rollDegrees = if (facingUpOrDown) 0f else roll,
            pitchDegrees = pitch,
            facingUpOrDown = facingUpOrDown,
        )
    }

    /** Wraps an angle into -180..180, so a horizon near vertical does not jump. */
    fun normalise(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Smooths a reading toward a new one.
     *
     * Raw accelerometer output jitters by a degree or two from hand tremor
     * alone, and an indicator that twitches is harder to use than one that
     * lags. Wrapping is handled so a horizon crossing 180 degrees does not
     * sweep the long way round.
     */
    fun smooth(previous: Float, next: Float, factor: Float = 0.15f): Float {
        val delta = normalise(next - previous)
        return normalise(previous + delta * factor.coerceIn(0f, 1f))
    }
}

/** Live attitude from the device's accelerometer. */
class LevelSensor(context: Context) {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var attitude: Attitude = Attitude(0f, 0f, facingUpOrDown = false)
        private set

    private var smoothedRoll = 0f

    /** Whether this device can offer a level at all. */
    val isAvailable: Boolean get() = sensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // The accelerometer reports the reaction to gravity, so the vector
            // pointing down is its negation.
            val raw = LevelGauge.attitudeFrom(
                -event.values[0], -event.values[1], -event.values[2],
            )
            smoothedRoll = LevelGauge.smooth(smoothedRoll, raw.rollDegrees)
            attitude = raw.copy(rollDegrees = smoothedRoll)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun enable() {
        sensor?.let {
            manager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun disable() {
        manager?.unregisterListener(listener)
    }
}
