package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuperResPolicyTest {
    @Test fun primaryModeAndZoomRequestTheCanvasOnAnyCapableLens() {
        assertThat(SuperResPolicy.wantsCanvas(CaptureMode.SUPER_RES, 1f, true)).isTrue()
        for (mode in listOf(CaptureMode.AUTO, CaptureMode.NIGHT, CaptureMode.ACTION)) {
            assertThat(SuperResPolicy.wantsCanvas(mode, 1.99f, true)).isFalse()
            assertThat(SuperResPolicy.wantsCanvas(mode, 2f, true)).isTrue()
            assertThat(SuperResPolicy.wantsCanvas(mode, 5f, false)).isFalse()
        }
        assertThat(SuperResPolicy.wantsCanvas(CaptureMode.SUPER_RES, 1f, false)).isFalse()
        assertThat(MosaicPreset.ONE_SHOT_FRAMES).isEqualTo(16)
    }
}
