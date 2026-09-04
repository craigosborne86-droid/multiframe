package dev.multiframe.camera.pipeline

import android.util.Log

private const val TAG = "Multiframe"

/**
 * Per-tile translation offsets for one alternate frame, expressed at full
 * resolution. Tiles are a fixed grid so offsets propagate trivially between
 * pyramid levels.
 */
class AlignmentField(
    val tilesX: Int,
    val tilesY: Int,
    val dx: IntArray,
    val dy: IntArray,
)

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
                }
            }
        }

        return AlignmentField(tilesX, tilesY, dx, dy)
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
        val ok = try {
            nAlign(
                refProxy.data, altProxy.data, refProxy.width, refProxy.height,
                tilesX, tilesY, dx, dy,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "native align failed, using Kotlin", e)
            false
        }
        return if (ok) AlignmentField(tilesX, tilesY, dx, dy) else null
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
