package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The long exposure is the merge with rejection off: what moved is kept,
 * averaged over the span, rather than dropped as a ghost.
 */
@RunWith(JUnit4::class)
class LongExposureTest {

    private val w = 192
    private val h = 144

    @Test fun movingContentIsAveragedNotRejected() {
        // A bright bar that sits in a different column in each of eight
        // frames: the long exposure spreads it across all eight at an eighth
        // of the light, which is the picture a long exposure is.
        val dark = 200
        val bright = 1800
        fun frame(col: Int) = BayerFrame(w, h, ShortArray(w * h) { i ->
            val x = i % w
            if (x in col until col + 4) bright.toShort() else dark.toShort()
        })
        val columns = (0 until 8).map { 40 + it * 8 }
        val field = AlignmentField(3, 2, IntArray(6), IntArray(6))

        val long = BayerAccumulator(w, h, TestBayer.PROFILE,
            BayerMergeParams(noiseTolerance = LongExposureCapture.NO_REJECTION_TOLERANCE))
        long.setReference(frame(columns.first()))
        columns.drop(1).forEach { long.add(frame(it), field) }
        val (smeared, stats) = long.finish()

        val y = h / 2
        val expected = dark + (bright - dark) / 8f
        for (col in columns) {
            assertThat(smeared.at(col + 1, y).toFloat()).isWithin(2f).of(expected)
        }
        assertThat(stats.framesMerged).isEqualTo(8)
    }

    @Test fun durationsCycleOffTwoFourEightAndBack() {
        assertThat(LongExposureCapture.next(0)).isEqualTo(2)
        assertThat(LongExposureCapture.next(2)).isEqualTo(4)
        assertThat(LongExposureCapture.next(4)).isEqualTo(8)
        assertThat(LongExposureCapture.next(8)).isEqualTo(0)
        assertThat(LongExposureCapture.next(5)).isEqualTo(0)
    }

    @Test fun theSpanRoundTripsAndAnUnknownOneIsOff() {
        val original = AppSettings(longExposureSeconds = 4)
        assertThat(AppSettings.decode(original.encode()).longExposureSeconds).isEqualTo(4)
        assertThat(AppSettings.decode(mapOf(AppSettings.KEY_LONG_EXPOSURE to "7")).longExposureSeconds).isEqualTo(0)
    }
}
