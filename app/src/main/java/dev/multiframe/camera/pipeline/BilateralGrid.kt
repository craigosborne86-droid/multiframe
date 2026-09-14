package dev.multiframe.camera.pipeline

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A bilateral grid over log luminance: the local tone operator of
 * REFERENCE.md §3.5, and the reference for the one in Tone.h.
 *
 * ### What it does
 *
 * A global curve compresses the whole picture's range with one function of
 * brightness, so a bright window and the dim room around it are pulled
 * toward each other everywhere at once and the room's own contrast goes with
 * it. This asks, for each pixel, how bright its *neighbourhood* is -- the
 * base -- compresses that, and carries the pixel's difference from its base
 * -- the detail -- through untouched. The window comes down, the room comes
 * up, and the texture in each stays as it was.
 *
 * ### Why a grid with a third axis
 *
 * A spatial blur for the base produces halos: at the edge of the window it
 * mixes window and wall, the base is wrong on both sides, and a bright fringe
 * runs along the wall. The bilateral grid (Chen, Paris and Durand, 2007) adds
 * luminance as a third axis, so a wall pixel's base is averaged with pixels
 * near it in space *and* in brightness -- the wall, not the window. Coarse in
 * all three axes: a cell is [CELL] pixels square and three quarters of a stop
 * deep, and slicing it is one trilinear read per pixel.
 *
 * ### The arithmetic
 *
 * Splat: each sample adds its log2 luminance and a count to the cell at
 * `(x / CELL, y / CELL, bin(log2 L))`. Blur: `[1 4 6 4 1] / 16` along each
 * axis in turn. Slice: trilinear at the pixel's own position and luminance
 * gives its base `b`. The pixel is scaled by `2 ^ (-strength * (b - pivot))`
 * where the pivot is the count-weighted mean base, so the base layer's range
 * about the mean shrinks by the strength and the detail layer is unchanged.
 *
 * Built from the luminance of each 2x2 CFA block rather than from a demosaic
 * -- the block's red, the mean of its greens, its blue -- which is the
 * luminance the grid needs at a sixteenth of the resolution it keeps, for a
 * read per pixel. The native develop builds it the same way.
 */
class BilateralGrid private constructor(
    val cellsX: Int,
    val cellsY: Int,
) {
    private var sum = FloatArray(cellsX * cellsY * BINS)
    private var count = FloatArray(cellsX * cellsY * BINS)
    var pivot = 0f
        private set
    var ready = false
        private set

    private fun index(cx: Int, cy: Int, b: Int) = (cy * cellsX + cx) * BINS + b

    /** Adds one luminance at a pixel position. */
    fun splat(x: Int, y: Int, luminance: Float) {
        val cx = min(x / CELL, cellsX - 1)
        val cy = min(y / CELL, cellsY - 1)
        val logL = logOf(luminance)
        val b = min((binOf(logL) + 0.5f).toInt(), BINS - 1)
        val i = index(cx, cy, b)
        sum[i] += logL
        count[i] += 1f
    }

    /** Blurs the grid in all three axes and fixes the pivot. */
    fun finish() {
        for (axis in 0 until 3) blurAxis(axis)
        var ws = 0.0
        var wc = 0.0
        for (i in sum.indices) { ws += sum[i]; wc += count[i] }
        pivot = if (wc > 0.0) (ws / wc).toFloat() else 0f
        ready = true
    }

    private fun blurAxis(axis: Int) {
        val s2 = FloatArray(sum.size)
        val c2 = FloatArray(count.size)
        for (cy in 0 until cellsY) for (cx in 0 until cellsX) for (b in 0 until BINS) {
            var s = 0f
            var c = 0f
            for (t in -2..2) {
                val xx = if (axis == 0) (cx + t).coerceIn(0, cellsX - 1) else cx
                val yy = if (axis == 1) (cy + t).coerceIn(0, cellsY - 1) else cy
                val bb = if (axis == 2) (b + t).coerceIn(0, BINS - 1) else b
                val i = index(xx, yy, bb)
                s += TAPS[t + 2] * sum[i]
                c += TAPS[t + 2] * count[i]
            }
            s2[index(cx, cy, b)] = s
            c2[index(cx, cy, b)] = c
        }
        sum = s2
        count = c2
    }

    /** The base at a pixel: its neighbourhood's mean log luminance near its own. */
    fun slice(x: Int, y: Int, logL: Float): Float {
        val fx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, (cellsX - 1).toFloat())
        val fy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, (cellsY - 1).toFloat())
        val fb = binOf(logL)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val b0 = fb.toInt()
        val x1 = min(x0 + 1, cellsX - 1); val y1 = min(y0 + 1, cellsY - 1); val b1 = min(b0 + 1, BINS - 1)
        val tx = fx - x0; val ty = fy - y0; val tb = fb - b0
        var s = 0f
        var c = 0f
        fun tap(cx: Int, cy: Int, b: Int, w: Float) {
            val i = index(cx, cy, b)
            s += w * sum[i]
            c += w * count[i]
        }
        tap(x0, y0, b0, (1 - tx) * (1 - ty) * (1 - tb)); tap(x1, y0, b0, tx * (1 - ty) * (1 - tb))
        tap(x0, y1, b0, (1 - tx) * ty * (1 - tb)); tap(x1, y1, b0, tx * ty * (1 - tb))
        tap(x0, y0, b1, (1 - tx) * (1 - ty) * tb); tap(x1, y0, b1, tx * (1 - ty) * tb)
        tap(x0, y1, b1, (1 - tx) * ty * tb); tap(x1, y1, b1, tx * ty * tb)
        // Nothing near this luminance in this neighbourhood: the pixel is its own base.
        return if (c > 1e-4f) s / c else logL
    }

    /** The scale to apply to a pixel of [luminance] at (x, y), for [strength] in 0..1. */
    fun gain(x: Int, y: Int, luminance: Float, strength: Float): Float {
        if (!ready || strength <= 0f || luminance <= MIN_LUMINANCE) return 1f
        val logL = logOf(luminance)
        val base = slice(x, y, logL)
        return 2f.pow(-strength * (base - pivot))
    }

    companion object {
        const val CELL = 16
        const val BINS = 16
        const val LOG_MIN = -10f
        const val LOG_MAX = 2f
        const val MIN_LUMINANCE = 1e-6f
        private val TAPS = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        private val LN2 = ln(2.0).toFloat()

        fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

        fun logOf(luminance: Float): Float = ln(max(luminance, MIN_LUMINANCE)) / LN2

        fun binOf(logL: Float): Float =
            ((logL - LOG_MIN) / (LOG_MAX - LOG_MIN) * (BINS - 1)).coerceIn(0f, (BINS - 1).toFloat())

        /** An empty grid over an image of [width] by [height]. */
        fun forImage(width: Int, height: Int): BilateralGrid =
            BilateralGrid((width + CELL - 1) / CELL, (height + CELL - 1) / CELL)

        /**
         * The grid for a CFA frame, from the luminance of each 2x2 block after
         * white balance, the colour matrix and the exposure gain -- the same
         * values the develop will render, at a quarter of the pixels.
         */
        fun fromFrame(
            frame: BayerFrame,
            sensor: SensorProfile,
            color: ColorProfile,
            params: DevelopParams,
            shading: ShadingMap? = null,
        ): BilateralGrid {
            val grid = forImage(frame.width, frame.height)
            val m = color.matrix
            val gain = params.exposureGain
            var by = 0
            while (by + 1 < frame.height) {
                var bx = 0
                while (bx + 1 < frame.width) {
                    var rr = 0f; var gg = 0f; var bb = 0f; var greens = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val v = Demosaic.sample(frame, sensor, color, bx + dx, by + dy, shading)
                        when (Demosaic.colourAt(sensor, bx + dx, by + dy)) {
                            0 -> rr = v
                            2 -> bb = v
                            else -> { gg += v; greens++ }
                        }
                    }
                    if (greens > 0) gg /= greens
                    val r = (m[0] * rr + m[1] * gg + m[2] * bb) * gain
                    val g = (m[3] * rr + m[4] * gg + m[5] * bb) * gain
                    val b = (m[6] * rr + m[7] * gg + m[8] * bb) * gain
                    grid.splat(bx, by, luminance(r, g, b))
                    bx += 2
                }
                by += 2
            }
            grid.finish()
            return grid
        }

        /**
         * The grid for a full-colour canvas, [rgb16] interleaved in sensor
         * counts as [LinearDeveloper] takes it: each 2x2 block's mean, through
         * the same chain to the exposure gain. Mirrors LinearDevelop.cpp.
         */
        fun fromCanvas(
            rgb16: ShortArray,
            width: Int,
            height: Int,
            profile: SensorProfile,
            color: ColorProfile,
            params: DevelopParams,
            shading: ShadingMap? = null,
        ): BilateralGrid {
            val grid = forImage(width, height)
            val black = LinearDeveloper.channelBlack(profile)
            val lo = minOf(black[0], black[1], black[2])
            val invRange = 1f / max(1f, profile.whiteLevel - lo)
            val wb = LinearDeveloper.channelGains(color)
            val m = color.matrix
            val shadingGrid = shading?.rgbGrid()
            val sensorW = width / 2
            val sensorH = height / 2
            var by = 0
            while (by + 1 < height) {
                var bx = 0
                while (bx + 1 < width) {
                    val lin = FloatArray(3)
                    for (dy in 0..1) for (dx in 0..1) {
                        val i = ((by + dy) * width + (bx + dx)) * 3
                        for (c in 0 until 3) {
                            var v = (((rgb16[i + c].toInt() and 0xFFFF) - black[c]) * invRange).coerceAtLeast(0f)
                            if (shadingGrid != null) {
                                v *= ShadingMap.rgbGainAt(
                                    shadingGrid, shading.columns, shading.rows,
                                    (bx + dx) shr 1, (by + dy) shr 1, sensorW, sensorH, c,
                                )
                            }
                            lin[c] += 0.25f * v * wb[c]
                        }
                    }
                    val r = (m[0] * lin[0] + m[1] * lin[1] + m[2] * lin[2]) * params.exposureGain
                    val g = (m[3] * lin[0] + m[4] * lin[1] + m[5] * lin[2]) * params.exposureGain
                    val b = (m[6] * lin[0] + m[7] * lin[1] + m[8] * lin[2]) * params.exposureGain
                    grid.splat(bx, by, luminance(r, g, b))
                    bx += 2
                }
                by += 2
            }
            grid.finish()
            return grid
        }
    }
}
