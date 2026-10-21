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
 * flat or one-directional tile, or an estimate it could not stand behind. The
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
     * borrows the measurement. A field with no fitted tile at all is a
     * tripod, and is returned as it is.
     */
    fun withFlatTilesBorrowed(): AlignmentField {
        val count = dx.size
        val fitted = (0 until count).filter { hasSubPixelAt(it) }
        if (fitted.isEmpty() || fitted.size == count) return this
        val outX = fracDx.copyOf()
        val outY = fracDy.copyOf()
        val fieldX = median(fitted.map { fracDx[it] })
        val fieldY = median(fitted.map { fracDy[it] })
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
                if (hasSubPixelAt(j)) near.add(j)
            }
            if (near.isEmpty()) {
                outX[i] = fieldX
                outY[i] = fieldY
            } else {
                outX[i] = median(near.map { fracDx[it] })
                outY[i] = median(near.map { fracDy[it] })
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
        val fraction = FloatArray(2)

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
                        refineSubPixel(ref, alt, x0, y0, tileW, tileH, bestDx, bestDy, fraction)
                        fracDx[idx] = fraction[0]
                        fracDy[idx] = fraction[1]
                    }
                }
            }
        }

        return AlignmentField(tilesX, tilesY, dx, dy, fracDx, fracDy)
    }

    /** Gauss-Newton updates of the translation. Two settle a fraction within a hundredth; the third is margin. */
    const val LK_ITERATIONS = 3

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
     *  * The weaker eigenvalue of H, per pixel, is under [LK_MIN_GRADIENT]
     *    (in (codes/pixel)^2 of grad T): a flat tile, or an edge, whose
     *    translation along itself is not determined.
     *  * The estimate leaves a pixel either way of the integer winner: the
     *    search was wrong, or the fit diverged, and neither is a fraction.
     *  * The weaker direction's gradient power, lambda_min / 4N, is less than
     *    [LK_MIN_SNR] times s^2, the mean squared residual of the last
     *    iteration: noise, motion within the tile, or a tile that does not
     *    translate. The estimate's standard error in that direction is
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
        out[0] = 0f
        out[1] = 0f
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
        if (det <= 0.0) return
        val lambdaMin = lkLambdaMin(hxx, hxy, hyy)
        // H is in (2 grad T)^2: a quarter of it is grad T's.
        if (lambdaMin / (4.0 * n) < LK_MIN_GRADIENT) return

        val aw = alt.width
        val ah = alt.height
        val ad = alt.data
        fun a(x: Int, y: Int) = ad[y.coerceIn(0, ah - 1) * aw + x.coerceIn(0, aw - 1)].toInt() and 0xFF

        var px = dx.toDouble()
        var py = dy.toDouble()
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
            val step = lkStep(hxx, hxy, hyy, det, bx, by)
            px -= step[0]
            py -= step[1]
            if (kotlin.math.abs(px - dx) > 1.0 || kotlin.math.abs(py - dy) > 1.0) return
            // Converged: a further step would move the warp by less than its
            // own weights can express.
            if (kotlin.math.abs(step[0]) < LK_CONVERGED && kotlin.math.abs(step[1]) < LK_CONVERGED) break
        }
        if (!lkConfident(ee, n, lambdaMin)) return
        out[0] = (px - dx).toFloat()
        out[1] = (py - dy).toFloat()
    }

    /** The smaller eigenvalue of [[hxx, hxy], [hxy, hyy]]. */
    internal fun lkLambdaMin(hxx: Long, hxy: Long, hyy: Long): Double {
        val trace = hxx.toDouble() + hyy.toDouble()
        val diff = hxx.toDouble() - hyy.toDouble()
        val root = kotlin.math.sqrt(diff * diff + 4.0 * hxy.toDouble() * hxy.toDouble())
        return 0.5 * (trace - root)
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
