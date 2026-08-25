package dev.multiframe.camera.pipeline

import android.content.ComponentCallbacks2

/** What to do when the system says memory is short. */
enum class PressureResponse {
    /** Carry on. */
    NONE,

    /** Give up the ring but keep the camera: capture continues, without ZSL. */
    RELEASE_RING,

    /** Give up everything holding memory. */
    RELEASE_ALL,
}

/**
 * Deciding when to hand memory back.
 *
 * The raw ring is the largest allocation in the app by a wide margin -- up to
 * 765 MB, which is more than most apps use in total. Holding it while the system
 * is short of memory is antisocial in a way that has a specific consequence:
 * Android does not ask twice, it kills the process, and the user loses the
 * viewfinder rather than losing zero shutter lag.
 *
 * Giving the ring back voluntarily degrades the app to sequential capture, which
 * still takes photographs. That is a far better outcome than being killed, and
 * it is reversible the moment pressure lifts.
 *
 * The thresholds are pure so they can be tested; nothing here needs a running
 * system to check.
 */
object MemoryPressure {

    /**
     * What a trim level asks for.
     *
     * `RUNNING_MODERATE` is deliberately ignored. It fires routinely on a busy
     * device and responding to it would mean surrendering the ring during
     * ordinary use, which is the whole feature. The response begins at
     * `RUNNING_LOW`, where the system is actually struggling.
     */
    fun responseTo(level: Int): PressureResponse = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> PressureResponse.RELEASE_ALL
        level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> PressureResponse.RELEASE_ALL
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> PressureResponse.RELEASE_ALL
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> PressureResponse.RELEASE_RING
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> PressureResponse.RELEASE_ALL
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> PressureResponse.RELEASE_RING
        else -> PressureResponse.NONE
    }

    /**
     * Whether a ring should be opened at all right now.
     *
     * Checked before allocating rather than only after failing: an allocation
     * that succeeds and then gets the process killed is worse than one that was
     * never attempted.
     */
    fun canAffordRing(availableBytes: Long, requiredBytes: Long, headroom: Float = 1.6f): Boolean =
        availableBytes > (requiredBytes * headroom).toLong()
}
