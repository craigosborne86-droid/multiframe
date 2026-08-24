package dev.multiframe.camera.pipeline

/**
 * Sizes the burst ring against the heap the device actually gives this process.
 *
 * A fixed frame count is a portability trap: twelve 2048x1536 YUV frames is
 * about 56 MB of ring alone, which is fine on a flagship and fatal on a phone
 * with a 96 MB heap limit.
 */
data class FrameSize(val width: Int, val height: Int)

object MemoryBudget {

    /**
     * Analysis resolutions to try, largest first. Requesting one of these does
     * not guarantee it; CameraX falls back to the nearest the camera supports.
     */
    val RESOLUTION_LADDER = listOf(
        FrameSize(4080, 3072),
        FrameSize(3264, 2448),
        FrameSize(2048, 1536),
        FrameSize(1600, 1200),
        FrameSize(1280, 960),
        FrameSize(960, 720),
        FrameSize(640, 480),
    )

    /** 4:2:0 is 12 bits per pixel. */
    fun bytesPerFrame(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * 3L / 2L

    /**
     * Peak working set of the merge itself, per pixel: two float accumulators,
     * the normalised result, the ARGB int buffer and the output bitmap.
     */
    private const val MERGE_BYTES_PER_PIXEL = 20L

    /** Each buffered frame is held twice at peak: in the ring and in the snapshot. */
    private const val FRAME_COPIES = 2L

    /** Headroom left for Compose, CameraX and the framework. */
    private const val USABLE_FRACTION = 0.55

    /**
     * Memory sets the ceiling, but alignment and merge cost scales with pixel
     * count, so latency caps it independently. At this size a burst takes a few
     * seconds; four times the pixels would take four times as long.
     */
    val LATENCY_CAP = FrameSize(2048, 1536)

    /**
     * Largest ladder resolution whose merge working set plus [minFrames] of ring
     * still fits the heap. Reducing frame count alone is not enough on a small
     * heap: the merge's own buffers can exceed the budget by themselves, so
     * resolution has to come down too.
     */
    fun recommendedAnalysisSize(
        maxHeapBytes: Long,
        minFrames: Int = 4,
        cap: FrameSize = LATENCY_CAP,
    ): FrameSize {
        val usable = (maxHeapBytes * USABLE_FRACTION).toLong()
        val capPixels = cap.width.toLong() * cap.height.toLong()
        for (size in RESOLUTION_LADDER) {
            if (size.width.toLong() * size.height.toLong() > capPixels) continue
            val pixels = size.width.toLong() * size.height.toLong()
            val need = pixels * MERGE_BYTES_PER_PIXEL +
                bytesPerFrame(size.width, size.height) * FRAME_COPIES * minFrames
            if (need <= usable) return size
        }
        return RESOLUTION_LADDER.last()
    }

    fun recommendedCapacity(
        width: Int,
        height: Int,
        maxHeapBytes: Long,
        desired: Int,
        minimum: Int = 2,
    ): Int {
        if (width <= 0 || height <= 0) return minimum
        val pixels = width.toLong() * height.toLong()
        val usable = (maxHeapBytes * USABLE_FRACTION).toLong()
        val mergeWorkingSet = pixels * MERGE_BYTES_PER_PIXEL
        if (usable <= mergeWorkingSet) return minimum

        val perFrame = bytesPerFrame(width, height) * FRAME_COPIES
        if (perFrame <= 0L) return minimum

        val affordable = ((usable - mergeWorkingSet) / perFrame).toInt()
        return affordable.coerceIn(minimum, desired)
    }
}
