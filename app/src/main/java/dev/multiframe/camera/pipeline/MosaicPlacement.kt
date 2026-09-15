package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns what the canvas measured about a tile's placement
 * ([MosaicCanvas.refine]) into the correction to compose onto it.
 *
 * ### One block, a translation; many, a homography
 *
 * Each block match says: the placement put this point here, the canvas holds
 * it there. One such measurement fixes a translation and nothing more. Four
 * fix a homography in principle, but four blocks of a noisy overlap fix a
 * wild one in practice, so a homography is fitted only from
 * [MIN_BLOCKS_FOR_HOMOGRAPHY] blocks, and only kept where it is a small
 * correction -- a placement that needs its scale changed by more than
 * [MAX_SCALE_CHANGE] was not nearly right, and the attitude that produced it
 * is the better authority than a fit through what may be the wrong tower.
 * Otherwise the translation: the weighted median of the blocks' offsets,
 * which one block of sky matched on noise cannot pull.
 *
 * Pure: canvas coordinates in, canvas-to-canvas transform out.
 */
object MosaicPlacement {

    /** Fewer blocks than this and only a translation is fitted. */
    const val MIN_BLOCKS_FOR_HOMOGRAPHY = 8

    /** A fitted correction that changes the tile's scale by more than this is not a correction. */
    const val MAX_SCALE_CHANGE = 0.03f

    /** A block whose offset is further than this from the median, in canvas pixels, matched something else. */
    const val OUTLIER_PX = 24f

    /** What the refinement decided, for the log. */
    data class Correction(
        /** Maps a canvas point where the placement put it to where the canvas holds it. */
        val transform: Homography,
        /** Blocks the fit used. */
        val blocks: Int,
        /** How far the tile's most-moved corner moved, canvas pixels. */
        val shiftPx: Float,
        val homography: Boolean,
    )

    /**
     * The correction for [matches], or null where there is nothing to
     * correct by: no blocks, or blocks that agree the placement was right
     * to within a pixel.
     */
    fun correctionFor(
        matches: List<MosaicCanvas.BlockMatch>,
        /** The tile's four corners on the canvas, as placed: what the correction is judged by. */
        placedCorners: List<FloatArray>,
    ): Correction? {
        if (matches.isEmpty() || placedCorners.isEmpty()) return null
        val tileCentreX = placedCorners.map { it[0] }.average().toFloat()
        val tileCentreY = placedCorners.map { it[1] }.average().toFloat()

        // The translation: the weighted median offset, then the blocks that
        // agree with it.
        val dx = weightedMedian(matches.map { (it.toX - it.fromX) to it.ncc })
        val dy = weightedMedian(matches.map { (it.toY - it.fromY) to it.ncc })
        val agreeing = matches.filter { hypot((it.toX - it.fromX) - dx, (it.toY - it.fromY) - dy) <= OUTLIER_PX }
        if (agreeing.isEmpty()) return null

        val translation = Homography.translation(dx.toDouble(), dy.toDouble())
        var chosen = translation
        var homography = false
        if (agreeing.size >= MIN_BLOCKS_FOR_HOMOGRAPHY) {
            val fit = Homography.fit(agreeing.map { Match(it.fromX, it.fromY, it.toX, it.toY) })
            // Kept only where it explains the blocks clearly better than the
            // translation does: a homography fitted to blocks a translation
            // already fits is fitting their noise.
            if (fit != null && scaleChangeOf(fit, tileCentreX, tileCentreY) <= MAX_SCALE_CHANGE &&
                residual(fit, agreeing) < residual(translation, agreeing) * HOMOGRAPHY_GAIN
            ) {
                chosen = fit
                homography = true
            }
        }

        // Judged by the corner that moves most: a rotation moves the centre
        // not at all and the corners by everything.
        var shift = 0f
        for (c in placedCorners) {
            val moved = chosen.apply(c[0], c[1])
            shift = maxOf(shift, hypot(moved[0] - c[0], moved[1] - c[1]))
        }
        if (shift < MIN_SHIFT_PX) return null
        return Correction(chosen, agreeing.size, shift, homography)
    }

    /** Under this, at every corner, the placement was right and is left alone. */
    const val MIN_SHIFT_PX = 1.0f

    /** A homography has to leave the blocks' residual under this share of the translation's to be preferred. */
    const val HOMOGRAPHY_GAIN = 0.8f

    private fun residual(h: Homography, matches: List<MosaicCanvas.BlockMatch>): Float {
        var sum = 0f
        for (m in matches) {
            val p = h.apply(m.fromX, m.fromY)
            sum += hypot(p[0] - m.toX, p[1] - m.toY)
        }
        return sum / matches.size
    }

    /** How much [h] scales lengths near the tile's centre, as a fraction from one. */
    private fun scaleChangeOf(h: Homography, cx: Float, cy: Float): Float {
        val step = 100f
        val o = h.apply(cx, cy)
        val px = h.apply(cx + step, cy)
        val py = h.apply(cx, cy + step)
        val sx = hypot(px[0] - o[0], px[1] - o[1]) / step
        val sy = hypot(py[0] - o[0], py[1] - o[1]) / step
        return maxOf(abs(sx - 1f), abs(sy - 1f))
    }

    private fun weightedMedian(values: List<Pair<Float, Float>>): Float {
        val sorted = values.sortedBy { it.first }
        val total = sorted.sumOf { it.second.coerceAtLeast(0f).toDouble() }
        if (total <= 0.0) return sorted[sorted.size / 2].first
        var seen = 0.0
        for ((v, w) in sorted) {
            seen += w.coerceAtLeast(0f)
            if (seen * 2 >= total) return v
        }
        return sorted.last().first
    }
}
