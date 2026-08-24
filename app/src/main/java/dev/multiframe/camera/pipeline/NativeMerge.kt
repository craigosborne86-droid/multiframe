package dev.multiframe.camera.pipeline

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
            estimatedSigmaAtMid = 0f,
        )
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
    private external fun nFramesMerged(h: Long): Int
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
