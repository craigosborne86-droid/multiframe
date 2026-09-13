package dev.multiframe.camera.pipeline

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

private const val TAG = "DitherPulse"

/**
 * The actuator, driven for a dither.
 *
 * ### Why this is not [dev.multiframe.camera.ui.Haptics]
 *
 * `Haptics` is the interface's tactile vocabulary: a confirmation on the
 * shutter, a tick at a detent. Those go through the view's haptic feedback
 * system, which picks an effect the platform considers appropriate and fires
 * it at some point soon. Neither the timing nor the waveform is under control,
 * and neither needs to be.
 *
 * This is not feedback. It is a physical displacement of the sensor, timed to
 * a window a few milliseconds wide between two exposures, and it needs the
 * shortest, weakest, most precisely timed pulse the motor can produce. That is
 * the `Vibrator` API driven directly with a `VibrationEffect`, and it is kept
 * apart from `Haptics` so the two are never confused: nothing in the interface
 * should ever call this, and nothing in the merge should ever call that.
 *
 * ### What the pulse is
 *
 * The shortest one-shot the platform will honour, at the lowest amplitude
 * that still moves the mass. A dither is meant to displace the sensor by a
 * fraction of a photosite -- a few microns -- and a pulse a person can feel is
 * orders of magnitude more than that. The parameters here are the starting
 * point; `DitherTimingDeviceTest` measures what they actually produce.
 */
class DitherPulse(context: Context) {

    private val vibrator: Vibrator? = try {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } catch (e: Exception) {
        Log.w(TAG, "no vibrator", e)
        null
    }

    /** Whether this device has a motor at all. */
    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    /** Whether the motor accepts an amplitude, or is on/off only. */
    val hasAmplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true

    /**
     * Fires one pulse, now.
     *
     * The caller has already decided the window is open. This does not check
     * the detector or the timing; it does the one thing it is for, and returns
     * whether the platform accepted the request.
     */
    fun fire(durationMs: Long = PULSE_MS, amplitude: Int = AMPLITUDE): Boolean {
        val v = vibrator ?: return false
        if (!v.hasVibrator()) return false
        return try {
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            } else {
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
            true
        } catch (e: Exception) {
            Log.w(TAG, "pulse refused", e)
            false
        }
    }

    /** Stops the motor, for a cancellation mid-pulse. */
    fun cancel() {
        runCatching { vibrator?.cancel() }
    }

    companion object {
        /**
         * Two milliseconds of drive. Short enough to be a nudge rather than a
         * buzz; long enough that the platform does not round it away. Some
         * vibrators quantise below this.
         */
        const val PULSE_MS = 2L

        /**
         * A tenth of full scale. Enough to move the mass; well under what is
         * felt through a tripod head. Measured and adjusted by the timing test.
         */
        const val AMPLITUDE = 25
    }
}
