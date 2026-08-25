package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Colorimetry.
 *
 * The property that matters more than any other is that neutral stays neutral.
 * A colour pipeline that tints greys is wrong in a way every viewer notices
 * immediately and no amount of pleasant saturation makes up for.
 */
class ColorScienceTest {

    /** Representative of a real phone sensor: reasonably close to sRGB. */
    private val colourMatrixDaylight = Mat3(
        floatArrayOf(
            0.72f, -0.20f, -0.08f,
            -0.28f, 1.12f, 0.14f,
            -0.05f, 0.18f, 0.62f,
        )
    )

    private val colourMatrixTungsten = Mat3(
        floatArrayOf(
            0.80f, -0.26f, -0.06f,
            -0.32f, 1.18f, 0.10f,
            -0.03f, 0.14f, 0.70f,
        )
    )

    /** Forward matrices map white-balanced camera space to XYZ under D50. */
    private val forwardDaylight = Mat3(
        floatArrayOf(
            0.62f, 0.24f, 0.10f,
            0.28f, 0.68f, 0.04f,
            0.02f, 0.06f, 0.74f,
        )
    )

    private val forwardTungsten = Mat3(
        floatArrayOf(
            0.58f, 0.26f, 0.12f,
            0.26f, 0.70f, 0.04f,
            0.03f, 0.05f, 0.74f,
        )
    )

    private val calibration = ColorScience.Calibration(
        colorMatrix1 = colourMatrixTungsten,
        colorMatrix2 = colourMatrixDaylight,
        forward1 = forwardTungsten,
        forward2 = forwardDaylight,
    )

    /** As-shot neutral: the camera-space colour of white under the scene's light. */
    private val daylightNeutral = floatArrayOf(0.55f, 1.0f, 0.62f)

    private fun applied(matrix: Mat3, r: Float, g: Float, b: Float): FloatArray {
        val out = FloatArray(3)
        matrix.apply(r, g, b, out)
        return out
    }

    // ------------------------------------------------------------------
    // The property everything else depends on.
    // ------------------------------------------------------------------

    @Test
    fun `the scene's white renders as neutral grey`() {
        // A pipeline that tints greys is wrong in a way every viewer notices.
        val matrix = ColorScience.cameraToSrgb(calibration, daylightNeutral, blend = 1f)

        val white = applied(matrix, daylightNeutral[0], daylightNeutral[1], daylightNeutral[2])

        println("white -> (%.4f, %.4f, %.4f)".format(white[0], white[1], white[2]))
        assertThat(white[0]).isWithin(0.01f).of(white[1])
        assertThat(white[1]).isWithin(0.01f).of(white[2])
        assertThat(white[1]).isWithin(0.02f).of(1f)
    }

    @Test
    fun `white stays neutral at either illuminant`() {
        for (blend in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val matrix = ColorScience.cameraToSrgb(calibration, daylightNeutral, blend)
            val white = applied(matrix, daylightNeutral[0], daylightNeutral[1], daylightNeutral[2])
            assertThat(white[0]).isWithin(0.015f).of(white[1])
            assertThat(white[1]).isWithin(0.015f).of(white[2])
        }
    }

    @Test
    fun `a different scene white also renders neutral`() {
        // Under tungsten the neutral is a different camera colour entirely, and
        // it must still come out grey.
        val tungstenNeutral = floatArrayOf(0.78f, 1.0f, 0.42f)

        val matrix = ColorScience.cameraToSrgb(calibration, tungstenNeutral, blend = 0f)
        val white = applied(matrix, tungstenNeutral[0], tungstenNeutral[1], tungstenNeutral[2])

        assertThat(white[0]).isWithin(0.015f).of(white[1])
        assertThat(white[1]).isWithin(0.015f).of(white[2])
    }

    @Test
    fun `the transform works without forward matrices`() {
        // Reporting them is optional, and a device without them must still get
        // correct colour rather than no colour.
        val noForward = calibration.copy(forward1 = null, forward2 = null)

        val matrix = ColorScience.cameraToSrgb(noForward, daylightNeutral, blend = 1f)
        val white = applied(matrix, daylightNeutral[0], daylightNeutral[1], daylightNeutral[2])

        assertThat(white[0]).isWithin(0.02f).of(white[1])
        assertThat(white[1]).isWithin(0.02f).of(white[2])
    }

    // ------------------------------------------------------------------
    // Interpolation.
    // ------------------------------------------------------------------

    @Test
    fun `the two illuminants give different matrices`() {
        // If they did not, interpolating between them would be pointless and
        // the whole two-illuminant characterisation would be wasted.
        val tungsten = ColorScience.cameraToSrgb(calibration, daylightNeutral, blend = 0f)
        val daylight = ColorScience.cameraToSrgb(calibration, daylightNeutral, blend = 1f)

        var largest = 0f
        for (i in 0 until 9) {
            largest = maxOf(largest, kotlin.math.abs(tungsten.m[i] - daylight.m[i]))
        }
        println("largest matrix difference between illuminants: %.4f".format(largest))
        assertThat(largest).isGreaterThan(0.01f)
    }

    @Test
    fun `interpolation moves smoothly between the two`() {
        val at0 = ColorScience.cameraToSrgb(calibration, daylightNeutral, 0f)
        val at5 = ColorScience.cameraToSrgb(calibration, daylightNeutral, 0.5f)
        val at1 = ColorScience.cameraToSrgb(calibration, daylightNeutral, 1f)

        for (i in 0 until 9) {
            val low = minOf(at0.m[i], at1.m[i])
            val high = maxOf(at0.m[i], at1.m[i])
            assertThat(at5.m[i]).isAtLeast(low - 1e-4f)
            assertThat(at5.m[i]).isAtMost(high + 1e-4f)
        }
    }

    @Test
    fun `a warm scene selects the tungsten end`() {
        // Under warm light a neutral reflects far more red than blue, so its
        // camera-space colour has a large red component. DNG puts the warmer
        // illuminant first, so a warm scene must land near zero.
        val warm = ColorScience.blendFor(floatArrayOf(0.80f, 1f, 0.40f))
        val cool = ColorScience.blendFor(floatArrayOf(0.45f, 1f, 0.75f))

        println("blend: warm scene %.2f, cool scene %.2f".format(warm, cool))
        assertThat(warm).isLessThan(cool)
        assertThat(warm).isAtLeast(0f)
        assertThat(cool).isAtMost(1f)
    }

    @Test
    fun `a nonsense neutral does not produce a nonsense blend`() {
        for (nonsense in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f), FloatArray(0))) {
            val blend = ColorScience.blendFor(nonsense)
            assertThat(blend).isAtLeast(0f)
            assertThat(blend).isAtMost(1f)
        }
    }

    // ------------------------------------------------------------------
    // Matrix algebra.
    // ------------------------------------------------------------------

    @Test
    fun `sRGB round trips through XYZ`() {
        // If these two constants disagree, every colour is wrong by a little.
        val round = ColorScience.XYZ_TO_SRGB * ColorScience.SRGB_TO_XYZ

        for (i in 0 until 9) {
            val expected = if (i % 4 == 0) 1f else 0f
            assertThat(round.m[i]).isWithin(1e-3f).of(expected)
        }
    }

    @Test
    fun `inverting a matrix undoes it`() {
        val inverse = colourMatrixDaylight.invert()!!
        val round = colourMatrixDaylight * inverse

        for (i in 0 until 9) {
            val expected = if (i % 4 == 0) 1f else 0f
            assertThat(round.m[i]).isWithin(1e-4f).of(expected)
        }
    }

    @Test
    fun `a singular matrix reports that it cannot be inverted`() {
        val flat = Mat3(floatArrayOf(1f, 2f, 3f, 2f, 4f, 6f, 1f, 1f, 1f))

        assertThat(flat.invert()).isNull()
    }

    @Test
    fun `normalising makes a matrix map its target to one`() {
        val target = floatArrayOf(0.6f, 1f, 0.7f)

        val normalised = forwardDaylight.normalisedFor(target)
        val result = applied(normalised, target[0], target[1], target[2])

        for (v in result) assertThat(v).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `a degenerate matrix cannot be normalised into a division by zero`() {
        val zeros = Mat3(FloatArray(9))

        val normalised = zeros.normalisedFor(floatArrayOf(1f, 1f, 1f))

        for (v in normalised.m) assertThat(v).isEqualTo(0f)
    }

    @Test
    fun `sRGB primaries land where they should in XYZ`() {
        // Green carries most of the luminance, which is the Y row.
        val green = applied(ColorScience.SRGB_TO_XYZ, 0f, 1f, 0f)

        assertThat(green[1]).isWithin(0.001f).of(0.7151522f)
        val red = applied(ColorScience.SRGB_TO_XYZ, 1f, 0f, 0f)
        assertThat(red[1]).isWithin(0.001f).of(0.2126729f)
    }
}
