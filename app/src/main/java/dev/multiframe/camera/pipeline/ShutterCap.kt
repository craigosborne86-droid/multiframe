package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Shutter priority for a camera that meters with the phone's own
 * auto-exposure.
 *
 * ### The problem
 *
 * Left to itself the camera's auto-exposure lengthens the shutter before it
 * raises the gain, and a still scene at dusk is exposed at 1/30 s in every
 * frame of the burst. A hand cannot hold 1/30 s on a 24mm lens: each frame
 * is smeared a few pixels, the merge aligns smeared frames to a smeared
 * reference, and the picture is soft at every ISO. The burst exists to take
 * the noise out of eight fast frames; it cannot take the blur out of eight
 * slow ones. So in AUTO the frame is exposed no longer than a cap -- 1/60 s,
 * `CaptureModes.AUTO_EXPOSURE_NS` -- and the gain makes up the light, and
 * the merge makes up the gain.
 *
 * ### Why it has to be a follower and not a setting
 *
 * Camera2 has no "longest exposure" for auto-exposure to honour. The only
 * ways to bound the shutter are to bound the frame duration -- which the raw
 * stream's own rate already does at 1/30 s and cannot tighten on a stream
 * that reads out at 30 fps -- or to take the exposure by hand. So this takes
 * it by hand, but only when auto-exposure has asked for more than the cap,
 * and it keeps the camera's meter as the authority for as long as it can:
 *
 * - **Auto.** The camera meters. Every result is checked; the moment its
 *   exposure runs past the cap the light it chose (ISO times exposure) is
 *   re-split as the cap and the ISO that keeps the light, and the frame that
 *   result described is measured for its mean level, which becomes the
 *   target.
 * - **Held.** The sensor runs at the cap. Each frame exposed at the held
 *   values is measured against the target and the ISO alone is corrected,
 *   which holds the level the camera's meter chose while the scene changes.
 *   When the ISO would fall under the sensor's minimum the scene has
 *   brightened past what the cap needs, and the camera's meter is handed
 *   the exposure back; if it asks for more than the cap again it is taken
 *   again. When the ISO would run past the sensor's maximum, the cap gives
 *   way -- the shutter lengthens toward what the light needs, up to the
 *   frame -- because a dark frame is not a photograph either, and the
 *   blur is the smaller loss once the gain is spent. That is the same
 *   decision `StabilityGate.sweepExposure` makes for a mosaic.
 *
 * Pure: it is told what the sensor did and what the frame measured, and it
 * answers with what to ask for next. The stream owns the requests.
 */
class ShutterCap(
    /** The longest a frame may be exposed while the ISO can make up the light. */
    val capNs: Long,
    private val isoMin: Int,
    private val isoMax: Int,
    /** The shortest exposure the sensor takes; the cap is never under it. */
    sensorMinExposureNs: Long,
    /** The longest a frame can be: the stream's frame interval. */
    private val maxExposureNs: Long,
) {
    init {
        require(capNs > 0) { "a cap has to be a length of time" }
        require(isoMax >= isoMin && isoMin > 0) { "ISO range $isoMin..$isoMax" }
    }

    /** The cap as the sensor can honour it. */
    val effectiveCapNs: Long = maxOf(capNs, sensorMinExposureNs).coerceAtMost(maxExposureNs.coerceAtLeast(capNs))

    /**
     * The exposure being held, or null while the camera's meter has it.
     * Written on the reader thread as frames land, read wherever the
     * repeating request is rebuilt.
     */
    @Volatile
    var held: Held? = null
        private set

    /** Frames in a row that were not the hold's own. */
    private var unmatched = 0

    val isHeld: Boolean get() = held != null

    /** What the sensor is asked for while the cap holds. */
    data class Held(
        val iso: Int,
        val exposureNs: Long,
        /** The mean level, 0..1 of white, the camera's meter chose; what the hold keeps. */
        val targetLevel: Float,
    )

    /** Whether the hold has had to let the shutter past the cap for the light. */
    val stretched: Boolean get() = held?.let { it.exposureNs > effectiveCapNs } ?: false

    /** What the stream should do after a measurement. */
    sealed interface Step {
        /** Nothing to change. */
        data object Keep : Step

        /** Ask the sensor for these. */
        data class Adjust(val held: Held) : Step

        /** Give the exposure back to the camera's meter. */
        data object Release : Step
    }

    /**
     * A result from the camera's own meter, while it has the exposure.
     * Returns the hold to take, or null where the meter's choice stands --
     * already inside the cap, or the sensor has no gain left to trade.
     * [level] is the mean level of the frame the result describes.
     */
    fun onAutoResult(iso: Int, exposureNs: Long, level: Float): Held? {
        if (held != null) return held
        if (iso <= 0 || exposureNs <= 0) return null
        if (exposureNs <= (effectiveCapNs * ENGAGE_MARGIN).toLong()) return null
        val split = split(iso.toDouble() * exposureNs) ?: return null
        // The meter is already spending every stop of gain: shortening the
        // shutter would only darken the frame.
        if (split.first >= isoMax && iso >= isoMax) return null
        val h = Held(split.first, split.second, level.coerceIn(MIN_LEVEL, 1f))
        held = h
        unmatched = 0
        return h
    }

    /**
     * A frame exposed at the held values, and its measured level. The
     * frame has to be the hold's own: a frame at some other ISO or exposure
     * is the sensor still catching up with the last request and says
     * nothing about this one, so it is skipped.
     */
    fun onHeldFrame(iso: Int, exposureNs: Long, level: Float): Step {
        val h = held ?: return Step.Keep
        if (iso <= 0 || exposureNs <= 0) return Step.Keep
        val own = matches(iso, h.iso) && matches(exposureNs.toDouble(), h.exposureNs.toDouble())
        if (!own && ++unmatched < MAX_UNMATCHED) return Step.Keep
        // A sensor that never reports the values it was asked for -- a gain
        // table with coarse steps -- would otherwise leave the hold waiting
        // for a frame that never comes. After enough of them, the frame is
        // taken as the hold's, at the values the sensor says it used.
        unmatched = 0
        val measured = level.coerceAtLeast(MIN_LEVEL)
        val ratio = h.targetLevel / measured
        val stops = ln(ratio.toDouble()) / ln(2.0)
        if (abs(stops) < DEADBAND_STOPS) return Step.Keep

        // No more than a stop and a half at a step: a single frame's reading
        // is a single frame's, and a scene that has changed by four stops is
        // reached in three frames rather than overshot in one.
        val step = stops.coerceIn(-MAX_STEP_STOPS, MAX_STEP_STOPS)
        val light = iso.toDouble() * exposureNs * 2.0.pow(step)
        val wanted = light / effectiveCapNs
        if (wanted < isoMin * RELEASE_FRACTION) {
            // Bright enough that the meter will keep the shutter inside the
            // cap on its own. Its exposure again; if it asks for more than
            // the cap, it is taken again.
            held = null
            return Step.Release
        }
        val split = split(light) ?: return Step.Keep
        val next = Held(split.first, split.second, h.targetLevel)
        if (next.iso == h.iso && next.exposureNs == h.exposureNs) return Step.Keep
        held = next
        return Step.Adjust(next)
    }

    /**
     * The highlight guard changed its pull while the cap held: the frame
     * should land [deltaStops] stops darker than the target so far (or
     * brighter, for a negative value). The guard speaks to the camera's
     * meter through exposure compensation, which a held exposure does not
     * hear; the target moves instead, so the frame lands where the guard
     * asked.
     *
     * The sign is stated because it was got wrong once: the stream passed
     * the change in its pull, which is negative stops, and every stop the
     * guard took off the frame the hold put back on twice over -- a room
     * with a lamp in it went to the sensor's ceiling in eight ticks.
     */
    fun retarget(deltaStops: Float) {
        val h = held ?: return
        held = h.copy(targetLevel = (h.targetLevel * 2.0.pow(-deltaStops.toDouble())).toFloat().coerceIn(MIN_LEVEL, 1f))
    }

    /** Lets go of the exposure: the meter has it. */
    fun release() {
        held = null
    }

    /**
     * ISO times exposure into the pair the cap allows: the cap and the ISO
     * that keeps the light, or, where that ISO is past the sensor, the
     * sensor's maximum and the exposure the light needs -- the cap giving
     * way, up to the frame. Null for no light at all.
     */
    private fun split(light: Double): Pair<Int, Long>? {
        if (light <= 0.0) return null
        val isoAtCap = light / effectiveCapNs
        if (isoAtCap <= isoMax) {
            return isoAtCap.roundToInt().coerceIn(isoMin, isoMax) to effectiveCapNs
        }
        val exposure = (light / isoMax).toLong().coerceIn(effectiveCapNs, maxExposureNs.coerceAtLeast(effectiveCapNs))
        return isoMax to exposure
    }

    private fun matches(actual: Int, asked: Int): Boolean =
        abs(actual - asked) <= asked * MATCH_TOLERANCE

    private fun matches(actual: Double, asked: Double): Boolean =
        abs(actual - asked) <= asked * MATCH_TOLERANCE

    companion object {
        /** The meter may run this far past the cap before the cap takes over: a twentieth. */
        const val ENGAGE_MARGIN = 1.05

        /** A frame within this of the target is left alone: a tenth of a stop. */
        const val DEADBAND_STOPS = 0.1

        /** The most one frame's reading moves the hold. */
        const val MAX_STEP_STOPS = 1.5

        /**
         * The hold lets go when the light would want an ISO under this share
         * of the sensor's minimum: the meter can then keep the shutter inside
         * the cap by itself, with a margin so the two do not trade it back
         * and forth at the boundary.
         */
        const val RELEASE_FRACTION = 0.8

        /** The sensor reports its settings with rounding; within this they are the ones asked for. */
        const val MATCH_TOLERANCE = 0.06

        /** Frames not the hold's own before one is taken as it anyway. */
        const val MAX_UNMATCHED = 8

        /** A level under this is a black frame, and dividing by it is not metering. */
        const val MIN_LEVEL = 1e-4f

        /**
         * The mean level of a frame from its luma histogram over equal bins
         * of 0..1, as the ring measures it.
         */
        fun meanLevel(histogram: IntArray): Float {
            var total = 0L
            var sum = 0.0
            val bins = histogram.size
            for (i in 0 until bins) {
                val n = histogram[i]
                if (n <= 0) continue
                total += n
                sum += n.toDouble() * ((i + 0.5) / bins)
            }
            return if (total == 0L) 0f else (sum / total).toFloat()
        }
    }
}
