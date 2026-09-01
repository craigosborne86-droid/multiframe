package dev.multiframe.camera.pipeline

import android.util.Log

/**
 * What it costs to reach the GPU, which is the half of the Vulkan question that
 * can be answered without writing a GPU develop.
 *
 * The plan of record once said Vulkan. The log records why it was dropped: the
 * merge was assumed to be dominated by accumulation, the timer was split, and
 * accumulation turned out to be 9% of it. The note written then was that the
 * idea was premature rather than wrong — `AHardwareBuffer` still imports into a
 * Vulkan pipeline without a copy, and that still matters *when there is
 * something on the GPU worth the crossing*.
 *
 * The develop is now that something. It is two passes, per-pixel, and the
 * largest thing left in a capture. So this measures the round trip a GPU
 * develop would have to pay before it did any work at all: 25 MB of merged CFA
 * in, 50 MB of RGBA out, and a shader between them chosen to be too cheap to
 * matter.
 *
 * **It does not measure whether a GPU develop would be faster**, and a figure
 * from here must never be quoted as though it did. It answers the prior
 * question, which is cheaper to ask: if the crossing alone costs more than the
 * CPU develop, no kernel wins it back and the idea is closed. If it is cheap,
 * the expensive question becomes worth asking.
 */
object GpuCrossing {

    /**
     * Where the frame lives while the GPU reads it, which on a phone is the
     * whole question — the CPU and GPU share one pool, so whether a copy
     * happens at all depends on which of these the driver offers.
     */
    enum class Route(val code: Int) {
        /** Host-visible staging copied to and from device-local buffers. */
        STAGING(0),

        /** One allocation that is both device-local and host-visible. */
        SHARED(1),

        /** An `AHardwareBuffer` imported into Vulkan, which is RingBuffer.h's claim. */
        IMPORTED(2),

        /**
         * Shared, but host-cached, with the flush and invalidate paid by hand.
         *
         * Without this the comparison is confounded: [SHARED] takes the first
         * coherent memory, which is uncached on at least one driver, and an
         * imported buffer asks for `CPU_READ_OFTEN` and gets cached memory. The
         * difference between them would then be the cache policy wearing the
         * import's name.
         */
        CACHED(3),
    }

    /** Microseconds, per round, for one route. */
    class Crossing(val upload: Long, val dispatch: Long, val download: Long) {
        /** What a GPU develop would pay before running a single useful instruction. */
        val roundTrip: Long get() = upload + download
        val total: Long get() = upload + dispatch + download
    }

    class Result(
        val setupMicros: Long,
        val mismatchesA: Long,
        val mismatchesB: Long,
        val a: List<Crossing>,
        val b: List<Crossing>,
    )

    /**
     * The GPU, and which routes to it exist on this device.
     *
     * Read this before the benchmark and log it beside any figure. Every route
     * is optional, and a driver offering no host-visible device-local memory
     * changes what the numbers below even mean.
     */
    fun probe(): String = if (!NativeMerge.isAvailable()) "native library unavailable" else nProbe()

    /**
     * Times two routes against each other, alternating within a round.
     *
     * Pass the same route twice for the A/A. Null when either route could not
     * be built — [probe] says which and why.
     */
    fun bench(width: Int, height: Int, a: Route, b: Route, rounds: Int): Result? {
        if (!NativeMerge.isAvailable()) return null
        val raw = nBench(width, height, a.code, b.code, rounds) ?: return null
        if (raw.size < 3 + rounds * 6) {
            Log.w(TAG, "short result: ${raw.size} values for $rounds rounds")
            return null
        }
        fun at(round: Int, slot: Int) = Crossing(
            raw[3 + round * 6 + slot * 3],
            raw[3 + round * 6 + slot * 3 + 1],
            raw[3 + round * 6 + slot * 3 + 2],
        )
        return Result(
            setupMicros = raw[0],
            mismatchesA = raw[1],
            mismatchesB = raw[2],
            a = List(rounds) { at(it, 0) },
            b = List(rounds) { at(it, 1) },
        )
    }

    private const val TAG = "GpuCrossing"

    private external fun nProbe(): String
    private external fun nBench(
        width: Int, height: Int, routeA: Int, routeB: Int, rounds: Int,
    ): LongArray?
}
