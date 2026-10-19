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

    @Test fun clipEvidenceSurvivesDefectCorrection() {
        val raw = ShortArray(1024) { sensor.blackAt(0, 0).toShort() }
        for (y in 16..17) for (x in 16..17) raw[y * 32 + x] = sensor.whiteLevel.toShort()
        val out = RawDeveloper.develop(BayerFrame(32, 32, raw), sensor, colour,
            DevelopParams(exposureGain = 1f, chroma = ChromaSmooth.Params(amount = 0f), sharpen = Sharpen.Params(amount = 0f)))
        assertThat(out[16 * 32 + 16]).isEqualTo(-1)
    }

    @Test fun greenClippedNearWhiteDoesNotTurnPink() {
        val levels = floatArrayOf(.75f, 1f, .66f)
        val raw = ShortArray(32 * 32) { i ->
            val x = i % 32; val y = i / 32
            val black = sensor.blackAt(x, y)
            val c = sensor.cfaPattern[(y and 1) * 2 + (x and 1)]
            (black + levels[c] * (sensor.whiteLevel - black)).toInt().toShort()
        }
        val p = DevelopParams(exposureGain = 1f, localTone = .15f, shadowLift = 1f)
        assertThat(RawDeveloper.develop(BayerFrame(32, 32, raw), sensor, colour, p)[16 * 32 + 16]).isEqualTo(-1)
        val black = LinearDeveloper.channelBlack(sensor)
        val canvas = ShortArray(32 * 32 * 3) { i ->
            val c = i % 3
            (black[c] + levels[c] * (sensor.whiteLevel - black[c])).toInt().toShort()
        }
        assertThat(LinearDeveloper.develop(canvas, 32, 32, sensor, colour, p)[16 * 32 + 16]).isEqualTo(-1)
    }

    @Test fun whiteBalanceCannotDeclareAnUnclippedSensorClipped() {
        assertThat(SensorSaturation.evidenceWeight(.4f, .69f, 1.5f)).isEqualTo(0f)
        assertThat(SensorSaturation.evidenceWeight(.1f, 1f, .2f)).isEqualTo(0f)
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
