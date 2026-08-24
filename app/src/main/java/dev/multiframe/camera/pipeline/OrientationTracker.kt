package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.OrientationEventListener

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
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val out = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, m, true,
            )
            if (out != bitmap) bitmap.recycle()
            return out
        }
    }
}
