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
        // 1/1000 s on the telephoto: 2 px / (1 ms * 12466 px/rad) = 0.160 rad/s,
        // between the floor and the cap, so the rule itself decides.
        val omega = StabilityGate.maxAngularSpeed(1_000_000L, ppr)
        assertThat(omega).isWithin(1e-4f).of(2f / (1e-3f * ppr))
        assertThat(StabilityGate.blurPixels(omega, 1_000_000L, ppr)).isWithin(1e-3f).of(2f)
    }

    @Test
    fun `a long exposure is floored at what a hand can do`() {
        // 1/30 s would ask for 0.005 rad/s, and 1/250 s for 0.040; the floor
        // holds both at six degrees a second, which is what a hand does.
        assertThat(StabilityGate.maxAngularSpeed(33_333_333L, ppr)).isEqualTo(StabilityGate.MIN_ANGULAR_SPEED)
        assertThat(StabilityGate.maxAngularSpeed(StabilityGate.SHUTTER_FLOOR_NS, ppr)).isEqualTo(StabilityGate.MIN_ANGULAR_SPEED)
        assertThat(Math.toDegrees(StabilityGate.MIN_ANGULAR_SPEED.toDouble())).isWithin(0.05).of(6.0)
    }

    @Test
    fun `a short exposure is capped so a wobble is not accepted`() {
        // 1/4000 s would allow 0.64 rad/s; the cap holds it at twelve degrees a second.
        assertThat(StabilityGate.maxAngularSpeed(250_000L, ppr)).isEqualTo(StabilityGate.MAX_ANGULAR_SPEED)
        assertThat(Math.toDegrees(StabilityGate.MAX_ANGULAR_SPEED.toDouble())).isWithin(0.1).of(12.0)
    }

    @Test
    fun `unknown geometry falls back to six degrees a second`() {
        assertThat(StabilityGate.maxAngularSpeed(4_000_000L, 0f)).isEqualTo(StabilityGate.DEFAULT_ANGULAR_SPEED)
        assertThat(StabilityGate.maxAngularSpeed(0L, ppr)).isEqualTo(StabilityGate.DEFAULT_ANGULAR_SPEED)
        assertThat(Math.toDegrees(StabilityGate.DEFAULT_ANGULAR_SPEED.toDouble())).isWithin(0.05).of(6.0)
    }

    @Test
    fun `the shutter floor is a 250th of a second on the telephoto and scales with the lens`() {
        assertThat(StabilityGate.SHUTTER_FLOOR_NS).isEqualTo(1_000_000_000L / 250L)
        assertThat(StabilityGate.shutterFloorNs(105)).isEqualTo(StabilityGate.SHUTTER_FLOOR_NS)
        // Half the focal length, twice the exposure for the same blur.
        assertThat(StabilityGate.shutterFloorNs(52)).isWithin(100_000L).of(8_000_000L)
        // And clamped: a 12mm is not allowed 1/28 s, a 240mm periscope is not asked for 1/570.
        assertThat(StabilityGate.shutterFloorNs(12)).isEqualTo(StabilityGate.SHUTTER_FLOOR_MAX_NS)
        assertThat(StabilityGate.shutterFloorNs(240)).isEqualTo(StabilityGate.SHUTTER_FLOOR_MIN_NS)
        assertThat(StabilityGate.shutterFloorNs(0)).isEqualTo(StabilityGate.SHUTTER_FLOOR_NS)
    }

    @Test
    fun `a slow converged exposure is pinned at the floor with the ISO raised to keep the light`() {
        // 1/30 s at ISO 100 on the 105: eight and a third times shorter, ISO 833.
        val pinned = StabilityGate.sweepExposure(100, 33_333_333L, 105, 50, 6400)!!
        assertThat(pinned.exposureNs).isEqualTo(StabilityGate.SHUTTER_FLOOR_NS)
        assertThat(pinned.iso).isEqualTo(833)
        assertThat(pinned.stretched).isFalse()
        // Already fast enough: nothing to pin.
        assertThat(StabilityGate.sweepExposure(400, 2_000_000L, 105, 50, 6400)).isNull()
        assertThat(StabilityGate.sweepExposure(400, 4_000_000L, 105, 50, 6400)).isNull()
        // A sensor whose shortest exposure is longer than the floor is pinned at its shortest.
        val slowSensor = StabilityGate.sweepExposure(100, 33_333_333L, 105, 50, 6400, sensorMinExposureNs = 8_000_000L)!!
        assertThat(slowSensor.exposureNs).isEqualTo(8_000_000L)
        assertThat(slowSensor.iso).isEqualTo(416)
    }

    /**
     * The evening telephoto. At the floor the light would need ISO 13,000; a
     * moving hand gets the floor and the ceiling, and a black frame with noise
     * in it. A still hand gets the floor stretched until the ISO is back at
     * 3200 -- here to the full 1/60 s -- and the gate tightens to pay for it.
     */
    @Test
    fun `in the dark the floor stretches for a still hand and not for a moving one`() {
        // 1/15 s at ISO 800: ISO 13,333 at 1/250. Moving: floored and clamped.
        val moving = StabilityGate.sweepExposure(800, 66_666_666L, 105, 50, 6400, steady = false)!!
        assertThat(moving.exposureNs).isEqualTo(StabilityGate.SHUTTER_FLOOR_NS)
        assertThat(moving.iso).isEqualTo(6400)
        assertThat(moving.stretched).isFalse()
        // Still: stretched. ISO 3200 would need 1/60 s; the stretch stops at
        // four times the floor, 1/62.5 s, and the ISO lands a shade over.
        val still = StabilityGate.sweepExposure(800, 66_666_666L, 105, 50, 6400, steady = true)!!
        assertThat(still.exposureNs).isEqualTo((StabilityGate.SHUTTER_FLOOR_NS * StabilityGate.LOW_LIGHT_STRETCH).toLong())
        assertThat(still.exposureNs).isAtMost(StabilityGate.LOW_LIGHT_MAX_NS)
        assertThat(still.iso).isEqualTo(3333)
        assertThat(still.stretched).isTrue()
        // A little dark: stretched only as far as the light needs. 1/30 s at
        // ISO 400 is ISO 3333 at 1/250; ISO 3200 needs 1/240.
        val slightly = StabilityGate.sweepExposure(400, 33_333_333L, 105, 50, 6400, steady = true)!!
        assertThat(slightly.exposureNs).isWithin(50_000L).of(4_166_666L)
        assertThat(slightly.iso).isEqualTo(3200)
        assertThat(slightly.stretched).isTrue()
        // Never longer than what auto exposure chose: 1/100 s at ISO 6400 is
        // ISO 16,000 at the floor, but the stretch stops at 1/100.
        val chosen = StabilityGate.sweepExposure(6400, 10_000_000L, 105, 50, 6400, steady = true)!!
        assertThat(chosen.exposureNs).isEqualTo(10_000_000L)
        assertThat(chosen.iso).isEqualTo(6400)
        // Light enough for the floor: still a plain floor, whatever the hand.
        val bright = StabilityGate.sweepExposure(100, 33_333_333L, 105, 50, 6400, steady = true)!!
        assertThat(bright.stretched).isFalse()
        assertThat(bright.exposureNs).isEqualTo(StabilityGate.SHUTTER_FLOOR_NS)
        // And the hand a stretch asks for is four degrees a second.
        assertThat(Math.toDegrees(StabilityGate.LOW_LIGHT_ANGULAR_SPEED.toDouble())).isWithin(0.05).of(4.0)
    }

    @Test
    fun `speed is the magnitude of the rotation rate`() {
        assertThat(StabilityGate.speedOf(0.3f, 0.4f, 0f)).isWithin(1e-6f).of(0.5f)
    }
}
