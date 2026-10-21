package dev.multiframe.camera.pipeline

import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The HDR rendition, carried as a gain map beside the SDR picture: REFERENCE
 * §3.1 step 6b.
 *
 * ### What it is
 *
 * The SDR picture is the develop as it always was, to the last code. The
 * HDR rendition is the same linear develop with the filmic shoulder's
 * display white raised from SDR white to [DevelopParams.HDR_HEADROOM] -- two
 * stops over it -- and what the file carries is the ratio between the two,
 * one number per 4x4 block of the picture: an ISO 21496-1 / Ultra HDR gain
 * map. A display with headroom multiplies the SDR picture by it, a display
 * without it shows the SDR picture, and nothing in either is anything the
 * sensor did not measure.
 *
 * ### Why the ratio is a function of the scene peak alone
 *
 * The shoulder is hue-preserving: it compresses the brightest channel and
 * scales all three by the same factor (`ToneCurve.renderLinear` stage 1).
 * The relaxed shoulder does the same, so for any pixel the two renditions
 * differ by one factor, `H(p) / S(p)` of its scene peak `p` -- the same in
 * all three channels. The highlight desaturation that follows mixes toward
 * the peak by an amount keyed on the scene luminance, identical in both
 * renditions, and a mix commutes with a scale. So the map is single-channel
 * by construction; a colour gain map would carry the same number three times.
 *
 * The local operator (§3.5) runs before both shoulders, so the ratio is
 * read at the peak *after* it: the measurement builds the same grid the
 * develop builds and scales each block by its gain. Left out, it was the
 * whole of the map's error on the desk -- the operator's compression about
 * the mean pulls a bright neighbourhood down by up to a third of a stop, and
 * a map that ignored it was that much too bright (`UltraHdrBench`: median
 * error 0.1-0.3 stops against the exact map without it, 0.004-0.02 with it).
 *
 * ### Why it is measured from the CFA and not from the picture
 *
 * So that the SDR path is not touched at all. The measurement reads the
 * merged CFA a second time, a 4x4 block at a time, through the first steps of
 * §3.1 (black, shading, white balance, the matrix and the exposure gain, the
 * purity compression, the local operator); the picture is then cropped, corrected for the lens
 * and turned, and [Placement] carries each gain-map pixel back through those
 * same three steps to the block it came from. Neither develop knows the map
 * exists.
 */
object HdrGainMap {

    /** The map is a quarter of the picture's width and height. */
    const val SCALE = 4

    /** A scene whose largest ratio is under this has no highlights to extend; it is written SDR. */
    const val MIN_USEFUL_RATIO = 1.02f

    /**
     * The relaxed shoulder: [ToneCurve.shoulder] with its display white at
     * [peak] rather than at 1.
     *
     * Identity to the knee. Past it, where the sensor's clip [white] is
     * under the peak the scene fits the display as it is and the curve stays
     * the identity to the clip -- the relaxed shoulder has nothing to
     * compress, and expanding the scene to fill the headroom would invent a
     * contrast it did not have. Where the clip is over the peak it is the
     * same logarithm as the SDR shoulder, C¹ at the knee, reaching [peak]
     * exactly at [white]. Since the develop holds the white at or under
     * four (`DevelopParams.SHOULDER_WHITE_MAX`) and the peak is four, the
     * shipped rendition is always the first case.
     */
    fun relaxedShoulder(x: Float, knee: Float, white: Float, peak: Float): Float {
        if (x <= 0f) return 0f
        if (x <= knee) return x
        if (white <= peak) return min(x, white)
        if (x >= white) return peak
        val headroom = peak - knee
        if (headroom <= 0f) return knee
        val uMax = (white - knee) / headroom
        val a = ToneCurve.shoulderSteepness(uMax)
        val u = (x - knee) / headroom
        return knee + headroom * (ln(1f + a * u) / ln(1f + a * uMax))
    }

    /**
     * The HDR-over-SDR ratio for a pixel of scene peak [peak], under
     * parameters resolved for their gain ([DevelopParams.withGain]). At
     * least one -- the HDR rendition is never darker than the SDR one, which
     * a white under nominal would otherwise make it -- and at most
     * [headroom].
     */
    fun ratio(peak: Float, params: DevelopParams, headroom: Float): Float {
        val knee = params.shoulderKnee
        if (peak <= knee || headroom <= 1f) return 1f
        val white = if (params.shoulderWhite > 0f) params.shoulderWhite
        else DevelopParams.shoulderWhiteFor(params.exposureGain)
        val sdr = ToneCurve.shoulder(peak, knee, white)
        if (sdr <= 0f) return 1f
        return (relaxedShoulder(peak, knee, white, headroom) / sdr).coerceIn(1f, headroom)
    }

    /**
     * Log2 ratios over the sensor, one per [scale]x[scale] block, in the
     * orientation and at the size the develop read.
     */
    class Field(val width: Int, val height: Int, val scale: Int, val log2: FloatArray) {

        /** Bilinear in log2, at a sensor-pixel coordinate (pixel centres on integers). */
        fun sample(sx: Float, sy: Float): Float {
            val fx = ((sx - (scale - 1) / 2f) / scale).coerceIn(0f, (width - 1).toFloat())
            val fy = ((sy - (scale - 1) / 2f) / scale).coerceIn(0f, (height - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val tx = fx - x0
            val ty = fy - y0
            val top = log2[y0 * width + x0] * (1f - tx) + log2[y0 * width + x1] * tx
            val bottom = log2[y1 * width + x0] * (1f - tx) + log2[y1 * width + x1] * tx
            return top * (1f - ty) + bottom * ty
        }
    }

    /**
     * Measures the ratio over [cfa], the merged mosaic of [width] x [height].
     *
     * [params] must be resolved for the gain the develop used
     * ([DevelopParams.withGain]); [headroom] is the HDR white over SDR white.
     *
     * Each block's sites go through black, shading and white balance, and
     * each site's samples are averaged with their largest left out: a hot
     * pixel at the clip, one of four red samples in a dark block, would
     * otherwise read as a highlight a stop over the knee and leave a bright
     * square in the HDR rendition. The block's colour then takes the matrix
     * and the gain and the purity compression. Where the local operator is
     * on, its grid is built as `BilateralGrid.fromFrame` builds it -- every
     * 2x2 cell's luminance, untrimmed -- and the block is scaled by its gain
     * at the block's centre. The peak then gives the ratio.
     */
    fun measure(
        cfa: ShortBuffer,
        width: Int,
        height: Int,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams,
        shading: ShadingMap?,
        headroom: Float,
        scale: Int = SCALE,
    ): Field {
        require(scale >= 2 && scale % 2 == 0) { "the block must hold whole CFA cells" }
        val fw = max(1, width / scale)
        val fh = max(1, height / scale)
        val blockRgb = FloatArray(fw * fh * 3)
        val range = sensor.range.toFloat()
        val black = IntArray(4) { sensor.blackAt(it and 1, it shr 1) }
        // Site index -> CFA colour, and its white balance (the greens differ by row).
        val colour = IntArray(4) { sensor.cfaPattern[it] }
        val balance = FloatArray(4) { color.gainFor(colour[it], it shr 1) }
        val m = color.matrix
        val gain = params.exposureGain
        val perSite = (scale / 2) * (scale / 2)
        val local = params.localTone > 0f || params.shadowLift > 0f
        // One luminance per 2x2 cell for the grid, as fromFrame splats it.
        val cellsX = width / 2
        val cellsY = height / 2
        val cellLuma = if (local) FloatArray(cellsX * cellsY) else null

        Bands.rows(fh) { rowStart, rowEnd ->
            val sum = FloatArray(4)
            val top = FloatArray(4)
            val rgb = FloatArray(3)
            val cell = FloatArray(4)
            val shade = FloatArray(4) { 1f }
            for (by in rowStart until rowEnd) {
                val y0 = min(by * scale, height - scale).coerceAtLeast(0)
                for (bx in 0 until fw) {
                    val x0 = min(bx * scale, width - scale).coerceAtLeast(0)
                    // Shading is smooth over hundreds of pixels; once per block is exact enough.
                    if (shading != null) {
                        val cx = x0 + scale / 2
                        val cy = y0 + scale / 2
                        for (s in 0 until 4) {
                            shade[s] = shading.gainAt(cx + (s and 1), cy + (s shr 1), width, height, s)
                        }
                    }
                    sum.fill(0f)
                    top.fill(0f)
                    for (dy in 0 until min(scale, height) step 2) {
                        for (dx in 0 until min(scale, width) step 2) {
                            for (s in 0 until 4) {
                                val x = x0 + dx + (s and 1)
                                val y = y0 + dy + (s shr 1)
                                val site = (y and 1) * 2 + (x and 1)
                                val raw = cfa.get(y * width + x).toInt() and 0xFFFF
                                val v = ((raw - black[site]) / range).coerceAtLeast(0f)
                                sum[site] += v
                                if (v > top[site]) top[site] = v
                                cell[site] = v
                            }
                            if (cellLuma != null) {
                                toLinear(cell, shade, balance, colour, m, gain, rgb)
                                ToneCurve.compressPurity(rgb, params.purityThreshold, params.purityCeiling)
                                val cx = (x0 + dx) / 2
                                val cy = (y0 + dy) / 2
                                if (cx < cellsX && cy < cellsY) {
                                    cellLuma[cy * cellsX + cx] = BilateralGrid.luminance(rgb[0], rgb[1], rgb[2])
                                }
                            }
                        }
                    }
                    for (s in 0 until 4) {
                        sum[s] = if (perSite > 1) (sum[s] - top[s]) / (perSite - 1) else sum[s]
                    }
                    toLinear(sum, shade, balance, colour, m, gain, rgb)
                    ToneCurve.compressPurity(rgb, params.purityThreshold, params.purityCeiling)
                    val i = (by * fw + bx) * 3
                    blockRgb[i] = rgb[0]; blockRgb[i + 1] = rgb[1]; blockRgb[i + 2] = rgb[2]
                }
            }
        }

        // The grid splats in one thread: it is a sum, and its order is its own.
        val grid = cellLuma?.let { luma ->
            BilateralGrid.forImage(width, height).apply {
                for (cy in 0 until cellsY) for (cx in 0 until cellsX) splat(cx * 2, cy * 2, luma[cy * cellsX + cx])
                finish()
            }
        }

        val out = FloatArray(fw * fh)
        Bands.rows(fh) { rowStart, rowEnd ->
            for (by in rowStart until rowEnd) {
                for (bx in 0 until fw) {
                    val i = (by * fw + bx) * 3
                    var r = blockRgb[i]; var g = blockRgb[i + 1]; var b = blockRgb[i + 2]
                    if (grid != null) {
                        val k = grid.gain(
                            min(bx * scale + scale / 2, width - 1), min(by * scale + scale / 2, height - 1),
                            BilateralGrid.luminance(r, g, b),
                            params.localTone, params.shadowLift, params.shadowLiftMax,
                        )
                        r *= k; g *= k; b *= k
                    }
                    out[by * fw + bx] = log2(ratio(max(r, max(g, b)), params, headroom))
                }
            }
        }
        return Field(fw, fh, scale, out)
    }

    /** Four site values (CFA order) to linear sRGB at the exposure gain, into [rgb]. */
    private fun toLinear(
        site: FloatArray, shade: FloatArray, balance: FloatArray, colour: IntArray,
        m: FloatArray, gain: Float, rgb: FloatArray,
    ) {
        var r = 0f
        var g = 0f
        var b = 0f
        var greens = 0
        for (s in 0 until 4) {
            val v = site[s] * shade[s] * balance[s]
            when (colour[s]) {
                0 -> r = v
                2 -> b = v
                else -> { g += v; greens++ }
            }
        }
        if (greens > 0) g /= greens
        rgb[0] = (m[0] * r + m[1] * g + m[2] * b) * gain
        rgb[1] = (m[3] * r + m[4] * g + m[5] * b) * gain
        rgb[2] = (m[6] * r + m[7] * g + m[8] * b) * gain
    }

    /**
     * Where a pixel of the finished picture came from on the sensor.
     *
     * The picture is the develop's output corrected for the lens and cropped
     * to what the correction could read ([inset], [distortion]: `Optics.
     * applyCropped`), cropped again to the framing ([framingLeft],
     * [framingTop]), then turned [rotation] degrees clockwise
     * (`OrientationTracker.rotate`). [width] x [height] is the finished
     * picture. Each step is undone in reverse; integer coordinates are pixel
     * centres, as every gather in the develop has them.
     */
    class Placement(
        val width: Int,
        val height: Int,
        rotation: Int = 0,
        val framingLeft: Int = 0,
        val framingTop: Int = 0,
        val inset: LensDistortion.Inset = LensDistortion.Inset.NONE,
        val distortion: LensDistortion.Model? = null,
    ) {
        val rotation = ((rotation % 360) + 360) % 360

        init {
            require(this.rotation % 90 == 0) { "a turn of $rotation is not a quarter turn" }
        }

        /** The finished pixel ([x], [y]) as a sensor coordinate, into [out]. */
        fun sensorOf(x: Float, y: Float, out: FloatArray) {
            // The picture before the turn: a quarter turn swaps its sides.
            val preW = if (rotation == 90 || rotation == 270) height else width
            val preH = if (rotation == 90 || rotation == 270) width else height
            val ux: Float
            val uy: Float
            when (rotation) {
                90 -> { ux = y; uy = preH - 1 - x }
                180 -> { ux = preW - 1 - x; uy = preH - 1 - y }
                270 -> { ux = preW - 1 - y; uy = x }
                else -> { ux = x; uy = y }
            }
            val cx = ux + framingLeft + inset.left
            val cy = uy + framingTop + inset.top
            val model = distortion
            if (model != null && !model.isIdentity) {
                LensDistortion.sourceFor(model, cx, cy, out)
            } else {
                out[0] = cx
                out[1] = cy
            }
        }
    }

    /** The map as the file carries it: 8-bit codes, log2 of the ratio over log2 of [maxRatio]. */
    class Encoded(val width: Int, val height: Int, val codes: ByteArray, val maxRatio: Float) {
        fun ratioAt(x: Int, y: Int): Float =
            maxRatio.pow((codes[y * width + x].toInt() and 0xFF) / 255f)
    }

    /**
     * The map for the finished picture: a quarter of its size, each pixel
     * carried back to the sensor by [placement] and read from [field].
     * Null when the scene's largest ratio is under [MIN_USEFUL_RATIO]: there
     * are no highlights to extend, and the picture is written SDR.
     */
    fun encode(field: Field, placement: Placement, scale: Int = SCALE): Encoded? {
        val gw = ceil(placement.width / scale.toFloat()).toInt()
        val gh = ceil(placement.height / scale.toFloat()).toInt()
        val log2 = FloatArray(gw * gh)
        val half = (scale - 1) / 2f
        Bands.rows(gh) { rowStart, rowEnd ->
            val s = FloatArray(2)
            for (gy in rowStart until rowEnd) {
                val y = min(gy * scale + half, (placement.height - 1).toFloat())
                for (gx in 0 until gw) {
                    val x = min(gx * scale + half, (placement.width - 1).toFloat())
                    placement.sensorOf(x, y, s)
                    log2[gy * gw + gx] = field.sample(s[0], s[1]).coerceAtLeast(0f)
                }
            }
        }
        val top = log2.maxOrNull() ?: 0f
        if (top < log2(MIN_USEFUL_RATIO)) return null
        val codes = ByteArray(log2.size) { i -> (log2[i] / top * 255f).roundToInt().coerceIn(0, 255).toByte() }
        return Encoded(gw, gh, codes, 2f.pow(top))
    }
}
