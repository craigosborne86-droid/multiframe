package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The readout that makes the merge visible.
 *
 * Mostly this pins that it says only what was measured. The temptation here is
 * a noise-reduction figure, which the merge does not record enough to support:
 * the improvement of a weighted mean is scale-invariant, so the mean weight —
 * the only thing kept — cannot produce it.
 */
class CaptureReadoutTest {

    @Test
    fun aSingleFrameIsNotDressedUpAsAMerge() {
        assertThat(CaptureReadout.of(1, 1f)).isEqualTo("1 frame")
        assertThat(CaptureReadout.of(0, 1f)).isEqualTo("1 frame")
        assertThat(CaptureReadout.effectiveFrames(1, 1f)).isEqualTo(1f)
    }

    @Test
    fun aRealCaptureReadsAsItWasMeasured() {
        // The figures from an actual capture on the phone.
        assertThat(CaptureReadout.of(12, 0.8926f)).isEqualTo("12 frames · 89% kept")
    }

    @Test
    fun theReferenceFrameIsNeverDiscounted() {
        // Even if every other frame is rejected outright, one frame is in there.
        assertThat(CaptureReadout.effectiveFrames(8, 0f)).isEqualTo(1f)
        assertThat(CaptureReadout.of(8, 0f)).isEqualTo("8 frames · 0% kept")
    }

    @Test
    fun everythingKeptIsTheWholeBurst() {
        assertThat(CaptureReadout.effectiveFrames(8, 1f)).isEqualTo(8f)
        assertThat(CaptureReadout.of(8, 1f)).isEqualTo("8 frames · 100% kept")
    }

    @Test
    fun theEffectiveCountSitsBetweenOneAndTheBurst() {
        for (frames in intArrayOf(2, 4, 8, 12, 32)) {
            for (w in floatArrayOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
                val effective = CaptureReadout.effectiveFrames(frames, w)
                assertThat(effective).isAtLeast(1f)
                assertThat(effective).isAtMost(frames.toFloat())
            }
        }
    }

    /** A weight outside 0..1 is a bug upstream, not a reason to print nonsense. */
    @Test
    fun anImpossibleWeightIsClamped() {
        assertThat(CaptureReadout.of(8, 1.4f)).isEqualTo("8 frames · 100% kept")
        assertThat(CaptureReadout.of(8, -0.2f)).isEqualTo("8 frames · 0% kept")
        assertThat(CaptureReadout.effectiveFrames(8, 2f)).isEqualTo(8f)
    }

    @Test
    fun theDetailedFormSpellsOutWhatActuallyWentIn() {
        // Twelve frames that kept half of each is not a burst of twelve.
        assertThat(CaptureReadout.detailed(12, 0.5f))
            .isEqualTo("12 frames · 50% kept · 6.5 effective")
    }
}
