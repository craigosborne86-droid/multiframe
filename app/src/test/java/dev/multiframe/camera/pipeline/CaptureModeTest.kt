package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureModeTest {
    private val bright = SceneAnalysis(0f, 0.5f, 0.1f, 3f)
    private val dark = SceneAnalysis(0f, 0.01f, 0.01f, 3f)

    @Test fun onlyPhotoAndNightAreModes() {
        assertThat(CaptureMode.entries.map { it.label }).containsExactly("PHOTO", "NIGHT").inOrder()
        assertThat(CaptureMode.PHOTO.next()).isEqualTo(CaptureMode.NIGHT)
        assertThat(CaptureMode.NIGHT.next()).isEqualTo(CaptureMode.PHOTO)
    }
    @Test fun photoRetainsHandheldExposureAndFrameBudget() {
        val plan = CaptureModes.plan(CaptureMode.PHOTO, bright, 28, 16)
        assertThat(plan.frames).isEqualTo(CaptureModes.AUTO_MAX_FRAMES)
        assertThat(plan.maxExposureNs).isEqualTo(CaptureModes.AUTO_EXPOSURE_NS)
        assertThat(plan.protectHighlights).isTrue()
    }
    @Test fun nightUsesAvailableFramesAndKeepsItsExposurePolicy() {
        val plan = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8)
        assertThat(plan.frames).isEqualTo(28)
        assertThat(plan.maxExposureNs).isEqualTo(CaptureModes.NIGHT_EXPOSURE_NS)
        assertThat(plan.protectHighlights).isFalse()
    }
    @Test fun nightChosenByNameTakesThePlanFromTheHand() {
        val night = NightPlanner.solve(MotionClass.BRACED, 0.0008f, 3000f, 3200, 33_333_333L, 50, 6400, 10_000L, 1_000_000_000L, 28)
        val plan = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8, night)
        assertThat(plan.night).isEqualTo(night)
        assertThat(plan.frames).isEqualTo(night.frames)
        // The repeating request is not where the long exposure is made.
        assertThat(plan.maxExposureNs).isEqualTo(CaptureModes.NIGHT_EXPOSURE_NS)
        assertThat(plan.protectHighlights).isFalse()
        // AUTO resolving to night keeps the handheld ten and the sixtieth.
        val auto = CaptureModes.plan(CaptureMode.PHOTO, dark, 28, 8, night)
        assertThat(auto.night).isNull()
        assertThat(auto.maxExposureNs).isEqualTo(CaptureModes.AUTO_EXPOSURE_NS)
    }
    @Test fun nightWithoutAGyroscopeIsTheFixedPlan() {
        assertThat(CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8, null).night).isNull()
    }
    @Test fun frameRequestCannotExceedRing() {
        for (mode in CaptureMode.entries) {
            assertThat(CaptureModes.plan(mode, bright, 3, 16).frames).isAtMost(3)
        }
    }
}
