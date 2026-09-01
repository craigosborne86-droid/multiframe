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
        // Five stages hide behind one figure here, and four of them have never
        // been timed. Every one is a full pass over twelve and a half
        // megapixels, so any of them could be the largest.
        val tExposure = System.currentTimeMillis()
        val gain = if (params.exposureGain > 0f) {
            params.exposureGain
        } else {
            nAutoExposure(
                merged, width, height, profile.cfaPattern, black, profile.whiteLevel,
                color.gains, params.highlightPercentile, params.highlightTarget,
            )
        }
        val tBitmap = System.currentTimeMillis()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tNative = System.currentTimeMillis()
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
        val tDefringe = System.currentTimeMillis()
        if (params.defringe.enabled) {
            val altered = nDefringe(
                bitmap, params.defringe.edgeThreshold,
                params.defringe.tolerance, params.defringe.strength,
                params.defringe.minChroma, params.defringe.innerRadius,
            )
            if (altered > 0) Log.i(TAG, "defringed $altered pixels")
        }
        val tSharpen = System.currentTimeMillis()
        if (params.sharpen.enabled) {
            nSharpen(
                bitmap, params.sharpen.amount, params.sharpen.threshold,
                params.sharpen.maxShift,
            )
        }
        val tEnd = System.currentTimeMillis()
        Log.i(TAG, "native develop gain=%.2f".format(gain))
        Log.i(
            TAG,
            "develop stages: autoexposure %dms, bitmap %dms, render %dms, defringe %dms, sharpen %dms".format(
                tBitmap - tExposure, tNative - tBitmap, tDefringe - tNative,
                tSharpen - tDefringe, tEnd - tSharpen,
            ),
        )
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
    private external fun nReleaseScratch()
    private external fun nPrepassBench(
        width: Int, height: Int, cfa: IntArray, black: IntArray, white: Int,
        gains: FloatArray, shading: FloatArray, columns: Int, rows: Int,
        hotPixelThreshold: Float, rounds: Int,
    ): LongArray?
    private external fun nShadingBench(
        width: Int, height: Int, cfa: IntArray, gains: FloatArray,
        shading: FloatArray, columns: Int, rows: Int,
        rounds: Int, selfCheck: Boolean,
    ): LongArray?
    private external fun nToneBench(
        width: Int, height: Int, cfa: IntArray, matrix: FloatArray,
        exposureGain: Float, knee: Float, contrast: Float,
        desatStrength: Float, desatStart: Float, blackPoint: Float,
        variantA: Int, variantB: Int, rounds: Int,
    ): LongArray?
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

        /**
         * Hands back the develop scratch buffers.
         *
         * They are kept between captures so their pages are faulted in once
         * rather than on every shot, which is worth about a fifth of a second.
         * Holding roughly 150 MB while the system is short of memory is not,
         * so this is called when it asks.
         */
        fun releaseScratch() {
            if (!isAvailable()) return
            runCatching { NativeMerge(0L, 0, 0, SensorProfile.DEFAULT).nReleaseScratch() }
                .onFailure { Log.w(TAG, "could not release develop scratch", it) }
        }

        /**
         * Sharpens a bitmap in place, without developing anything.
         *
         * Sharpening is otherwise reachable only through a full develop, which
         * spends three times as long on everything else and buries the figure.
         * It reads nothing from the accumulator, so it needs no handle -- the
         * same reason [releaseScratch] can go through an instance that owns
         * none.
         */
        internal fun sharpenInPlace(bitmap: Bitmap, params: Sharpen.Params): Boolean {
            if (!isAvailable()) return false
            return NativeMerge(0L, 0, 0, SensorProfile.DEFAULT)
                .nSharpen(bitmap, params.amount, params.threshold, params.maxShift)
        }

        /**
         * Times the shading pass against the form it replaced, in one process.
         *
         * Reachable only through a full develop otherwise, which spends four
         * times as long on the demosaic and buries the figure -- and, worse,
         * would need a second install to compare against, which is the one
         * thing this project has established it cannot measure through. Both
         * implementations live in the binary, so a round times them back to
         * back on the same cores.
         *
         * Returns `[a, b]` per round in microseconds, then the count of values
         * the two disagreed on and the largest disagreement in nano-units.
         */
        internal fun shadingBench(
            width: Int,
            height: Int,
            profile: SensorProfile,
            gains: FloatArray,
            map: ShadingMap,
            rounds: Int,
            selfCheck: Boolean,
        ): LongArray? {
            if (!isAvailable()) return null
            return NativeMerge(0L, 0, 0, profile).nShadingBench(
                width, height, profile.cfaPattern, gains,
                map.gains, map.columns, map.rows, rounds, selfCheck,
            )
        }

        /**
         * What `demosaic+tone` is made of, by leaving one piece out.
         *
         * The pass is the largest item in a capture and reports as one figure.
         * It cannot be timed in halves: they are fused so that the demosaic's
         * output never leaves the registers, and an intermediate buffer would
         * add 50 MB of traffic and be what got measured. So a variant is the
         * whole pass with one item removed, timed against the whole pass, in
         * one process and paired within a round. See [ToneAblation].
         *
         * Returns `[a, b]` per round in microseconds, then the bytes on which
         * the two outputs disagreed, then how many pixels of the harness's
         * scene landed above the knee, then the largest of those byte
         * disagreements.
         */
        internal fun toneBench(
            width: Int,
            height: Int,
            profile: SensorProfile,
            color: ColorProfile,
            params: DevelopParams,
            variantA: Int,
            variantB: Int,
            rounds: Int,
        ): LongArray? {
            if (!isAvailable()) return null
            return NativeMerge(0L, 0, 0, profile).nToneBench(
                width, height, profile.cfaPattern, color.matrix,
                params.exposureGain, params.shoulderKnee, params.contrast,
                params.highlightDesaturation, params.desaturationStart,
                params.blackPoint, variantA, variantB, rounds,
            )
        }

        /**
         * What the develop's three preparatory passes cost, and what folding
         * one of them saves.
         *
         * `black`, `hotpixels` and `shading` are three sweeps of a 50 MB plane.
         * The first and third are pure per-pixel maps and fold trivially; the
         * second sits between them and must see values black-subtracted and not
         * yet shaded, so folding all three honestly needs a two-row-lag pipeline
         * and a halo at every band edge. This prices that before it is built:
         * the second slot folds the first and third and runs hot pixels after,
         * which is the wrong picture and the right cost.
         */
        internal fun prepassBench(
            width: Int,
            height: Int,
            profile: SensorProfile,
            gains: FloatArray,
            map: ShadingMap,
            hotPixelThreshold: Float,
            rounds: Int,
        ): LongArray? {
            if (!isAvailable()) return null
            val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
            return NativeMerge(0L, 0, 0, profile).nPrepassBench(
                width, height, profile.cfaPattern, black, profile.whiteLevel,
                gains, map.gains, map.columns, map.rows, hotPixelThreshold, rounds,
            )
        }

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
