package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "DitherTiming"

/**
 * What the vibration motor actually does when asked for a dither.
 *
 * ### Why this test exists before the feature does
 *
 * `docs/PRD.md` §2.3.6 is explicit: the dither's timing constraint "must be
 * measured before the feature is built, not designed around an assumption."
 * At 30 fps there is about 25 ms of dead time after an 8 ms exposure, and a
 * linear resonant actuator rings down over 10-30 ms. Whether a pulse and its
 * settle fit inside that window is not something to guess about, because a
 * dither that has not settled when the next exposure begins is motion blur --
 * exactly the thing the no-haptics-during-capture rule exists to prevent.
 *
 * ### What is measured
 *
 * The accelerometer, sampled as fast as the platform allows, around a single
 * pulse. From the trace:
 *
 *  * **onset latency** -- from the `vibrate()` call to the first sample above
 *    baseline. This is the platform's scheduling delay plus the motor's
 *    spin-up, and it is the uncertainty the controller's margin has to cover.
 *  * **active duration** -- how long the acceleration stays above baseline.
 *    This is the pulse plus the ring-down together, which is the figure that
 *    has to fit the window.
 *  * **peak acceleration** -- how hard the phone actually moved. A dither
 *    wants a fraction of a photosite; this says whether the amplitude is in
 *    the right order of magnitude or needs turning down.
 *
 * Each is logged. The assertions are on the measurement having happened, not
 * on the values, because the values are the finding.
 */
@RunWith(AndroidJUnit4::class)
class DitherTimingDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Reading(val timestampNs: Long, val magnitude: Float)

    /**
     * Records accelerometer magnitude, gravity removed by subtracting the
     * pre-pulse mean, for [durationMs] around a pulse fired at [fireAfterMs].
     */
    private fun trace(pulse: DitherPulse, fireAfterMs: Long, durationMs: Long, amplitude: Int): Pair<List<Reading>, Long> {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: throw AssertionError("no accelerometer")

        val readings = CopyOnWriteArrayList<Reading>()
        val thread = HandlerThread("accel").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                readings.add(Reading(e.timestamp, m))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST, Handler(thread.looper))
        Thread.sleep(fireAfterMs)
        val fireNs = android.os.SystemClock.elapsedRealtimeNanos()
        pulse.fire(DitherPulse.PULSE_MS, amplitude)
        Thread.sleep(durationMs - fireAfterMs)
        sensors.unregisterListener(listener)
        thread.quitSafely()

        return readings.toList() to fireNs
    }

    private data class Measurement(
        val onsetMs: Double,
        val activeMs: Double,
        val peak: Float,
        val baseline: Float,
        val noise: Float,
        val sampleRateHz: Double,
    )

    private fun analyse(readings: List<Reading>, fireNs: Long): Measurement? {
        if (readings.size < 50) return null

        // Baseline from the samples before the pulse: mean and spread.
        val before = readings.filter { it.timestampNs < fireNs }
        if (before.size < 20) return null
        val baseline = before.map { it.magnitude }.average().toFloat()
        val noise = kotlin.math.sqrt(before.map { (it.magnitude - baseline).let { d -> d * d } }.average()).toFloat()

        // Above baseline means more than five sigma of the pre-pulse noise.
        val threshold = baseline + 5f * noise.coerceAtLeast(0.005f)

        val after = readings.filter { it.timestampNs >= fireNs }
        val first = after.firstOrNull { it.magnitude > threshold } ?: return Measurement(
            onsetMs = -1.0, activeMs = 0.0, peak = 0f, baseline = baseline, noise = noise,
            sampleRateHz = sampleRate(readings),
        )
        val last = after.lastOrNull { it.magnitude > threshold } ?: first
        val peak = after.maxOf { it.magnitude } - baseline

        return Measurement(
            onsetMs = (first.timestampNs - fireNs) / 1e6,
            activeMs = (last.timestampNs - first.timestampNs) / 1e6,
            peak = peak,
            baseline = baseline,
            noise = noise,
            sampleRateHz = sampleRate(readings),
        )
    }

    private fun sampleRate(readings: List<Reading>): Double {
        if (readings.size < 2) return 0.0
        val span = (readings.last().timestampNs - readings.first().timestampNs) / 1e9
        return if (span > 0) (readings.size - 1) / span else 0.0
    }

    @Test
    fun theDeviceHasAMotorAndSaysWhatKind() {
        val pulse = DitherPulse(context)
        Log.i(TAG, "vibrator present: ${pulse.isAvailable}, amplitude control: ${pulse.hasAmplitudeControl}")
        // A phone without a motor cannot dither and the controller should know.
        assertThat(pulse.isAvailable).isTrue()
    }

    /**
     * The finding. One pulse at the dither amplitude, traced.
     *
     * The phone should be resting on a firm surface for this, not held: a hand
     * adds motion the analysis will read as ring-down. It is run several times
     * and the median reported, because a single trace is at the mercy of
     * whatever else the phone was doing in that hundred milliseconds.
     */
    @Test
    fun aDitherPulseIsMeasuredOffTheAccelerometer() {
        val pulse = DitherPulse(context)
        if (!pulse.isAvailable) {
            Log.i(TAG, "no vibrator; nothing to measure")
            return
        }

        val results = ArrayList<Measurement>()
        repeat(ROUNDS) {
            val (readings, fireNs) = trace(pulse, fireAfterMs = 300, durationMs = 600, amplitude = DitherPulse.AMPLITUDE)
            analyse(readings, fireNs)?.let { results.add(it) }
            Thread.sleep(400)
        }
        assertThat(results).isNotEmpty()

        val rate = results.map { it.sampleRateHz }.average()
        Log.i(TAG, "accelerometer at %.0f Hz (%.1f ms between samples)".format(rate, 1000.0 / rate))

        val detected = results.filter { it.onsetMs >= 0 }
        Log.i(TAG, "pulse detected in ${detected.size} of ${results.size} traces at amplitude ${DitherPulse.AMPLITUDE}")

        if (detected.isEmpty()) {
            Log.i(
                TAG,
                "no motion above baseline (noise %.4f m/s^2): the pulse is below the accelerometer's floor, or the motor did not fire".format(
                    results.map { it.noise }.average(),
                ),
            )
            return
        }

        fun median(v: List<Double>) = v.sorted()[v.size / 2]
        val onset = median(detected.map { it.onsetMs })
        val active = median(detected.map { it.activeMs })
        val peak = detected.map { it.peak.toDouble() }.let { median(it) }

        Log.i(TAG, "onset latency: median %.1f ms (range %.1f-%.1f)".format(
            onset, detected.minOf { it.onsetMs }, detected.maxOf { it.onsetMs }))
        Log.i(TAG, "active (pulse + ring-down): median %.1f ms (range %.1f-%.1f)".format(
            active, detected.minOf { it.activeMs }, detected.maxOf { it.activeMs }))
        Log.i(TAG, "peak acceleration above baseline: %.3f m/s^2".format(peak))

        // What this means for a 30 fps frame with an 8 ms exposure.
        val period = 33.333
        val exposure = 8.0
        val margins = 2 * DitherController.DEFAULT_MARGIN_NS / 1e6
        val needed = exposure + onset + active + margins
        Log.i(
            TAG,
            "at 30 fps, 8 ms exposure: need %.1f ms of %.1f ms -- %s".format(
                needed, period, if (needed <= period) "FITS" else "does not fit; lengthen period or decline",
            ),
        )
        DeviceKind.warnIfNotAPhone(TAG)
    }

    /**
     * The same, at a few amplitudes, so the relationship between drive and
     * displacement is visible. A dither wants the least that moves the sensor
     * measurably, and that is not knowable without the curve.
     */
    @Test
    fun theAmplitudeCurveIsRecorded() {
        val pulse = DitherPulse(context)
        if (!pulse.isAvailable || !pulse.hasAmplitudeControl) {
            Log.i(TAG, "no amplitude control; the curve is a point")
            return
        }
        for (amp in intArrayOf(10, 25, 60, 120, 255)) {
            val (readings, fireNs) = trace(pulse, fireAfterMs = 300, durationMs = 600, amplitude = amp)
            val m = analyse(readings, fireNs)
            if (m == null) {
                Log.i(TAG, "amp %3d: insufficient samples".format(amp))
            } else if (m.onsetMs < 0) {
                Log.i(TAG, "amp %3d: not detected above noise %.4f".format(amp, m.noise))
            } else {
                Log.i(TAG, "amp %3d: onset %.1f ms, active %.1f ms, peak %.3f m/s^2".format(
                    amp, m.onsetMs, m.activeMs, m.peak))
            }
            Thread.sleep(500)
        }
    }

    private companion object {
        const val ROUNDS = 5
    }
}
