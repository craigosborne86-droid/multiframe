package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Choosing between the two colour matrices a sensor was characterised with.
 *
 * The interesting part is not the interpolation itself but the two things
 * around it: that it runs in mired rather than kelvin, and that it terminates
 * at all despite the matrix and the temperature each needing the other first.
 */
class DualIlluminantTest {

    /** Standard A and D65, the pair almost every phone reports. */
    private fun calibration(
        k1: Int? = 2856,
        k2: Int? = 6504,
    ) = ColorScience.Calibration(
        colorMatrix1 = ColorScience.XYZ_TO_SRGB,
        colorMatrix2 = ColorScience.XYZ_TO_SRGB,
        forward1 = ColorScience.SRGB_TO_XYZ,
        forward2 = ColorScience.SRGB_TO_XYZ,
        illuminant1Kelvin = k1,
        illuminant2Kelvin = k2,
    )

    // --- illuminant codes --------------------------------------------------

    @Test
    fun theStandardIlluminantsMapToTheirAcceptedTemperatures() {
        assertThat(ColorScience.illuminantKelvin(17)).isEqualTo(2856)  // Standard A
        assertThat(ColorScience.illuminantKelvin(21)).isEqualTo(6504)  // D65
        assertThat(ColorScience.illuminantKelvin(23)).isEqualTo(5003)  // D50
        assertThat(ColorScience.illuminantKelvin(3)).isEqualTo(2856)   // Tungsten
        assertThat(ColorScience.illuminantKelvin(11)).isEqualTo(7500)  // Shade
    }

    /**
     * An unrecognised code produces nothing rather than a plausible default.
     * Interpolating against a guessed temperature is worse than not
     * interpolating.
     */
    @Test
    fun anUnknownIlluminantCodeIsNotGuessedAt() {
        assertThat(ColorScience.illuminantKelvin(0)).isNull()
        assertThat(ColorScience.illuminantKelvin(99)).isNull()
        assertThat(ColorScience.illuminantKelvin(-1)).isNull()
    }

    // --- mired interpolation -----------------------------------------------

    @Test
    fun theEndpointsSelectTheirOwnMatrix() {
        assertThat(ColorScience.blendForTemperature(2856, 2856, 6504))
            .isWithin(1e-4f).of(0f)
        assertThat(ColorScience.blendForTemperature(6504, 2856, 6504))
            .isWithin(1e-4f).of(1f)
    }

    /**
     * The property the whole thing exists for: interpolation is linear in
     * reciprocal temperature, not in temperature.
     *
     * A linear-in-kelvin blend would put the midpoint at 4680 K. In mired the
     * midpoint sits near 4000 K, and it is mired that matches how the colour
     * actually shifts — 500 K is a large move at the tungsten end and a small
     * one at the daylight end.
     */
    @Test
    fun interpolationIsLinearInMiredNotInKelvin() {
        val half = ColorScience.blendForTemperature(4000, 2856, 6504)
        assertThat(half).isWithin(0.02f).of(0.5f)

        // The kelvin midpoint is well past halfway in mired.
        val kelvinMidpoint = ColorScience.blendForTemperature(4680, 2856, 6504)
        assertThat(kelvinMidpoint).isGreaterThan(0.6f)
    }

    @Test
    fun warmerScenesSitNearerTheWarmerIlluminant() {
        val tungsten = ColorScience.blendForTemperature(3000, 2856, 6504)
        val daylight = ColorScience.blendForTemperature(6000, 2856, 6504)
        assertThat(tungsten).isLessThan(daylight)
    }

    /**
     * A characterisation is not evidence about light outside the illuminants it
     * was measured under, so the blend clamps rather than extrapolating.
     */
    @Test
    fun temperaturesOutsideThePairAreClampedNotExtrapolated() {
        assertThat(ColorScience.blendForTemperature(1800, 2856, 6504)).isEqualTo(0f)
        assertThat(ColorScience.blendForTemperature(12000, 2856, 6504)).isEqualTo(1f)
    }

    @Test
    fun degenerateTemperaturesFallBackToTheMidpoint() {
        assertThat(ColorScience.blendForTemperature(5000, 5000, 5000)).isEqualTo(0.5f)
        assertThat(ColorScience.blendForTemperature(0, 2856, 6504)).isEqualTo(0.5f)
        assertThat(ColorScience.blendForTemperature(5000, 0, 6504)).isEqualTo(0.5f)
    }

    /** The pair may be reported in either order; both must work. */
    @Test
    fun theIlluminantOrderDoesNotMatter() {
        val forward = ColorScience.blendForTemperature(4000, 2856, 6504)
        val reversed = ColorScience.blendForTemperature(4000, 6504, 2856)
        assertThat(forward + reversed).isWithin(1e-3f).of(1f)
    }

    // --- the circularity ---------------------------------------------------

    /**
     * Choosing the matrix needs the temperature; computing the temperature
     * needs the matrix. This is the test that the iteration terminates and
     * lands somewhere sensible rather than oscillating.
     */
    @Test
    fun theBlendSolvesRatherThanRecursing() {
        val c = calibration()
        for (neutral in listOf(
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1.6f, 1f, 0.6f),
            floatArrayOf(0.7f, 1f, 1.5f),
        )) {
            val blend = ColorScience.solveBlend(c, neutral)
            assertThat(blend).isAtLeast(0f)
            assertThat(blend).isAtMost(1f)
            assertThat(blend.isNaN()).isFalse()
        }
    }

    /**
     * The solved blend is a fixed point: feeding its temperature back in
     * returns the same blend. If it were not, the picture would be rendered
     * against a matrix chosen for a different temperature than the one the
     * scene was judged to have.
     */
    @Test
    fun theSolvedBlendIsAFixedPoint() {
        val c = calibration()
        val neutral = floatArrayOf(1.3f, 1f, 0.8f)

        val blend = ColorScience.solveBlend(c, neutral)
        val kelvin = ColorScience.cctForBlend(c, neutral, blend)!!
        val again = ColorScience.blendForTemperature(
            kelvin, c.illuminant1Kelvin!!, c.illuminant2Kelvin!!,
        )
        assertThat(again).isWithin(5e-3f).of(blend)
    }

    @Test
    fun aWarmNeutralSolvesWarmerThanACoolOne() {
        val c = calibration()
        val warm = ColorScience.solveBlend(c, floatArrayOf(1.6f, 1f, 0.6f))
        val cool = ColorScience.solveBlend(c, floatArrayOf(0.7f, 1f, 1.5f))
        assertThat(warm).isLessThan(cool)
    }

    /**
     * Without published illuminants there is no temperature scale to
     * interpolate on, and the older ratio proxy is used unchanged. This is the
     * common case on devices that publish matrices but not light sources.
     */
    @Test
    fun withoutIlluminantsItFallsBackToTheProxy() {
        val bare = calibration(k1 = null, k2 = null)
        assertThat(bare.hasIlluminants).isFalse()

        val neutral = floatArrayOf(1.4f, 1f, 0.7f)
        assertThat(ColorScience.solveBlend(bare, neutral))
            .isEqualTo(ColorScience.blendFor(neutral))
    }

    @Test
    fun twoIdenticalIlluminantsAreNotAPair() {
        val same = calibration(k1 = 5000, k2 = 5000)
        assertThat(same.hasIlluminants).isFalse()
    }

    // --- the temperature the gauge shows -----------------------------------

    /**
     * The viewfinder's reading and the matrix the develop picks now come from
     * one solve, so they cannot disagree about what light the scene is under.
     */
    @Test
    fun theReportedTemperatureMatchesTheBlendUsedToRender() {
        val c = calibration()
        val neutral = floatArrayOf(1.2f, 1f, 0.9f)

        val reported = ColorScience.correlatedColourTemperature(c, neutral)
        val viaSolve = ColorScience.cctForBlend(c, neutral, ColorScience.solveBlend(c, neutral))
        assertThat(reported).isEqualTo(viaSolve)
    }

    @Test
    fun aDegenerateNeutralStillProducesNoTemperature() {
        val c = calibration()
        assertThat(ColorScience.cctForBlend(c, floatArrayOf(0f, 1f, 1f), 0.5f)).isNull()
        assertThat(ColorScience.correlatedColourTemperature(c, floatArrayOf(1f, 1f)))
            .isNull()
    }
}
