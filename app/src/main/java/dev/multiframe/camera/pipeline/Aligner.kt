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
 * [fracDx] and [fracDy] are the sub-pixel refinement of those winners -- where
 * the true minimum sits between the integer candidates, from a parabola fitted
 * through the cost at the winner and its two neighbours on each axis. Bounded
 * to half a pixel either way. The full sub-pixel displacement of a tile is
 * `dx + fracDx`, in proxy pixels; the merge never reads it, and the sub-pixel
 * accumulation in `docs/REFERENCE.md` §3.6 exists to.
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

                    // Where between the integer candidates the minimum really
                    // sits. Only at the finest level: a refinement made higher
                    // up would be doubled into the next level's integer search
                    // and lost. See [subPixelOffset] for the fit.
                    if (level == 0) {
                        val left = tileCost(ref, alt, x0, y0, tileW, tileH, bestDx - 1, bestDy, step, Long.MAX_VALUE)
                        val right = tileCost(ref, alt, x0, y0, tileW, tileH, bestDx + 1, bestDy, step, Long.MAX_VALUE)
                        val up = tileCost(ref, alt, x0, y0, tileW, tileH, bestDx, bestDy - 1, step, Long.MAX_VALUE)
                        val down = tileCost(ref, alt, x0, y0, tileW, tileH, bestDx, bestDy + 1, step, Long.MAX_VALUE)
                        fracDx[idx] = subPixelOffset(left, bestCost, right)
                        fracDy[idx] = subPixelOffset(up, bestCost, down)
                    }
                }
            }
        }

        return AlignmentField(tilesX, tilesY, dx, dy, fracDx, fracDy)
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
