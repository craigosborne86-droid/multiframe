package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The temperature the viewfinder puts on screen.
 *
 * The bar is higher than for [ColorScience.blendFor], and deliberately so. That
 * one is a proxy choosing between two matrices and is allowed to err toward the
 * middle. A figure displayed to a photographer as "5200K" is a claim about the
 * light, and the test for it is whether known illuminants come back at their
 * known temperatures.
 */
class ColourTemperatureTest {

    /**
     * Chromaticities of the standard illuminants, from the CIE tables, with the
     * temperatures they are defined at.
     */
    private val standardIlluminants = listOf(
        Triple("A (tungsten)", 0.4476f to 0.4074f, 2856),
        Triple("D50 (horizon)", 0.3457f to 0.3585f, 5003),
        Triple("D55", 0.3324f to 0.3474f, 5503),
        Triple("D65 (daylight)", 0.3127f to 0.3290f, 6504),
        Triple("D75 (north sky)", 0.2990f to 0.3149f, 7504),
    )

    /**
     * McCamy's cubic against the illuminants it is meant to reproduce.
     *
     * Two percent, which is the accuracy the approximation is documented to
     * have over this range and is far finer than the display: nobody can tell
     * 6504K from 6600K by looking at a photograph.
     */
    @Test
    fun theStandardIlluminantsComeBackAtTheirOwnTemperatures() {
        for ((name, xy, expected) in standardIlluminants) {
            val (x, y) = xy
            val cct = ColorScience.cctFromChromaticity(x, y)
            assertThat(cct).isNotNull()
            assertThat(kotlin.math.abs(cct!! - expected) / expected.toFloat())
                .isLessThan(0.02f)
        }
    }

    @Test
    fun warmerLightReportsALowerTemperature() {
        // Ordered along the Planckian locus, which is the one monotonicity a
        // temperature reading has to have: tungsten below daylight below sky.
        val temperatures = standardIlluminants.map { (_, xy, _) ->
            ColorScience.cctFromChromaticity(xy.first, xy.second)!!
        }
        assertThat(temperatures).isInOrder()
    }

    /**
     * The epicentre has no temperature, and saying so beats returning the
     * enormous number the cubic produces as the slope diverges.
     */
    @Test
    fun theEpicentreIsRefusedRatherThanReported() {
        assertThat(ColorScience.cctFromChromaticity(0.3320f, 0.1858f)).isNull()
    }

    @Test
    fun aTemperatureOutsideTheUsefulRangeIsClamped() {
        // Far off the locus in both directions. The cubic still returns a
        // number; what it does not return is a temperature, so the bounds hold
        // it to something that can be printed without being a lie.
        val hot = ColorScience.cctFromChromaticity(0.20f, 0.20f)
        val cold = ColorScience.cctFromChromaticity(0.60f, 0.35f)
        assertThat(hot).isNotNull()
        assertThat(cold).isNotNull()
        assertThat(hot!!).isAtMost(ColorScience.CCT_MAX)
        assertThat(hot).isAtLeast(ColorScience.CCT_MIN)
        assertThat(cold!!).isAtMost(ColorScience.CCT_MAX)
        assertThat(cold).isAtLeast(ColorScience.CCT_MIN)
    }

    // --- through a sensor calibration --------------------------------------

    /**
     * A calibration whose forward matrix is the sRGB primaries, so a neutral in
     * "camera" space is a neutral in XYZ and the answer is checkable by hand.
     */
    private fun srgbCalibration() = ColorScience.Calibration(
        colorMatrix1 = ColorScience.XYZ_TO_SRGB,
        colorMatrix2 = ColorScience.XYZ_TO_SRGB,
        forward1 = ColorScience.SRGB_TO_XYZ,
        forward2 = ColorScience.SRGB_TO_XYZ,
    )

    @Test
    fun anEqualEnergyNeutralIsAboutDaylight() {
        // Equal linear sRGB is the D65 white point by construction, so this has
        // to come back near 6500K or the route through the matrices is wrong.
        val cct = ColorScience.correlatedColourTemperature(
            srgbCalibration(), floatArrayOf(1f, 1f, 1f),
        )
        assertThat(cct).isNotNull()
        assertThat(cct!!).isGreaterThan(6000)
        assertThat(cct).isLessThan(7000)
    }

    @Test
    fun aRedHeavyNeutralIsWarmerThanAnEqualOne() {
        val calibration = srgbCalibration()
        val neutral = ColorScience.correlatedColourTemperature(
            calibration, floatArrayOf(1f, 1f, 1f),
        )!!
        // A neutral object reflecting more red than blue means warm light.
        val warm = ColorScience.correlatedColourTemperature(
            calibration, floatArrayOf(1.6f, 1f, 0.6f),
        )!!
        assertThat(warm).isLessThan(neutral)
    }

    @Test
    fun aBlueHeavyNeutralIsCoolerThanAnEqualOne() {
        val calibration = srgbCalibration()
        val neutral = ColorScience.correlatedColourTemperature(
            calibration, floatArrayOf(1f, 1f, 1f),
        )!!
        val cool = ColorScience.correlatedColourTemperature(
            calibration, floatArrayOf(0.7f, 1f, 1.5f),
        )!!
        assertThat(cool).isGreaterThan(neutral)
    }

    /**
     * Gains are the reciprocal of a neutral, which is the form a capture result
     * reports them in. The two routes have to agree or the gauge is reading
     * something other than what the develop is rendering against.
     */
    @Test
    fun theGainsRouteAgreesWithTheNeutralRoute() {
        val calibration = srgbCalibration()
        val gains = floatArrayOf(2.0f, 1f, 1f, 1.4f)
        val viaGains = ColorScience.cctFromGains(calibration, gains)
        val viaNeutral = ColorScience.correlatedColourTemperature(
            calibration, floatArrayOf(1f / 2.0f, 1f, 1f / 1.4f),
        )
        assertThat(viaGains).isEqualTo(viaNeutral)
    }

    /**
     * Gains and temperature run in opposite directions, which is the sign
     * convention easiest to get backwards.
     *
     * A gain is a correction, so a *large* red gain means the sensor had little
     * red to work with -- a blue-rich scene, and cool light. The neutral is the
     * reciprocal, and it is the neutral that is red-heavy under warm light.
     *
     * Worth pinning explicitly because the intuition points the other way: it
     * is tempting to read "red gain 2.0" as "lots of red, so tungsten", and a
     * gauge built on that reads every scene backwards.
     */
    @Test
    fun aLargeRedGainMeansCoolLightRatherThanWarm() {
        val calibration = srgbCalibration()
        val redBoosted = ColorScience.cctFromGains(
            calibration, floatArrayOf(2.0f, 1f, 1f, 1.0f),
        )!!
        val blueBoosted = ColorScience.cctFromGains(
            calibration, floatArrayOf(1.0f, 1f, 1f, 2.0f),
        )!!
        assertThat(redBoosted).isGreaterThan(blueBoosted)
    }

    // --- refusals ----------------------------------------------------------

    @Test
    fun aDegenerateNeutralIsRefusedRatherThanFabricated() {
        val calibration = srgbCalibration()
        assertThat(
            ColorScience.correlatedColourTemperature(calibration, floatArrayOf(0f, 1f, 1f))
        ).isNull()
        assertThat(
            ColorScience.correlatedColourTemperature(calibration, floatArrayOf(1f, -1f, 1f))
        ).isNull()
        assertThat(
            ColorScience.correlatedColourTemperature(calibration, floatArrayOf(1f, 1f))
        ).isNull()
    }

    @Test
    fun shortGainsAreRefused() {
        val calibration = srgbCalibration()
        assertThat(ColorScience.cctFromGains(calibration, floatArrayOf(1f, 1f, 1f))).isNull()
        assertThat(
            ColorScience.cctFromGains(calibration, floatArrayOf(0f, 1f, 1f, 1f))
        ).isNull()
    }
}
