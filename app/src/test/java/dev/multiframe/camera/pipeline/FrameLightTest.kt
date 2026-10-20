package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameLightTest {

    @Test
    fun `a frame at the reference's exposure merges at a gain of exactly one`() {
        // The DcgDeviceTest identity: gainScale = 1 is the pre-DCG merge, bit for bit.
        val light = 400.0 * 16_666_666.0
        assertThat(FrameLight.gainScale(light, light)).isEqualTo(1f)
    }

    @Test
    fun `an unknown light leaves the frame unscaled`() {
        assertThat(FrameLight.gainScale(0.0, 400.0 * 1e7)).isEqualTo(1f)
        assertThat(FrameLight.gainScale(400.0 * 1e7, 0.0)).isEqualTo(1f)
        assertThat(FrameLight.of(null)).isEqualTo(0.0)
    }

    @Test
    fun `a frame that gathered twice the light is scaled by two`() {
        // ISO 800 at 1/60 s against ISO 400 at 1/60 s, and the shutter cap's
        // trade of exposure for gain gathers the same light.
        assertThat(FrameLight.gainScale(400.0 * 16_666_666.0, 800.0 * 16_666_666.0)).isEqualTo(2f)
        assertThat(FrameLight.gainScale(400.0 * 16_666_666.0, 200.0 * 33_333_332.0)).isEqualTo(1f)
    }
}
