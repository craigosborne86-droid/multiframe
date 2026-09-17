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
    /** Scene-linear level, in units of nominal white, where the highlight roll-off begins. */
    val shoulderKnee: Float = 0.70f,
    /**
     * Scene-linear level rendered as display white: the top of the filmic
     * shoulder. [SHOULDER_WHITE_AUTO], the default, places it where the
     * sensor's own clip lands after the exposure gain -- see [withGain] --
     * so a clipped pixel is white and everything under it has its tone. An
     * explicit value is honoured as given. See [ToneCurve.shoulder].
     */
    val shoulderWhite: Float = SHOULDER_WHITE_AUTO,
    /**
     * Linear level the bright end of the scene is mapped to.
     *
     * 0.40 -- two thirds of display white once encoded -- and not the 0.62
     * it was. At 0.62 the bright end of every scene, the sky and the sunlit
     * wall included, sat at four fifths of white and the midtones with it:
     * the lifted, veiled rendering of the eighth field test, the "HDR soup"
     * the PRD's photographic rendering is against. Chosen on the desk over
     * fourteen of that test's DNGs (`DngDevelopBench`, variant `e`):
     * skies deep with the clouds' structure kept, the backlit tree dark as
     * it was, the dog on the sofa the colour of the dog.
     */
    val highlightTarget: Float = 0.40f,
    /** Percentile treated as the bright end, ignoring speculars. */
    val highlightPercentile: Float = 0.92f,
    /**
     * The gain that reproduces the meter's own intent -- two to the guard's
     * pull undone -- or zero where the develop has no meter to defer to.
     *
     * The bright end's target above is a *placement*, and left to itself it
     * placed every scene the same: a night street was lifted until its
     * bright end reached the target, which is a grey daylight with the noise
     * floor for texture. Where the meter's intent is known, the automatic
     * gain is held within [METERED_GAIN_BELOW_STOPS] under it and
     * [METERED_GAIN_ABOVE_STOPS] over it, so a scene the meter exposed dark
     * stays dark -- shadows are shadows -- and the develop only puts back
     * what the guard took, plus a nudge for the highlights' placement.
     * See [RawDeveloper.boundedGain].
     */
    val meteredGain: Float = 0f,
    /**
     * Midtone contrast, as the strength of an S-curve applied after the gamma
     * encode. The slope at mid-grey is 1 + 0.875 * this. Zero renders the scene
     * with no curve at all, which looks washed out rather than neutral.
     * 0.42, from 0.30, with the bright end placed lower: the two together are
     * what put the depth back into a picture the old placement flattened.
     */
    val contrast: Float = 0.42f,
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
     * Chrominance smoothing: the coloured speckle of a high-ISO frame averaged
     * away, with luminance left exactly alone. See [ChromaSmooth]. Before the
     * sharpen, so the sharpen never sees it.
     */
    val chroma: ChromaSmooth.Params = ChromaSmooth.Params(),
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
    /** Colour fringe suppression. See [Defringe] for what it does and does not fix. */
    val defringe: Defringe.Params = Defringe.Params(),
    /**
     * Geometry the lens got wrong: barrel and pincushion, and the per-channel
     * scale difference that shows as coloured fringing.
     *
     * Null on a lens that published no calibration, and on every path that has
     * no lens to describe. See [Optics].
     */
    val optics: Optics.Params? = null,
    /**
     * Local tone mapping, as the strength of the bilateral-grid operator of
     * REFERENCE.md §3.5: how much of the base layer's range about its mean is
     * taken in, with the detail layer carried through untouched. Zero is off,
     * which is the default and the PRD's photographic rendering: a global
     * scale and a roll-off. One would flatten every neighbourhood to the
     * picture's mean brightness. Half is a strong but photographic setting.
     * See [BilateralGrid].
     */
    val localTone: Float = 0f,
    /**
     * The shadow side of the local operator: stops of lift the scene earns
     * per stop of its range beyond what the display curve carries, up to
     * [shadowLiftMax]. Zero is the symmetric operator [localTone] alone. A
     * scene of two or three stops of range earns nothing; a backlit tower
     * against the sun earns the ceiling, and its facade comes up out of the
     * silhouette while the sky stays where it was. See
     * [BilateralGrid.ceiling] and [BilateralGrid.lift].
     */
    val shadowLift: Float = 0f,
    /** The most the local operator lifts any neighbourhood, in stops. */
    val shadowLiftMax: Float = SHADOW_LIFT_MAX,
    /**
     * Purity compression after the colour matrix: the purity above which a
     * colour is compressed, and the purity it is compressed toward and
     * never reaches. See [ToneCurve.compressPurity]. A threshold of zero,
     * or a ceiling at or under it, turns the step off.
     */
    val purityThreshold: Float = PURITY_THRESHOLD,
    val purityCeiling: Float = PURITY_CEILING,
) {
    /**
     * The same rendering, adapted to the sensor gain the frame was taken at.
     *
     * Capture sharpening restores acutance the demosaic cost; on a frame at
     * ISO 3200 what it restores is grain, and sharpened grain is static. The
     * sharpen fades from full at [LOW_ISO] to nothing at [HIGH_ISO], and the
     * chroma smoothing widens its luminance tolerance over the same range,
     * since the speckle it removes is larger. Below [LOW_ISO] nothing
     * changes, so a daylight frame renders exactly as before.
     */
    /**
     * These parameters with the exposure resolved to [gain], and the
     * shoulder placed for it: an automatic white becomes the sensor's clip
     * at that gain, and the knee is kept under it. Every develop path goes
     * through this once its gain is known.
     */
    fun withGain(gain: Float): DevelopParams {
        val white = if (shoulderWhite > 0f) shoulderWhite else shoulderWhiteFor(gain)
        return copy(
            exposureGain = gain,
            shoulderWhite = white,
            shoulderKnee = minOf(shoulderKnee, white * KNEE_SHARE_OF_WHITE),
        )
    }

    fun adaptedTo(iso: Int?): DevelopParams {
        if (iso == null || iso <= LOW_ISO) return this
        val t = ((iso - LOW_ISO).toFloat() / (HIGH_ISO - LOW_ISO)).coerceIn(0f, 1f)
        return copy(
            sharpen = sharpen.copy(amount = sharpen.amount * (1f - t)),
            chroma = chroma.copy(lumaSigma = chroma.lumaSigma * (1f + t)),
        )
    }

    companion object {
        const val AUTO_EXPOSURE = -1f

        /**
         * How far the automatic gain may fall under, or rise over, the
         * meter's own: half a stop under (a bright end already high is
         * brought down a little), three quarters over (the highlights'
         * placement may lift a low-key scene a little, and no more). A night
         * scene is then a night scene: the meter made it dark, and the
         * develop keeps it within a stop of that.
         */
        /**
         * Where the purity compression begins and what it approaches. Seven
         * tenths is over every ordinary colour -- a clear sky sits at six
         * to seven tenths after the matrix and is not touched -- and under a
         * rose in the sun, which the matrix put at nine (`DngAnalysisBench`,
         * the purity histogram). 0.85 is the purest colour the rendering
         * shows: the low channels never under fifteen hundredths of the peak.
         */
        const val PURITY_THRESHOLD = 0.7f
        const val PURITY_CEILING = 0.85f

        const val METERED_GAIN_BELOW_STOPS = 0.5f
        const val METERED_GAIN_ABOVE_STOPS = 0.75f

        /**
         * A shoulder white of zero: resolve it from the gain. See [withGain].
         *
         * The shoulder's white was a constant of five times nominal white
         * for one field test, so that a cloud's tones stayed apart out to
         * the sensor's clip. It did that; it also rendered the sensor's
         * clip -- a stop or two over nominal after the develop's gain -- at
         * 0.86 of display white, and everything bright with it. Skies came
         * out grey, sunlit walls dull, the whole picture under a haze. The
         * white point is not a constant: it is wherever the sensor stops,
         * which after the develop's gain is the gain itself.
         */
        const val SHOULDER_WHITE_AUTO = 0f

        /** The resolved white is held inside these: under half of nominal is not a photograph, past four is never reached. */
        const val SHOULDER_WHITE_MIN = 0.5f
        const val SHOULDER_WHITE_MAX = 4.0f

        /** The knee stays at most this share of the white, so there is always a shoulder to roll off on. */
        const val KNEE_SHARE_OF_WHITE = 0.8f

        /**
         * Where the shoulder's white lands for an exposure [gain]: the
         * sensor's clip, which is nominal white times the gain. The one
         * rule the three develops share -- `Tone.h` states it for the
         * native ones.
         */
        fun shoulderWhiteFor(gain: Float): Float = gain.coerceIn(SHOULDER_WHITE_MIN, SHOULDER_WHITE_MAX)

        /**
         * The strength the LOCAL TONE control turns on. Half, for one field
         * test, compressed the base layer's range by half and with it the
         * scene's own light: a backlit tree as bright as its sky, a window
         * and its room on one level. 0.15 keeps the operator to what it is
         * for -- a little back from the extremes -- and leaves chiaroscuro
         * to the scene (`DngDevelopBench`, variants `b` against `e`).
         */
        const val LOCAL_TONE_ON = 0.15f

        /**
         * The shadow lift the LOCAL TONE control turns on with it: a stop of
         * lift per stop of the scene's range beyond what the display holds,
         * to a ceiling of three. Chosen on the desk over the tenth field
         * test's backlit tower (`DngDevelopBench`, `lift10m30`): the
         * facade's arches and corbels readable, the sky where it was, and
         * the same scene at a range of two or three stops -- the dog on the
         * sofa, the rose -- untouched.
         */
        const val SHADOW_LIFT_ON = 1.0f
        const val SHADOW_LIFT_MAX = 3.0f

        /** Up to this ISO the rendering is the daylight rendering. */
        const val LOW_ISO = 800

        /** From this ISO the sharpen is off and the chroma smoothing at its widest. */
        const val HIGH_ISO = 3200
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

    private val threads = Bands.threads

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
        boundedGain(autoExposureGain(frame, sensor, color, params), params.meteredGain)
    }

    /**
     * The automatic gain, held near the meter's intent where that is known:
     * see [DevelopParams.meteredGain]. Every develop -- this one, the native
     * CFA develop and the linear develop of the 2x canvas -- applies the same
     * bound, so the three agree about how bright a scene is.
     */
    fun boundedGain(auto: Float, meteredGain: Float): Float {
        if (meteredGain <= 0f) return auto
        val floor = meteredGain * 2f.pow(-DevelopParams.METERED_GAIN_BELOW_STOPS)
        val ceiling = meteredGain * 2f.pow(DevelopParams.METERED_GAIN_ABOVE_STOPS)
        return auto.coerceIn(floor, ceiling)
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
        val resolved = params.withGain(gain)
        val grid = if (resolved.localTone > 0f || resolved.shadowLift > 0f) BilateralGrid.fromFrame(frame, sensor, color, resolved, shading) else null
        val band = IntArray(w * bandRows)
        var y = 0
        while (y < h) {
            val rows = min(bandRows, h - y)
            developBand(frame, sensor, color, resolved, y, rows, band, shading, grid)
            bitmap.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
        // Applied after the whole image exists, since all of them need each
        // pixel's neighbours and a band does not have them at its edges. The
        // optics correction needs more than neighbours -- it gathers from
        // several pixels away -- so it could not be banded at all.
        val optics = params.optics?.takeIf { it.hasWork }
        if (params.sharpen.enabled || params.defringe.enabled || params.chroma.enabled || optics != null) {
            val all = IntArray(w * h)
            bitmap.getPixels(all, 0, w, 0, 0, w, h)
            // Geometry first, for the reason in `develop`: the passes below
            // read neighbourhoods, and correcting after them would be deciding
            // pixels from neighbours that are about to move.
            optics?.let { Optics.apply(all, w, h, it) }
            // The colour speckle next, before anything reads chroma: the
            // defringe judges outliers and the sharpen must not see noise.
            if (params.chroma.enabled) ChromaSmooth.apply(all, w, h, params.chroma)
            // Defringe next: sharpening a fringe would make it worse.
            if (params.defringe.enabled) Defringe.apply(all, w, h, params.defringe)
            if (params.sharpen.enabled) Sharpen.apply(all, w, h, params.sharpen)
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
        grid: BilateralGrid? = null,
    ) {
        val w = frame.width
        val h = frame.height
        parallelRows(rows) { rStart, rEnd ->
            renderRows(frame, sensor, color, params, w, h, yOffset + rStart, yOffset + rEnd, out, yOffset, shading, grid)
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
        val resolved = params.withGain(resolveGain(frame, sensor, color, params))
        val grid = if (resolved.localTone > 0f || resolved.shadowLift > 0f) BilateralGrid.fromFrame(frame, sensor, color, resolved, shading) else null
        parallelRows(h) { yStart, yEnd ->
            renderRows(frame, sensor, color, resolved, w, h, yStart, yEnd, out, 0, shading, grid)
        }
        // Geometry before anything that reads a neighbourhood. Both of the
        // passes below decide what a pixel is from the pixels around it, and
        // doing that before the optics are corrected means deciding it from
        // neighbours that are about to move.
        //
        // It also removes most of the work Defringe would otherwise have: a
        // fringe that has been put back where it belongs is not a fringe.
        resolved.optics?.takeIf { it.hasWork }?.let { Optics.apply(out, w, h, it) }
        // The colour speckle, before anything reads chroma.
        if (resolved.chroma.enabled) ChromaSmooth.apply(out, w, h, resolved.chroma)

        // Defringe next: sharpening a fringe would make it worse.
        if (resolved.defringe.enabled) Defringe.apply(out, w, h, resolved.defringe)
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
        grid: BilateralGrid? = null,
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

                rgb[0] = (m[0] * r0 + m[1] * g0 + m[2] * b0) * params.exposureGain
                rgb[1] = (m[3] * r0 + m[4] * g0 + m[5] * b0) * params.exposureGain
                rgb[2] = (m[6] * r0 + m[7] * g0 + m[8] * b0) * params.exposureGain
                // The matrix's overshoot on a spectral colour, brought in: §3.1 step 6a.
                ToneCurve.compressPurity(rgb, params.purityThreshold, params.purityCeiling)
                // The local operator, before the global curve: REFERENCE §3.5.
                if (grid != null) {
                    val k = grid.gain(
                        x, y, BilateralGrid.luminance(rgb[0], rgb[1], rgb[2]),
                        params.localTone, params.shadowLift, params.shadowLiftMax,
                    )
                    rgb[0] *= k; rgb[1] *= k; rgb[2] *= k
                }
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
    fun shoulder(x: Float, knee: Float, white: Float = DevelopParams.SHOULDER_WHITE_MAX): Float =
        ToneCurve.shoulder(x, knee, white)

    /**
     * Banding, now shared with the optics passes. See [Bands] for why it moved
     * out of here.
     */
    private inline fun parallelRows(height: Int, crossinline body: (Int, Int) -> Unit) =
        Bands.rows(height, body)
}
