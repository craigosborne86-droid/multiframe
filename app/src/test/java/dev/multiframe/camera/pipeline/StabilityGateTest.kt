package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The gate's arithmetic: what "still enough" is, in the numbers the sensor
 * and the lens publish, and where the clamps take over.
 */
class StabilityGateTest {

    // The Pixel's telephoto: 4080 px across a frame the 110mm-equivalent
    // lens paints, so 4080 / (36 / 110) mm... via the equivalent route.
    private val ppr = StabilityGate.pixelsPerRadianFromEquivalent(110, 4080)

    @Test
    fun `pixels per radian is the focal length in pixels`() {
        // A 110mm-equivalent lens on a 36mm-wide frame of 4080 px: 12,466 px/rad.
        assertThat(ppr).isWithin(1f).of(110f * 4080f / 36f)
        // From physical numbers: a 20mm lens on a 7mm sensor of 4080 px.
        assertThat(StabilityGate.pixelsPerRadian(20f, 7f, 4080)).isWithin(1f).of(20f * 4080f / 7f)
        assertThat(StabilityGate.pixelsPerRadian(0f, 7f, 4080)).isEqualTo(0f)
        assertThat(StabilityGate.pixelsPerRadianFromEquivalent(0, 4080)).isEqualTo(0f)
    }

    @Test
    fun `the ceiling is the speed that blurs two pixels in the exposure`() {
        // 1/250 s on the telephoto: 2 px / (4 ms * 12466 px/rad) = 0.040 rad/s,
        // between the floor and the cap, so the rule itself decides.
        val omega = StabilityGate.maxAngularSpeed(4_000_000L, ppr)
        assertThat(omega).isWithin(1e-4f).of(2f / (4e-3f * ppr))
        assertThat(StabilityGate.blurPixels(omega, 4_000_000L, ppr)).isWithin(1e-3f).of(2f)
    }

    @Test
    fun `a long exposure is floored at what a hand can do`() {
        // 1/30 s would ask for 0.005 rad/s; the floor holds it at two degrees a second.
        assertThat(StabilityGate.maxAngularSpeed(33_333_333L, ppr)).isEqualTo(StabilityGate.MIN_ANGULAR_SPEED)
        assertThat(Math.toDegrees(StabilityGate.MIN_ANGULAR_SPEED.toDouble())).isWithin(0.05).of(2.0)
    }

    @Test
    fun `a short exposure is capped so a wobble is not accepted`() {
        // 1/2000 s would allow 0.32 rad/s; the cap holds it at five degrees a second.
        assertThat(StabilityGate.maxAngularSpeed(500_000L, ppr)).isEqualTo(StabilityGate.MAX_ANGULAR_SPEED)
        assertThat(Math.toDegrees(StabilityGate.MAX_ANGULAR_SPEED.toDouble())).isWithin(0.2).of(5.2)
    }

    @Test
    fun `unknown geometry falls back to two degrees a second`() {
        assertThat(StabilityGate.maxAngularSpeed(4_000_000L, 0f)).isEqualTo(StabilityGate.DEFAULT_ANGULAR_SPEED)
        assertThat(StabilityGate.maxAngularSpeed(0L, ppr)).isEqualTo(StabilityGate.DEFAULT_ANGULAR_SPEED)
        assertThat(Math.toDegrees(StabilityGate.DEFAULT_ANGULAR_SPEED.toDouble())).isWithin(0.05).of(2.0)
    }

    @Test
    fun `speed is the magnitude of the rotation rate`() {
        assertThat(StabilityGate.speedOf(0.3f, 0.4f, 0f)).isWithin(1e-6f).of(0.5f)
    }
}
