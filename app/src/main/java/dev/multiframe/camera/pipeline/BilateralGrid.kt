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
 * base -- moves that, and carries the pixel's difference from its base --
 * the detail -- through untouched. The window comes down a little, the room
 * comes up, and the texture in each stays as it was.
 *
 * ### Why a grid with a third axis
 *
 * A spatial blur for the base produces halos: at the edge of the window it
 * mixes window and wall, the base is wrong on both sides, and a bright fringe
 * runs along the wall. The bilateral grid (Chen, Paris and Durand, 2007) adds
 * luminance as a third axis, so a wall pixel's base is averaged with pixels
 * near it in space *and* in brightness -- the wall, not the window. Coarse in
 * all three axes: a cell is a twenty-fourth of the picture's shorter side
 * square ([cellFor]; 128 pixels on a twelve-megapixel frame) and three
 * quarters of a stop deep, and slicing it is one trilinear read per pixel.
 * The cell is that large on purpose: the base is meant to be the
 * *illumination* -- the tower as a whole against the sky as a whole -- and
 * not the tower's own arches and courses, which at a sixteen-pixel cell it
 * was, so that lifting the base flattened them. At 128 they are detail and
 * ride through.
 *
 * ### The arithmetic
 *
 * Splat: each sample adds its log2 luminance and a count to the cell at
 * `(x / cell, y / cell)`, shared between the two bins nearest its log2 L.
 * Blur: `[1 4 6 4 1] / 16` along each axis in turn. Slice: trilinear at the
 * pixel's own position and luminance gives its base `b`. The pixel is
 * scaled by `2 ^ lift(b)` -- see [lift] -- whose two parts are:
 *
 * - the symmetric operator, `-highlight * (b - pivot)` about the
 *   count-weighted mean base, so the base layer's range about the mean
 *   shrinks a little by the strength;
 * - the shadow lift: a neighbourhood far enough under the scene's bright
 *   end ([BRIGHT_END_PERCENTILE] of the splats) is raised by up to the
 *   [ceiling] the scene has earned -- a stop per stop of the scene's range
 *   beyond [RANGE_HELD], to [DevelopParams.shadowLiftMax]. A room or a
 *   portrait earns nothing; a backlit tower earns the ceiling, and its
 *   facade comes up out of the silhouette while the sky, within
 *   [SHADOW_DEAD_ZONE] of the bright end, does not move.
 *
 * Built from the luminance of each 2x2 CFA block rather than from a demosaic
 * -- the block's red, the mean of its greens, its blue -- which is the
 * luminance the grid needs at a sixteenth of the resolution it keeps, for a
 * read per pixel. The native develop builds it the same way.
 */
class BilateralGrid private constructor(
    val cellsX: Int,
    val cellsY: Int,
    /** The cell's side in pixels: see [cellFor]. */
    val cell: Int,
) {
    private var sum = FloatArray(cellsX * cellsY * BINS)
    private var count = FloatArray(cellsX * cellsY * BINS)
    /** A fine histogram of the splatted log luminance, for the percentiles. */
    private val histogram = IntArray(HISTOGRAM_BINS)
    private var splats = 0
    /** The count-weighted mean base: what the symmetric operator works about. */
    var pivot = 0f
        private set
    /** The scene's bright end: the log2 luminance at [BRIGHT_END_PERCENTILE]. */
    var brightEnd = 0f
        private set
    /** The scene's range in stops, bright end to [DARK_END_PERCENTILE]. */
    var range = 0f
        private set
    var ready = false
        private set

    /**
     * The share of the earned lift each cell takes, 1 everywhere when null:
     * the region-aware tone (Labs, [RegionTone]). Ignored unless it was made
     * for this grid's cells. Scales the ceiling only; the bright end and the
     * highlight term are untouched.
     */
    var liftScale: LiftScaleMap? = null
        set(value) { field = value?.takeIf { it.cellsX == cellsX && it.cellsY == cellsY } }

    private fun index(cx: Int, cy: Int, b: Int) = (cy * cellsX + cx) * BINS + b

    /** Adds one luminance at a pixel position. */
    fun splat(x: Int, y: Int, luminance: Float) {
        val cx = min(x / cell, cellsX - 1)
        val cy = min(y / cell, cellsY - 1)
        val logL = logOf(luminance)
        // Shared between the two nearest bins by distance, not dropped into
        // the nearer, so that a smooth gradient reads a base that follows it
        // rather than one that steps at each bin's edge. The lift's slope
        // against the base reaches three quarters, and a step in the base
        // would be three quarters of a step in the picture.
        val fb = binOf(logL)
        val b0 = min(fb.toInt(), BINS - 1)
        val b1 = min(b0 + 1, BINS - 1)
        val t = fb - b0
        val i0 = index(cx, cy, b0)
        val i1 = index(cx, cy, b1)
        sum[i0] += logL * (1f - t); count[i0] += 1f - t
        sum[i1] += logL * t; count[i1] += t
        histogram[((logL - LOG_MIN) / (LOG_MAX - LOG_MIN) * HISTOGRAM_BINS).toInt().coerceIn(0, HISTOGRAM_BINS - 1)]++
        splats++
    }

    /** Blurs the grid in all three axes and fixes the pivot. */
    fun finish() {
        for (axis in 0 until 3) blurAxis(axis)
        var ws = 0.0
        var wc = 0.0
        for (i in sum.indices) { ws += sum[i]; wc += count[i] }
        pivot = if (wc > 0.0) (ws / wc).toFloat() else 0f
        brightEnd = percentile(BRIGHT_END_PERCENTILE)
        range = brightEnd - percentile(DARK_END_PERCENTILE)
        ready = true
    }

    /** The log2 luminance under which [fraction] of the splats fell. */
    fun percentile(fraction: Float): Float {
        var seen = 0
        var bin = 0
        val wanted = (splats * fraction).toInt()
        while (bin < HISTOGRAM_BINS - 1 && seen + histogram[bin] < wanted) { seen += histogram[bin]; bin++ }
        return LOG_MIN + (bin + 0.5f) / HISTOGRAM_BINS * (LOG_MAX - LOG_MIN)
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
    fun slice(x: Int, y: Int, logL: Float): Float =
        sliceAt((x + 0.5f) / cell - 0.5f, (y + 0.5f) / cell - 0.5f, logL)

    /**
     * [slice] at a position in cells rather than pixels: cell `i`'s node is
     * at `i`, which is pixel `(i + 0.5) * cell - 0.5`. What the DNG's gain
     * table map samples the operator at ([ProfileGainTableMap]).
     */
    fun sliceAt(cellX: Float, cellY: Float, logL: Float): Float {
        val fx = cellX.coerceIn(0f, (cellsX - 1).toFloat())
        val fy = cellY.coerceIn(0f, (cellsY - 1).toFloat())
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

    /**
     * The scale for a pixel under the two-sided operator: the symmetric
     * compression about the mean by [highlight], as before, plus the shadow
     * lift of [lift] -- a neighbourhood far enough under the scene's bright
     * end is raised by up to [ceiling] stops. See [ceiling] for how much
     * the scene earns and [lift] for how it is spent.
     */
    fun gain(x: Int, y: Int, luminance: Float, highlight: Float, shadowLift: Float, shadowLiftMax: Float): Float =
        gainAt((x + 0.5f) / cell - 0.5f, (y + 0.5f) / cell - 0.5f, luminance, highlight, shadowLift, shadowLiftMax)

    /** [gain] at a position in cells: see [sliceAt]. */
    fun gainAt(cellX: Float, cellY: Float, luminance: Float, highlight: Float, shadowLift: Float, shadowLiftMax: Float): Float {
        if (!ready || (highlight <= 0f && shadowLift <= 0f) || luminance <= MIN_LUMINANCE) return 1f
        val logL = logOf(luminance)
        val base = sliceAt(cellX, cellY, logL)
        val scale = liftScale?.at(cellX, cellY) ?: 1f
        return 2f.pow(lift(base - pivot, brightEnd - base, highlight, ceiling(shadowLift, shadowLiftMax) * scale))
    }

    /**
     * The stops of lift this scene earns: [shadowLift] per stop of its range
     * -- the bright end over the tenth percentile -- beyond [RANGE_HELD], the
     * range the display curve carries on its own, and never more than
     * [shadowLiftMax]. A room, a portrait, a flower in the sun (two to three
     * stops of range) earn nothing and render exactly as the symmetric
     * operator renders them; a backlit tower or a street under cumulus (six
     * stops) earns the ceiling. The operator gives back only what the
     * display could not hold.
     */
    fun ceiling(shadowLift: Float, shadowLiftMax: Float): Float =
        if (shadowLift <= 0f) 0f else min(shadowLiftMax, shadowLift * max(0f, range - RANGE_HELD))

    companion object {
        /** The cell's side on a small image, and the floor for [cellFor]. */
        const val CELL = 16
        /** The image's shorter side is this many cells across. */
        const val CELLS_ACROSS = 24
        const val BINS = 16
        private const val HISTOGRAM_BINS = 240
        const val LOG_MIN = -10f
        const val LOG_MAX = 2f
        const val MIN_LUMINANCE = 1e-6f
        private val TAPS = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        private val LN2 = ln(2.0).toFloat()

        fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

        fun logOf(luminance: Float): Float = ln(max(luminance, MIN_LUMINANCE)) / LN2

        fun binOf(logL: Float): Float =
            ((logL - LOG_MIN) / (LOG_MAX - LOG_MIN) * (BINS - 1)).coerceIn(0f, (BINS - 1).toFloat())

        /**
         * The cell for an image: a twenty-fourth of its shorter side, and
         * never under [CELL]. 127 pixels on a twelve-megapixel frame, 63 on
         * the bench's half-size develop of the same frame, so the operator
         * reads the same neighbourhood on both. See the class note for why
         * it is that large.
         */
        fun cellFor(width: Int, height: Int): Int = max(CELL, min(width, height) / CELLS_ACROSS)

        /** The bright end is the luminance this fraction of the splats fall under; the range runs down to [DARK_END_PERCENTILE]. */
        const val BRIGHT_END_PERCENTILE = 0.92f
        const val DARK_END_PERCENTILE = 0.10f

        /**
         * The stops of range, bright end to dark end, that the global chain
         * renders readably on its own: past it the dark end is under the
         * display curve's toe, and the lift begins.
         */
        const val RANGE_HELD = 3.3f

        /**
         * The lift is nothing within this many stops of the bright end -- the
         * midtones are the picture and are not touched -- and rises over the
         * next [SHADOW_RAMP_PER_STOP] stops per stop of ceiling. Two, so the
         * lift's slope against the base never passes three quarters: two
         * neighbourhoods on the ramp keep their order and a quarter of their
         * separation, and past the ramp all of it.
         */
        const val SHADOW_DEAD_ZONE = 0.5f
        const val SHADOW_RAMP_PER_STOP = 2.0f

        /**
         * The operator's lift in stops for a neighbourhood whose base sits
         * [dMean] stops from the picture's mean and [depth] stops under its
         * bright end: the symmetric compression `-highlight * dMean` as it
         * always was, plus the shadow lift -- zero to [SHADOW_DEAD_ZONE] of
         * depth, a smoothstep over the ramp, [ceiling] beyond it. Beyond the
         * ramp every neighbourhood is lifted by the same amount, so the
         * deepest shadows keep their order and their contrast.
         */
        fun lift(dMean: Float, depth: Float, highlight: Float, ceiling: Float): Float {
            var l = -highlight * dMean
            if (ceiling > 0f) {
                val past = depth - SHADOW_DEAD_ZONE
                if (past > 0f) {
                    val t = (past / (SHADOW_RAMP_PER_STOP * ceiling)).coerceAtMost(1f)
                    l += ceiling * t * t * (3f - 2f * t)
                }
            }
            return l
        }

        /** An empty grid over an image of [width] by [height]. */
        fun forImage(width: Int, height: Int): BilateralGrid {
            val cell = cellFor(width, height)
            return BilateralGrid((width + cell - 1) / cell, (height + cell - 1) / cell, cell)
        }

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
            val rgb = FloatArray(3)
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
                    rgb[0] = (m[0] * rr + m[1] * gg + m[2] * bb) * gain
                    rgb[1] = (m[3] * rr + m[4] * gg + m[5] * bb) * gain
                    rgb[2] = (m[6] * rr + m[7] * gg + m[8] * bb) * gain
                    // The same pixel the develop will render: purity compressed first.
                    ToneCurve.compressPurity(rgb, params.purityThreshold, params.purityCeiling)
                    grid.splat(bx, by, luminance(rgb[0], rgb[1], rgb[2]))
                    bx += 2
                }
                by += 2
            }
            grid.finish()
            grid.liftScale = params.liftScale
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
            val shadingGrid = shading?.rgbGrid(profile.cfaPattern)
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
                    lin[0] = r; lin[1] = g; lin[2] = b
                    ToneCurve.compressPurity(lin, params.purityThreshold, params.purityCeiling)
                    grid.splat(bx, by, luminance(lin[0], lin[1], lin[2]))
                    bx += 2
                }
                by += 2
            }
            grid.finish()
            return grid
        }
    }
}
