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
    private external fun nDestroy(h: Long)
    private external fun nCreate(
        sensorW: Int, sensorH: Int, cfa: IntArray, black: IntArray,
        white: Int, noiseTolerance: Float, minSigma: Float,
    ): Long

    companion object {
        fun create(
            sensorWidth: Int,
            sensorHeight: Int,
            profile: SensorProfile,
            params: BayerMergeParams = BayerMergeParams(),
        ): NativeSubPixel? {
            if (!NativeMerge.isAvailable()) return null
            val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
            // nCreate is an instance method on the JNI side, as NativeMerge's
            // is; a zero-handle instance reaches it and allocates nothing.
            val h = try {
                NativeSubPixel(0L, sensorWidth, sensorHeight).nCreate(
                    sensorWidth, sensorHeight, profile.cfaPattern, black,
                    profile.whiteLevel, params.noiseTolerance, params.minNoiseSigma,
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
