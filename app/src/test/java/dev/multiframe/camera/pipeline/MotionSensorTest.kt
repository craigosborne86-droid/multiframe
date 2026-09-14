package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arithmetic the settle check rests on. The sensor itself needs a phone;
 * the angle between two attitudes does not.
 */
class MotionSensorTest {

    @Test
    fun `the angle between two attitudes is the rotation that takes one to the other`() {
        val thirty = Math.toRadians(30.0).toFloat()
        assertThat(MotionSensor.angleBetween(ImuGuidance.IDENTITY, ImuGuidance.yaw(thirty))).isWithin(1e-4f).of(thirty)
        assertThat(MotionSensor.angleBetween(ImuGuidance.pitch(thirty), ImuGuidance.pitch(thirty))).isWithin(1e-4f).of(0f)
        // Composed rotations: the angle is of the relative rotation, not the sum of the parts.
        val a = ImuGuidance.times(ImuGuidance.yaw(thirty), ImuGuidance.roll(thirty))
        val b = ImuGuidance.times(ImuGuidance.yaw(thirty), ImuGuidance.roll(thirty * 2))
        assertThat(MotionSensor.angleBetween(a, b)).isWithin(1e-4f).of(thirty)
        // And it is symmetric.
        assertThat(MotionSensor.angleBetween(b, a)).isWithin(1e-4f).of(thirty)
    }

    @Test
    fun `the settle slack is half a degree and the window is under half a second`() {
        assertThat(Math.toDegrees(MotionSensor.SETTLE_SLACK_RAD.toDouble())).isWithin(0.01).of(0.5)
        assertThat(MotionSensor.SETTLE_WINDOW_NS).isLessThan(500_000_000L)
    }
}
