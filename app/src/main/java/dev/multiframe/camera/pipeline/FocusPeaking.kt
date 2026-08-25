package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Marks what is in focus.
 *
 * Manual focus on a phone is close to guesswork: the screen is small, bright,
 * and shows a preview that has already been sharpened, so the difference
 * between nearly right and right is invisible. Peaking replaces judgement with
 * a reading -- it marks the pixels where local contrast is high, which is where
 * the image is actually resolving detail.
 *
 * ### Contrast, normalised by brightness
 *
 * Raw gradient magnitude is the obvious measure and it is misleading: a bright
 * area produces larger differences than a dark one at the same sharpness,
 * because the same relative change spans more codes. Peaking on raw gradient
 * lights up every highlight and ignores the shadows, which is precisely the
 * opposite of useful when focusing on something dark.
 *
 * Dividing by local brightness measures *relative* contrast, which is what
 * sharpness actually is, and makes the reading comparable across the frame.
 *
 * ### Why the sensor rather than the preview
 *
 * The preview has been through the ISP's own sharpening, which manufactures
 * edges that are not in the optical image. Peaking on that measures the
 * processing rather than the focus. The raw ring holds what the sensor saw.
 */
object FocusPeaking {

    /**
     * Below this relative contrast, nothing is marked.
     *
     * High on purpose. A defocused edge still has contrast -- blur spreads it
     * over more pixels rather than removing it -- so a low threshold marks a
     * soft image *more* heavily than a sharp one, since more pixels carry some
     * gradient. The threshold has to sit above what defocus leaves behind.
     */
    const val DEFAULT_THRESHOLD = 0.22f

    /** Dark enough that its contrast is noise rather than detail. */
    private const val BLACK_FLOOR = 12f

    /**
     * Writes an edge-strength map into [out], one byte per pixel of [plane].
     *
     * Values are 0 where nothing is resolving and rise toward 255 with relative
     * contrast, so a caller can either threshold them or fade them.
     */
    fun detect(
        plane: Plane,
        out: ByteArray,
        threshold: Float = DEFAULT_THRESHOLD,
    ) {
        require(out.size >= plane.width * plane.height) { "output is too small" }
        val w = plane.width
        val h = plane.height
        java.util.Arrays.fill(out, 0, w * h, 0)
        if (w < 3 || h < 3) return

        val data = plane.data
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val here = data[row + x].toInt() and 0xFF

                // Differences across the pixel in both directions. A single
                // direction misses edges that run parallel to it.
                val dx = abs(
                    (data[row + x + 1].toInt() and 0xFF) - (data[row + x - 1].toInt() and 0xFF)
                )
                val dy = abs(
                    (data[row + w + x].toInt() and 0xFF) - (data[row - w + x].toInt() and 0xFF)
                )
                val gradient = max(dx, dy).toFloat()

                // Relative to local brightness, so a dark subject reads the
                // same as a bright one at equal sharpness.
                val brightness = max(here.toFloat(), BLACK_FLOOR)
                val relative = gradient / brightness

                if (relative < threshold) continue
                // Mapped so the threshold is barely visible and twice it is
                // solid, which is the range a person actually focuses within.
                val strength = ((relative - threshold) / threshold).coerceIn(0f, 1f)
                out[row + x] = (strength * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }
    }

    /**
     * How sharply the frame is resolving, as a single number.
     *
     * Mean *squared* relative gradient, which is the classic autofocus measure
     * and the only one of the obvious candidates that actually works.
     *
     * Counting pixels above a threshold does not: blur spreads an edge across
     * more pixels, so a defocused frame can have more of them marked than a
     * focused one. Summing the gradient does not either: blurring a step into a
     * ramp leaves its total variation unchanged, so the sum barely moves.
     * Squaring rewards concentration, which is exactly what focus is -- the
     * same contrast delivered across fewer pixels.
     */
    fun focusScore(plane: Plane): Float {
        val w = plane.width
        val h = plane.height
        if (w < 3 || h < 3) return 0f

        val data = plane.data
        var total = 0.0
        var counted = 0L
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val here = data[row + x].toInt() and 0xFF
                val dx = abs(
                    (data[row + x + 1].toInt() and 0xFF) - (data[row + x - 1].toInt() and 0xFF)
                )
                val dy = abs(
                    (data[row + w + x].toInt() and 0xFF) - (data[row - w + x].toInt() and 0xFF)
                )
                val relative = max(dx, dy).toFloat() / max(here.toFloat(), BLACK_FLOOR)
                total += (relative * relative).toDouble()
                counted++
            }
        }
        return if (counted == 0L) 0f else (total / counted).toFloat()
    }

    /** Fraction of the frame the overlay marks, for reporting. */
    fun markedFraction(mask: ByteArray, pixels: Int): Float {
        if (pixels <= 0) return 0f
        var marked = 0
        for (i in 0 until min(pixels, mask.size)) {
            if (mask[i].toInt() != 0) marked++
        }
        return marked.toFloat() / pixels
    }

    /**
     * Whether [candidate] is better focused than [reference].
     *
     * Used to tell a user which way to turn, and deliberately given a dead
     * band: two readings a fraction of a percent apart are the same reading,
     * and an indicator that flickers between "sharper" and "softer" on noise is
     * worse than none.
     */
    fun isSharper(candidate: Float, reference: Float, deadband: Float = 0.002f): Boolean =
        candidate > reference + deadband
}
