package dev.multiframe.camera.pipeline

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tone settings for the raw back end.
 *
 * Same philosophy as the YUV path: linear below the knee, gentle exponential
 * roll-off above it, no local tone mapping, no sharpening, no saturation boost.
 */
data class DevelopParams(
    /**
     * Linear exposure multiplier. Zero or negative means measure it from the
     * frame, which is what a raw developer normally does: sensor data is linear
     * and typically far darker than a viewable image, so rendering it with a
     * fixed gain leaves everything crushed.
     */
    val exposureGain: Float = AUTO_EXPOSURE,
    val shoulderKnee: Float = 0.70f,
    /** Linear level the bright end of the scene is mapped to. */
    val highlightTarget: Float = 0.62f,
    /** Percentile treated as the bright end, ignoring speculars. */
    val highlightPercentile: Float = 0.92f,
    /**
     * Midtone contrast, as the strength of an S-curve applied after the gamma
     * encode. The slope at mid-grey is 1 + 0.875 * this. Zero renders the scene
     * with no curve at all, which looks washed out rather than neutral.
     */
    val contrast: Float = 0.30f,
    /** How completely the brightest areas give up their colour. */
    val highlightDesaturation: Float = 1.0f,
    /**
     * Scene-linear level at which that begins, in units where 1.0 is nominal
     * white. Anything below this keeps its colour untouched.
     */
    val desaturationStart: Float = 1.0f,
    /** Toe. A little density in the deepest shadows, as film has. */
    val blackPoint: Float = 0.012f,
    /**
     * Capture sharpening, restoring the acutance the CFA sampling and demosaic
     * cost. See [Sharpen] for why this is not an effect.
     */
    val sharpen: Sharpen.Params = Sharpen.Params(),
    /**
     * How far a site must sit beyond its same-colour neighbours to be treated
     * as defective. Zero disables the correction entirely.
     */
    val hotPixelThreshold: Float = HotPixels.DEFAULT_THRESHOLD,
) {
    companion object {
        const val AUTO_EXPOSURE = -1f
    }
}

/**
 * Turns merged CFA data into viewable sRGB.
 *
 * This is the back end of the unified pipeline: one raw merge feeds both the
 * DNG (written straight from the merged CFA) and the JPEG (developed here), so
 * the two outputs carry identical computational-photography benefit rather than
 * coming from separate pipelines.
 *
 * Demosaicing is gradient-corrected linear interpolation; see [Demosaic] for
 * why, and for what the simple gather it replaced was costing.
 */
object RawDeveloper {

    private val threads = max(2, Runtime.getRuntime().availableProcessors())
    private val pool = Executors.newFixedThreadPool(threads)

    /** sRGB transfer function, applied only at the very end. */
    private val gammaLut = FloatArray(4096) { i ->
        val v = i / 4095f
        val e = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        e * 255f
    }

    /**
     * Measures a global exposure multiplier from the frame.
     *
     * Deliberately one number for the whole image: a global scale plus the
     * highlight roll-off, with no local tone mapping, which is what keeps the
     * result looking photographic rather than processed.
     */
    fun autoExposureGain(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
    ): Float {
        val w = frame.width
        val h = frame.height
        val range = sensor.range.toFloat()
        val cfa = sensor.cfaPattern

        // Sample the green sites only; green carries most of the luminance and
        // is half the CFA, so it is both representative and cheap.
        val samples = ArrayList<Float>(8192)
        val stepY = max(1, h / 400)
        val stepX = max(1, w / 400)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (cfa[(y and 1) * 2 + (x and 1)] == 1) {
                    val raw = frame.data[y * w + x].toInt() and 0xFFFF
                    val lin = (raw - sensor.blackAt(x, y)).toFloat() / range
                    samples.add(lin.coerceAtLeast(0f) * color.gainFor(1, y))
                }
                x += stepX
            }
            y += stepY
        }
        if (samples.size < 32) return 1f

        samples.sort()
        val idx = ((samples.size - 1) * params.highlightPercentile).toInt()
        val bright = samples[idx]
        if (bright <= 1e-5f) return MAX_AUTO_GAIN

        return (params.highlightTarget / bright).coerceIn(MIN_AUTO_GAIN, MAX_AUTO_GAIN)
    }

    private const val MIN_AUTO_GAIN = 0.25f
    private const val MAX_AUTO_GAIN = 64f

    private fun resolveGain(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams,
    ): Float = if (params.exposureGain > 0f) {
        params.exposureGain
    } else {
        autoExposureGain(frame, sensor, color, params)
    }

    /**
     * Develops directly into a Bitmap in horizontal bands.
     *
     * Bitmap pixel storage lives in native memory, but a full-resolution
     * IntArray does not: at 12.5 MP that is a 50 MB Java allocation, which is
     * what previously exhausted the heap. Only one band is held at a time.
     */
    fun developIntoBitmap(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
        bandRows: Int = 128,
        shading: ShadingMap? = null,
    ): android.graphics.Bitmap {
        val w = frame.width
        val h = frame.height
        val bitmap = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888,
        )
        correctDefects(frame, sensor, params)
        val gain = resolveGain(frame, sensor, color, params)
        val resolved = params.copy(exposureGain = gain)
        val band = IntArray(w * bandRows)
        var y = 0
        while (y < h) {
            val rows = min(bandRows, h - y)
            developBand(frame, sensor, color, resolved, y, rows, band, shading)
            bitmap.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
        // Applied after the whole image exists, since the mask needs each
        // pixel's neighbours and a band does not have them at its edges.
        if (params.sharpen.enabled) {
            val all = IntArray(w * h)
            bitmap.getPixels(all, 0, w, 0, 0, w, h)
            Sharpen.apply(all, w, h, params.sharpen)
            bitmap.setPixels(all, 0, w, 0, 0, w, h)
        }
        return bitmap
    }

    private fun developBand(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams,
        yOffset: Int,
        rows: Int,
        out: IntArray,
        shading: ShadingMap?,
    ) {
        val w = frame.width
        val h = frame.height
        parallelRows(rows) { rStart, rEnd ->
            renderRows(frame, sensor, color, params, w, h, yOffset + rStart, yOffset + rEnd, out, yOffset, shading)
        }
    }

    /**
     * Returns packed ARGB pixels for the whole frame. Used by tests, where the
     * images are small; production goes through [developIntoBitmap].
     */
    fun develop(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams = DevelopParams(),
        shading: ShadingMap? = null,
    ): IntArray {
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * h)
        correctDefects(frame, sensor, params)
        val resolved = params.copy(exposureGain = resolveGain(frame, sensor, color, params))
        parallelRows(h) { yStart, yEnd ->
            renderRows(frame, sensor, color, resolved, w, h, yStart, yEnd, out, 0, shading)
        }
        if (resolved.sharpen.enabled) Sharpen.apply(out, w, h, resolved.sharpen)
        return out
    }

    /** Shared per-pixel render. [rowBase] lets a band write into a smaller buffer. */
    private fun renderRows(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams,
        w: Int,
        h: Int,
        yStart: Int,
        yEnd: Int,
        out: IntArray,
        rowBase: Int,
        shading: ShadingMap?,
    ) {
        val m = color.matrix
        val rgb = FloatArray(3)

        for (y in yStart until yEnd) {
            for (x in 0 until w) {
                // Gradient-corrected demosaic: the sample the sensor actually
                // measured at this site is kept exactly, and only the two
                // missing colours are interpolated.
                Demosaic.pixel(frame, sensor, color, x, y, rgb, shading)
                val r0 = rgb[0]
                val g0 = rgb[1]
                val b0 = rgb[2]

                var r = m[0] * r0 + m[1] * g0 + m[2] * b0
                var g = m[3] * r0 + m[4] * g0 + m[5] * b0
                var b = m[6] * r0 + m[7] * g0 + m[8] * b0

                rgb[0] = r * params.exposureGain
                rgb[1] = g * params.exposureGain
                rgb[2] = b * params.exposureGain
                ToneCurve.renderLinear(rgb, params)

                out[(y - rowBase) * w + x] = (0xFF shl 24) or
                    (encode(rgb[0], params) shl 16) or
                    (encode(rgb[1], params) shl 8) or
                    encode(rgb[2], params)
            }
        }
    }

    /**
     * Replaces defective sensor sites, in place on the frame.
     *
     * Before exposure is measured, so a stuck-bright site cannot drag the
     * highlight percentile and darken the whole picture on its own.
     */
    private fun correctDefects(
        frame: BayerFrame,
        sensor: SensorProfile,
        params: DevelopParams,
    ) {
        if (params.hotPixelThreshold <= 0f) return
        HotPixels.suppressInFrame(frame, sensor, params.hotPixelThreshold)
    }

    /** Gamma encode, then the display-domain contrast stage. */
    private fun encode(v: Float, params: DevelopParams): Int {
        val gamma = gammaLut[(v.coerceIn(0f, 1f) * 4095f).toInt()] / 255f
        return (ToneCurve.renderDisplay(gamma, params) * 255f + 0.5f)
            .toInt().coerceIn(0, 255)
    }

    /** Retained for callers that want the roll-off alone. */
    fun shoulder(x: Float, knee: Float): Float = ToneCurve.shoulder(x, knee)

    private inline fun parallelRows(height: Int, crossinline body: (Int, Int) -> Unit) {
        val band = (height + threads - 1) / threads
        val tasks = ArrayList<Callable<Unit>>(threads)
        for (t in 0 until threads) {
            val start = t * band
            val end = min(start + band, height)
            if (start >= end) continue
            tasks.add(Callable { body(start, end) })
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }
}
