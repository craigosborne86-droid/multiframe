package dev.multiframe.camera.pipeline

/**
 * One RAW_SENSOR output configuration as the camera describes it.
 *
 * [stallDurationNs] is the decisive field. A non-zero stall means producing a
 * raw frame blocks the other streams for that long, so a continuous raw stream
 * would stutter the viewfinder. Zero means raw can ride along with preview at
 * the sensor's own rate, which is the entire premise of a ZSL raw ring.
 */
data class RawStreamConfig(
    val width: Int,
    val height: Int,
    val minFrameDurationNs: Long,
    val stallDurationNs: Long,
) {
    val pixels: Long get() = width.toLong() * height.toLong()

    val fps: Double
        get() = if (minFrameDurationNs <= 0L) 0.0 else 1_000_000_000.0 / minFrameDurationNs

    override fun toString(): String =
        "${width}x$height %.1ffps stall=%.1fms".format(fps, stallDurationNs / 1e6)
}

/**
 * Whether continuous raw streaming is viable, and at what size.
 *
 * The fallback is not a failure path bolted on afterwards: on hardware that
 * stalls on raw, sequential capture is the correct behaviour, and this type is
 * what makes that choice explicit and testable without a camera attached.
 */
sealed interface ZslDecision {
    data class Stream(val config: RawStreamConfig) : ZslDecision
    data class Fallback(val reason: String) : ZslDecision

    val engaged: Boolean get() = this is Stream

    val describe: String
        get() = when (this) {
            is Stream -> "ZSL raw stream: $config"
            is Fallback -> "sequential capture: $reason"
        }
}

object ZslPolicy {

    /**
     * Slowest raw stream still worth calling zero shutter lag. Below this the
     * burst window stops being tight enough for the frames to agree, which is
     * the whole reason for streaming rather than capturing sequentially.
     */
    const val MAX_FRAME_DURATION_NS = 50_000_000L // 20 fps

    /**
     * Chooses the largest zero-stall raw configuration the camera offers, or
     * explains why there isn't one.
     *
     * Kept free of Camera2 types so the decision can be unit tested against
     * capability profiles the development device does not have.
     */
    fun evaluate(
        supportsRaw: Boolean,
        configs: List<RawStreamConfig>,
        maxFrameDurationNs: Long = MAX_FRAME_DURATION_NS,
    ): ZslDecision {
        if (!supportsRaw) return ZslDecision.Fallback("camera reports no RAW capability")
        if (configs.isEmpty()) return ZslDecision.Fallback("no RAW_SENSOR output sizes")

        val zeroStall = configs.filter { it.stallDurationNs == 0L }
        if (zeroStall.isEmpty()) {
            val best = configs.minBy { it.stallDurationNs }
            return ZslDecision.Fallback(
                "raw stalls %.1fms per frame".format(best.stallDurationNs / 1e6)
            )
        }

        val fastEnough = zeroStall.filter {
            it.minFrameDurationNs in 1..maxFrameDurationNs
        }
        if (fastEnough.isEmpty()) {
            val best = zeroStall.maxBy { it.fps }
            return ZslDecision.Fallback("raw streams at only %.1f fps".format(best.fps))
        }

        // Largest sensor area wins: a ZSL ring is only worth having at the
        // resolution the DNG is meant to carry.
        return ZslDecision.Stream(fastEnough.maxWith(compareBy({ it.pixels }, { it.fps })))
    }
}

/**
 * Sizes the native ring against physical memory.
 *
 * The Dalvik heap cap does not apply here -- the pool is mmap'd outside it --
 * but physical RAM and the low-memory killer very much do. At 4080x3072 a slot
 * is 25 MB, so a 32-deep ring reserves 800 MB and needs a device with room to
 * spare.
 */
object RawRingBudget {

    const val MIN_CAPACITY = 4
    const val MAX_CAPACITY = 32

    /**
     * Slots kept free beyond the burst so the camera keeps taking frames while
     * a merge holds the rest. A burst may not consume the whole ring: doing so
     * would stall intake for the two seconds a merge runs, leaving nothing
     * buffered for the next shutter press. Preview is unaffected either way --
     * it is a separate output, not a ring slot.
     */
    const val WRITE_HEADROOM = 4

    /**
     * Headroom for a ring of [capacity]. A flat four slots is most of a small
     * ring: on a busy device that can only afford ten, it would cut the burst
     * to six and make the shallow ring shallower still. Two slots are enough
     * for the writer never to block, since it recycles rather than waits.
     */
    fun headroomFor(capacity: Int): Int =
        (capacity / 8).coerceIn(2, WRITE_HEADROOM)

    /** Share of currently free RAM the pool may reserve. */
    private const val AVAILABLE_FRACTION = 0.35

    /** Hard ceiling as a share of total RAM, for devices reporting lots free. */
    private const val TOTAL_FRACTION = 0.20

    /**
     * Peak native working set of the merge itself: two float accumulators and
     * the uint16 reference, per pixel. Reserved before the ring gets its share.
     */
    const val MERGE_BYTES_PER_PIXEL = 10L

    fun frameBytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 2L

    /**
     * Ring depth that holds [desired] frames if memory allows, and the largest
     * depth that does otherwise. Returns 0 when the device cannot afford even
     * [MIN_CAPACITY] slots, which is a refusal rather than a small ring: below
     * that depth the pool cannot outrun its own reader and the sequential path
     * is the better camera.
     */
    fun capacityFor(
        width: Int,
        height: Int,
        availableRamBytes: Long,
        totalRamBytes: Long,
        desired: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 0
        val perFrame = frameBytes(width, height)
        if (perFrame <= 0L) return 0

        val mergeWorkingSet = width.toLong() * height.toLong() * MERGE_BYTES_PER_PIXEL
        val budget = minOf(
            (availableRamBytes * AVAILABLE_FRACTION).toLong(),
            (totalRamBytes * TOTAL_FRACTION).toLong(),
        ) - mergeWorkingSet

        if (budget <= 0L) return 0
        val affordable = (budget / perFrame).toInt()
        if (affordable < MIN_CAPACITY) return 0
        return affordable.coerceAtMost(desired.coerceAtMost(MAX_CAPACITY))
    }

    /** Ring depth needed to serve a burst of [burst] without starving the writer. */
    fun depthForBurst(burst: Int): Int =
        (burst + WRITE_HEADROOM).coerceIn(MIN_CAPACITY, MAX_CAPACITY)

    /** Largest burst a ring of [capacity] can serve while still accepting frames. */
    fun burstForDepth(capacity: Int): Int =
        (capacity - headroomFor(capacity)).coerceAtLeast(1)
}
