package dev.multiframe.camera.pipeline
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class SuperResPolicyTest {
    @Test fun zoomRequestsCanvasInBothModes() {
        for (mode in CaptureMode.entries) {
            assertThat(SuperResPolicy.wantsCanvas(mode, 1f, true)).isFalse()
            for (zoom in listOf(1.5f, 2f, 10f)) {
                assertThat(SuperResPolicy.wantsCanvas(mode, zoom, true)).isTrue()
                assertThat(SuperResPolicy.wantsCanvas(mode, zoom, false)).isFalse()
            }
        }
        assertThat(MosaicPreset.ONE_SHOT_FRAMES).isEqualTo(16)
    }
}
