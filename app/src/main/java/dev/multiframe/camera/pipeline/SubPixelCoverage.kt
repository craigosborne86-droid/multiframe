package dev.multiframe.camera.pipeline

import kotlin.math.abs

/**
 * Whether a burst has the sub-pixel diversity a 2x canvas needs, decided from
 * the alignment alone, before anything is accumulated.
 *
 * ### Why this can be known in advance
 *
 * Accumulating onto the canvas costs more than the ordinary merge -- it sweeps
 * every frame once per strip -- and a burst shot from a tripod earns none of
 * it: every frame lands on the reference's own sites and the cells between
 * them stay empty. REFERENCE.md §3.6.3 says the hole fraction is the signal
 * to decline. But the hole fraction is measured after the work is done, and
 * the geometry that produces it is visible in the alignment fields before any
 * of it starts.
 *
 * A canvas cell is two sensor pixels to the sensor's one, so a frame's samples
 * land at `2 * ax - 4 * (dx + fracDx)` -- four canvas cells per proxy pixel of
 * alignment. The integer part moves a sample by a multiple of four cells,
 * which is the period of every channel's pattern on the canvas, so it changes
 * nothing about *which* cells are reached. Only the fractional part does. That
 * is the whole of what this computes: for each tile and each channel, the set
 * of positions modulo four that the reference's sites and every frame's
 * shifted sites occupy, and the fraction of the sixteen positions in a period
 * that lie within the kernel's reach of one.
 *
 * The kernel reaches one cell (`kRadius` in the accumulator), and at that
 * distance its weight is 0.135, above the 0.05 that marks a hole, so reach is
 * Euclidean distance at most one on the wrapped 4x4 grid. This is optimistic
 * about one thing: a sample the robustness weight rejects contributes less,
 * and a cell fed only by rejected samples is a hole the geometry did not
 * predict. The accumulator's own measured hole fraction is the confirmation
 * and is checked after it runs; this is the reason not to run it.
 *
 * Pure, so the tripod case and the handheld case can both be tested without a
 * frame.
 */
object SubPixelCoverage {

    /**
     * Predicted green hole fraction above which the canvas is not attempted.
     *
     * Green is the channel the resolution claim rests on -- it is sampled at
     * every other site and carries most of the luminance -- so its holes are
     * held to two percent. The quad-shift burst in §3.6.3, four frames at
     * exactly the half-pixel offsets, covers green completely; a handheld
     * burst of eight at offsets spread over the period leaves about one cell
     * in fifty, and a tripod burst leaves six in sixteen.
     */
    const val MAX_GREEN_HOLES = 0.02f

    /**
     * The same bound for red and for blue, and it is looser on purpose.
     *
     * Red and blue are sampled at every fourth site, so on the canvas each
     * frame reaches a quarter of what green reaches, and a burst of eight at
     * random offsets leaves about a fifth of their cells for the fill. That
     * sounds like a lot until it is set beside the ordinary develop, where the
     * demosaic *interpolates* red at three sites in four -- 75% -- and does it
     * from neighbours two sensor pixels away, which is four cells here. A
     * canvas with a fifth of its red cells filled from within three cells is
     * still three times the measured chroma of the picture it would otherwise
     * have been. A quarter is where it stops being that, and where the burst
     * should have been longer instead.
     */
    const val MAX_CHROMA_HOLES = 0.25f

    class Prediction(
        /** Mean over channels and tiles. */
        val holeFraction: Float,
        /** Red, green, blue. */
        val perChannel: FloatArray,
        val frames: Int,
    ) {
        val worthAttempting: Boolean
            get() = frames >= 2 &&
                perChannel[1] <= MAX_GREEN_HOLES &&
                perChannel[0] <= MAX_CHROMA_HOLES &&
                perChannel[2] <= MAX_CHROMA_HOLES

        /** Whether a finished canvas's measured holes honour the same bounds. */
        companion object {
            fun acceptable(red: Float, green: Float, blue: Float): Boolean =
                green <= MAX_GREEN_HOLES && red <= MAX_CHROMA_HOLES && blue <= MAX_CHROMA_HOLES
        }
    }

    private const val PERIOD = 4

    /**
     * Predicts the hole fraction of a canvas built from the reference plus the
     * frames whose [fields] these are. [cfa] is the sensor's 2x2 colour pattern
     * in site order, as [SensorProfile.cfaPattern] carries it.
     */
    fun predict(fields: List<AlignmentField>, cfa: IntArray): Prediction {
        val perChannel = FloatArray(3)
        if (fields.isEmpty()) {
            // Reference alone. Every channel's coverage is what its own sites reach.
            for (c in 0 until 3) perChannel[c] = holesFor(c, cfa, emptyList(), 0)
            return Prediction(perChannel.average().toFloat(), perChannel, 1)
        }
        val tiles = fields.first().dx.size
        for (c in 0 until 3) {
            var sum = 0f
            for (t in 0 until tiles) sum += holesFor(c, cfa, fields, t)
            perChannel[c] = sum / tiles
        }
        return Prediction(perChannel.average().toFloat(), perChannel, fields.size + 1)
    }

    /** Hole fraction of one channel in one tile, over the 4x4 period. */
    private fun holesFor(channel: Int, cfa: IntArray, fields: List<AlignmentField>, tile: Int): Float {
        // Sample positions modulo the period, as (x, y) pairs.
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (site in 0 until 4) {
            if (cfa.getOrElse(site) { -1 } != channel) continue
            val sx = (site and 1) * 2f
            val sy = (site shr 1) * 2f
            // The reference, at exactly twice its position.
            xs.add(sx); ys.add(sy)
            // Each frame, shifted by four cells per proxy pixel of fraction.
            for (f in fields) {
                if (tile >= f.fracDx.size) continue
                xs.add(wrap(sx - 4f * f.fracDx[tile]))
                ys.add(wrap(sy - 4f * f.fracDy[tile]))
            }
        }
        if (xs.isEmpty()) return 1f

        var holes = 0
        for (j in 0 until PERIOD) {
            for (i in 0 until PERIOD) {
                var covered = false
                for (k in xs.indices) {
                    val dx = wrappedDistance(i - xs[k])
                    val dy = wrappedDistance(j - ys[k])
                    if (dx * dx + dy * dy <= 1f + EPSILON) { covered = true; break }
                }
                if (!covered) holes++
            }
        }
        return holes / (PERIOD * PERIOD).toFloat()
    }

    private const val EPSILON = 1e-4f

    private fun wrap(v: Float): Float {
        var w = v % PERIOD
        if (w < 0f) w += PERIOD
        return w
    }

    private fun wrappedDistance(d: Float): Float {
        val w = wrap(abs(d))
        return minOf(w, PERIOD - w)
    }
}
