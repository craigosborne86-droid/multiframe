package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The night sky on a tripod: minutes of exposure, made of frames short enough
 * that the stars do not trail within one, aligned so they do not trail across
 * them, with the foreground merged where it stands.
 *
 * See REFERENCE §3.13 for the derivations; this file holds the pure parts --
 * the plan, the hot-pixel map and the sky mask -- so the desk can test them.
 */
object AstroPlanner {

    /** Total spans offered, in minutes. Zero is off. */
    val MINUTES: List<Int> = listOf(0, 1, 2, 4, 8, 15)

    fun next(current: Int): Int {
        val i = MINUTES.indexOf(current)
        return MINUTES[if (i < 0) 0 else (i + 1) % MINUTES.size]
    }

    /** The Earth's rotation, radians per second (sidereal). */
    const val SIDEREAL_RAD_PER_S = 7.2921e-5

    /** The most a star may drift within one frame, in pixels. */
    const val TRAIL_PIXELS = 1.5

    /** Never longer than this per frame, whatever the sensor allows: a satellite or plane spoils less. */
    const val MAX_FRAME_NS = 16_000_000_000L

    data class Plan(val exposureNs: Long, val iso: Int, val frames: Int, val trailLimitNs: Long) {
        val totalSeconds: Double get() = frames * exposureNs / 1e9
        fun reading(locale: java.util.Locale = java.util.Locale.getDefault()): String =
            "ASTRO · %d × %s · ISO %d".format(locale, frames, NightPlanner.shutterLabel(exposureNs, locale), iso)
    }

    /**
     * The frame time is the shortest of the sensor's longest exposure, the
     * trailing limit t ≤ TRAIL_PIXELS / (f_px · ω), and [MAX_FRAME_NS]. The
     * gain is the meter's light over that time, a stop under (the highlight
     * guard's standing bias, so a moonlit sky keeps its highlights), held
     * between the sensor's floor and its analog ceiling -- past the analog
     * ceiling the gain is digital and adds nothing a merge cannot.
     */
    fun solve(
        minutes: Int,
        pixelsPerRadian: Float,
        meterIso: Int,
        meterExposureNs: Long,
        exposureMaxNs: Long?,
        isoMin: Int,
        isoMax: Int,
        maxAnalogIso: Int?,
    ): Plan {
        require(minutes > 0 && pixelsPerRadian > 0f)
        val trailNs = (TRAIL_PIXELS / (pixelsPerRadian * SIDEREAL_RAD_PER_S) * 1e9).toLong()
        val exposure = min(min(exposureMaxNs ?: 1_000_000_000L, trailNs), MAX_FRAME_NS).coerceAtLeast(1_000_000L)
        val light = meterIso.toDouble() * meterExposureNs // ISO·ns the meter wanted
        val ceiling = min(maxAnalogIso ?: isoMax, isoMax)
        val iso = (0.5 * light / exposure).roundToInt().coerceIn(isoMin, max(isoMin, ceiling))
        val frames = max(2, (minutes * 60e9 / exposure).roundToInt())
        return Plan(exposure, iso, frames, trailNs)
    }
}

/**
 * Pixels that are bright in every frame are the sensor, not the sky.
 *
 * A long exposure lights up hot pixels and, on an aligned sky, a fixed hot
 * pixel lands somewhere different in every frame and draws a dotted trail.
 * A pixel is marked hot when it stands above the mean of its four same-colour
 * neighbours (two pixels away) by more than [THRESHOLD] codes in at least
 * [FRACTION] of the frames sampled; a star moves between frames and does not
 * stay hot. Marked pixels are replaced by that neighbour mean in every frame
 * before it is merged.
 */
class HotPixelMap(private val width: Int, private val height: Int) {
    private val counts = ShortArray(width * height)
    private var sampled = 0
    var hot: IntArray = IntArray(0)
        private set

    fun sample(src: FocusStack.CfaSource) {
        for (y in 2 until height - 2) for (x in 2 until width - 2) {
            val v = src.at(x, y)
            val n = (src.at(x - 2, y) + src.at(x + 2, y) + src.at(x, y - 2) + src.at(x, y + 2)) / 4
            if (v - n > THRESHOLD) counts[y * width + x]++
        }
        sampled++
    }

    /** Settles the map from the frames sampled so far. */
    fun settle(): IntArray {
        val need = max(1, (sampled * FRACTION).toInt())
        hot = counts.indices.filter { counts[it] >= need }.toIntArray()
        return hot
    }

    /** Writes the neighbour mean over each hot pixel of a frame held in [set]/[src]. */
    fun repair(src: FocusStack.CfaSource, set: (x: Int, y: Int, v: Int) -> Unit) {
        for (i in hot) {
            val x = i % width; val y = i / width
            set(x, y, (src.at(x - 2, y) + src.at(x + 2, y) + src.at(x, y - 2) + src.at(x, y + 2)) / 4)
        }
    }

    companion object {
        const val THRESHOLD = 48
        const val FRACTION = 0.75f
    }
}

/**
 * Which pixels are sky, measured from the stack itself.
 *
 * On a tripod the foreground stands still and the sky turns. Every frame
 * offers two explanations of each pixel: the tile alignment (which follows
 * the stars) and no shift at all (which fits the ground). The one whose
 * residual against the reference is smaller, summed over the stack, is the
 * pixel's motion. The difference is box-smoothed over [radius] proxy pixels
 * and mapped through a logistic, so the mask is soft at the horizon instead
 * of stair-stepped by tiles; 1 is sky, 0 is foreground.
 *
 * No model is involved: the mask is the sensor's own evidence. Where neither
 * explanation fits (a tree in the wind) the merge's rejection still applies.
 */
class SkyMask(private val proxyWidth: Int, private val proxyHeight: Int, private val radius: Int = 6) {
    private val evidence = FloatArray(proxyWidth * proxyHeight) // static residual − aligned residual
    var frames = 0
        private set

    fun add(reference: Plane, frame: Plane, field: AlignmentField) {
        val w = proxyWidth; val h = proxyHeight
        val tileW = max(1, w / field.tilesX); val tileH = max(1, h / field.tilesY)
        for (y in 0 until h) for (x in 0 until w) {
            val t = min(field.tilesY - 1, y / tileH) * field.tilesX + min(field.tilesX - 1, x / tileW)
            val r = reference.data[y * w + x].toInt() and 0xFF
            val still = frame.data[y * w + x].toInt() and 0xFF
            val ax = (x + field.dx[t]).coerceIn(0, w - 1); val ay = (y + field.dy[t]).coerceIn(0, h - 1)
            val moved = frame.data[ay * w + ax].toInt() and 0xFF
            evidence[y * w + x] += (abs(still - r) - abs(moved - r)).toFloat()
        }
        frames++
    }

    /** The mask, per proxy pixel (one per CFA cell), 0..1. */
    fun finish(): FloatArray {
        val w = proxyWidth; val h = proxyHeight
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += evidence[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        val scale = 1f / max(1, frames)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
            val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
            val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            val mean = (s / ((x1 - x0) * (y1 - y0))).toFloat() * scale
            out[y * w + x] = (1.0 / (1.0 + exp(-mean / SOFTNESS))).toFloat()
        }
        return out
    }

    companion object {
        /** Codes of mean residual difference over which the mask goes from ¼ to ¾. */
        const val SOFTNESS = 0.9f

        /** Blends the sky-aligned and the static stacks by [mask] (per CFA cell) into [out], 16-bit LE CFA. */
        fun blend(
            sky: java.nio.ByteBuffer, ground: java.nio.ByteBuffer, mask: FloatArray,
            width: Int, height: Int, out: java.nio.ByteBuffer,
        ) {
            val pw = width / 2
            val s = sky.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val g = ground.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val o = out.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until height) for (x in 0 until width) {
                val m = mask[min(height / 2 - 1, y / 2) * pw + min(pw - 1, x / 2)]
                val i = (y * width + x) * 2
                val v = m * (s.getShort(i).toInt() and 0xFFFF) + (1 - m) * (g.getShort(i).toInt() and 0xFFFF)
                o.putShort(i, v.roundToInt().coerceIn(0, 0xFFFF).toShort())
            }
        }
    }
}
