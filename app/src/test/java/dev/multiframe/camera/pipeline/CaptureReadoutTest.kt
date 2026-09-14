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
    fun dcgIsShownWhenActive() {
        assertThat(CaptureReadout.of(8, 0.85f, dcgActive = true))
            .isEqualTo("8 frames · 85% kept · DCG")
    }

    @Test
    fun dcgIsAbsentWhenOff() {
        assertThat(CaptureReadout.of(8, 0.85f, dcgActive = false))
            .isEqualTo("8 frames · 85% kept")
        assertThat(CaptureReadout.of(8, 0.85f))
            .isEqualTo("8 frames · 85% kept")
    }

    @Test
    fun theDetailedFormSpellsOutWhatActuallyWentIn() {
        // Twelve frames that kept half of each is not a burst of twelve.
        assertThat(CaptureReadout.detailed(12, 0.5f))
            .isEqualTo("12 frames · 50% kept · 6.5 effective")
    }

    // --- the 2x path ------------------------------------------------------

    private fun outcome(produced: Boolean, reason: String? = null) = SuperResOutcome(
        produced = produced,
        predictedHoles = floatArrayOf(0.1f, 0.01f, 0.1f),
        measuredHoles = null,
        reason = reason,
    )

    @Test
    fun aTwoXOutputSaysSo() {
        // A different claim about the photograph from a merge, and it is said.
        assertThat(CaptureReadout.of(8, 0.9f, superRes = outcome(produced = true)))
            .isEqualTo("8 frames · 90% kept · 2X")
    }

    @Test
    fun aDeclinedTwoXSaysThatToo() {
        // The user asked for one thing and got another; the readout does not
        // pretend otherwise, and the reason is available for the line below.
        val declined = outcome(produced = false, reason = "burst lacks sub-pixel diversity")
        assertThat(CaptureReadout.of(8, 0.9f, superRes = declined))
            .isEqualTo("8 frames · 90% kept · 2X declined")
        assertThat(CaptureReadout.superResDecline(declined))
            .isEqualTo("burst lacks sub-pixel diversity")
        assertThat(CaptureReadout.superResDecline(outcome(produced = true))).isNull()
        assertThat(CaptureReadout.superResDecline(null)).isNull()
    }

    @Test
    fun notAskingForTwoXSaysNothingAboutIt() {
        assertThat(CaptureReadout.of(8, 0.9f)).doesNotContain("2X")
        assertThat(CaptureReadout.of(8, 0.9f, dcgActive = true, superRes = outcome(true)))
            .isEqualTo("8 frames · 90% kept · DCG · 2X")
    }
}
