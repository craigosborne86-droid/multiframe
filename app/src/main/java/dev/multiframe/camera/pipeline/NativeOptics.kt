package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log

private const val TAG = "NativeOptics"

/**
 * The optics corrections on the native side, as one gather over a bitmap.
 *
 * Both corrections -- undistortion and lateral aberration -- run in Optics.cpp
 * as a single fused pass directly on the locked pixel buffer. Against the
 * Kotlin path this removes: the getPixels() copy in, the setPixels() copy out,
 * the second full-frame sweep, the 50 MB intermediate that sweep needed, and
 * one of the two interpolations each channel was put through.
 *
 * Returns null for anything it will not handle, and the caller falls back to
 * [Optics.applyReference], which does the same arithmetic in Kotlin.
 */
internal object NativeOptics {

    /**
     * The model packed for the JNI boundary, or null for no geometric
     * correction. Order is fixed and Optics.cpp reads it positionally.
     */
    fun pack(model: LensDistortion.Model?): FloatArray? = model?.let {
        floatArrayOf(it.fx, it.fy, it.cx, it.cy, it.skew, it.k1, it.k2, it.k3, it.p1, it.p2)
    }

    /**
     * Corrects [bitmap] into a new bitmap of the same size.
     *
     * The source is not modified and not recycled: the caller owns both and
     * decides when the original goes.
     */
    fun correct(
        bitmap: Bitmap,
        model: LensDistortion.Model?,
        scale: ChromaticAberration.Scale,
    ): Bitmap? {
        if (!NativeMerge.isAvailable()) return null
        @Suppress("DEPRECATION")
        if (bitmap.config != Bitmap.Config.ARGB_8888) return null

        val out = try {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "no room for a corrected bitmap", e)
            return null
        }

        if (!correctInto(bitmap, out, model, scale)) {
            out.recycle()
            return null
        }
        return out
    }

    /**
     * The pass alone, into a destination the caller already holds.
     *
     * Split from [correct] so the encode can be timed without the allocation:
     * a fresh 50 MB bitmap costs tens of milliseconds to obtain and zero, and
     * that is the graphics allocator's time, not this pass's.
     */
    fun correctInto(
        src: Bitmap,
        dst: Bitmap,
        model: LensDistortion.Model?,
        scale: ChromaticAberration.Scale,
    ): Boolean {
        if (!NativeMerge.isAvailable()) return false
        return try {
            nCorrect(src, dst, pack(model), scale.red, scale.blue)
        } catch (e: Throwable) {
            Log.w(TAG, "native optics failed", e)
            false
        }
    }

    private external fun nCorrect(
        src: Bitmap,
        dst: Bitmap,
        model: FloatArray?,
        scaleRed: Float,
        scaleBlue: Float,
    ): Boolean
}
