package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraMetadata
import kotlin.math.hypot

/** WB is a capture parameter independent of exposure/focus and panel visibility. */
enum class WhiteBalanceMode { AUTO, LOCK, MANUAL }
fun ManualSettings.whiteBalanceLabel(): String = when (whiteBalanceMode) {
    WhiteBalanceMode.AUTO -> if (awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO && !manualColor) "Auto WB" else "WB preset / lock"
    WhiteBalanceMode.LOCK -> "Locked WB"
    WhiteBalanceMode.MANUAL -> "$kelvin K · Tint ${if (tint >= 0) "+" else ""}$tint"
}

object CalibratedWhiteBalance {
    data class Solution(val gains: FloatArray, val transform: Mat3, val neutral: FloatArray)
    /** Only interpolate within the sensor's measured illuminants; no profile extrapolation. */
    fun range(calibration: ColorScience.Calibration?): IntRange? = calibration?.takeIf { it.hasIlluminants }?.let {
        minOf(it.illuminant1Kelvin!!, it.illuminant2Kelvin!!) .. maxOf(it.illuminant1Kelvin!!, it.illuminant2Kelvin!!)
    }
    /** The camera neutral of the named illuminant through the calibration's
     * XYZ → camera matrices (DNG Specification 1.6, chapter 6), and the gains and
     * balanced transform that render it white. */
    fun resolve(cal: ColorScience.Calibration?, kelvin: Int, tint: Int): Solution? {
        val c = cal ?: return null
        val supported = range(c) ?: return null
        if (kelvin !in supported || tint !in -100..100) return null
        val (x, y) = chromaticity(kelvin, tint)
        val xyz = floatArrayOf((x / y).toFloat(), 1f, ((1 - x - y) / y).toFloat())
        val blend = ColorScience.blendForTemperature(kelvin, c.illuminant1Kelvin!!, c.illuminant2Kelvin!!)
        val xyzToCamera = c.analogBalance * Mat3.lerp(c.calibration1, c.calibration2, blend) *
            Mat3.lerp(c.colorMatrix1, c.colorMatrix2, blend)
        val neutral = FloatArray(3)
        xyzToCamera.apply(xyz[0], xyz[1], xyz[2], neutral)
        if (neutral.any { !it.isFinite() || it <= 0f }) return null
        val normal = neutral.max()
        val gains = floatArrayOf(normal / neutral[0], normal / neutral[1], normal / neutral[1], normal / neutral[2])
        val matrix = ColorScience.balancedCameraToSrgb(c, neutral, blend, gains)
        if (matrix.m.any { !it.isFinite() }) return null
        return Solution(gains, matrix, neutral)
    }
    /** The illuminant a Kelvin/tint setting names, as CIE 1931 xy. See [Isotherms]. */
    fun chromaticity(kelvin: Int, tint: Int): Pair<Double, Double> {
        require(kelvin >= 1667 && kelvin <= 50000 && tint in -100..100)
        return Isotherms.xy(kelvin.toDouble(), tint / TINT_PER_UV)
    }

    /** Tint units in one CIE 1960 uv unit, so tint ±100 lies 0.033 off the locus.
     * Positive is the green side: a positive setting balances green light and
     * renders the picture towards magenta. Stored settings depend on this scale. */
    private const val TINT_PER_UV = 3000.0
}

/**
 * Kelvin and tint to chromaticity by Robertson's isotemperature lines.
 *
 * In the CIE 1960 UCS (u, v) diagram an isotemperature line is the normal to the
 * Planckian locus, and colour temperature is close to linear in reciprocal
 * temperature (mired, 10⁶/K) along the locus [1, 2]. Robertson tabulated the
 * locus point and the slope dv/du of the isotemperature line at 31 mired values
 * for the CIE 1931 2° observer [1]; Wyszecki & Stiles reprint the table [2].
 *
 * A temperature between two tabulated lines takes the locus point and the line's
 * direction linearly in mired between them. Tint is a distance along that line,
 * which is the standard measure of how far a light is off the locus (Δuv, CIE
 * 15:2004 §9.5 [3]). The result goes to xy by the inverse of the UCS
 * transform u = 4x/(−2x + 12y + 3), v = 6y/(−2x + 12y + 3) [3].
 *
 * [1] A. R. Robertson, "Computation of correlated color temperature and
 *     distribution temperature", J. Opt. Soc. Am. 58(11), 1528–1535 (1968).
 * [2] G. Wyszecki and W. S. Stiles, Color Science, 2nd ed., Wiley (1982),
 *     §3.11 and Table 1(3.11), p. 228.
 * [3] CIE 15:2004, Colorimetry, 3rd ed.
 *
 * The 325-mired row reads u = 0.24792. The reprint gives 0.24702, a misprint: the
 * neighbouring rows, Krystek's rational fit to the locus (J. Opt. Soc. Am. A 2,
 * 1985) and the locus integrated from the observer all put it near 0.2479, and
 * `WhiteBalanceConversionTest` checks every row against Krystek's fit.
 */
internal object Isotherms {
    /** Rows of (mired, u, v, slope dv/du of the isotemperature line), from [2]. */
    val rows = arrayOf(
        doubleArrayOf(0.0, 0.18006, 0.26352, -0.24341),
        doubleArrayOf(10.0, 0.18066, 0.26589, -0.25479),
        doubleArrayOf(20.0, 0.18133, 0.26846, -0.26876),
        doubleArrayOf(30.0, 0.18208, 0.27119, -0.28539),
        doubleArrayOf(40.0, 0.18293, 0.27407, -0.30470),
        doubleArrayOf(50.0, 0.18388, 0.27709, -0.32675),
        doubleArrayOf(60.0, 0.18494, 0.28021, -0.35156),
        doubleArrayOf(70.0, 0.18611, 0.28342, -0.37915),
        doubleArrayOf(80.0, 0.18740, 0.28668, -0.40955),
        doubleArrayOf(90.0, 0.18880, 0.28997, -0.44278),
        doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888),
        doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
        doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471),
        doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
        doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182),
        doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
        doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512),
        doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
        doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637),
        doubleArrayOf(325.0, 0.24792, 0.34655, -2.4681),
        doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641),
        doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
        doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633),
        doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
        doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262),
        doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
        doubleArrayOf(500.0, 0.30505, 0.35907, -11.324),
        doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
        doubleArrayOf(550.0, 0.32129, 0.36011, -23.325),
        doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
        doubleArrayOf(600.0, 0.33724, 0.36051, -116.45),
    )

    /** (u, v) at [kelvin], moved [duv] along the isotemperature line, positive towards green. */
    fun uv(kelvin: Double, duv: Double): Pair<Double, Double> {
        val mired = 1e6 / kelvin
        var hi = 1
        while (hi < rows.lastIndex && rows[hi][0] <= mired) hi++
        val lo = rows[hi - 1]; val up = rows[hi]
        val w = (mired - lo[0]) / (up[0] - lo[0])
        val u = lo[1] + w * (up[1] - lo[1])
        val v = lo[2] + w * (up[2] - lo[2])
        // Every slope is negative, so (−1, −slope) is the line's green-pointing direction.
        val (gu0, gv0) = greenward(lo[3]); val (gu1, gv1) = greenward(up[3])
        val gu = gu0 + w * (gu1 - gu0); val gv = gv0 + w * (gv1 - gv0)
        val norm = hypot(gu, gv)
        return (u + duv * gu / norm) to (v + duv * gv / norm)
    }

    fun xy(kelvin: Double, duv: Double): Pair<Double, Double> {
        val (u, v) = uv(kelvin, duv)
        val d = 2 * u - 8 * v + 4
        return (3 * u / d) to (2 * v / d)
    }

    private fun greenward(slope: Double): Pair<Double, Double> {
        val n = hypot(1.0, slope)
        return (-1 / n) to (-slope / n)
    }
}

internal fun CalibratedWhiteBalance.Solution.cameraGains() = android.hardware.camera2.params.RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
internal fun CalibratedWhiteBalance.Solution.cameraTransform() = android.hardware.camera2.params.ColorSpaceTransform(
    Array(9) { android.util.Rational((transform.m[it] * 1_000_000).toInt(), 1_000_000) })
