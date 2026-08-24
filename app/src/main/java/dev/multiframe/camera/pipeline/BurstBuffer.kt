package dev.multiframe.camera.pipeline

import androidx.camera.core.ImageProxy

/**
 * Rolling buffer of the most recent analyzer frames.
 *
 * Holding frames continuously is what gives zero shutter lag: the reference
 * frame already exists when the shutter is pressed rather than being captured
 * after it. Buffers are preallocated and reused, because allocating a
 * multi-megabyte frame per callback at preview frame rate churns the heap.
 */
class BurstBuffer(private val capacity: Int) {

    private val lock = Any()
    private var pool: Array<YuvFrame>? = null
    private var stamps: LongArray = LongArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    var frameWidth = 0
        private set
    var frameHeight = 0
        private set

    fun offer(image: ImageProxy) {
        synchronized(lock) {
            var p = pool
            if (p == null || p[0].width != image.width || p[0].height != image.height) {
                p = Array(capacity) { YuvFrame.allocate(image.width, image.height) }
                pool = p
                writeIndex = 0
                filled = 0
                frameWidth = image.width
                frameHeight = image.height
            }
            YuvFrame.copyInto(image, p[writeIndex])
            stamps[writeIndex] = image.imageInfo.timestamp
            writeIndex = (writeIndex + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /**
     * Newest frame first, deep copied so the analyzer can keep writing while
     * the merge runs. Returns at most [count] frames.
     */
    fun snapshot(count: Int): List<YuvFrame> {
        synchronized(lock) {
            val p = pool ?: return emptyList()
            val n = minOf(count, filled)
            val out = ArrayList<YuvFrame>(n)
            for (i in 0 until n) {
                val idx = ((writeIndex - 1 - i) % capacity + capacity) % capacity
                val src = p[idx]
                out.add(
                    YuvFrame(
                        src.width, src.height,
                        src.y.copyOf(), src.u.copyOf(), src.v.copyOf(),
                        stamps[idx],
                    )
                )
            }
            return out
        }
    }

    fun availableFrames(): Int = synchronized(lock) { filled }
}
