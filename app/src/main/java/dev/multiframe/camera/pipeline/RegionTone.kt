package dev.multiframe.camera.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * The local operator's lift, per grid cell, scaled by what the cell is
 * (Labs, opt-in): a sky keeps its depth, foliage is lifted a little less than
 * it earned, and skin always takes all it earned.
 *
 * ### Why a scale under one, never over
 *
 * The lift is earned from the scene's range ([BilateralGrid.ceiling]): a
 * neighbourhood comes up by at most what the range beyond 3.3 stops allows.
 * A region may decline some of that -- the underside of a cloud lifted to
 * the sky's brightness is the haze the eighth field test showed, and lifted
 * leaves turn to the crunchy "HDR" texture -- but no region may take more
 * than the scene earned, so the scale is held in [[FLOOR], 1]. The bright end
 * never moves either way: the highlight term of the lift is not scaled.
 *
 * ### Two ways to find the regions
 *
 *  - [RegionToneMode.MEASURED], the deterministic default: a colour
 *    classifier on the merged CFA's own balanced colours -- sky is blue and
 *    bright and nearer the top, foliage is green and saturated, skin is the
 *    narrow band of hues and saturations every complexion falls in. Soft
 *    memberships, averaged over each grid cell.
 *  - [RegionToneMode.MODEL]: a segmentation model's three masks (sky, skin,
 *    foliage) for the same job, where one is bundled ([AnalysisTask.REGIONS]).
 *
 * Either way the output is a map of numbers between [FLOOR] and 1. No pixel
 * is made; the operator scales the measured pixel as it always does.
 */
object RegionTone {
    const val SKY_DECLINE = 0.6f
    const val FOLIAGE_DECLINE = 0.25f
    const val FLOOR = 0.4f
    const val THUMB_W = 256
    const val THUMB_H = 192

    /** The scale for one place's memberships, each 0..1. */
    fun scale(sky: Float, skin: Float, foliage: Float): Float =
        max(skin, 1f - SKY_DECLINE * sky - FOLIAGE_DECLINE * foliage).coerceIn(FLOOR, 1f)

    /**
     * Soft memberships (sky, skin, foliage) per cell of linear-sRGB cells
     * ([CfaThumbnail.linearSrgb]), channels last.
     */
    fun classify(linear: FloatArray, ow: Int, oh: Int): FloatArray {
        val count = ow * oh
        val luma = FloatArray(count) { BilateralGrid.luminance(linear[it * 3], linear[it * 3 + 1], linear[it * 3 + 2]) }
        val sorted = luma.copyOf().also { it.sort() }
        val bright = max(1e-6f, sorted[(count * BilateralGrid.BRIGHT_END_PERCENTILE).toInt().coerceAtMost(count - 1)])
        val out = FloatArray(count * 3)
        for (i in 0 until count) {
            val r = linear[i * 3]; val g = linear[i * 3 + 1]; val b = linear[i * 3 + 2]
            val hi = max(r, max(g, b)); val lo = min(r, min(g, b))
            if (hi <= 1e-6f) continue
            val sat = (hi - lo) / hi
            val hue = hueOf(r, g, b, hi, lo)
            val level = luma[i] / bright
            val up = 1f - (i / ow).toFloat() / oh // 1 at the top row
            val sky = band(hue, 190f, 255f, 15f) * ramp(sat, 0.08f, 0.2f) * ramp(level, 0.35f, 0.7f) * (0.5f + 0.5f * up)
            val foliage = band(hue, 65f, 165f, 15f) * ramp(sat, 0.15f, 0.3f)
            val skin = band(hue, 5f, 45f, 8f) * ramp(sat, 0.12f, 0.2f) * (1f - ramp(sat, 0.6f, 0.75f)) * ramp(level, 0.08f, 0.2f)
            out[i * 3] = sky.coerceIn(0f, 1f)
            out[i * 3 + 1] = skin.coerceIn(0f, 1f)
            out[i * 3 + 2] = foliage.coerceIn(0f, 1f)
        }
        return out
    }

    /** The memberships (any size, three channels) averaged into the develop grid's cells and turned into scales. */
    fun liftScale(members: FloatArray, ow: Int, oh: Int, imageWidth: Int, imageHeight: Int): LiftScaleMap {
        val grid = BilateralGrid.forImage(imageWidth, imageHeight)
        val cx = grid.cellsX; val cy = grid.cellsY
        val channels = Array(3) { c -> ModelImage.area(ModelImage.channel(members, 3, c), ow, oh, cx, cy) }
        return LiftScaleMap(cx, cy, FloatArray(cx * cy) { scale(channels[0][it], channels[1][it], channels[2][it]) })
    }

    private fun hueOf(r: Float, g: Float, b: Float, hi: Float, lo: Float): Float {
        val d = hi - lo
        if (d <= 1e-9f) return 0f
        val h = when (hi) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    private fun ramp(v: Float, a: Float, b: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** One inside [lo, hi], falling to zero over [soft] degrees outside. */
    private fun band(hue: Float, lo: Float, hi: Float, soft: Float): Float =
        ramp(hue, lo - soft, lo) * (1f - ramp(hue, hi, hi + soft))
}

/** A scale for the local operator's earned lift, one per develop-grid cell. */
class LiftScaleMap(val cellsX: Int, val cellsY: Int, val values: FloatArray) {
    init { require(values.size == cellsX * cellsY) }

    /** Bilinear at fractional cell coordinates, as the grid slices. */
    fun at(cellX: Float, cellY: Float): Float {
        val fx = cellX.coerceIn(0f, (cellsX - 1).toFloat())
        val fy = cellY.coerceIn(0f, (cellsY - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, cellsX - 1); val y1 = min(y0 + 1, cellsY - 1)
        val tx = fx - x0; val ty = fy - y0
        return (values[y0 * cellsX + x0] * (1 - tx) + values[y0 * cellsX + x1] * tx) * (1 - ty) +
            (values[y1 * cellsX + x0] * (1 - tx) + values[y1 * cellsX + x1] * tx) * ty
    }
}
