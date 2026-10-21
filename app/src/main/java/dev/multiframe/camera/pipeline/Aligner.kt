package dev.multiframe.camera.pipeline

import android.util.Log

private const val TAG = "Multiframe"

/**
 * Per-tile translation offsets for one alternate frame, in proxy pixels.
 * Tiles are a fixed grid so offsets propagate trivially between pyramid levels.
 *
 * ### Two resolutions of the same answer
 *
 * [dx] and [dy] are the integer winners of the search. The Bayer-domain merge
 * doubles them and uses nothing else: an offset in the CFA domain must be even
 * or a red sample lands on a green one, so a fractional part is not something
 * that merge can spend.
 *
 * [fracDx] and [fracDy] are the sub-pixel refinement of those winners: an
 * inverse-compositional Lucas-Kanade fit of the tile's translation on the
 * proxy, started at the winner and bounded to a pixel either way of it (see
 * [Aligner.refineSubPixel]). Zero on both axes where the fit declined -- a
 * flat tile, or an estimate it could not stand behind; across the edge only,
 * on a tile that is an edge. Usually within a pixel of the winner; more
 * where [Aligner.consensus] found the winner had slid and refitted the tile
 * from the frame's motion, which leaves [dx] and [dy] as the search found
 * them. The
 * full sub-pixel displacement of a tile is `dx + fracDx`, in proxy pixels;
 * the merge never reads it, and the sub-pixel accumulation in
 * `docs/REFERENCE.md` §3.6 exists to.
 *
 * Both arrays default to zero, so a field built without refinement -- by the
 * mosaic path, or by a test -- describes exactly what it did before.
 */
class AlignmentField(
    val tilesX: Int,
    val tilesY: Int,
    val dx: IntArray,
    val dy: IntArray,
    val fracDx: FloatArray = FloatArray(dx.size),
    val fracDy: FloatArray = FloatArray(dy.size),
) {
    /** Whether any tile carries a refinement. False for a field built the old way. */
    val hasSubPixel: Boolean
        get() = fracDx.any { it != 0f } || fracDy.any { it != 0f }

    /** Whether tile [i]'s sub-pixel fit said anything: a fit that declined reads as exactly zero on both axes. */
    fun hasSubPixelAt(i: Int): Boolean = fracDx[i] != 0f || fracDy[i] != 0f

    /**
     * The field with every tile that has no sub-pixel fit of its own given
     * its neighbours': the median of the fitted tiles among the eight
     * around it, or of the whole field where none of those fitted.
     *
     * A tile with no texture -- sky, a wall -- has a flat cost curve and
     * the sub-pixel fit declines it, which reads as a fraction of exactly
     * zero: "this frame landed on the reference's own sites". It did not;
     * the hand moved the whole frame, and the flat tile moved with the
     * textured ones beside it. Left at zero, the 2x canvas read a third of
     * a sky scene as a tripod burst and declined the picture, and where it
     * ran, the sky's cells between the reference's sites went unfed. The
     * hand's motion is one motion; a tile that could not measure it
     * borrows the measurement -- from tiles that agree with its own winner
     * to a pixel, and from none where none do. A field with no fitted tile
     * at all is a tripod, and is returned as it is.
     */
    fun withFlatTilesBorrowed(): AlignmentField {
        val count = dx.size
        val fitted = (0 until count).filter { hasSubPixelAt(it) }
        if (fitted.isEmpty() || fitted.size == count) return this
        val outX = fracDx.copyOf()
        val outY = fracDy.copyOf()
        // Displacements, not fractions, are what is borrowed -- a neighbour
        // whose winner differs by a pixel carries its fraction from a
        // different integer -- and only from fitted tiles within a pixel of
        // this tile's own winner. The tile keeps its integer, and a
        // neighbour the search lost by fifty pixels lends it nothing: a
        // flat tile beside one on a tripod burst once borrowed its fifty.
        fun totalX(j: Int) = dx[j] + fracDx[j]
        fun totalY(j: Int) = dy[j] + fracDy[j]
        fun agrees(j: Int, i: Int) =
            kotlin.math.abs(totalX(j) - dx[i]) <= 1f && kotlin.math.abs(totalY(j) - dy[i]) <= 1f
        for (i in 0 until count) {
            if (hasSubPixelAt(i)) continue
            val tx = i % tilesX
            val ty = i / tilesX
            val near = ArrayList<Int>(8)
            for (oy in -1..1) for (ox in -1..1) {
                if (ox == 0 && oy == 0) continue
                val nx = tx + ox
                val ny = ty + oy
                if (nx < 0 || ny < 0 || nx >= tilesX || ny >= tilesY) continue
                val j = ny * tilesX + nx
                if (hasSubPixelAt(j) && agrees(j, i)) near.add(j)
            }
            if (near.isEmpty()) {
                val field = fitted.filter { agrees(it, i) }
                if (field.isEmpty()) continue
                outX[i] = median(field.map(::totalX)) - dx[i]
                outY[i] = median(field.map(::totalY)) - dy[i]
            } else {
                outX[i] = median(near.map(::totalX)) - dx[i]
                outY[i] = median(near.map(::totalY)) - dy[i]
            }
        }
        return AlignmentField(tilesX, tilesY, dx, dy, outX, outY)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}

/**
 * Hierarchical tile-based alignment on the luma plane, following the coarse-to-fine
 * approach described for HDR+ burst photography (Hasinoff et al., 2016): build a
 * Gaussian-ish pyramid, and at each level search a small window around the offset
 * inherited from the coarser level, minimising the sum of absolute differences.
 *
 * Integer-pixel offsets only, and the two merges spend them differently.
 *
 * [Merger], the YUV path, interpolates the four surrounding tile displacements
 * bilinearly and samples the luma at the fractional result, which recovers most
 * of the benefit of subpixel alignment without the extra search cost.
 *
 * [BayerAccumulator] cannot: an offset in the CFA domain must be even or a red
 * sample lands on a green one, and doubling an interpolated displacement is odd
 * half the time. It blends the four *samples* instead, weighted by a raised
 * cosine over tiles that overlap by half. **This comment used to claim the
 * interpolation for both**, and the raw merge -- the one every shutter press
 * runs -- came out in visible squares for as long as it did.
 */
object Aligner {

    const val LEVELS = 4
    private const val SEARCH_RADIUS = 4
    private const val MIN_TILE = 8

    fun buildPyramid(base: Plane, levels: Int = LEVELS): List<Plane> {
        val pyramid = ArrayList<Plane>(levels)
        pyramid.add(base)
        var current = base
        for (i in 1 until levels) {
            if (current.width < 32 || current.height < 32) break
            current = downsampleByTwo(current)
            pyramid.add(current)
        }
        return pyramid
    }

    /**
     * 2x2 box downsample.
     *
     * Public because registration for a mosaic needs a much smaller proxy than
     * the merge does. Corner detection scans every pixel, so running it on a
     * half-resolution proxy of a twelve-megapixel frame would cost seconds per
     * frame; a further three halvings brings that to tens of milliseconds
     * without costing accuracy, since a homography is fitted to dozens of
     * correspondences rather than read off one pixel.
     */
    fun downsampleByTwo(src: Plane): Plane {
        val w = src.width / 2
        val h = src.height / 2
        val out = ByteArray(w * h)
        val s = src.data
        val sw = src.width
        for (row in 0 until h) {
            val r0 = (row * 2) * sw
            val r1 = r0 + sw
            var o = row * w
            for (col in 0 until w) {
                val c0 = col * 2
                val sum = (s[r0 + c0].toInt() and 0xFF) +
                    (s[r0 + c0 + 1].toInt() and 0xFF) +
                    (s[r1 + c0].toInt() and 0xFF) +
                    (s[r1 + c0 + 1].toInt() and 0xFF)
                out[o] = (sum shr 2).toByte()
                o++
            }
        }
        return Plane(w, h, out)
    }

    /**
     * Aligns [altPyramid] onto [refPyramid]. Both must come from [buildPyramid].
     */
    fun align(
        refPyramid: List<Plane>,
        altPyramid: List<Plane>,
        tilesX: Int,
        tilesY: Int,
    ): AlignmentField {
        val count = tilesX * tilesY
        val dx = IntArray(count)
        val dy = IntArray(count)
        val fracDx = FloatArray(count)
        val fracDy = FloatArray(count)

        val levels = minOf(refPyramid.size, altPyramid.size)
        val fit = LkFit()
        val mode = IntArray(count)
        val residual = LongArray(count)

        // Coarsest level first; offsets double as we descend.
        for (level in levels - 1 downTo 0) {
            if (level < levels - 1) {
                for (i in 0 until count) {
                    dx[i] *= 2
                    dy[i] *= 2
                }
            }

            val ref = refPyramid[level]
            val alt = altPyramid[level]
            val step = if (level == 0) 2 else 1

            val tileW = ref.width / tilesX
            val tileH = ref.height / tilesY
            // Tiles below this carry too little texture to match reliably, and a
            // wrong match here propagates down as a large, uncorrectable offset.
            if (tileW < MIN_TILE || tileH < MIN_TILE) continue

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val idx = ty * tilesX + tx
                    val x0 = tx * tileW
                    val y0 = ty * tileH

                    // Seed the search from the best of: the offset inherited from
                    // the coarser level, already-solved neighbours, and zero. This
                    // lets a tile recover when the coarse level misled it.
                    var seedDx = dx[idx]
                    var seedDy = dy[idx]
                    var seedCost = tileCost(
                        ref, alt, x0, y0, tileW, tileH, seedDx, seedDy, step, Long.MAX_VALUE,
                    )

                    fun consider(cdx: Int, cdy: Int) {
                        if (cdx == seedDx && cdy == seedDy) return
                        val c = tileCost(ref, alt, x0, y0, tileW, tileH, cdx, cdy, step, seedCost)
                        if (c < seedCost) {
                            seedCost = c
                            seedDx = cdx
                            seedDy = cdy
                        }
                    }

                    if (tx > 0) consider(dx[idx - 1], dy[idx - 1])
                    if (ty > 0) consider(dx[idx - tilesX], dy[idx - tilesX])
                    consider(0, 0)

                    var bestDx = seedDx
                    var bestDy = seedDy
                    var bestCost = seedCost

                    for (cy in -SEARCH_RADIUS..SEARCH_RADIUS) {
                        for (cx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                            val tryDx = seedDx + cx
                            val tryDy = seedDy + cy
                            val cost = tileCost(
                                ref, alt, x0, y0, tileW, tileH, tryDx, tryDy, step, bestCost,
                            )
                            if (cost < bestCost) {
                                bestCost = cost
                                bestDx = tryDx
                                bestDy = tryDy
                            }
                        }
                    }
                    dx[idx] = bestDx
                    dy[idx] = bestDy

                    // Where between the integer candidates the tile really
                    // sits. Only at the finest level: a refinement made higher
                    // up would be doubled into the next level's integer search
                    // and lost. See [refineSubPixel].
                    if (level == 0) {
                        fitTile(ref, alt, x0, y0, tileW, tileH, bestDx.toDouble(), bestDy.toDouble(), fit)
                        fracDx[idx] = fit.fx.toFloat()
                        fracDy[idx] = fit.fy.toFloat()
                        mode[idx] = fit.mode
                        residual[idx] = fit.ee
                    }
                }
            }
        }

        // Only where the finest level was searched: it is the one refined.
        if (refPyramid[0].width / tilesX >= MIN_TILE && refPyramid[0].height / tilesY >= MIN_TILE) {
            consensus(refPyramid[0], altPyramid[0], tilesX, tilesY, dx, dy, fracDx, fracDy, mode, residual)
        }
        return AlignmentField(tilesX, tilesY, dx, dy, fracDx, fracDy)
    }

    /** Gauss-Newton updates of the translation. Two settle a fraction within a hundredth; the third is margin. */
    const val LK_ITERATIONS = 3

    /** How [fitTile] fitted a tile: not at all, across its edge only, or on both axes. */
    const val LK_DECLINED = 0
    const val LK_EDGE = 1
    const val LK_BOTH = 2

    /** One tile's fit: its fraction from the start it was given, how it was fitted, and its last sum of squared error (1/256 codes)^2. */
    internal class LkFit {
        var fx = 0.0
        var fy = 0.0
        var mode = LK_DECLINED
        var ee = 0L
    }

    /**
     * Strong-axis over weak-axis gradient energy beyond which a tile is an
     * edge, whatever texture the weak axis seems to have: a slanted edge's
     * staircase gives it some, and a two-axis step along the edge on that
     * little leaves the reach of the fit from the right start.
     */
    const val LK_MAX_ANISOTROPY = 16.0

    /** A step under this on both axes, proxy pixels, ends the iterations: a quarter of the weights' resolution. */
    const val LK_CONVERGED = 1.0 / 1024

    /** Bilinear weights in 1/256: positions to 0.004 proxy pixels, and every sum an exact integer. */
    private const val LK_ONE = 256

    /**
     * The weaker direction's mean squared gradient, (codes / pixel)^2, under
     * which a tile is flat: no texture to fit, or texture in one direction only.
     */
    const val LK_MIN_GRADIENT = 0.25

    /**
     * The tile's weaker-direction gradient power over its residual power,
     * under which the fit is not used: the noise is fitting the noise.
     */
    const val LK_MIN_SNR = 1.0

    /**
     * The fractional translation of one tile, by inverse-compositional
     * Lucas-Kanade on the proxy, written into [out] as (x, y) -- or zero on
     * both axes where the fit declines.
     *
     * ### Why this and not a fit through three costs
     *
     * The fit this replaced read the fraction from the SAD at the winner and
     * its four neighbours, sampled every other pixel. On a known-shift desk
     * bench (`SuperResEvidenceBench`) its median error was 0.32 sensor pixels,
     * and on byte-identical frames it reported up to 0.43: three samples of a
     * cost curve that is not symmetric about its minimum do not locate the
     * minimum. The 2x canvas places every sample by this number, and a cell
     * there is half a sensor pixel. This fits the translation to every pixel
     * of the tile instead.
     *
     * ### The method
     *
     * The template T is the reference tile; the image I is the alternate; the
     * translation p is sought with I(x + p) = T(x). Inverse compositional
     * (Baker & Matthews 2004), translation-only: linearise T rather than I, so
     * the gradient and the Gauss-Newton Hessian are the reference's and are
     * computed once,
     *
     *     g = (T(x+1,y) - T(x-1,y), T(x,y+1) - T(x,y-1)) = 2 grad T
     *     H = sum g g^T
     *
     * and each iteration warps the alternate bilinearly at p, takes the error
     * e = I(x + p) - T(x), and steps
     *
     *     dp = 2 H^-1 sum g e,   p <- p - dp.
     *
     * For a translation the inverse composition is that subtraction. At most
     * three iterations from the integer winner, fewer once a step falls under
     * [LK_CONVERGED].
     *
     * ### Fixed point, so the native port agrees exactly
     *
     * The warp's fraction is constant over the tile, so its bilinear weights
     * are four numbers. They are rounded to 1/256, the interpolated value is
     * kept in 1/256 of a code, and every sum -- H, sum g e, sum e^2 -- is an
     * exact integer. Align.cpp vectorises the warp with NEON and sums in a
     * different order and gets the same integers; the only floating point is
     * the 2x2 solve, done the same way on both sides. The rounding of the
     * weights moves the warp by at most 1/512 pixel, which is all the
     * quantisation can leave in the answer: each step is measured from where
     * the warp actually was.
     *
     * ### When it declines
     *
     *  * The stronger eigenvalue of H, per pixel, is under [LK_MIN_GRADIENT]
     *    (in (codes/pixel)^2 of grad T): a flat tile.
     *
     * ### An edge
     *
     * Where only the weaker eigenvalue is under that, or the stronger is more
     * than [LK_MAX_ANISOTROPY] times it, the tile has texture in one
     * direction -- an edge -- and its translation along the edge is not
     * determined. Each step is then taken along the strong axis u alone,
     * dp = 2 u (u . sum g e) / lambda_max, the least-squares step of least
     * norm, and the fraction along the edge stays at the winner's. Declining
     * here instead cost the 2x canvas its sharpest feature: the integer
     * search slides along a straight edge (the bench's slanted edges were
     * found eight pixels along themselves), which a five-degree slant turns
     * into most of a pixel across, and only this step, on this tile, can
     * take that back. A borrowed fraction cannot -- it is the frame's, and
     * this error is the tile's. Along a straight edge the canvas is the same
     * wherever the sample lands.
     *  * The estimate leaves a pixel either way of the integer winner: the
     *    search was wrong, or the fit diverged, and neither is a fraction.
     *  * The solved direction's gradient power, lambda_min / 4N (or
     *    lambda_max / 4N for an edge), is less than [LK_MIN_SNR] times s^2,
     *    the mean squared residual of the last iteration: noise, motion
     *    within the tile, or a tile that does not translate. A two-axis fit
     *    that fails this on its weaker axis but passes on its stronger keeps
     *    the part of its answer along the stronger, as for an edge. The estimate's standard error in that direction is
     *    sqrt(4 s^2 / lambda_min) = 1 / sqrt(N * ratio), so passing bounds it
     *    by 1 / sqrt(N), 0.03 pixels on a 32-pixel tile. Without this a tile
     *    of pure noise passes every other test and pins its fraction near
     *    zero, wherever the frame really moved; declined, it borrows its
     *    neighbours'. Byte-identical frames have zero residual and pass.
     *
     * A declined tile reads zero, which [AlignmentField.withFlatTilesBorrowed]
     * fills from the fitted tiles around it for the 2x canvas.
     *
     * Border pixels read the planes clamped, as [tileCost] does.
     */
    fun refineSubPixel(
        ref: Plane,
        alt: Plane,
        x0: Int,
        y0: Int,
        tileW: Int,
        tileH: Int,
        dx: Int,
        dy: Int,
        out: FloatArray,
    ) {
        val fit = LkFit()
        fitTile(ref, alt, x0, y0, tileW, tileH, dx.toDouble(), dy.toDouble(), fit)
        out[0] = fit.fx.toFloat()
        out[1] = fit.fy.toFloat()
    }

    /**
     * [refineSubPixel] from any start ([startX], [startY]), proxy pixels,
     * with how the tile was fitted and its last residual, for [consensus].
     * The fraction it reports is from that start, and its reach a pixel
     * either way of it.
     */
    internal fun fitTile(
        ref: Plane,
        alt: Plane,
        x0: Int,
        y0: Int,
        tileW: Int,
        tileH: Int,
        startX: Double,
        startY: Double,
        fit: LkFit,
    ) {
        fit.fx = 0.0
        fit.fy = 0.0
        fit.mode = LK_DECLINED
        fit.ee = 0L
        val n = tileW * tileH
        if (n == 0) return
        val rw = ref.width
        val rh = ref.height
        val rd = ref.data
        fun r(x: Int, y: Int) = rd[y.coerceIn(0, rh - 1) * rw + x.coerceIn(0, rw - 1)].toInt() and 0xFF

        val gx = IntArray(n)
        val gy = IntArray(n)
        val t = IntArray(n)
        var hxx = 0L
        var hxy = 0L
        var hyy = 0L
        for (yy in 0 until tileH) {
            val y = y0 + yy
            for (xx in 0 until tileW) {
                val x = x0 + xx
                val i = yy * tileW + xx
                val a = r(x + 1, y) - r(x - 1, y)
                val b = r(x, y + 1) - r(x, y - 1)
                gx[i] = a
                gy[i] = b
                t[i] = r(x, y) * LK_ONE
                hxx += a.toLong() * a
                hxy += a.toLong() * b
                hyy += b.toLong() * b
            }
        }
        val det = hxx.toDouble() * hyy.toDouble() - hxy.toDouble() * hxy.toDouble()
        val axes = lkAxes(hxx, hxy, hyy)
        val lambdaMin = axes[0]
        val lambdaMax = axes[1]
        val ux = axes[2]
        val uy = axes[3]
        // H is in (2 grad T)^2: a quarter of it is grad T's.
        if (lambdaMax / (4.0 * n) < LK_MIN_GRADIENT) return
        // Texture in one direction only -- an edge -- determines the
        // translation across it and nothing along it. The step is then taken
        // along the strong axis alone, the least-squares solution of least
        // norm, and the fraction along the edge is left at the winner's.
        val bothAxes = det > 0.0 && lambdaMin / (4.0 * n) >= LK_MIN_GRADIENT &&
            lambdaMax <= LK_MAX_ANISOTROPY * lambdaMin

        val aw = alt.width
        val ah = alt.height
        val ad = alt.data
        fun a(x: Int, y: Int) = ad[y.coerceIn(0, ah - 1) * aw + x.coerceIn(0, aw - 1)].toInt() and 0xFF

        var px = startX
        var py = startY
        var ee = 0L
        for (iteration in 0 until LK_ITERATIONS) {
            var ix = kotlin.math.floor(px).toInt()
            var wx = kotlin.math.floor((px - ix) * LK_ONE + 0.5).toInt()
            if (wx == LK_ONE) { ix++; wx = 0 }
            var iy = kotlin.math.floor(py).toInt()
            var wy = kotlin.math.floor((py - iy) * LK_ONE + 0.5).toInt()
            if (wy == LK_ONE) { iy++; wy = 0 }

            var bx = 0L
            var by = 0L
            ee = 0L
            for (yy in 0 until tileH) {
                val sy = y0 + yy + iy
                for (xx in 0 until tileW) {
                    val sx = x0 + xx + ix
                    val h0 = a(sx, sy) * (LK_ONE - wx) + a(sx + 1, sy) * wx
                    val h1 = a(sx, sy + 1) * (LK_ONE - wx) + a(sx + 1, sy + 1) * wx
                    val v = (h0 * (LK_ONE - wy) + h1 * wy + LK_ONE / 2) shr 8
                    val i = yy * tileW + xx
                    val e = v - t[i]
                    bx += gx[i].toLong() * e
                    by += gy[i].toLong() * e
                    ee += e.toLong() * e
                }
            }
            val step = if (bothAxes) lkStep(hxx, hxy, hyy, det, bx, by) else lkStepAlong(ux, uy, lambdaMax, bx, by)
            px -= step[0]
            py -= step[1]
            if (kotlin.math.abs(px - startX) > 1.0 || kotlin.math.abs(py - startY) > 1.0) return
            // Converged: a further step would move the warp by less than its
            // own weights can express.
            if (kotlin.math.abs(step[0]) < LK_CONVERGED && kotlin.math.abs(step[1]) < LK_CONVERGED) break
        }
        val fx = px - startX
        val fy = py - startY
        fit.ee = ee
        if (bothAxes && lkConfident(ee, n, lambdaMin)) {
            fit.fx = fx
            fit.fy = fy
            fit.mode = LK_BOTH
            return
        }
        // Both axes, but the weaker one no stronger than the residual: the
        // part of the answer across the strong axis still stands. One axis
        // from the start: that part is the whole answer.
        if (!lkConfident(ee, n, lambdaMax)) return
        val along = fx * ux + fy * uy
        fit.fx = along * ux
        fit.fy = along * uy
        fit.mode = LK_EDGE
    }

    /**
     * The eigen-decomposition of [[hxx, hxy], [hxy, hyy]]: the smaller
     * eigenvalue, the larger, and the larger's unit eigenvector (x, y).
     */
    internal fun lkAxes(hxx: Long, hxy: Long, hyy: Long): DoubleArray {
        val a = hxx.toDouble()
        val b = hxy.toDouble()
        val c = hyy.toDouble()
        val trace = a + c
        val diff = a - c
        val root = kotlin.math.sqrt(diff * diff + 4.0 * b * b)
        val lambdaMax = 0.5 * (trace + root)
        // Of the two forms of the eigenvector, the one with the larger
        // components; the other cancels to nothing when b is small.
        var vx: Double
        var vy: Double
        if (a >= c) { vx = lambdaMax - c; vy = b } else { vx = b; vy = lambdaMax - a }
        val norm = kotlin.math.sqrt(vx * vx + vy * vy)
        if (norm > 0.0) { vx /= norm; vy /= norm } else if (a >= c) { vx = 1.0; vy = 0.0 } else { vx = 0.0; vy = 1.0 }
        return doubleArrayOf(0.5 * (trace - root), lambdaMax, vx, vy)
    }

    /** dp along the strong axis u alone: 2 u (u . sum g e) / lambda_max, with e in 1/256 of a code. */
    private fun lkStepAlong(ux: Double, uy: Double, lambdaMax: Double, bx: Long, by: Long): DoubleArray {
        val along = 2.0 / LK_ONE * (ux * bx.toDouble() + uy * by.toDouble()) / lambdaMax
        return doubleArrayOf(along * ux, along * uy)
    }

    /** dp = 2 H^-1 sum g e, with e in 1/256 of a code. */
    private fun lkStep(hxx: Long, hxy: Long, hyy: Long, det: Double, bx: Long, by: Long): DoubleArray {
        val scale = 2.0 / LK_ONE / det
        return doubleArrayOf(
            scale * (hyy.toDouble() * bx.toDouble() - hxy.toDouble() * by.toDouble()),
            scale * (hxx.toDouble() * by.toDouble() - hxy.toDouble() * bx.toDouble()),
        )
    }

    /** Gradient power in the weaker direction against the residual's, both per pixel in codes^2. */
    private fun lkConfident(ee: Long, n: Int, lambdaMin: Double): Boolean {
        val residual = ee.toDouble() / n / (LK_ONE.toDouble() * LK_ONE)
        return lambdaMin / (4.0 * n) >= LK_MIN_SNR * residual
    }

    /** Fewer tiles fitted on both axes than this, and there is no frame motion to agree with. */
    const val CONSENSUS_MIN_TILES = 6

    /** A tile further than this from the frame's motion, proxy pixels, is tried from the frame's motion. */
    const val CONSENSUS_DISAGREEMENT = 0.5

    /** Tiles further than this from the median translation, proxy pixels, are not in the first fit: room for a rotation, none for a lost tile. */
    const val CONSENSUS_GATE = 4.0

    /** A tile further than this from a fit of the frame's motion is not part of the next fit. */
    const val CONSENSUS_TRIM = 1.0

    /**
     * Where the integer search slid, the frame's own motion takes it back.
     *
     * ### What this is for
     *
     * The tile search minimises a sum of absolute differences over every other
     * pixel, and two kinds of tile defeat it however good the fraction after
     * it: a straight edge, along which every offset costs the same, and a
     * periodic texture, which matches itself a period away. On the desk's
     * chart the search found its slanted edges eight to ten pixels along
     * themselves and parts of its Siemens star a pixel to five off, and with
     * the fractions exact the 2x canvas still lost to enlargement there --
     * replacing those tiles alone with the truth closed the whole gap.
     *
     * ### What it does
     *
     * A hand-held burst moves as a frame: translation and a little rotation.
     * The tiles fitted on both axes, starting from those within
     * [CONSENSUS_GATE] of their median translation, are fitted with an
     * affine motion, total displacement against tile centre, by least
     * squares, twice trimmed of tiles more than [CONSENSUS_TRIM] from the
     * fit. Fewer than a quarter of them left, and the frame has no one
     * motion to offer, and nothing is changed. A tile more
     * than [CONSENSUS_DISAGREEMENT] from that motion, and every tile fitted
     * as an edge -- whose place along itself its own fit left at the
     * winner's integer -- is refined again from
     * the motion's own prediction, and the new fit is kept where it fits at
     * all -- unless the tile's own fit was on both axes and fitted at least a
     * quarter better: a subject that really moved keeps its own offset.
     *
     * An edge's own fit gets no such say. Its residual cannot rank places
     * along it: the proxy bins 2x2 with no other filter, a sharp edge aliases
     * there into a staircase, and an integer slide along the edge reproduces
     * the staircase exactly where the true, fractional place must interpolate
     * it. On the bench's edges the slid winner fitted ten times better than
     * the true place. The margin leans toward the frame on purpose: a moving
     * subject placed by the frame's motion disagrees with the reference and
     * the canvas's robustness weight rejects it, but an edge slid along
     * itself agrees everywhere and is kept -- correct values twenty pixels
     * from where they belong, which leaves the rows they left with the
     * sub-pixel phases of fewer frames, and the bench measured exactly that
     * as an edge rendered as soft as a tripod burst's.
     *
     * ### What it leaves alone
     *
     * [dx] and [dy]. The CFA merge reads the integer winners and nothing
     * else, and this changes none of them: a re-seeded tile's displacement is
     * carried in its fraction, which may then exceed a pixel. Only the 2x
     * canvas reads it.
     */
    internal fun consensus(
        ref: Plane,
        alt: Plane,
        tilesX: Int,
        tilesY: Int,
        dx: IntArray,
        dy: IntArray,
        fracDx: FloatArray,
        fracDy: FloatArray,
        mode: IntArray,
        ee: LongArray,
    ) {
        val tileW = ref.width / tilesX
        val tileH = ref.height / tilesY
        val count = tilesX * tilesY
        val cx = DoubleArray(count) { (it % tilesX) * tileW + tileW / 2.0 }
        val cy = DoubleArray(count) { (it / tilesX) * tileH + tileH / 2.0 }
        val totalX = DoubleArray(count) { dx[it] + fracDx[it].toDouble() }
        val totalY = DoubleArray(count) { dy[it] + fracDy[it].toDouble() }
        // A robust start: the median translation of the tiles fitted on both
        // axes, and only those within [CONSENSUS_GATE] of it. A least-squares
        // fit to all of them is dragged by any tile the search lost by a
        // hundred pixels -- a television, a fence -- and a photograph has
        // them; a tripod burst's motion came out sixteen pixels.
        val both = (0 until count).filter { mode[it] == LK_BOTH }
        if (both.size < CONSENSUS_MIN_TILES) return
        val startX = consensusMedian(both.map { totalX[it] })
        val startY = consensusMedian(both.map { totalY[it] })
        val keep = BooleanArray(count) {
            mode[it] == LK_BOTH && kotlin.math.hypot(totalX[it] - startX, totalY[it] - startY) <= CONSENSUS_GATE
        }
        var motion: DoubleArray? = null
        for (pass in 0..2) {
            motion = affineMotion(cx, cy, totalX, totalY, keep) ?: return
            if (pass == 2) break
            for (i in 0 until count) {
                if (mode[i] != LK_BOTH) continue
                keep[i] = kotlin.math.hypot(totalX[i] - motionX(motion, cx[i], cy[i]), totalY[i] - motionY(motion, cx[i], cy[i])) <= CONSENSUS_TRIM
            }
        }
        // A motion most of the frame does not share is not the frame's.
        if (keep.count { it } * 4 < both.size) return
        val m = motion ?: return
        val fit = LkFit()
        for (i in 0 until count) {
            val px = motionX(m, cx[i], cy[i])
            val py = motionY(m, cx[i], cy[i])
            // An edge's place along itself is the frame's to give, near or far.
            if (mode[i] != LK_EDGE && kotlin.math.hypot(totalX[i] - px, totalY[i] - py) <= CONSENSUS_DISAGREEMENT) continue
            // From the motion's prediction itself, not its rounding: on an
            // edge the fit moves only across it, and the prediction is the
            // best word there is on where along it the tile sits.
            fitTile(ref, alt, (i % tilesX) * tileW, (i / tilesX) * tileH, tileW, tileH, px, py, fit)
            if (fit.mode == LK_DECLINED) continue
            // Only a tile fitted on both axes can say its own place is better.
            if (mode[i] == LK_BOTH && fit.ee * 4 > ee[i] * 5) continue
            fracDx[i] = (px + fit.fx - dx[i]).toFloat()
            fracDy[i] = (py + fit.fy - dy[i]).toFloat()
        }
    }

    private fun consensusMedian(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }

    private fun motionX(m: DoubleArray, x: Double, y: Double) = m[0] + m[1] * (x - m[6]) + m[2] * (y - m[7])
    private fun motionY(m: DoubleArray, x: Double, y: Double) = m[3] + m[4] * (x - m[6]) + m[5] * (y - m[7])

    /**
     * Least-squares affine motion of the kept tiles about their centroid:
     * (ax, bx, cx, ay, by, cy, mean x, mean y), with total = a + b (x - mx)
     * + c (y - my). A translation alone where the tiles do not span two
     * dimensions; null where too few are kept.
     */
    private fun affineMotion(cx: DoubleArray, cy: DoubleArray, tx: DoubleArray, ty: DoubleArray, keep: BooleanArray): DoubleArray? {
        var n = 0
        var mx = 0.0; var my = 0.0; var mtx = 0.0; var mty = 0.0
        for (i in keep.indices) if (keep[i]) { n++; mx += cx[i]; my += cy[i]; mtx += tx[i]; mty += ty[i] }
        if (n < CONSENSUS_MIN_TILES) return null
        mx /= n; my /= n; mtx /= n; mty /= n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var sxtx = 0.0; var sytx = 0.0; var sxty = 0.0; var syty = 0.0
        for (i in keep.indices) if (keep[i]) {
            val x = cx[i] - mx; val y = cy[i] - my
            sxx += x * x; sxy += x * y; syy += y * y
            sxtx += x * tx[i]; sytx += y * tx[i]; sxty += x * ty[i]; syty += y * ty[i]
        }
        val det = sxx * syy - sxy * sxy
        if (det <= 1e-6 * (sxx * syy + 1.0)) return doubleArrayOf(mtx, 0.0, 0.0, mty, 0.0, 0.0, mx, my)
        return doubleArrayOf(
            mtx, (syy * sxtx - sxy * sytx) / det, (sxx * sytx - sxy * sxtx) / det,
            mty, (syy * sxty - sxy * syty) / det, (sxx * syty - sxy * sxty) / det,
            mx, my,
        )
    }

    /**
     * The fractional offset of a minimum, from three costs a pixel apart.
     *
     * ### The equiangular fit, and why not the parabola
     *
     * The first version fitted a parabola through (-1, [before]), (0, [at])
     * and (+1, [after]) and took its vertex, `(before - after) / (2 * (before
     * - 2*at + after))` -- the textbook refinement, and the right one for a
     * cost that is quadratic near its minimum. A sum of absolute differences
     * is not. Shift a textured image by t and the SAD against the unshifted
     * one grows as |t|, not t²: it is a V, and a parabola through three
     * points of a V puts its vertex at `t / (2 * (1 - t))` rather than at t.
     * That is a bias toward zero of nearly half, and it was measured before
     * it was reasoned: `SubPixelBiasDeviceTest` sweeps a known shift through
     * the real proxy and search at the sensor's real width, and the parabola
     * returned 0.26 for a true 0.50 and 0.43 for 0.70.
     *
     * The equiangular fit models the V. Its two arms meet where
     * `(before - after) / (2 * (max(before, after) - at))`, which is exactly
     * t for a V through the three points and within a few hundredths for
     * what a real SAD does near its minimum. The same sweep with it: 0.41 for
     * 0.50, 0.60 for 0.70, 0.90 for 1.00 -- a slope of one with a tenth of a
     * pixel of offset that comes from the other axis' unrefined fraction
     * leaking into this one through diagonal texture, which a separable
     * three-point refinement cannot see. See the test's log for the curve.
     *
     * No longer what the search uses: [refineSubPixel] replaced it, after
     * a known-shift bench measured 0.32 sensor pixels of median error from
     * it. Kept, with its tests, as the record of what the three-point fit
     * can and cannot do.
     *
     * This matters more for the 2x canvas than for the merge. The merge
     * blends samples over a tile and a quarter-pixel error softens nothing
     * visible; the canvas *places* each sample by this fraction, and a bias of
     * a quarter of a sensor pixel is half a canvas cell -- the kernel's whole
     * sigma -- on every sample of every frame.
     *
     * Three cases return zero rather than a number:
     *
     *  * a flat or inverted curvature, where the three points do not describe
     *    a minimum at all -- a tile with no texture, or one where the search
     *    landed on a plateau;
     *  * a vertex outside ±0.5, which means the integer search was wrong by at
     *    least a pixel and no fit through these three points can fix that --
     *    clamping to the edge would assert a precision the fit does not have;
     *  * the sentinel [Long.MAX_VALUE], which is what an early-exit cost reads
     *    as and is not a measurement.
     *
     * Computed in double: the costs are sums over a tile and run to millions,
     * and their difference is the small number that matters.
     */
    fun subPixelOffset(before: Long, at: Long, after: Long): Float {
        if (before == Long.MAX_VALUE || at == Long.MAX_VALUE || after == Long.MAX_VALUE) return 0f
        val b = before.toDouble()
        val a = at.toDouble()
        val c = after.toDouble()
        if (b - 2.0 * a + c <= 0.0) return 0f
        val steep = maxOf(b, c) - a
        if (steep <= 0.0) return 0f
        val vertex = (b - c) / (2.0 * steep)
        if (vertex < -0.5 || vertex > 0.5) return 0f
        return vertex.toFloat()
    }

    /**
     * The same search, in native code, or null where that is not available.
     *
     * Measured on a Pixel 9 Pro XL, this search was 2858 ms of a 3427 ms merge
     * across five frames -- 83% of it -- while the native accumulation it is
     * grouped with under one "merge" figure was 295 ms. The inner loop is a sum
     * of absolute differences over bytes, which ARM has dedicated instructions
     * for and the JVM does one byte at a time.
     *
     * The Kotlin above stays as the reference implementation. It is what the
     * unit tests cover and what [AlignerParityTest] holds this to, in the same
     * arrangement the develop stage already uses.
     */
    fun alignNative(
        refProxy: Plane,
        altProxy: Plane,
        tilesX: Int,
        tilesY: Int,
    ): AlignmentField? {
        if (!NativeMerge.isAvailable()) return null
        if (refProxy.width != altProxy.width || refProxy.height != altProxy.height) return null
        if (tilesX <= 0 || tilesY <= 0) return null

        val count = tilesX * tilesY
        val dx = IntArray(count)
        val dy = IntArray(count)
        val fracDx = FloatArray(count)
        val fracDy = FloatArray(count)
        val ok = try {
            nAlign(
                refProxy.data, altProxy.data, refProxy.width, refProxy.height,
                tilesX, tilesY, dx, dy, fracDx, fracDy,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "native align failed, using Kotlin", e)
            false
        }
        return if (ok) AlignmentField(tilesX, tilesY, dx, dy, fracDx, fracDy) else null
    }

    private external fun nAlign(
        ref: ByteArray,
        alt: ByteArray,
        width: Int,
        height: Int,
        tilesX: Int,
        tilesY: Int,
        outDx: IntArray,
        outDy: IntArray,
        outFracDx: FloatArray,
        outFracDy: FloatArray,
    ): Boolean

    /** Sum of absolute differences, abandoning early once [ceiling] is exceeded. */
    private fun tileCost(
        ref: Plane,
        alt: Plane,
        x0: Int,
        y0: Int,
        tileW: Int,
        tileH: Int,
        offX: Int,
        offY: Int,
        step: Int,
        ceiling: Long,
    ): Long {
        var cost = 0L
        val rd = ref.data
        val ad = alt.data
        val aw = alt.width
        val ah = alt.height

        var yy = 0
        while (yy < tileH) {
            val ry = y0 + yy
            var ay = ry + offY
            if (ay < 0) ay = 0 else if (ay >= ah) ay = ah - 1
            val rRow = ry * ref.width
            val aRow = ay * aw

            var xx = 0
            while (xx < tileW) {
                val rx = x0 + xx
                var ax = rx + offX
                if (ax < 0) ax = 0 else if (ax >= aw) ax = aw - 1

                val d = (rd[rRow + rx].toInt() and 0xFF) - (ad[aRow + ax].toInt() and 0xFF)
                cost += if (d < 0) -d.toLong() else d.toLong()
                xx += step
            }
            if (cost >= ceiling) return cost
            yy += step
        }
        return cost
    }
}
