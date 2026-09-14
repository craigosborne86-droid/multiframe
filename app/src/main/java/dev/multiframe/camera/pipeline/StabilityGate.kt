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
 * which is what the gyroscope is compared against, poll by poll. The rule
 * gives an answer for the exposure in hand; the clamps say what a hand can do
 * with it. The floor is six degrees a second: the first field test's floor of
 * two was a tripod's number -- a hand at 105mm, with its pulse and its tremor,
 * peaks at three to five degrees a second poll to poll and never completed a
 * hold. Six admits the hand and still refuses the pan, which is ten to twenty.
 * Honestly stated, six degrees a second at 1/250 s on the telephoto is five
 * sensor pixels of blur -- but that is the peak of a single gyroscope sample
 * in the poll, not the exposure's mean, and the tile lands on the canvas at
 * half to two-thirds scale; the shutter floor the mosaic locks at (1/250 s,
 * see `ZslRawStream.lockForSweep`) is what keeps it there and not at forty.
 * The cap is twelve, so that a very short exposure does not make a wobble the
 * merge's alignment has to chase acceptable.
 *
 * The attitude supplies a second reading of the same thing: how far the
 * reticle moved on the canvas between two polls. That is held to what the
 * gyroscope's ceiling would move it in the same time, so the two measures
 * agree by construction; it is the one that works when the gyroscope is
 * absent.
 *
 * Pure, so the arithmetic is checkable without a phone in the hand.
 */
object StabilityGate {

    /** How much motion blur a tile is allowed, in sensor pixels. */
    const val MAX_BLUR_PIXELS = 2f

    /** The angular speed threshold is never below this -- six degrees a second -- or a hand could never satisfy it. */
    const val MIN_ANGULAR_SPEED = 0.105f

    /** Nor above this, twelve degrees a second, or a short exposure would accept a visible wobble. */
    const val MAX_ANGULAR_SPEED = 0.21f

    /** The fallback where the lens's geometry is unknown: six degrees a second. */
    const val DEFAULT_ANGULAR_SPEED = 0.105f

    /**
     * Reticle movement allowed between two polls where the gate has no
     * angular ceiling to derive it from, as a fraction of the placed tile's
     * width per nominal poll.
     */
    const val STILL_FRACTION = 0.02f

    /** Polls that must read steady, one after another, before a hold counts. */
    const val MIN_STEADY_POLLS = 3

    /**
     * The shutter floor for a telephoto burst, in nanoseconds: 1/250 s on the
     * reference telephoto. Auto exposure on a dim scene will happily choose
     * 1/30 s on a lens where a hand's tremor moves the image forty pixels in
     * that time; the mosaic asks for the exposure it needs and lets the ISO
     * rise to pay for it, because the merge removes noise and nothing removes
     * blur.
     */
    const val SHUTTER_FLOOR_NS = 4_000_000L

    /** The lens the floor is stated for: a 105mm-equivalent telephoto. */
    const val SHUTTER_FLOOR_REFERENCE_MM = 105

    /** The floor is never longer than 1/60 s on any lens, nor shorter than 1/500 s. */
    const val SHUTTER_FLOOR_MAX_NS = 16_666_666L
    const val SHUTTER_FLOOR_MIN_NS = 2_000_000L

    /**
     * The shutter floor for a lens, from its 35mm-equivalent focal length:
     * blur scales with focal length, so a lens half as long is allowed twice
     * the exposure for the same movement of the image. 1/250 s at 105mm,
     * about 1/57 s at 24mm, clamped to [SHUTTER_FLOOR_MIN_NS]..[SHUTTER_FLOOR_MAX_NS].
     */
    fun shutterFloorNs(equivalent35mm: Int): Long {
        if (equivalent35mm <= 0) return SHUTTER_FLOOR_NS
        val scaled = SHUTTER_FLOOR_NS.toDouble() * SHUTTER_FLOOR_REFERENCE_MM / equivalent35mm
        return scaled.toLong().coerceIn(SHUTTER_FLOOR_MIN_NS, SHUTTER_FLOOR_MAX_NS)
    }

    /**
     * The ISO past which the shutter floor gives way. At the floor, a dim
     * scene asks for an ISO the sensor may not have, and a frame at the
     * ceiling is a black frame with noise in it: the third field test's
     * telephoto at 1/250 s in the evening saw nothing to register and nothing
     * to keep. Past this the floor stretches instead.
     */
    const val LOW_LIGHT_ISO = 3200

    /** The floor stretches by at most this factor: 1/250 s to 1/60 s on the telephoto. */
    const val LOW_LIGHT_STRETCH = 4.0

    /** And never past this, on any lens: 1/60 s. */
    const val LOW_LIGHT_MAX_NS = 16_666_666L

    /**
     * A stretched shutter is allowed only to a hand this still, four degrees
     * a second, and the gate holds every node to it for the capture. A longer
     * exposure is more blur per degree; the stillness has to pay for it.
     */
    const val LOW_LIGHT_ANGULAR_SPEED = 0.0698f

    /** What the mosaic's lock pins the sensor at. */
    data class SweepExposure(
        val iso: Int,
        val exposureNs: Long,
        /** Whether the floor was stretched for the light; the gate tightens when it was. */
        val stretched: Boolean,
    )

    /**
     * What to pin a mosaic's sensor at, given what auto exposure converged on.
     * Null where the converged exposure is already at or under the lens's
     * floor. Otherwise the floor -- raised to the sensor's own shortest
     * exposure where that is longer -- and the ISO scaled to keep the light,
     * `iso * exposure / floor`, clamped to the sensor's range.
     *
     * Where keeping the light at the floor would need more than
     * [LOW_LIGHT_ISO], and [steady] says the hand is still enough for it,
     * the floor stretches instead: to the exposure that brings the ISO back
     * to [LOW_LIGHT_ISO], no more than [LOW_LIGHT_STRETCH] times the floor,
     * never past [LOW_LIGHT_MAX_NS], and never longer than what auto
     * exposure had chosen. Starving the sensor guarantees a black frame; a
     * longer exposure on a still hand is a photograph.
     */
    fun sweepExposure(
        iso: Int,
        exposureNs: Long,
        equivalent35mm: Int,
        isoMin: Int,
        isoMax: Int,
        sensorMinExposureNs: Long = 0L,
        steady: Boolean = false,
    ): SweepExposure? {
        val floor = maxOf(shutterFloorNs(equivalent35mm), sensorMinExposureNs)
        if (exposureNs <= floor) return null
        val light = iso.toDouble() * exposureNs
        val isoAtFloor = light / floor
        val ceiling = minOf(LOW_LIGHT_ISO, isoMax)
        if (isoAtFloor <= ceiling || !steady) {
            return SweepExposure(isoAtFloor.toLong().coerceIn(isoMin.toLong(), isoMax.toLong()).toInt(), floor, stretched = false)
        }
        val stretched = (light / ceiling).toLong()
            .coerceAtMost((floor * LOW_LIGHT_STRETCH).toLong())
            .coerceAtMost(LOW_LIGHT_MAX_NS)
            .coerceAtMost(exposureNs)
            .coerceAtLeast(floor)
        val isoStretched = (light / stretched).toLong().coerceIn(isoMin.toLong(), isoMax.toLong())
        return SweepExposure(isoStretched.toInt(), stretched, stretched = stretched > floor)
    }

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
