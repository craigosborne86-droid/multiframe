package dev.multiframe.camera.pipeline

import kotlin.math.roundToInt

/** What the user is trying to photograph. */
enum class CaptureMode {
    PHOTO, NIGHT;
    val label: String get() = name
    val isSingleCapture: Boolean get() = true
    fun next(): CaptureMode = if (this == NIGHT) PHOTO else NIGHT
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
    /**
     * NIGHT's still burst, solved from the hand ([NightPlanner]), or null
     * where NIGHT is the fixed plan: no gyroscope, no manual sensor, or a
     * NIGHT that AUTO resolved to rather than the user chose.
     */
    val night: NightPlan? = null,
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

    /**
     * NIGHT's shutter cap where it has no [NightPlan]: past the frame
     * interval, so no cap, and the burst is the meter's own frames from the
     * ring. With a plan the cap is the same -- the repeating request is never
     * exposed past the frame interval -- and the long exposures are the
     * still burst's ([NightPlanner]).
     */
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
        /** NIGHT's plan from the hand, where the hardware can shoot one. */
        night: NightPlan? = null,
    ): CapturePlan {
        val ceiling = availableFrames.coerceAtLeast(1)
        val resolved = if (mode == CaptureMode.PHOTO) resolveAuto(analysis) else mode

        return when (resolved) {
            // NIGHT chosen by name, with a hand to read: the planner's count
            // and still burst. The shutter cap is the fixed plan's.
            CaptureMode.NIGHT if mode == CaptureMode.NIGHT && night != null -> CapturePlan(
                frames = minOf(ceiling, night.frames),
                maxExposureNs = NIGHT_EXPOSURE_NS,
                protectHighlights = false,
                resolved = CaptureMode.NIGHT,
                reason = "night mode: ${night.motion.label.lowercase()}",
                night = night,
            )

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
                reason = if (mode == CaptureMode.PHOTO) "scene is dark" else "night mode",
            )

            CaptureMode.PHOTO -> CapturePlan(
                frames = requestedFrames.coerceIn(1, minOf(ceiling, AUTO_MAX_FRAMES)),
                maxExposureNs = AUTO_EXPOSURE_NS,
                protectHighlights = true,
                resolved = CaptureMode.PHOTO,
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
        if (analysis.highlightLevel < DARK_THRESHOLD) CaptureMode.NIGHT else CaptureMode.PHOTO

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
