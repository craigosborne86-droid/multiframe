package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "Multiframe"

/**
 * Bayer merge running in native memory.
 *
 * The reason this exists is the Dalvik heap. This device caps an app at 256 MB
 * of Java heap on 15 GB of physical RAM, and one raw frame is 25 MB, so a large
 * burst cannot be held on the managed heap at all. Everything here is allocated
 * natively, and camera frames are read straight from their own direct buffers
 * with no copy into Java.
 *
 * Alignment deliberately stays in Kotlin: it runs on the half-resolution luma
 * proxy, which is small enough not to matter, and reuses the aligner that is
 * already covered by tests.
 */
class NativeMerge private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
    private val profile: SensorProfile,
) : AutoCloseable {

    private var closed = false

    val proxyWidth = width / 2
    val proxyHeight = height / 2

    fun lumaProxy(frame: ByteBuffer, rowStride: Int): Plane {
        val out = ByteArray(proxyWidth * proxyHeight)
        nLumaProxy(handle, frame, rowStride, out)
        return Plane(proxyWidth, proxyHeight, out)
    }

    fun setReference(frame: ByteBuffer, rowStride: Int) =
        nSetReference(handle, frame, rowStride)

    fun addFrame(frame: ByteBuffer, rowStride: Int, field: AlignmentField) =
        nAddFrame(handle, frame, rowStride, field.dx, field.dy, field.tilesX, field.tilesY)

    /**
     * Writes the merged CFA data into a direct buffer, which is also off the
     * Java heap, and returns it alongside the merge statistics.
     */
    fun finish(): Pair<ByteBuffer, BayerMergeStats> {
        val out = ByteBuffer
            .allocateDirect(width * height * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val contribution = nFinish(handle, out)
        out.rewind()
        return out to BayerMergeStats(
            framesMerged = nFramesMerged(handle),
            meanContribution = contribution,
            // Measured from the burst itself rather than assumed: how much the
            // frames disagreed at mid brightness is what the merge had to work
            // with, and reporting it makes its improvement checkable.
            estimatedSigmaAtMid = nEstimatedSigma(handle),
        )
    }

    /**
     * Develops merged CFA data straight into a Bitmap's pixel store.
     *
     * Both ends are native: the merged buffer is a direct ByteBuffer and the
     * destination is the Bitmap's own pixels, so the developed image never
     * passes through a Java array.
     */
    fun develop(
        merged: ByteBuffer,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
        shading: ShadingMap? = null,
    ): Bitmap? {
        val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
        val gain = if (params.exposureGain > 0f) {
            params.exposureGain
        } else {
            nAutoExposure(
                merged, width, height, profile.cfaPattern, black, profile.whiteLevel,
                color.gains, params.highlightPercentile, params.highlightTarget,
            )
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val ok = nDevelop(
            merged, bitmap, width, height,
            profile.cfaPattern, black, profile.whiteLevel,
            color.gains, color.matrix, gain, params.shoulderKnee,
            params.contrast, params.highlightDesaturation,
            params.desaturationStart, params.blackPoint,
            shading?.gains, shading?.columns ?: 0, shading?.rows ?: 0,
            params.hotPixelThreshold,
        )
        if (!ok) {
            Log.w(TAG, "native develop failed, caller should fall back")
            bitmap.recycle()
            return null
        }
        // Further passes, because both need each pixel's neighbours and the
        // develop loop writes one pixel at a time. Defringe first: sharpening a
        // fringe would make it worse.
        if (params.defringe.enabled) {
            val altered = nDefringe(
                bitmap, params.defringe.edgeThreshold,
                params.defringe.tolerance, params.defringe.strength,
                params.defringe.minChroma, params.defringe.innerRadius,
            )
            if (altered > 0) Log.i(TAG, "defringed $altered pixels")
        }
        if (params.sharpen.enabled) {
            nSharpen(
                bitmap, params.sharpen.amount, params.sharpen.threshold,
                params.sharpen.maxShift,
            )
        }
        Log.i(TAG, "native develop gain=%.2f".format(gain))
        return bitmap
    }

    override fun close() {
        if (closed) return
        closed = true
        nDestroy(handle)
        handle = 0
    }

    private external fun nLumaProxy(h: Long, buf: ByteBuffer, rowStride: Int, out: ByteArray)
    private external fun nSetReference(h: Long, buf: ByteBuffer, rowStride: Int)
    private external fun nAddFrame(
        h: Long, buf: ByteBuffer, rowStride: Int,
        dx: IntArray, dy: IntArray, tilesX: Int, tilesY: Int,
    )
    private external fun nFinish(h: Long, out: ByteBuffer): Float
    private external fun nAutoExposure(
        merged: ByteBuffer, width: Int, height: Int,
        cfa: IntArray, black: IntArray, white: Int,
        gains: FloatArray, percentile: Float, target: Float,
    ): Float
    private external fun nDevelop(
        merged: ByteBuffer, bitmap: Bitmap, width: Int, height: Int,
        cfa: IntArray, black: IntArray, white: Int,
        gains: FloatArray, matrix: FloatArray,
        exposureGain: Float, knee: Float,
        contrast: Float, desatStrength: Float, desatStart: Float, blackPoint: Float,
        shading: FloatArray?, shadingColumns: Int, shadingRows: Int,
        hotPixelThreshold: Float,
    ): Boolean
    private external fun nDefringe(
        bitmap: Bitmap, edgeThreshold: Float, tolerance: Float, strength: Float,
        minChroma: Float, innerRadius: Float,
    ): Int
    private external fun nSharpen(
        bitmap: Bitmap, amount: Float, threshold: Float, maxShift: Float,
    ): Boolean
    private external fun nFramesMerged(h: Long): Int
    private external fun nEstimatedSigma(h: Long): Float
    private external fun nDestroy(h: Long)
    private external fun nCreate(
        width: Int, height: Int, cfa: IntArray, black: IntArray, white: Int,
        tolerance: Float, minSigma: Float,
    ): Long

    companion object {
        @Volatile
        private var available: Boolean? = null

        /** Whether the native library loaded. Falls back to Kotlin if not. */
        fun isAvailable(): Boolean {
            available?.let { return it }
            val ok = try {
                System.loadLibrary("multiframe")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "native merge unavailable, using Kotlin path", e)
                false
            }
            available = ok
            return ok
        }

        fun create(
            width: Int,
            height: Int,
            profile: SensorProfile,
            params: BayerMergeParams = BayerMergeParams(),
        ): NativeMerge? {
            if (!isAvailable()) return null
            val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
            // nCreate is an instance method on the JNI side, so a zero handle
            // instance is used purely to reach it. It allocates nothing.
            val h = NativeMerge(0L, width, height, profile).nCreate(
                width, height, profile.cfaPattern, black, profile.whiteLevel,
                params.noiseTolerance, params.minNoiseSigma,
            )
            if (h == 0L) return null
            return NativeMerge(h, width, height, profile)
        }
    }
}
