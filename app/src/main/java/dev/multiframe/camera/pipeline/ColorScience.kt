package dev.multiframe.camera.pipeline

import kotlin.math.abs

/**
 * A 3x3 matrix, row-major. Small enough that a dedicated type earns its keep
 * over passing bare float arrays around and hoping the convention holds.
 */
@JvmInline
value class Mat3(val m: FloatArray) {

    init {
        require(m.size == 9) { "a 3x3 matrix has nine elements" }
    }

    operator fun times(other: Mat3): Mat3 {
        val r = FloatArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += m[i * 3 + k] * other.m[k * 3 + j]
                r[i * 3 + j] = sum
            }
        }
        return Mat3(r)
    }

    /** Applies to a colour, writing into [out] which may be the input. */
    fun apply(x: Float, y: Float, z: Float, out: FloatArray) {
        val a = m[0] * x + m[1] * y + m[2] * z
        val b = m[3] * x + m[4] * y + m[5] * z
        val c = m[6] * x + m[7] * y + m[8] * z
        out[0] = a; out[1] = b; out[2] = c
    }

    fun invert(): Mat3? {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-9f) return null
        return Mat3(
            floatArrayOf(
                (a[4] * a[8] - a[5] * a[7]) / det,
                (a[2] * a[7] - a[1] * a[8]) / det,
                (a[1] * a[5] - a[2] * a[4]) / det,
                (a[5] * a[6] - a[3] * a[8]) / det,
                (a[0] * a[8] - a[2] * a[6]) / det,
                (a[2] * a[3] - a[0] * a[5]) / det,
                (a[3] * a[7] - a[4] * a[6]) / det,
                (a[1] * a[6] - a[0] * a[7]) / det,
                (a[0] * a[4] - a[1] * a[3]) / det,
            )
        )
    }

    /**
     * Scales each row so the matrix maps [toward] exactly onto [target].
     *
     * The target matters and is easy to get wrong. A forward matrix delivers
     * XYZ under D50, so the scene's neutral has to land on the *D50 white
     * point* rather than on (1, 1, 1) -- those are different colours, and
     * normalising to the wrong one leaves every grey visibly blue.
     */
    fun normalisedFor(
        toward: FloatArray,
        target: FloatArray = floatArrayOf(1f, 1f, 1f),
    ): Mat3 {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            val sum = m[row * 3] * toward[0] +
                m[row * 3 + 1] * toward[1] +
                m[row * 3 + 2] * toward[2]
            val scale = if (abs(sum) < 1e-9f) 0f else target[row] / sum
            for (col in 0 until 3) out[row * 3 + col] = m[row * 3 + col] * scale
        }
        return Mat3(out)
    }

    companion object {
        val IDENTITY = Mat3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        fun diagonal(a: Float, b: Float, c: Float) =
            Mat3(floatArrayOf(a, 0f, 0f, 0f, b, 0f, 0f, 0f, c))

        /** Element-wise blend, which is how DNG interpolates between illuminants. */
        fun lerp(a: Mat3, b: Mat3, t: Float): Mat3 {
            val f = t.coerceIn(0f, 1f)
            return Mat3(FloatArray(9) { a.m[it] * (1f - f) + b.m[it] * f })
        }
    }
}

/**
 * Colour, done the way a raw converter does it rather than the way the ISP does.
 *
 * ### What was wrong with the previous approach
 *
 * The pipeline used `COLOR_CORRECTION_TRANSFORM` from the capture result: the
 * matrix the camera's own image processor chose for this frame. It works, and
 * it is tuned to produce the *manufacturer's* rendering -- the look the stock
 * camera app ships. Inheriting it means inheriting that look, which is the
 * opposite of the point.
 *
 * The sensor also carries a proper colorimetric characterisation, the same data
 * a DNG carries and Lightroom uses: two colour matrices measured under two
 * illuminants, a calibration transform for unit-to-unit variation, and forward
 * matrices taking white-balanced camera space to XYZ. That describes what the
 * sensor actually sees rather than what the vendor wants it to look like, and it
 * is the honest starting point for a rendering of one's own.
 *
 * ### Why two illuminants
 *
 * A sensor's response to a colour depends on the light illuminating it, and no
 * single matrix is right for both tungsten and daylight. The characterisation is
 * measured under two standard illuminants and interpolated according to the
 * white balance actually in force -- which is what makes colour hold up under
 * mixed and unusual lighting instead of only under the one the vendor tuned for.
 *
 * Falls back to the capture result's matrix where a device reports no
 * calibration, which is optional in the Camera2 spec.
 */
object ColorScience {

    /** sRGB primaries under D65, from linear sRGB to XYZ. */
    val SRGB_TO_XYZ = Mat3(
        floatArrayOf(
            0.4124564f, 0.3575761f, 0.1804375f,
            0.2126729f, 0.7151522f, 0.0721750f,
            0.0193339f, 0.1191920f, 0.9503041f,
        )
    )

    /** XYZ to linear sRGB, the inverse of the above. */
    val XYZ_TO_SRGB = Mat3(
        floatArrayOf(
            3.2404542f, -1.5371385f, -0.4985314f,
            -0.9692660f, 1.8760108f, 0.0415560f,
            0.0556434f, -0.2040259f, 1.0572252f,
        )
    )

    /**
     * Bradford adaptation from D50, which DNG works in, to D65, which sRGB
     * assumes. Skipping this leaves every image slightly warm.
     */
    val D50_TO_D65 = Mat3(
        floatArrayOf(
            0.9555766f, -0.0230393f, 0.0631636f,
            -0.0282895f, 1.0099416f, 0.0210077f,
            0.0122982f, -0.0204830f, 1.3299098f,
        )
    )

    /** The reference white DNG's forward matrices deliver. */
    val D50_WHITE = floatArrayOf(0.9642f, 1.0000f, 0.8249f)

    /**
     * Everything a sensor's characterisation contains.
     *
     * [forward1] and [forward2] are optional: a device may report colour
     * matrices without them, in which case the transform is derived by
     * inverting the colour matrix instead.
     */
    data class Calibration(
        val colorMatrix1: Mat3,
        val colorMatrix2: Mat3,
        val calibration1: Mat3 = Mat3.IDENTITY,
        val calibration2: Mat3 = Mat3.IDENTITY,
        val forward1: Mat3? = null,
        val forward2: Mat3? = null,
        /**
         * Colour temperatures of the two illuminants the matrices were measured
         * under, in kelvin.
         *
         * Null where the device did not publish them, which is common enough to
         * plan for. Without them the pair cannot be interpolated by
         * temperature and [blendFor]'s ratio proxy is used instead.
         */
        val illuminant1Kelvin: Int? = null,
        val illuminant2Kelvin: Int? = null,
    ) {
        /** Whether this calibration supports temperature interpolation. */
        val hasIlluminants: Boolean
            get() = illuminant1Kelvin != null &&
                illuminant2Kelvin != null &&
                illuminant1Kelvin != illuminant2Kelvin
    }

    /**
     * Colour temperatures of the standard illuminants, by the EXIF LightSource
     * code Camera2 reports in `SENSOR_REFERENCE_ILLUMINANT1` and `2`.
     *
     * These are the values the DNG specification and every raw converter agree
     * on. Codes not listed are ones no camera has been seen to report for a
     * calibration illuminant -- an unknown code returns null and the pair falls
     * back to the ratio proxy rather than being interpolated against a guess.
     */
    fun illuminantKelvin(code: Int): Int? = when (code) {
        1 -> 5500     // Daylight
        2 -> 4200     // Fluorescent
        3 -> 2856     // Tungsten
        4 -> 5500     // Flash
        9 -> 5500     // Fine weather
        10 -> 6500    // Cloudy weather
        11 -> 7500    // Shade
        12 -> 6400    // Daylight fluorescent
        13 -> 4900    // Day white fluorescent
        14 -> 4200    // Cool white fluorescent
        15 -> 3450    // White fluorescent
        17 -> 2856    // Standard A
        18 -> 4874    // Standard B
        19 -> 6774    // Standard C
        20 -> 5503    // D55
        21 -> 6504    // D65
        22 -> 7504    // D75
        23 -> 5003    // D50
        24 -> 3200    // ISO studio tungsten
        else -> null
    }

    /**
     * Builds the matrix taking white-balanced camera RGB to linear sRGB.
     *
     * [neutral] is the camera-space colour of a white object under the scene's
     * light: the reciprocal of the white balance gains, which is what the DNG
     * specification calls AsShotNeutral.
     *
     * [blend] selects between the two illuminants, 0 for the first and 1 for
     * the second.
     */
    fun cameraToSrgb(
        calibration: Calibration,
        neutral: FloatArray,
        blend: Float,
    ): Mat3 {
        val forward = interpolatedForward(calibration, blend)
        if (forward != null) {
            // The forward matrix path, which is what DNG prefers: it maps
            // white-balanced camera space directly to XYZ under D50, and by
            // construction it takes the neutral to the reference white exactly.
            val whiteBalanced = forward.normalisedFor(neutral, D50_WHITE)
            return XYZ_TO_SRGB * D50_TO_D65 * whiteBalanced
        }

        // No forward matrix. Invert the colour matrix instead, which is less
        // well conditioned but is what the data allows.
        val colour = Mat3.lerp(calibration.colorMatrix1, calibration.colorMatrix2, blend)
        val calibrationTransform =
            Mat3.lerp(calibration.calibration1, calibration.calibration2, blend)
        val cameraToXyz = (calibrationTransform * colour).invert() ?: return Mat3.IDENTITY
        return XYZ_TO_SRGB * D50_TO_D65 * cameraToXyz.normalisedFor(neutral, D50_WHITE)
    }

    private fun interpolatedForward(calibration: Calibration, blend: Float): Mat3? {
        val a = calibration.forward1
        val b = calibration.forward2
        return when {
            a != null && b != null -> Mat3.lerp(a, b, blend)
            a != null -> a
            b != null -> b
            else -> null
        }
    }

    /**
     * How far toward the second illuminant the scene's light sits.
     *
     * The DNG specification interpolates by correlated colour temperature,
     * which requires solving for the temperature that would produce the
     * observed neutral -- circular, since the matrix needed to compute the
     * temperature is the one being chosen. Converters resolve it by iterating.
     *
     * A simpler proxy is used here: how warm the neutral is, as the ratio of the
     * red and blue gains. It agrees with a temperature-based blend at the ends,
     * where it matters most, and errs toward the middle in between, where the
     * two matrices are most similar and the choice matters least.
     */
    /** Red-to-blue ratio of a neutral under tungsten and under daylight. */
    private const val TUNGSTEN_WARMTH = 2.2f
    private const val DAYLIGHT_WARMTH = 0.55f

    fun blendFor(neutral: FloatArray): Float {
        if (neutral.size < 3 || neutral[0] <= 0f || neutral[2] <= 0f) return 0.5f
        // Under warm light a neutral object reflects far more red than blue, so
        // its camera-space colour has a large red component. The DNG convention
        // puts the warmer illuminant first, so a warm scene sits at zero.
        val warmth = neutral[0] / neutral[2]
        return ((TUNGSTEN_WARMTH - warmth) / (TUNGSTEN_WARMTH - DAYLIGHT_WARMTH))
            .coerceIn(0f, 1f)
    }

    /**
     * Where a scene at [kelvin] sits between two calibration illuminants.
     *
     * Zero selects the matrix belonging to [kelvin1], one selects [kelvin2],
     * and the interpolation between them runs in *mired* -- reciprocal
     * temperature -- not in kelvin.
     *
     * ### Why reciprocal
     *
     * Because that is the scale colour temperature is perceptually and
     * physically linear on. The step from 2800 K to 3300 K is a large visible
     * shift; the step from 6000 K to 6500 K, the same 500 kelvin, is slight.
     * In mired both are about 54 units. Interpolating linearly in kelvin would
     * weight the daylight end far too heavily and leave every tungsten scene
     * rendered against nearly the wrong matrix. The DNG specification
     * interpolates in mired for exactly this reason.
     *
     * Outside the pair the result clamps: extrapolating a measured
     * characterisation past the illuminants it was measured under is not
     * supported by anything.
     */
    fun blendForTemperature(kelvin: Int, kelvin1: Int, kelvin2: Int): Float {
        if (kelvin <= 0 || kelvin1 <= 0 || kelvin2 <= 0) return 0.5f
        if (kelvin1 == kelvin2) return 0.5f

        val mired = 1e6f / kelvin
        val mired1 = 1e6f / kelvin1
        val mired2 = 1e6f / kelvin2

        return ((mired - mired1) / (mired2 - mired1)).coerceIn(0f, 1f)
    }

    /**
     * How many rounds the blend is allowed to chase itself. Three is generous:
     * it settles in two on every real calibration tried.
     */
    private const val BLEND_ITERATIONS = 6

    /** Blend movement below this is settled. */
    private const val BLEND_EPSILON = 1e-3f

    /**
     * The blend a scene's [neutral] actually calls for, resolved by iteration.
     *
     * ### The circularity, and how the specification resolves it
     *
     * Choosing between the two matrices requires knowing the scene's colour
     * temperature. Computing that temperature requires taking the neutral to
     * chromaticity, which requires a matrix. Each needs the other first.
     *
     * The DNG specification's answer is to iterate, and it converges quickly
     * because the dependency is weak: the two matrices disagree most about
     * saturated colour and very little about where a near-neutral lands, so
     * even a poor starting blend produces a temperature close to the right one,
     * and the second round is usually within a few kelvin of the fixed point.
     *
     * Starts from [blendFor]'s ratio proxy rather than from the midpoint. It is
     * a worse answer than this one but a much better starting guess than 0.5,
     * and it costs nothing.
     *
     * Falls back to the proxy where the illuminant temperatures are unknown --
     * without them there is no temperature scale to interpolate on.
     */
    fun solveBlend(calibration: Calibration, neutral: FloatArray): Float {
        if (!calibration.hasIlluminants) return blendFor(neutral)
        val k1 = calibration.illuminant1Kelvin!!
        val k2 = calibration.illuminant2Kelvin!!

        var blend = blendFor(neutral)
        repeat(BLEND_ITERATIONS) {
            val kelvin = cctForBlend(calibration, neutral, blend) ?: return blend
            val next = blendForTemperature(kelvin, k1, k2)
            if (kotlin.math.abs(next - blend) < BLEND_EPSILON) return next
            blend = next
        }
        return blend
    }

    /**
     * The scene's colour temperature as seen through a specific [blend].
     *
     * Separated from [correlatedColourTemperature] so the iteration above has
     * something to call that does not itself choose a blend -- which would
     * recurse forever.
     */
    fun cctForBlend(
        calibration: Calibration,
        neutral: FloatArray,
        blend: Float,
    ): Int? {
        if (neutral.size < 3) return null
        if (neutral[0] <= 0f || neutral[1] <= 0f || neutral[2] <= 0f) return null

        val cameraToXyz = interpolatedForward(calibration, blend)
            ?: run {
                val colour =
                    Mat3.lerp(calibration.colorMatrix1, calibration.colorMatrix2, blend)
                val calibrationTransform =
                    Mat3.lerp(calibration.calibration1, calibration.calibration2, blend)
                (calibrationTransform * colour).invert() ?: return null
            }

        val xyz = FloatArray(3)
        cameraToXyz.apply(neutral[0], neutral[1], neutral[2], xyz)

        val sum = xyz[0] + xyz[1] + xyz[2]
        if (sum <= 0f) return null
        return cctFromChromaticity(xyz[0] / sum, xyz[1] / sum)
    }

    /**
     * The bounds a reported temperature is held to.
     *
     * Not a preference. Below about 1500 K and above about 15000 K, McCamy's
     * cubic is being asked about chromaticities that are nowhere near the
     * Planckian locus, and what it returns there is a number rather than a
     * temperature. Clamping says so instead of printing it.
     */
    const val CCT_MIN = 1500
    const val CCT_MAX = 15000

    /**
     * Correlated colour temperature of the scene's light, in kelvin.
     *
     * [neutral] is the camera-space colour of a white object under that light —
     * AsShotNeutral, the same vector [cameraToSrgb] and [blendFor] take. The
     * route is the textbook one: take the neutral to XYZ with the sensor's own
     * characterisation, reduce to xy chromaticity, then McCamy's cubic.
     *
     * ### Why this is not the ratio [blendFor] uses
     *
     * That ratio is a *proxy*, and it is honest about being one: it only has to
     * order two illuminants correctly, and it is allowed to err toward the
     * middle because the two matrices it chooses between are most alike there.
     * A number displayed to the photographer as "5200K" is a different promise.
     * It has to be right in the middle of the range as well as at the ends,
     * which means going through chromaticity rather than around it.
     *
     * Returns null when the calibration cannot be inverted or the neutral is
     * degenerate — the gauge shows nothing rather than a fabricated figure.
     */
    fun correlatedColourTemperature(
        calibration: Calibration,
        neutral: FloatArray,
    ): Int? = cctForBlend(calibration, neutral, solveBlend(calibration, neutral))

    /**
     * McCamy's cubic approximation, which is accurate to within a couple of
     * kelvin over the range a camera ever meets.
     *
     * The epicentre is the point the isotemperature lines converge on; the
     * cubic is in the slope from it. Near that point the slope diverges, which
     * is what the guard is for — a chromaticity sitting on the epicentre has no
     * temperature, not an infinite one.
     */
    fun cctFromChromaticity(x: Float, y: Float): Int? {
        val dy = 0.1858f - y
        if (kotlin.math.abs(dy) < 1e-6f) return null
        val n = (x - 0.3320f) / dy
        val cct = 449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f
        if (cct.isNaN() || cct.isInfinite()) return null
        return cct.toInt().coerceIn(CCT_MIN, CCT_MAX)
    }

    /**
     * Correlated colour temperature straight from white balance gains.
     *
     * The gains are the reciprocal of the neutral, which is the form a capture
     * result reports them in (`COLOR_CORRECTION_GAINS`, as R, Gr, Gb, B). The
     * two greens are averaged: they differ only by sensor row and mean the same
     * thing to a white point.
     */
    fun cctFromGains(calibration: Calibration, gains: FloatArray): Int? {
        if (gains.size < 4) return null
        val r = gains[0]
        val g = (gains[1] + gains[2]) / 2f
        val b = gains[3]
        if (r <= 0f || g <= 0f || b <= 0f) return null
        return correlatedColourTemperature(
            calibration,
            floatArrayOf(1f / r, 1f / g, 1f / b),
        )
    }
}
