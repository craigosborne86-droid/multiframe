package dev.multiframe.camera.pipeline

import kotlin.math.max
import kotlin.math.pow

/**
 * The develop for a canvas that is already full colour: the reference.
 *
 * The sub-pixel accumulator hands back 16-bit RGB in sensor counts at twice
 * the sensor's resolution. It has no colour filter array, so the chain in
 * REFERENCE.md §3.1 runs with the demosaic removed:
 *
 * ```
 * Black subtract -> Lens shading -> White balance -> Colour matrix
 *   -> Tone curve -> sRGB gamma -> Sharpen
 * ```
 *
 * Hot pixels are not here because they were dealt with before the samples
 * were placed -- see [SubPixelAccumulator.setReference] -- and defringe is not
 * here because the canvas was built from aligned measured samples rather than
 * interpolated across colour planes, so it has no demosaic fringes to remove.
 *
 * Everything from the matrix onward is [ToneCurve] and [Sharpen], the same
 * functions the CFA develop uses, so a 2x photograph and its ordinary sibling
 * render the same tone. This is the JVM implementation the unit tests cover
 * and the reference `LinearDevelopParityTest` holds the native path to; the
 * native path (LinearDevelop.cpp) is what a capture runs, since a fifty
 * megapixel canvas cannot be an IntArray on a 256 MB heap.
 */
object LinearDeveloper {

    /** sRGB transfer function, as [RawDeveloper] tabulates it. */
    private val gammaLut = FloatArray(4096) { i ->
        val v = i / 4095f
        val e = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        e * 255f
    }

    /**
     * Black level per output channel, in raw counts, from the sensor's per-site
     * levels: red's site, blue's site, and the mean of the two greens.
     */
    fun channelBlack(profile: SensorProfile): FloatArray {
        val out = FloatArray(3)
        val count = IntArray(3)
        for (site in 0 until 4) {
            val c = profile.cfaPattern.getOrElse(site) { 1 }.coerceIn(0, 2)
            out[c] += profile.blackLevel.getOrElse(site) { 0 }.toFloat()
            count[c]++
        }
        for (c in 0 until 3) if (count[c] > 0) out[c] /= count[c]
        return out
    }

    /** White balance per output channel: red, the mean of the greens, blue. */
    fun channelGains(color: ColorProfile): FloatArray = floatArrayOf(
        color.gains[0],
        0.5f * (color.gains[1] + color.gains[2]),
        color.gains[3],
    )

    /**
     * The exposure multiplier the canvas asks for.
     *
     * The green channel's bright percentile brought to the target, exactly as
     * [RawDeveloper.autoExposureGain] measures it from the green sites. Shading
     * is left out of the measurement there and is left out here.
     */
    fun autoExposureGain(
        rgb16: ShortArray,
        width: Int,
        height: Int,
        profile: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
    ): Float {
        val black = channelBlack(profile)
        val lo = minOf(black[0], black[1], black[2])
        val range = max(1f, profile.whiteLevel - lo)
        val greenGain = channelGains(color)[1]

        val samples = ArrayList<Float>(8192)
        val stepY = max(1, height / 400)
        val stepX = max(1, width / 400)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val raw = rgb16[(y * width + x) * 3 + 1].toInt() and 0xFFFF
                val lin = (raw - black[1]) / range
                samples.add(lin.coerceAtLeast(0f) * greenGain)
                x += stepX
            }
            y += stepY
        }
        if (samples.size < 32) return 1f
        samples.sort()
        val bright = samples[((samples.size - 1) * params.highlightPercentile).toInt()]
        if (bright <= 1e-5f) return MAX_AUTO_GAIN
        return (params.highlightTarget / bright).coerceIn(MIN_AUTO_GAIN, MAX_AUTO_GAIN)
    }

    private const val MIN_AUTO_GAIN = 0.25f
    private const val MAX_AUTO_GAIN = 64f

    /**
     * Develops the canvas to packed ARGB.
     *
     * [rgb16] is `width * height * 3` interleaved values in sensor counts, as
     * [SubPixelResult] and [NativeSubPixel.Result] carry them. [shading] is
     * the map the camera reported for the *sensor*; the canvas reads it at
     * half its own coordinates.
     */
    fun develop(
        rgb16: ShortArray,
        width: Int,
        height: Int,
        profile: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
        shading: ShadingMap? = null,
    ): IntArray {
        require(rgb16.size >= width * height * 3) { "canvas is too small" }
        val gain = if (params.exposureGain > 0f) params.exposureGain
        else autoExposureGain(rgb16, width, height, profile, color, params)
        val resolved = params.copy(exposureGain = gain)

        val black = channelBlack(profile)
        val lo = minOf(black[0], black[1], black[2])
        val invRange = 1f / max(1f, profile.whiteLevel - lo)
        val wb = channelGains(color)
        val m = color.matrix
        val grid = shading?.rgbGrid()
        val sensorW = width / 2
        val sensorH = height / 2

        val out = IntArray(width * height)
        Bands.rows(height) { y0, y1 ->
            val local = FloatArray(3)
            for (y in y0 until y1) {
                val sy = y shr 1
                for (x in 0 until width) {
                    val sx = x shr 1
                    val i = (y * width + x) * 3
                    for (c in 0 until 3) {
                        val raw = rgb16[i + c].toInt() and 0xFFFF
                        var lin = ((raw - black[c]) * invRange).coerceAtLeast(0f)
                        if (grid != null) {
                            lin *= ShadingMap.rgbGainAt(
                                grid, shading.columns, shading.rows, sx, sy, sensorW, sensorH, c,
                            )
                        }
                        local[c] = lin * wb[c]
                    }
                    val r0 = local[0]
                    val g0 = local[1]
                    val b0 = local[2]
                    local[0] = (m[0] * r0 + m[1] * g0 + m[2] * b0) * gain
                    local[1] = (m[3] * r0 + m[4] * g0 + m[5] * b0) * gain
                    local[2] = (m[6] * r0 + m[7] * g0 + m[8] * b0) * gain
                    ToneCurve.renderLinear(local, resolved)
                    out[y * width + x] = (0xFF shl 24) or
                        (encode(local[0], resolved) shl 16) or
                        (encode(local[1], resolved) shl 8) or
                        encode(local[2], resolved)
                }
            }
        }
        if (resolved.sharpen.enabled) Sharpen.apply(out, width, height, resolved.sharpen)
        return out
    }

    /** Gamma encode, then the display-domain contrast stage. As [RawDeveloper]. */
    private fun encode(v: Float, params: DevelopParams): Int {
        val gamma = gammaLut[(v.coerceIn(0f, 1f) * 4095f).toInt()] / 255f
        return (ToneCurve.renderDisplay(gamma, params) * 255f + 0.5f)
            .toInt().coerceIn(0, 255)
    }
}
