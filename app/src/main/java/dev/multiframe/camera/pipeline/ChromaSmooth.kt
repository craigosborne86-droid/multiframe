package dev.multiframe.camera.pipeline

import kotlin.math.roundToInt

/**
 * Chrominance smoothing: the coloured salt-and-pepper taken out of a
 * high-ISO frame, with the luminance -- and so the detail -- left alone.
 *
 * ### Why chroma, and why here
 *
 * Sensor noise is per site and per colour. After the demosaic and the matrix
 * it lands in every channel, but the eye reads it two ways: as luminance
 * grain, which is fine texture and reads as film, and as *colour* speckle --
 * a red pixel beside a green one in what was a grey wall -- which reads as
 * damage. The eye's spatial resolution for colour is a fraction of its
 * resolution for brightness, so smoothing the colour over a few pixels costs
 * nothing it could see and removes the speckle it could. Every raw converter
 * does this by default; Lightroom's colour noise reduction is on at 25 out of
 * the box. This pipeline did not, and a telephoto tile at ISO 3200 was
 * confetti.
 *
 * It runs on the gamma-encoded picture, after the tone curve and before the
 * sharpen (REFERENCE.md §3.1): the speckle is perceptually uniform there,
 * where in linear light a dark pixel's noise is a tenth of a bright one's;
 * and the sharpen must not see it, or it turns grain into static.
 *
 * ### The filter
 *
 * A joint bilateral on the two colour differences, guided by luminance, at
 * *half* resolution: the picture's Y, Cb and Cr are box-averaged two by two,
 * the smoothing runs on that, and the smoothed chroma is read back
 * bilinearly for every full-resolution pixel and blended in by [Params.amount].
 * Half resolution because the eye resolves colour at a fraction of the
 * acuity it resolves brightness -- which is why every JPEG this pipeline
 * writes carries its chroma at half resolution already (4:2:0), and why the
 * eye never notices -- and because it makes the pass four times cheaper: at
 * full resolution it cost 380 ms of a 12.5-megapixel develop against 80 for
 * the sharpen. Y is written back exactly, at full resolution, so no
 * brightness and no detail moves anywhere; a coloured edge is softened by at
 * most the pixel the JPEG would have softened it by anyway.
 *
 * The smoothing itself is two separable passes -- along the rows, then down
 * the columns -- of `2r+1` taps each. Each tap's weight falls with how far
 * its luminance is from the centre pixel's:
 *
 *     w(ΔY) = max(0, 1 − (ΔY / (2.5σ))²)²
 *
 * a clamped quadratic that follows a Gaussian of width σ closely -- 0.71
 * against 0.61 at ΔY = σ, 0.13 against 0.14 at 2σ -- and reaches zero at
 * 2.5σ, so a colour on one side of a brightness edge never bleeds to the
 * other, which is what keeps a red flower's petal edge red and the leaf
 * behind it green. Flat in space, so every neighbour of like brightness
 * counts alike. The kernel is arithmetic, not a table, so a vector unit does
 * four pixels of it at a time.
 *
 * Pure. The native passes in bayer_merge.cpp and LinearDevelop.cpp mirror this
 * arithmetic, and the device parity test holds them to it within two codes.
 */
object ChromaSmooth {

    data class Params(
        /** Taps either side of the pixel in each pass, in half-resolution pixels: 2 is five taps. */
        val radius: Int = 2,
        /** The luminance width of the kernel, in 8-bit codes; the reach is 2.5 of it. */
        val lumaSigma: Float = 12f,
        /** How much of the smoothed chroma replaces the original; zero is off. */
        val amount: Float = 1f,
    ) {
        val enabled: Boolean get() = amount > 0f && radius > 0
    }

    /** The kernel reaches zero at this many sigmas. */
    const val REACH_SIGMAS = 2.5f

    /** Rec. 709 luma, as the rest of the display-domain passes read it. */
    fun luma(r: Int, g: Int, b: Int): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** The weight of a neighbour whose luminance differs by [delta] codes. */
    fun weight(delta: Float, lumaSigma: Float): Float {
        val t = delta / (REACH_SIGMAS * lumaSigma)
        val q = 1f - t * t
        if (q <= 0f) return 0f
        return q * q
    }

    /** Smooths the chroma of packed ARGB pixels in place. */
    fun apply(pixels: IntArray, width: Int, height: Int, params: Params = Params()) {
        if (!params.enabled || width < 4 || height < 4) return
        val r = params.radius
        val invReach = 1f / (REACH_SIGMAS * params.lumaSigma)

        // Half-resolution planes: each a two-by-two box mean of the picture's
        // Y, Cb and Cr. An odd edge row or column is its own box.
        val hw = (width + 1) / 2
        val hh = (height + 1) / 2
        val y = FloatArray(hw * hh)
        val cb = FloatArray(hw * hh)
        val cr = FloatArray(hw * hh)
        for (j in 0 until hh) {
            for (i in 0 until hw) {
                var sumY = 0f
                var sumCb = 0f
                var sumCr = 0f
                var n = 0
                for (dy in 0..1) {
                    val py = j * 2 + dy
                    if (py >= height) continue
                    for (dx in 0..1) {
                        val px = i * 2 + dx
                        if (px >= width) continue
                        val p = pixels[py * width + px]
                        val red = (p shr 16) and 0xFF
                        val green = (p shr 8) and 0xFF
                        val blue = p and 0xFF
                        val l = luma(red, green, blue)
                        sumY += l
                        sumCb += blue - l
                        sumCr += red - l
                        n++
                    }
                }
                val k = j * hw + i
                y[k] = sumY / n
                cb[k] = sumCb / n
                cr[k] = sumCr / n
            }
        }

        // Pass one: along the half-resolution rows.
        val cbH = FloatArray(hw * hh)
        val crH = FloatArray(hw * hh)
        for (j in 0 until hh) {
            val row = j * hw
            for (i in 0 until hw) {
                val centre = y[row + i]
                var sumW = 0f
                var sumCb = 0f
                var sumCr = 0f
                for (dx in -r..r) {
                    val ni = (i + dx).coerceIn(0, hw - 1)
                    val w = weightOf(y[row + ni] - centre, invReach)
                    sumW += w
                    sumCb += w * cb[row + ni]
                    sumCr += w * cr[row + ni]
                }
                cbH[row + i] = sumCb / sumW
                crH[row + i] = sumCr / sumW
            }
        }

        // Pass two: down the half-resolution columns of the row-smoothed planes.
        val cbS = FloatArray(hw * hh)
        val crS = FloatArray(hw * hh)
        for (j in 0 until hh) {
            for (i in 0 until hw) {
                val k = j * hw + i
                val centre = y[k]
                var sumW = 0f
                var sumCb = 0f
                var sumCr = 0f
                for (dy in -r..r) {
                    val nj = (j + dy).coerceIn(0, hh - 1)
                    val m = nj * hw + i
                    val w = weightOf(y[m] - centre, invReach)
                    sumW += w
                    sumCb += w * cbH[m]
                    sumCr += w * crH[m]
                }
                cbS[k] = sumCb / sumW
                crS[k] = sumCr / sumW
            }
        }

        // Back to full resolution, guided by luminance again: the smoothed
        // chroma is read at each pixel's position in the half planes from the
        // four half-pixels around it, each weighted by the bilinear distance
        // *and* by how near its luminance is to the pixel's own. A plain
        // bilinear read would take a quarter of the far side of a hard edge
        // into the pixel beside it -- a red petal's edge pixel turning a
        // quarter green -- which is what a JPEG's chroma does and what this
        // does not have to. Where no tap is of like brightness -- a thin
        // coloured line one pixel wide -- the pixel keeps its own chroma.
        for (py in 0 until height) {
            val fy = ((py + 0.5f) / 2f - 0.5f).coerceIn(0f, (hh - 1).toFloat())
            val j0 = fy.toInt().coerceAtMost(hh - 1)
            val j1 = minOf(j0 + 1, hh - 1)
            val ty = fy - j0
            for (px in 0 until width) {
                val fx = ((px + 0.5f) / 2f - 0.5f).coerceIn(0f, (hw - 1).toFloat())
                val i0 = fx.toInt().coerceAtMost(hw - 1)
                val i1 = minOf(i0 + 1, hw - 1)
                val tx = fx - i0
                val index = py * width + px
                val p = pixels[index]
                val red = (p shr 16) and 0xFF
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF
                val l = luma(red, green, blue)
                val ownCb = blue - l
                val ownCr = red - l

                var sumW = 0f
                var sumCb = 0f
                var sumCr = 0f
                fun tap(k: Int, bilinear: Float) {
                    val w = bilinear * weightOf(y[k] - l, invReach)
                    sumW += w
                    sumCb += w * cbS[k]
                    sumCr += w * crS[k]
                }
                tap(j0 * hw + i0, (1f - tx) * (1f - ty))
                tap(j0 * hw + i1, tx * (1f - ty))
                tap(j1 * hw + i0, (1f - tx) * ty)
                tap(j1 * hw + i1, tx * ty)
                if (sumW <= 1e-6f) continue
                val sCb = sumCb / sumW
                val sCr = sumCr / sumW
                val smoothedCb = ownCb + (sCb - ownCb) * params.amount
                val smoothedCr = ownCr + (sCr - ownCr) * params.amount
                pixels[index] = pack(p, l, smoothedCb, smoothedCr)
            }
        }
    }

    /** [weight], with the reach's reciprocal already taken. The centre tap is always one. */
    private fun weightOf(delta: Float, invReach: Float): Float {
        val t = delta * invReach
        val q = 1f - t * t
        return if (q <= 0f) 0f else q * q
    }

    /** Back to RGB with the luminance kept exactly: R and B from the differences, G from what Y demands. */
    fun pack(original: Int, y: Float, cb: Float, cr: Float): Int {
        val red = y + cr
        val blue = y + cb
        val green = (y - 0.2126f * red - 0.0722f * blue) / 0.7152f
        return (original.toLong() and 0xFF000000L).toInt() or
            (code(red) shl 16) or (code(green) shl 8) or code(blue)
    }

    private fun code(v: Float): Int = v.roundToInt().coerceIn(0, 255)
}
