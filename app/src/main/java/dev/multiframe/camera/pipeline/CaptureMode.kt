package dev.multiframe.camera.pipeline

import kotlin.math.roundToInt

/** What the user is trying to photograph. */
enum class CaptureMode {
    /** Let the scene decide. */
    AUTO,

    /** Everything is still; spend time to gather light. */
    NIGHT,

    /** Something is moving; spend light to stop it. */
    ACTION;

    val label: String
        get() = when (this) {
            AUTO -> "AUTO"
            NIGHT -> "NIGHT"
            ACTION -> "ACTION"
        }

    fun next(): CaptureMode = entries[(ordinal + 1) % entries.size]
}

/**
 * The settings a mode implies for a particular scene.
 */
data class CapturePlan(
    val frames: Int,
    /** Longest single exposure allowed, to keep motion from smearing. */
    val maxExposureNs: Long,
    /** Whether the highlight guard may pull exposure down. */
    val protectHighlights: Boolean,
    /** What the mode resolved to, which for AUTO is the interesting part. */
    val resolved: CaptureMode,
    val reason: String,
)

/**
 * Turning an intention into numbers.
 *
 * Every piece of machinery this needs already exists -- the ring decides how
 * many frames are available, the exposure strategy decides how far highlights
 * can be protected, the merge decides what a burst is worth. What was missing
 * was a way for a person to say what they are photographing, since the right
 * answers are contradictory: a night scene wants many frames and long
 * exposures, and a moving subject wants the opposite of both.
 *
 * ### Why more frames is not simply better
 *
 * Signal-to-noise improves as the square root of frame count, so going from 8
 * to 32 buys one stop. That is worth having on a still scene. On a moving one
 * it is worth nothing: the merge's robustness weighting rejects frames where
 * the subject has moved, so those extra frames contribute nothing and the burst
 * window simply gets longer, making the rejection worse. Beyond a point a
 * longer burst actively costs quality.
 */
object CaptureModes {

    /** Below this mean brightness, a scene is dark enough to want night mode. */
    const val DARK_THRESHOLD = 0.10f

    /** Long enough to gather light, short enough that a held phone stays sharp. */
    const val NIGHT_EXPOSURE_NS = 250_000_000L

    /** Fast enough to stop ordinary movement. */
    const val ACTION_EXPOSURE_NS = 4_000_000L

    /** Ordinary handheld limit before shake shows. */
    const val AUTO_EXPOSURE_NS = 33_000_000L

    /**
     * Frames worth taking for a moving subject.
     *
     * Deliberately few. The merge rejects frames where the subject has moved,
     * so extra ones contribute nothing while lengthening the window and making
     * the rejection worse.
     */
    const val ACTION_FRAMES = 4

    fun plan(
        mode: CaptureMode,
        analysis: SceneAnalysis,
        availableFrames: Int,
        requestedFrames: Int,
    ): CapturePlan {
        val ceiling = availableFrames.coerceAtLeast(1)

        val resolved = when (mode) {
            CaptureMode.AUTO -> resolveAuto(analysis)
            else -> mode
        }

        return when (resolved) {
            CaptureMode.NIGHT -> CapturePlan(
                // Everything the ring can give: the scene is still, so every
                // extra frame is a real improvement.
                frames = ceiling,
                maxExposureNs = NIGHT_EXPOSURE_NS,
                // A dark scene has no highlights worth protecting, and pulling
                // exposure would spend the shadow detail the mode exists for.
                protectHighlights = false,
                resolved = CaptureMode.NIGHT,
                reason = if (mode == CaptureMode.AUTO) "scene is dark" else "night mode",
            )

            CaptureMode.ACTION -> CapturePlan(
                frames = minOf(ACTION_FRAMES, ceiling),
                maxExposureNs = ACTION_EXPOSURE_NS,
                protectHighlights = true,
                resolved = CaptureMode.ACTION,
                reason = "freezing motion",
            )

            CaptureMode.AUTO -> CapturePlan(
                frames = requestedFrames.coerceIn(1, ceiling),
                maxExposureNs = AUTO_EXPOSURE_NS,
                protectHighlights = true,
                resolved = CaptureMode.AUTO,
                reason = "ordinary scene",
            )
        }
    }

    /**
     * What AUTO settles on.
     *
     * Only ever chooses between ordinary and night. It never chooses ACTION,
     * because whether something is moving is not visible in a brightness
     * histogram -- guessing it from the scene would be wrong as often as right,
     * and wrong in the expensive direction. Motion is something the user knows
     * and the camera does not.
     */
    fun resolveAuto(analysis: SceneAnalysis): CaptureMode =
        if (analysis.highlightLevel < DARK_THRESHOLD) CaptureMode.NIGHT else CaptureMode.AUTO

    /**
     * Stops of shadow recovery this plan has bought, for display.
     *
     * The number that makes the mode's benefit legible: it is what the burst
     * has earned, not a promise.
     */
    fun shadowStops(plan: CapturePlan): Float =
        ExposureStrategy.shadowRecoveryStops(plan.frames)

    /** Roughly how long the burst will take, at the sensor's frame rate. */
    fun burstMillis(plan: CapturePlan, frameIntervalNs: Long): Int {
        val interval = maxOf(frameIntervalNs, plan.maxExposureNs.coerceAtMost(frameIntervalNs))
        return ((plan.frames * interval) / 1_000_000.0).roundToInt()
    }
}
