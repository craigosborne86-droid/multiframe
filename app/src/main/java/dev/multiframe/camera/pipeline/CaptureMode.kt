package dev.multiframe.camera.pipeline

import kotlin.math.roundToInt

/** What the user is trying to photograph. */
enum class CaptureMode {
    /** Let the scene decide. */
    AUTO,

    /** Everything is still; spend time to gather light. */
    NIGHT,

    /** Something is moving; spend light to stop it. */
    ACTION,

    /**
     * A picture larger than the sensor: a grid of telephoto tiles, each a
     * merged burst, composited into one canvas. The mode's exposure is the
     * mosaic's own -- locked, floored -- so as a capture plan it is AUTO's.
     * See `MosaicCapture`.
     */
    SUPER_RES;

    val label: String
        get() = when (this) {
            AUTO -> "AUTO"
            NIGHT -> "NIGHT"
            ACTION -> "ACTION"
            SUPER_RES -> "SUPER RES"
        }

    /** Whether this mode is a single burst from the ring, as against a mosaic of them. */
    val isSingleCapture: Boolean get() = this != SUPER_RES

    fun next(): CaptureMode = entries[(ordinal + 1) % entries.size]
}

/**
 * The settings a mode implies for a particular scene.
 */
data class CapturePlan(
    val frames: Int,
    /**
     * Longest single exposure allowed, to keep motion from smearing. The
     * stream holds auto-exposure to it ([ZslRawStream.setExposureCap]); a
     * value at or past the frame interval is no cap.
     */
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

    /** Fast enough to stop ordinary movement: 1/250 s. */
    const val ACTION_EXPOSURE_NS = 4_000_000L

    /**
     * The longest a handheld frame is exposed in AUTO: 1/60 s.
     *
     * Was 1/30 s, the frame interval, which is to say no cap at all; and the
     * field test's dusk pictures were soft in every frame of the burst,
     * because the camera's meter lengthens the shutter before it raises the
     * gain and a hand does not hold 1/30 s on a 24mm lens. The merge takes
     * the noise out of eight fast frames; it cannot take the blur out of
     * eight slow ones. So the frame is capped and the gain makes up the
     * stop, and the burst's sqrt(N) makes up the gain. The cap is a cap on
     * the *meter*: where the sensor's gain runs out it gives way, see
     * `ShutterCap`. Applied by the stream through `ZslRawStream.setExposureCap`.
     */
    const val AUTO_EXPOSURE_NS = 16_666_667L

    /**
     * The most frames an AUTO burst takes from the ring.
     *
     * Signal-to-noise grows as the square root of the count: eight frames
     * are a stop and a half, ten a little more, and twenty are only a
     * further half stop for a burst window twice as long -- twice the hand's
     * drift for the alignment to chase and twice the chance the subject
     * moved. Night mode, on a scene that is still, takes what the ring has.
     */
    const val AUTO_MAX_FRAMES = 10

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
            // A Super Res tile is exposed by the mosaic's lock, not by a plan;
            // as a plan it is the ordinary one.
            CaptureMode.AUTO, CaptureMode.SUPER_RES -> resolveAuto(analysis)
            else -> mode
        }

        return when (resolved) {
            CaptureMode.NIGHT -> CapturePlan(
                // Everything the ring can give: the scene is still, so every
                // extra frame is a real improvement. Unless the mode the user
                // chose was AUTO and the histogram resolved it here: then the
                // hand is a hand, and the frame count and the shutter cap are
                // AUTO's -- ten frames, a sixtieth -- with night's guard,
                // since a dark scene has no highlights to protect. NIGHT
                // chosen by name is the still scene, and gathers light.
                frames = if (mode == CaptureMode.NIGHT) ceiling else minOf(ceiling, AUTO_MAX_FRAMES),
                maxExposureNs = if (mode == CaptureMode.NIGHT) NIGHT_EXPOSURE_NS else AUTO_EXPOSURE_NS,
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

            CaptureMode.AUTO, CaptureMode.SUPER_RES -> CapturePlan(
                frames = requestedFrames.coerceIn(1, minOf(ceiling, AUTO_MAX_FRAMES)),
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
