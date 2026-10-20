package dev.multiframe.camera.pipeline

import android.hardware.camera2.CaptureResult

/**
 * A frame's gathered light, ISO times exposure time, and the gain a merge
 * brings it to the reference's scale by: REFERENCE.md §3.2, `gainScale_i`.
 * The ZSL merge and the sequential one both read it from here, so a burst
 * whose meter moved is normalised the same way on either path.
 */
internal object FrameLight {

    /** ISO times exposure time, or zero where the result does not say. */
    fun of(result: CaptureResult?): Double {
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: return 0.0
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return 0.0
        if (iso <= 0 || exposure <= 0L) return 0.0
        return iso.toDouble() * exposure.toDouble()
    }

    /**
     * The alternate's light over the reference's. Exactly one when either is
     * unknown, which is the merge with no normalisation -- the pre-DCG merge
     * the `gainScale = 1` invariant protects -- and exactly one when they are
     * equal.
     */
    fun gainScale(referenceLight: Double, frameLight: Double): Float =
        if (referenceLight > 0.0 && frameLight > 0.0)
            (frameLight / referenceLight).toFloat() else 1f
}
