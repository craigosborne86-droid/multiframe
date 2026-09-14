package dev.multiframe.camera.pipeline

import kotlin.math.sqrt

/**
 * How still is still enough to fire a burst, in numbers the sensor reports.
 *
 * ### The problem it answers
 *
 * The first field test fired the burst the moment the reticle crossed a dot.
 * The rule asked for a quarter-second hold, but a hold was measured as "still
 * inside a radius of 5% of the frame", and a hand decelerating onto a target
 * is inside 5% of the frame for a quarter of a second while still moving a
 * degree a second. Every tile was smeared. The radius says where the reticle
 * is; it says nothing about how fast it is moving, and blur is speed.
 *
 * ### What still means
 *
 * A frame is blurred by how far the image moves during its exposure. The
 * sensor turns through `omega * t` radians in an exposure of `t` seconds at an
 * angular speed of `omega`, and the image moves `pixelsPerRadian` pixels per
 * radian -- the focal length in pixels. So the largest angular speed that
 * keeps the blur inside [maxBlurPixels] is
 *
 *     omega_max = maxBlurPixels / (t * pixelsPerRadian)
 *
 * which is what the gyroscope is compared against, poll by poll. On the 110mm
 * telephoto (about 11,500 pixels per radian) at 1/250 s that is 0.043 rad/s,
 * two and a half degrees a second: a deliberate hold, and one a hand can do.
 * At 1/30 s it would be a fifth of that, which a hand cannot -- a hand
 * holding still peaks at one to two degrees a second, poll to poll -- so the
 * threshold is floored at two degrees a second: the mosaic then accepts the
 * tremor an ordinary handheld capture at that exposure accepts, rather than
 * never firing, while still refusing the pan, which is five to twenty. And it
 * is capped at five, so that a short exposure does not make a wobble the
 * merge's alignment will have to chase acceptable.
 *
 * The registration supplies a second, independent reading of the same thing:
 * how far the frame's centre moved on the canvas between two polls. That is
 * held to [STILL_FRACTION] of a tile per poll, which at ten polls a second on
 * a 4080-pixel tile is thirty pixels a tenth of a second -- and it is the one
 * that works when the gyroscope is absent.
 *
 * Pure, so the arithmetic is checkable without a phone in the hand.
 */
object StabilityGate {

    /** How much motion blur a tile is allowed, in sensor pixels. */
    const val MAX_BLUR_PIXELS = 2f

    /** The angular speed threshold is never below this -- two degrees a second -- or a hand could never satisfy it. */
    const val MIN_ANGULAR_SPEED = 0.035f

    /** Nor above this, five degrees a second, or a short exposure would accept a visible wobble. */
    const val MAX_ANGULAR_SPEED = 0.09f

    /** The fallback where the lens's geometry is unknown: two degrees a second. */
    const val DEFAULT_ANGULAR_SPEED = 0.035f

    /**
     * Registration jitter allowed between two polls, as a fraction of the
     * placed tile's width. Independent of the gyroscope, and the whole gate
     * where there is none.
     */
    const val STILL_FRACTION = 0.0075f

    /** Polls that must read steady, one after another, before a hold counts. */
    const val MIN_STEADY_POLLS = 3

    /**
     * The image's scale in pixels per radian of rotation: the focal length in
     * pixels. From the lens's physical focal length and the sensor's physical
     * width across its pixel width; zero where either is unknown.
     */
    fun pixelsPerRadian(focalLengthMm: Float, sensorWidthMm: Float, sensorWidthPx: Int): Float {
        if (focalLengthMm <= 0f || sensorWidthMm <= 0f || sensorWidthPx <= 0) return 0f
        return focalLengthMm * (sensorWidthPx / sensorWidthMm)
    }

    /**
     * The same from a 35mm-equivalent focal length, where the physical numbers
     * are not published: a full-frame sensor is 36 mm across.
     */
    fun pixelsPerRadianFromEquivalent(equivalent35mm: Int, sensorWidthPx: Int): Float {
        if (equivalent35mm <= 0 || sensorWidthPx <= 0) return 0f
        return equivalent35mm * (sensorWidthPx / 36f)
    }

    /**
     * The largest angular speed, in radians per second, at which an exposure
     * of [exposureNs] moves the image less than [maxBlurPixels], clamped to
     * what a hand can and cannot be asked for.
     */
    fun maxAngularSpeed(
        exposureNs: Long,
        pixelsPerRadian: Float,
        maxBlurPixels: Float = MAX_BLUR_PIXELS,
    ): Float {
        if (exposureNs <= 0L || pixelsPerRadian <= 0f) return DEFAULT_ANGULAR_SPEED
        val seconds = exposureNs / 1e9f
        val raw = maxBlurPixels / (seconds * pixelsPerRadian)
        return raw.coerceIn(MIN_ANGULAR_SPEED, MAX_ANGULAR_SPEED)
    }

    /** The blur, in pixels, an exposure would carry at a given angular speed. */
    fun blurPixels(angularSpeed: Float, exposureNs: Long, pixelsPerRadian: Float): Float =
        angularSpeed * (exposureNs / 1e9f) * pixelsPerRadian

    /** Magnitude of an angular velocity vector. */
    fun speedOf(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /** The horizontal field of view in radians of a 35mm-equivalent focal length. */
    fun fieldOfViewRadians(equivalent35mm: Int): Float =
        2f * kotlin.math.atan(18f / equivalent35mm.coerceAtLeast(1))
}
