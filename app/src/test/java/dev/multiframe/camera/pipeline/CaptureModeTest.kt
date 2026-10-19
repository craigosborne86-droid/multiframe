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
    @Test fun frameRequestCannotExceedRing() {
        for (mode in CaptureMode.entries) {
            assertThat(CaptureModes.plan(mode, bright, 3, 16).frames).isAtMost(3)
        }
    }
}
