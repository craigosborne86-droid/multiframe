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

    @Test fun highResAccumulatesTheFullFrameInPhotoOnly() {
        assertThat(SuperResPolicy.wantsCanvas(CaptureMode.PHOTO, 1f, capable = true, opticalZoom = 1f, highRes = true)).isTrue()
        assertThat(SuperResPolicy.wantsCanvas(CaptureMode.NIGHT, 1f, capable = true, opticalZoom = 1f, highRes = true)).isFalse()
        assertThat(SuperResPolicy.wantsCanvas(CaptureMode.PHOTO, 1f, capable = true, opticalZoom = 1f, highRes = false)).isFalse()
        assertThat(SuperResPolicy.wantsCanvas(CaptureMode.PHOTO, 1f, capable = false, opticalZoom = 1f, highRes = true)).isFalse()
        assertThat(AppSettings.decode(AppSettings(highRes = true).encode()).highRes).isTrue()
        assertThat(AppSettings().highRes).isFalse()
    }
}
