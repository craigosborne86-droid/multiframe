package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.sqrt

@RunWith(JUnit4::class)
class MergerTest {

    private val width = 256
    private val height = 192

    /** RMS error of a merged result against the clean reference, in linear light. */
    private fun rmsAgainst(clean: ByteArray, actual: FloatArray): Float {
        var acc = 0.0
        for (i in clean.indices) {
            val want = Merger.linearOf(clean[i].toInt() and 0xFF)
            val diff = (actual[i] - want).toDouble()
            acc += diff * diff
        }
        return sqrt(acc / clean.size).toFloat()
    }

    private fun noisyBurst(clean: ByteArray, count: Int, sigma: Float): List<YuvFrame> =
        (0 until count).map { i ->
            TestImages.frame(TestImages.addNoise(clean, sigma, seed = 100 + i), width, height)
        }

    @Test
    fun `merging static frames reduces noise close to the square root of frame count`() {
        val clean = TestImages.texture(width, height)
        val frames = noisyBurst(clean, count = 8, sigma = 6f)

        // A large robustness sigma effectively disables rejection, so this
        // measures the averaging itself.
        val ideal = MergeParams(exposureGain = 1f, robustnessSigma = 100f)

        val single = Merger.mergePlanes(frames, mergeEnabled = false, params = ideal)
        val merged = Merger.mergePlanes(frames, mergeEnabled = true, params = ideal)

        val singleRms = rmsAgainst(clean, single.luma)
        val mergedRms = rmsAgainst(clean, merged.luma)

        println("single RMS=$singleRms  merged RMS=$mergedRms  ratio=${mergedRms / singleRms}")

        assertThat(merged.stats.framesUsed).isEqualTo(8)
        // sqrt(8) = 2.83x improvement in theory; allow generous headroom.
        assertThat(mergedRms).isLessThan(singleRms * 0.5f)
    }

    @Test
    fun `merging still helps with robustness rejection enabled`() {
        val clean = TestImages.texture(width, height)
        val frames = noisyBurst(clean, count = 8, sigma = 6f)
        val params = MergeParams(exposureGain = 1f)

        val single = Merger.mergePlanes(frames, mergeEnabled = false, params = params)
        val merged = Merger.mergePlanes(frames, mergeEnabled = true, params = params)

        val singleRms = rmsAgainst(clean, single.luma)
        val mergedRms = rmsAgainst(clean, merged.luma)
        println("robust: single=$singleRms merged=$mergedRms ratio=${mergedRms / singleRms}")

        assertThat(mergedRms).isLessThan(singleRms * 0.85f)
    }

    @Test
    fun `noise reduction improves as frames are added`() {
        val clean = TestImages.texture(width, height)
        val params = MergeParams(exposureGain = 1f, robustnessSigma = 100f)

        val rms = listOf(2, 4, 8).map { n ->
            val merged = Merger.mergePlanes(noisyBurst(clean, n, 6f), true, params)
            rmsAgainst(clean, merged.luma)
        }
        println("RMS by frame count 2/4/8 = $rms")

        assertThat(rms[1]).isLessThan(rms[0])
        assertThat(rms[2]).isLessThan(rms[1])
    }

    @Test
    fun `merge disabled returns the reference frame untouched`() {
        val clean = TestImages.texture(width, height)
        val frames = noisyBurst(clean, count = 4, sigma = 6f)

        val result = Merger.mergePlanes(frames, mergeEnabled = false, params = MergeParams())

        assertThat(result.stats.framesUsed).isEqualTo(1)
        val reference = frames.first()
        for (i in 0 until 500) {
            val expected = Merger.linearOf(reference.y[i].toInt() and 0xFF)
            assertThat(result.luma[i]).isWithin(1e-6f).of(expected)
        }
    }

    @Test
    fun `misaligned frames are realigned before merging`() {
        val clean = TestImages.texture(width, height)

        // Reference plus three handheld-style displacements.
        val shifts = listOf(0 to 0, 2 to 1, -3 to 2, 1 to -2)
        val frames = shifts.mapIndexed { i, (sx, sy) ->
            val shifted = TestImages.shift(clean, width, height, sx, sy)
            TestImages.frame(TestImages.addNoise(shifted, 6f, seed = 200 + i), width, height)
        }
        val params = MergeParams(exposureGain = 1f, robustnessSigma = 100f)

        val single = Merger.mergePlanes(frames, mergeEnabled = false, params = params)
        val merged = Merger.mergePlanes(frames, mergeEnabled = true, params = params)

        // Compare only the interior, where clamped edges do not distort the metric.
        fun interiorRms(actual: FloatArray): Float {
            var acc = 0.0
            var n = 0
            for (y in 16 until height - 16) for (x in 16 until width - 16) {
                val i = y * width + x
                val want = Merger.linearOf(clean[i].toInt() and 0xFF)
                val d = (actual[i] - want).toDouble()
                acc += d * d; n++
            }
            return sqrt(acc / n).toFloat()
        }

        val singleRms = interiorRms(single.luma)
        val mergedRms = interiorRms(merged.luma)
        println("shifted burst: single=$singleRms merged=$mergedRms ratio=${mergedRms / singleRms}")

        assertThat(mergedRms).isLessThan(singleRms * 0.75f)
    }
}
