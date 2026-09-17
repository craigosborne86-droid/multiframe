package dev.multiframe.camera.pipeline

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "NativeSubPixel"

/**
 * Sub-pixel accumulation on the native side, in strips.
 *
 * The same algorithm as [SubPixelAccumulator], which is the reference, with
 * the memory arranged for a photograph rather than a test image: the output
 * lives in an anonymous mmap and the accumulators exist for one strip of rows
 * at a time. See SubPixel.cpp for the arrangement and its cost.
 *
 * ### The buffers must outlive this
 *
 * [setReference] and [addFrame] hold *pointers* into the buffers they are
 * given -- the strip-major sweep reads every frame once per strip, so the
 * frames cannot be copied in and released. The caller keeps the burst open
 * until [finish] has returned. The ZSL ring's buffers are mmap-backed and
 * stay valid until the burst is closed, which is the case this is built for.
 *
 * Returns null from [create] where the native library is absent or the device
 * has too little memory for the canvas, and the caller falls back to the
 * ordinary merge.
 */
class NativeSubPixel private constructor(
    private var handle: Long,
    val sensorWidth: Int,
    val sensorHeight: Int,
) : AutoCloseable {

    val width: Int get() = sensorWidth * 2
    val height: Int get() = sensorHeight * 2

    fun setReference(buffer: ByteBuffer, strideBytes: Int): Boolean {
        check(handle != 0L) { "closed" }
        require(buffer.isDirect) { "frames must be direct buffers" }
        return nSetReference(handle, buffer, strideBytes)
    }

    fun addFrame(buffer: ByteBuffer, strideBytes: Int, field: AlignmentField, gainScale: Float = 1f): Boolean {
        check(handle != 0L) { "closed" }
        require(buffer.isDirect) { "frames must be direct buffers" }
        return nAddFrame(handle, buffer, strideBytes, field.dx, field.dy, field.fracDx, field.fracDy, gainScale)
    }

    /**
     * Runs the accumulation. Returns the canvas as interleaved 16-bit RGB in
     * sensor units, little-endian, backed by the native mmap -- valid until
     * [close].
     */
    fun finish(): Result? {
        check(handle != 0L) { "closed" }
        if (!nFinish(handle)) return null
        val out = nOutput(handle) ?: return null
        val stats = nStats(handle) ?: return null
        return Result(
            width, height,
            out.order(ByteOrder.LITTLE_ENDIAN),
            holeFraction = stats[0],
            interiorHoleFraction = stats[1],
            meanContribution = stats[2],
            framesMerged = stats[3].toInt(),
            interiorHolesPerChannel = if (stats.size >= 7) floatArrayOf(stats[4], stats[5], stats[6])
            else FloatArray(3) { stats[1] },
        )
    }

    class Result(
        val width: Int,
        val height: Int,
        /** `width * height * 3` uint16 values, interleaved RGB, sensor units. */
        val rgb16: ByteBuffer,
        val holeFraction: Float,
        val interiorHoleFraction: Float,
        val meanContribution: Float,
        val framesMerged: Int,
        /** Red, green, blue. See [SubPixelResult.interiorHolesPerChannel]. */
        val interiorHolesPerChannel: FloatArray,
    ) {
        /** Whether the measured coverage honours the bounds the prediction was made against. */
        val acceptable: Boolean
            get() = SubPixelCoverage.Prediction.acceptable(
                interiorHolesPerChannel[0], interiorHolesPerChannel[1], interiorHolesPerChannel[2],
            )
    }

    /**
     * What the develop did: the exposure it chose and where its time went.
     * Milliseconds, because they are for the log and the breakdown line.
     */
    class DevelopStats(
        val exposureGain: Float,
        val exposureMillis: Float,
        val developMillis: Float,
        val sharpenMillis: Float,
        val encodeMillis: Float,
    )

    /**
     * Develops the finished canvas and writes the JPEG down [fd].
     *
     * The linear chain of [LinearDeveloper], natively, from the canvas's own
     * pages straight to the descriptor: no Bitmap, nothing of image size on
     * the Java heap. **Consumes the canvas** -- its pages are released as they
     * are read, so [finish]'s buffer reads as zero afterwards and only [close]
     * remains to be called.
     *
     * Returns null if the develop or the encode declined, in which case
     * whatever reached the descriptor is not a JPEG and the caller should fall
     * back to the ordinary merge it has alongside.
     */
    fun developToJpeg(
        fd: Int,
        profile: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
        shading: ShadingMap? = null,
        quality: Int = 95,
    ): DevelopStats? {
        check(handle != 0L) { "closed" }
        val grid = shading?.rgbGrid()
        val stats = runCatching {
            nDevelopToJpeg(
                handle, fd,
                LinearDeveloper.channelBlack(profile), profile.whiteLevel,
                LinearDeveloper.channelGains(color), color.matrix,
                grid, shading?.columns ?: 0, shading?.rows ?: 0,
                toneArray(params), sharpenArray(params), quality,
            )
        }.onFailure { Log.w(TAG, "linear develop failed", it) }.getOrNull() ?: return null
        return DevelopStats(stats[0], stats[1], stats[2], stats[3], stats[4])
    }

    /**
     * The same develop into packed RGB bytes, `width * height * 3`, without
     * the encode and without consuming the canvas. For the parity test.
     */
    fun developToArray(
        profile: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
        shading: ShadingMap? = null,
    ): ByteArray? {
        check(handle != 0L) { "closed" }
        val grid = shading?.rgbGrid()
        return runCatching {
            nDevelopToArray(
                handle,
                LinearDeveloper.channelBlack(profile), profile.whiteLevel,
                LinearDeveloper.channelGains(color), color.matrix,
                grid, shading?.columns ?: 0, shading?.rows ?: 0,
                toneArray(params), sharpenArray(params),
            )
        }.onFailure { Log.w(TAG, "linear develop failed", it) }.getOrNull()
    }

    /** The tone parameters in the order SubPixel.cpp's readDevelopParams unpacks them. */
    private fun toneArray(p: DevelopParams) = floatArrayOf(
        p.exposureGain, p.shoulderKnee, p.contrast, p.highlightDesaturation,
        p.desaturationStart, p.blackPoint, p.highlightPercentile, p.highlightTarget,
        p.localTone, p.shoulderWhite, p.meteredGain,
    )

    /** The sharpen, then the chroma smoothing, in the order SubPixel.cpp unpacks them. */
    private fun sharpenArray(p: DevelopParams) = floatArrayOf(
        if (p.sharpen.enabled) p.sharpen.amount else 0f, p.sharpen.threshold, p.sharpen.maxShift,
        p.chroma.radius.toFloat(), p.chroma.lumaSigma, if (p.chroma.enabled) p.chroma.amount else 0f,
    )

    override fun close() {
        if (handle != 0L) {
            nDestroy(handle)
            handle = 0L
        }
    }

    private external fun nSetReference(h: Long, buffer: ByteBuffer, strideBytes: Int): Boolean
    private external fun nAddFrame(
        h: Long, buffer: ByteBuffer, strideBytes: Int,
        dx: IntArray, dy: IntArray, fracDx: FloatArray, fracDy: FloatArray, gainScale: Float,
    ): Boolean
    private external fun nFinish(h: Long): Boolean
    private external fun nOutput(h: Long): ByteBuffer?
    private external fun nStats(h: Long): FloatArray?
    private external fun nDevelopToJpeg(
        h: Long, fd: Int, black: FloatArray, white: Int, wb: FloatArray, matrix: FloatArray,
        shading: FloatArray?, shadingColumns: Int, shadingRows: Int,
        tone: FloatArray, sharpen: FloatArray, quality: Int,
    ): FloatArray?
    private external fun nDevelopToArray(
        h: Long, black: FloatArray, white: Int, wb: FloatArray, matrix: FloatArray,
        shading: FloatArray?, shadingColumns: Int, shadingRows: Int,
        tone: FloatArray, sharpen: FloatArray,
    ): ByteArray?
    private external fun nDestroy(h: Long)
    private external fun nCreate(
        sensorW: Int, sensorH: Int, cfa: IntArray, black: IntArray,
        white: Int, noiseTolerance: Float, minSigma: Float, hotPixelThreshold: Float,
    ): Long

    companion object {
        /**
         * The accumulator tile the strip sweep claims, in canvas rows and
         * columns. A knob for the speed harness alone -- `SuperResDeviceTest`
         * compares sizes on one burst -- and the shipped values, 32 by 256,
         * are the ones the harness chose. See parallelTiles in SubPixel.cpp.
         */
        fun setTile(rows: Int, cols: Int) {
            if (NativeMerge.isAvailable()) nSetTile(rows, cols)
        }

        const val TILE_ROWS = 32
        const val TILE_COLS = 256

        @JvmStatic
        private external fun nSetTile(rows: Int, cols: Int)

        fun create(
            sensorWidth: Int,
            sensorHeight: Int,
            profile: SensorProfile,
            params: BayerMergeParams = BayerMergeParams(),
            hotPixelThreshold: Float = HotPixels.DEFAULT_THRESHOLD,
        ): NativeSubPixel? {
            if (!NativeMerge.isAvailable()) return null
            val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
            // nCreate is an instance method on the JNI side, as NativeMerge's
            // is; a zero-handle instance reaches it and allocates nothing.
            val h = try {
                NativeSubPixel(0L, sensorWidth, sensorHeight).nCreate(
                    sensorWidth, sensorHeight, profile.cfaPattern, black,
                    profile.whiteLevel, params.noiseTolerance, params.minNoiseSigma,
                    hotPixelThreshold,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "native sub-pixel unavailable", e)
                0L
            }
            if (h == 0L) return null
            return NativeSubPixel(h, sensorWidth, sensorHeight)
        }
    }
}
