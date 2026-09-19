package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DngDevelopBenchTest {
    @Test fun inspectionIsACropOfTheFullyCorrectedImage() {
        val w = 96; val h = 80
        val pixels = IntArray(w * h) { i -> -0x1000000 or ((i * 137) and 0xffffff) }
        val model = LensDistortion.Model(fx = 85f, fy = 85f, cx = 48f, cy = 40f, k1 = .08f, skew = 0f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f)
        val optics = Optics.Params(distortion = model, correctAberration = false)
        val full = DngDevelopBench.finish(pixels.copyOf(), w, h, optics)
        val crop = DngDevelopBench.finish(pixels.copyOf(), w, h, optics, listOf(5, 7, 20, 16))
        for (y in 0 until 16) for (x in 0 until 20)
            assertThat(crop.pixels[y * 20 + x]).isEqualTo(full.pixels[(y + 7) * full.width + x + 5])
        assertThat(full.width).isLessThan(w)
    }
}
