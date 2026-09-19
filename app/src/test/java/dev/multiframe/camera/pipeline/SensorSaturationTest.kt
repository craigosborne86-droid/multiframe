package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensorSaturationTest {
    private val sensor = SensorProfile.DEFAULT
    private val colour = ColorProfile(floatArrayOf(2.2f, 1f, 1f, 1.7f),
        floatArrayOf(1.6f, -.5f, -.1f, -.2f, 1.5f, -.3f, .1f, -.5f, 1.4f))

    @Test fun clippedNeutralIsWhiteUnderBalanceMatrixAndLocalTone() {
        val frame = BayerFrame(32, 32, ShortArray(1024) { sensor.whiteLevel.toShort() })
        for (gain in listOf(.5f, 1f, 4f)) {
            val params = DevelopParams(exposureGain = gain, localTone = .15f, shadowLift = 1f)
            val out = RawDeveloper.develop(frame, sensor, colour, params)
            assertThat(out[16 * 32 + 16]).isEqualTo(-1)
            val canvas = ShortArray(32 * 32 * 3) { sensor.whiteLevel.toShort() }
            assertThat(LinearDeveloper.develop(canvas, 32, 32, sensor, colour, params)[16 * 32 + 16]).isEqualTo(-1)
        }
    }

    @Test fun singleChannelClipDoesNotNeutraliseFlowers() {
        val raw = ShortArray(32 * 32) { i ->
            if (sensor.cfaPattern[(i / 32 and 1) * 2 + (i and 1)] == 0) 1023 else 200
        }
        val frame = BayerFrame(32, 32, raw)
        assertThat(SensorSaturation.cfaWeight(frame, sensor, 12, 12)).isEqualTo(0f)
        assertThat(RawDeveloper.develop(frame, sensor, colour)).isEqualTo(
            RawDeveloper.develop(frame, sensor, colour, recoverSensorSaturation = false))
    }

    @Test fun fadeIsContinuousMonotonicAndOrdinaryColoursAreUnchanged() {
        var previous = 0f
        for (i in 0..1000) {
            val w = SensorSaturation.weight(i / 1000f)
            assertThat(w).isAtLeast(previous)
            assertThat(w - previous).isLessThan(.006f)
            if (i <= 700) assertThat(w).isEqualTo(0f)
            previous = w
        }
        assertThat(previous).isEqualTo(1f)
    }
}
