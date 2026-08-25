package dev.multiframe.camera.pipeline

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "Multiframe"

/**
 * Counters describing how the ring behaved while streaming.
 *
 * [droppedNoSlot] and [cameraGaps] answer different questions. The first is our
 * fault -- every slot was locked or in flight when a frame arrived. The second
 * is a gap in the sensor timestamps, meaning a frame was lost before it ever
 * reached us. A ZSL ring is only honest if both are reported.
 */
data class RingStats(
    val pushed: Long,
    /** Frames the ring could not take while idle. The one that must be zero. */
    val droppedNoSlot: Long,
    val recycled: Long,
    val cameraGaps: Long,
    val maxIntervalNs: Long,
    val lastIntervalNs: Long,
    val intervalSumNs: Long,
    val intervalCount: Long,
    /** Frames refused because a merge held the slots. By design, not a fault. */
    val droppedWhileLocked: Long = 0,
) {
    val meanIntervalNs: Long
        get() = if (intervalCount == 0L) 0L else intervalSumNs / intervalCount

    val measuredFps: Double
        get() = if (meanIntervalNs <= 0L) 0.0 else 1_000_000_000.0 / meanIntervalNs

    override fun toString(): String =
        "pushed=$pushed dropped=$droppedNoSlot heldOff=$droppedWhileLocked " +
            "gaps=$cameraGaps fps=%.1f maxInterval=%.1fms".format(
                measuredFps, maxIntervalNs / 1e6,
            )
}

/**
 * A locked set of ring slots, newest first.
 *
 * Locking copies nothing: it marks slots so the pool will not recycle them, and
 * hands out direct ByteBuffers over the pool's own memory. That is what makes
 * the shutter handover independent of frame size -- it costs a mutex and a scan
 * of at most 32 entries whether frames are 25 MB or 25 KB.
 */
class RingSnapshot internal constructor(
    private val ring: RawRing,
    val slots: IntArray,
    val timestampsNs: LongArray,
    val frameNumbers: LongArray,
) : AutoCloseable {

    val count: Int get() = slots.size

    /** Direct buffer over slot [i]'s pixels. Valid until [close]. */
    fun buffer(i: Int): ByteBuffer? = ring.slotBuffer(slots[i])

    /** Row stride of a pooled frame: the pool stores rows packed. */
    val rowStride: Int get() = ring.width * 2

    /** Span from oldest to newest frame in this snapshot. */
    val spanNs: Long
        get() = if (timestampsNs.size < 2) 0L
        else timestampsNs.max() - timestampsNs.min()

    override fun close() = ring.unlock()
}

/**
 * Rolling pool of raw frames in native memory.
 *
 * The whole ring is off the Java heap: at 4080x3072 a frame is 25 MB, so a
 * 16-deep ring is 400 MB against a 256 MB Dalvik heap cap. [push] takes the
 * camera's own direct buffer and allocates nothing on the Java side, so the
 * streaming path stays allocation-free at 30 fps.
 */
class RawRing private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
    val capacity: Int,
) : AutoCloseable {

    private var closed = false
    private val statsScratch = LongArray(9)

    /** Bytes the pool reserves. Lazily committed, so this is an upper bound. */
    val reservedBytes: Long
        get() = width.toLong() * height.toLong() * 2L * capacity.toLong()

    /**
     * Copies one frame into the pool. Returns the slot index, or -1 when the
     * ring had no free slot -- reported, never silently swallowed.
     *
     * Called on the ImageReader thread at frame rate. Nothing here allocates.
     */
    fun push(frame: ByteBuffer, rowStride: Int, timestampNs: Long, frameNumber: Long): Int {
        if (closed) return -1
        return nPush(handle, frame, rowStride, timestampNs, frameNumber)
    }

    /**
     * Locks the [count] newest frames for the merge. The camera keeps writing
     * into the remaining slots, so preview and streaming continue underneath.
     */
    fun lockNewest(count: Int): RingSnapshot {
        if (closed || count <= 0) {
            return RingSnapshot(this, IntArray(0), LongArray(0), LongArray(0))
        }
        val slots = IntArray(count)
        val stamps = LongArray(count)
        val frames = LongArray(count)
        val n = nLockNewest(handle, count, slots, stamps, frames)
        return RingSnapshot(
            this,
            slots.copyOf(n),
            stamps.copyOf(n),
            frames.copyOf(n),
        )
    }

    internal fun unlock() {
        if (!closed) nUnlock(handle)
    }

    internal fun slotBuffer(slot: Int): ByteBuffer? {
        if (closed) return null
        return nSlotBuffer(handle, slot)?.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun lockedCount(): Int = if (closed) 0 else nLockedCount(handle)

    /** Frames a shutter press could take right now. */
    fun readyCount(): Int = if (closed) 0 else nReadyCount(handle)

    /**
     * Histograms the newest frame without consuming it.
     *
     * Highlight protection has to run while streaming: by the time the shutter
     * is pressed the frames already exist, so an exposure decision taken then
     * would be about the next photograph. Taking a snapshot to measure would
     * consume frames the shutter is meant to use, so this reads in place.
     */
    fun histogramNewest(
        bins: IntArray,
        profile: SensorProfile,
        stride: Int = 8,
    ): Boolean {
        if (closed) return false
        val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
        return nHistogramNewest(handle, bins, stride, black, profile.whiteLevel, profile.cfaPattern)
    }

    fun stats(): RingStats {
        if (closed) return RingStats(0, 0, 0, 0, 0, 0, 0, 0, 0)
        nStats(handle, statsScratch)
        return RingStats(
            pushed = statsScratch[0],
            droppedNoSlot = statsScratch[1],
            recycled = statsScratch[2],
            cameraGaps = statsScratch[3],
            maxIntervalNs = statsScratch[4],
            lastIntervalNs = statsScratch[5],
            intervalSumNs = statsScratch[6],
            intervalCount = statsScratch[7],
            droppedWhileLocked = statsScratch[8],
        )
    }

    /**
     * A downscaled luma view of the newest frame, without consuming it.
     *
     * Focus peaking wants the sensor's own image: the preview has been through
     * the ISP's sharpening, so peaking it would measure the processing rather
     * than the focus.
     */
    fun lumaNewest(out: ByteArray, width: Int, height: Int, profile: SensorProfile): Boolean {
        if (closed) return false
        val black = IntArray(4) { profile.blackLevel.getOrElse(it) { 0 } }
        return nLumaNewest(handle, out, width, height, black, profile.whiteLevel)
    }

    fun resetStats() {
        if (!closed) nResetStats(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        nDestroy(handle)
        handle = 0
    }

    private external fun nCreate(
        width: Int, height: Int, capacity: Int, nominalIntervalNs: Long,
    ): Long

    private external fun nDestroy(h: Long)
    private external fun nPush(
        h: Long, buffer: ByteBuffer, rowStride: Int, timestampNs: Long, frameNumber: Long,
    ): Int

    private external fun nLockNewest(
        h: Long, count: Int, outSlots: IntArray, outTimestamps: LongArray,
        outFrameNumbers: LongArray,
    ): Int

    private external fun nUnlock(h: Long)
    private external fun nSlotBuffer(h: Long, slot: Int): ByteBuffer?
    private external fun nStats(h: Long, out: LongArray)
    private external fun nResetStats(h: Long)
    private external fun nLockedCount(h: Long): Int
    private external fun nReadyCount(h: Long): Int
    private external fun nLumaNewest(
        h: Long, out: ByteArray, outWidth: Int, outHeight: Int,
        black: IntArray, white: Int,
    ): Boolean
    private external fun nHistogramNewest(
        h: Long, outBins: IntArray, stride: Int,
        black: IntArray, white: Int, cfa: IntArray,
    ): Boolean

    companion object {
        /**
         * Allocates a pool of [capacity] frames, backing off to smaller rings if
         * the mapping fails rather than giving up on ZSL entirely.
         */
        fun create(
            width: Int,
            height: Int,
            capacity: Int,
            nominalIntervalNs: Long,
        ): RawRing? {
            if (!NativeMerge.isAvailable()) return null

            var want = capacity
            while (want >= RawRingBudget.MIN_CAPACITY) {
                // A zero-handle instance only reaches the native entry point;
                // it owns nothing until nCreate returns.
                val h = RawRing(0L, width, height, want)
                    .nCreate(width, height, want, nominalIntervalNs)
                if (h != 0L) {
                    if (want < capacity) {
                        Log.w(TAG, "ring shrank to $want slots; $capacity would not map")
                    }
                    return RawRing(h, width, height, want)
                }
                want /= 2
            }
            Log.e(TAG, "ring allocation failed at every capacity")
            return null
        }
    }
}
