package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.OrientationEventListener

private const val TAG = "Multiframe"

/**
 * Tracks physical device orientation so captures come out upright even when the
 * system has rotation locked. Without this the saved image follows the locked UI
 * rather than how the phone is actually being held.
 */
class OrientationTracker(context: Context) {

    @Volatile
    var deviceDegrees: Int = 0
        private set

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // Snap to the nearest quarter turn.
            deviceDegrees = (orientation + 45) / 90 * 90 % 360
        }
    }

    fun enable() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun disable() = listener.disable()

    /**
     * Clockwise rotation to apply to a sensor-native frame, per the Camera2
     * JPEG orientation rule for a back-facing camera.
     */
    fun captureRotation(sensorOrientation: Int): Int =
        (sensorOrientation + deviceDegrees + 360) % 360

    companion object {
        fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return bitmap

            // A tiled transpose in native code, where it is a memory problem
            // rather than an arithmetic one. Falls back to the framework, which
            // is also the reference the parity test holds the native one to.
            NativeRotate.rotate(bitmap, degrees)?.let { rotated ->
                bitmap.recycle()
                return rotated
            }

            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val out = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, m, true,
            )
            if (out != bitmap) bitmap.recycle()
            return out
        }

        /** The framework's rotation, kept reachable so a test can compare. */
        fun rotateWithMatrix(bitmap: Bitmap, degrees: Int): Bitmap {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, m, true,
            )
        }
    }
}

/**
 * A quarter turn done as a tiled transpose.
 *
 * Returns null for anything it will not handle -- a turn that is not a multiple
 * of ninety, a bitmap that is not RGBA_8888, no native library, or no memory
 * for the destination -- and the caller falls back to the framework.
 */
internal object NativeRotate {

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap? {
        if (!NativeMerge.isAvailable()) return null
        @Suppress("DEPRECATION")
        if (bitmap.config != Bitmap.Config.ARGB_8888) return null

        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0 || turn % 90 != 0) return null
        val swaps = turn == 90 || turn == 270

        val out = try {
            Bitmap.createBitmap(
                if (swaps) bitmap.height else bitmap.width,
                if (swaps) bitmap.width else bitmap.height,
                Bitmap.Config.ARGB_8888,
            )
        } catch (e: OutOfMemoryError) {
            // The framework path needs the same allocation, so this will
            // probably fail too -- but it gets to fail on its own terms.
            Log.w(TAG, "no room for a rotated bitmap", e)
            return null
        }

        val ok = try {
            nRotate(bitmap, out, turn)
        } catch (e: Throwable) {
            Log.w(TAG, "native rotate failed", e)
            false
        }
        if (!ok) {
            out.recycle()
            return null
        }
        return out
    }

    private external fun nRotate(src: Bitmap, dst: Bitmap, degrees: Int): Boolean
}
