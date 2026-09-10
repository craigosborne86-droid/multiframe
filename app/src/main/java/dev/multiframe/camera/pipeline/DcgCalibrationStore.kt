package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log

private const val TAG = "DcgCalStore"

/**
 * What the noise profiling concluded, kept between launches.
 *
 * The measurement costs a second or two of camera time and its answer cannot
 * change: a sensor's analog gain switch is a property of the silicon. Running
 * it once per camera per install is the right frequency, and that means the
 * result has to be written down.
 *
 * ### Why a negative result is stored too
 *
 * Most sensors have no gain switch, and "no switch here" is a real finding that
 * took the same second to establish as a positive one would have. Storing only
 * successes would mean re-profiling every launch on exactly the hardware where
 * profiling has nothing to offer. [NO_THRESHOLD] is that record.
 *
 * A camera that has never been profiled is absent from the store entirely,
 * which is a third state and distinct from both.
 */
object DcgCalibrationStore {

    private const val PREFERENCES = "dcg_calibration"

    /** Written where profiling ran and found the noise curve unbroken. */
    private const val NO_THRESHOLD = -1

    /**
     * Bumped when the fitting changes in a way that invalidates stored answers.
     *
     * A stored threshold is only as good as the code that found it. Without
     * this, a device that profiled under an earlier and worse fit would keep
     * that answer forever, because the store cannot tell a good record from a
     * stale one by looking at it.
     */
    private const val VERSION = 1
    private const val KEY_VERSION = "version"

    /**
     * The threshold for [cameraId]: the ISO where the gain switches, null where
     * profiling ran and found none, and absent where it has never run.
     *
     * The two nulls are told apart by [hasProfiled], which is what the caller
     * needs to decide whether to spend a second measuring.
     */
    fun thresholdFor(context: Context, cameraId: String): Int? {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) != VERSION) return null
        val stored = prefs.getInt(cameraId, 0)
        return if (stored > 0) stored else null
    }

    /** Whether this camera has been profiled at all, whatever the answer was. */
    fun hasProfiled(context: Context, cameraId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) != VERSION) return false
        return prefs.contains(cameraId)
    }

    fun record(context: Context, cameraId: String, result: DcgCalibration.Result) {
        val value = result.thresholdIso ?: NO_THRESHOLD
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VERSION, VERSION)
            .putInt(cameraId, value)
            .apply()
        Log.i(TAG, "recorded for $cameraId: $result")
    }

    /** For the settings reset, which should not keep a measurement either. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
