package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The level.
 *
 * Tested against known gravity vectors, because signs and axes are exactly the
 * sort of thing that is easy to get backwards and impossible to notice from a
 * screenshot. A level that reads the wrong way is worse than no level at all.
 */
class LevelGaugeTest {

    private val g = 9.81f

    // Android device axes with the phone upright in portrait: +x to the right,
    // +y toward the top edge, +z out of the screen. Gravity therefore points
    // along -y, and every vector below is the direction *down*.

    @Test
    fun `held upright and level reads as level`() {
        val attitude = LevelGauge.attitudeFrom(0f, -g, 0f)

        assertThat(attitude.rollDegrees).isWithin(0.5f).of(0f)
        assertThat(attitude.isLevel).isTrue()
        assertThat(attitude.facingUpOrDown).isFalse()
    }

    @Test
    fun `tilting the phone clockwise reads as a clockwise roll`() {
        // The direction has to be right or the indicator tells the user to
        // correct the wrong way.
        val tilted = LevelGauge.attitudeFrom(g * 0.17f, -g * 0.98f, 0f)

        assertThat(tilted.rollDegrees).isGreaterThan(5f)
        assertThat(tilted.rollDegrees).isLessThan(15f)
        assertThat(tilted.isLevel).isFalse()
    }

    @Test
    fun `tilting the other way reads the other way`() {
        val left = LevelGauge.attitudeFrom(-g * 0.17f, -g * 0.98f, 0f)
        val right = LevelGauge.attitudeFrom(g * 0.17f, -g * 0.98f, 0f)

        assertThat(left.rollDegrees).isLessThan(0f)
        assertThat(right.rollDegrees).isGreaterThan(0f)
        assertThat(left.rollDegrees).isWithin(0.5f).of(-right.rollDegrees)
    }

    @Test
    fun `a quarter turn reads as ninety degrees`() {
        // Phone on its side: gravity now runs across the screen.
        val landscape = LevelGauge.attitudeFrom(g, 0f, 0f)

        assertThat(kotlin.math.abs(landscape.rollDegrees)).isWithin(0.5f).of(90f)
    }

    @Test
    fun `holding the phone in landscape still reads level`() {
        // Corrected for how the device is held, or the level would insist the
        // user is ninety degrees out whenever they turn the phone.
        val landscape = LevelGauge.attitudeFrom(g, 0f, 0f, deviceRotationDegrees = 90)

        assertThat(landscape.rollDegrees).isWithin(0.5f).of(0f)
        assertThat(landscape.isLevel).isTrue()
    }

    @Test
    fun `pointing at the floor is reported as such rather than as a wild roll`() {
        // Phone face up, camera looking down: gravity runs along the viewing
        // axis, roll is undefined, and a confident answer would have the
        // indicator spin as the phone moves.
        val down = LevelGauge.attitudeFrom(0f, 0f, -g)

        assertThat(down.facingUpOrDown).isTrue()
        assertThat(down.rollDegrees).isEqualTo(0f)
        assertThat(down.pitchDegrees).isLessThan(-(LevelGauge.GIMBAL_LIMIT_DEGREES - 1f))
    }

    @Test
    fun `pointing at the sky is also reported`() {
        val up = LevelGauge.attitudeFrom(0f, 0f, g)

        assertThat(up.facingUpOrDown).isTrue()
        assertThat(up.pitchDegrees).isGreaterThan(LevelGauge.GIMBAL_LIMIT_DEGREES - 1f)
    }

    @Test
    fun `a modest downward tilt still gives a usable roll`() {
        // Photographing something below eye level must not disable the level.
        val slight = LevelGauge.attitudeFrom(g * 0.1f, -g * 0.9f, -g * 0.35f)

        assertThat(slight.facingUpOrDown).isFalse()
        assertThat(slight.rollDegrees).isNotEqualTo(0f)
    }

    @Test
    fun `weightlessness does not produce a random reading`() {
        // A dropped or shaken phone can report almost nothing.
        val nothing = LevelGauge.attitudeFrom(0f, 0f, 0f)

        assertThat(nothing.rollDegrees).isEqualTo(0f)
        assertThat(nothing.pitchDegrees).isEqualTo(0f)
    }

    // ------------------------------------------------------------------

    @Test
    fun `angles wrap instead of running away`() {
        assertThat(LevelGauge.normalise(190f)).isWithin(0.01f).of(-170f)
        assertThat(LevelGauge.normalise(-190f)).isWithin(0.01f).of(170f)
        assertThat(LevelGauge.normalise(45f)).isWithin(0.01f).of(45f)
        assertThat(LevelGauge.normalise(360f)).isWithin(0.01f).of(0f)
    }

    @Test
    fun `smoothing moves toward the new reading without twitching`() {
        // Hand tremor alone jitters the raw signal by a degree or two, and an
        // indicator that twitches is harder to use than one that lags.
        var value = 0f
        repeat(40) { value = LevelGauge.smooth(value, 10f) }

        assertThat(value).isWithin(0.5f).of(10f)
        // A single step must not jump the whole way.
        assertThat(LevelGauge.smooth(0f, 10f)).isLessThan(3f)
    }

    @Test
    fun `smoothing takes the short way round the wrap point`() {
        // Crossing 180 degrees must not sweep the indicator the long way.
        val stepped = LevelGauge.smooth(179f, -179f, factor = 0.5f)

        assertThat(kotlin.math.abs(stepped)).isGreaterThan(179f)
    }
}
