package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The model sees on the phone what it was trained on, and its mask lands back on the proxy. */
@RunWith(JUnit4::class)
class SkyModelContractTest {

    @Test fun theInputIsTheProxyAreaAveragedAndStretched() {
        // A 2040x1536 proxy, top half dark sky (8), bottom half ground (80).
        val w = 2040; val h = 1536
        val proxy = Plane(w, h, ByteArray(w * h) { if (it / w < h / 2) 8 else 80 })
        val input = SkyModelContract.preprocess(proxy)
        assertThat(input.size).isEqualTo(SkyModelContract.WIDTH * SkyModelContract.HEIGHT)
        assertThat(input.maxOrNull()).isWithin(1e-6f).of(1f)               // stretched to the 99th percentile
        assertThat(input[10]).isWithin(1e-3f).of(0.1f)                      // sky: 8 / 80
        assertThat(input[input.size - 10]).isWithin(1e-6f).of(1f)          // ground
    }

    @Test fun theMaskComesBackToTheProxysSize() {
        val small = FloatArray(SkyModelContract.WIDTH * SkyModelContract.HEIGHT) { if (it / SkyModelContract.WIDTH < SkyModelContract.HEIGHT / 2) 1f else 0f }
        val full = SkyModelContract.upscale(small, 2040, 1536)
        assertThat(full.size).isEqualTo(2040 * 1536)
        assertThat(full[100 * 2040 + 500]).isEqualTo(1f)
        assertThat(full[1400 * 2040 + 500]).isEqualTo(0f)
        // And the training label is the measured mask taken down the same way.
        assertThat(SkyModelContract.downscaleMask(full, 2040, 1536)[0]).isWithin(1e-6f).of(1f)
    }
}
