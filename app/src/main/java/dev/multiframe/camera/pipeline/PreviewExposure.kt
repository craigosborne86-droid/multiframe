package dev.multiframe.camera.pipeline

/** Preview is an exposure aid; the original manual duration remains the still exposure. */
object PreviewExposure {
    const val FRAME_NS = 33_333_333L
    fun time(requestedNs: Long): Long = requestedNs.coerceAtMost(FRAME_NS)
    fun iso(requestedIso: Int, requestedNs: Long, maximumIso: Int): Int =
        (requestedIso.toDouble() * requestedNs / time(requestedNs).coerceAtLeast(1))
            .coerceIn(requestedIso.toDouble(), maximumIso.coerceAtLeast(requestedIso).toDouble()).toInt()
}
