package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(JUnit4::class)
class BayerMergerTest {

    private val w = 192
    private val h = 144

    private fun accumulate(frames: List<BayerFrame>): Pair<BayerFrame, BayerMergeStats> {
        val acc = BayerAccumulator(w, h, TestBayer.PROFILE)
        acc.setReference(frames.first())
        frames.drop(1).forEach { acc.add(it) }
        return acc.finish()
    }

    private fun rms(a: BayerFrame, truth: BayerFrame, inset: Int = 12): Double {
        var s = 0.0; var n = 0
        for (y in inset until h - inset) for (x in inset until w - inset) {
            val d = (a.at(x, y) - truth.at(x, y)).toDouble()
            s += d * d; n++
        }
        return sqrt(s / n)
    }

    // The failure mode unique to raw merging: an odd alignment offset lands red
    // samples on green ones. Colour separation is the canary.
    @Test
    fun `colour planes stay separated through a shifted merge`() {
        val clean = TestBayer.scene(w, h)
        val before = TestBayer.meansByColour(clean)

        val frames = listOf(0 to 0, 4 to 2, -2 to 4, 6 to -4).mapIndexed { i, (sx, sy) ->
            TestBayer.addNoise(TestBayer.shift(clean, sx, sy), 8f, seed = 40 + i)
        }
        val (merged, _) = accumulate(frames)
        val after = TestBayer.meansByColour(merged)

        println("colour means before=$before after=$after")

        // Each colour must stay near its own level. Mixing would pull them
        // together toward the overall mean.
        for (colour in 0..2) {
            assertThat(abs(after[colour]!! - before[colour]!!)).isLessThan(30.0)
        }
        // And they must remain clearly distinct from one another.
        assertThat(after[1]!! - after[0]!!).isGreaterThan(150.0)   // green above red
        assertThat(after[0]!! - after[2]!!).isGreaterThan(60.0)    // red above blue
    }

    @Test
    fun `merging static raw frames reduces noise`() {
        val clean = TestBayer.scene(w, h)
        val frames = (0 until 8).map { TestBayer.addNoise(clean, 10f, seed = 70 + it) }

        val single = frames.first()
        val (merged, stats) = accumulate(frames)

        val singleRms = rms(single, clean)
        val mergedRms = rms(merged, clean)
        println("raw merge: single=$singleRms merged=$mergedRms ratio=${mergedRms / singleRms} stats=$stats")

        assertThat(stats.framesMerged).isEqualTo(8)
        assertThat(mergedRms).isLessThan(singleRms * 0.55)
    }

    @Test
    fun `noise reduction improves with more raw frames`() {
        val clean = TestBayer.scene(w, h)
        val results = listOf(2, 4, 8).map { n ->
            val frames = (0 until n).map { TestBayer.addNoise(clean, 10f, seed = 90 + it) }
            rms(accumulate(frames).first, clean)
        }
        println("raw RMS by frame count 2/4/8 = $results")
        assertThat(results[1]).isLessThan(results[0])
        assertThat(results[2]).isLessThan(results[1])
    }

    @Test
    fun `a shifted burst still merges cleanly`() {
        val clean = TestBayer.scene(w, h)
        val frames = listOf(0 to 0, 2 to 2, -4 to 2, 2 to -2, 4 to 4, -2 to -4)
            .mapIndexed { i, (sx, sy) ->
                TestBayer.addNoise(TestBayer.shift(clean, sx, sy), 10f, seed = 120 + i)
            }
        val single = frames.first()
        val (merged, stats) = accumulate(frames)

        val singleRms = rms(single, clean, inset = 20)
        val mergedRms = rms(merged, clean, inset = 20)
        println("shifted raw burst: single=$singleRms merged=$mergedRms ratio=${mergedRms / singleRms} contrib=${stats.meanContribution}")

        assertThat(mergedRms).isLessThan(singleRms * 0.75)
    }

    @Test
    fun `a moving object does not ghost into the raw merge`() {
        val clean = TestBayer.scene(w, h)
        val frames = (0 until 5).map { i ->
            val f = TestBayer.addNoise(clean, 6f, seed = 150 + i)
            if (i > 0) {
                val ox = 40 + i * 12
                val d = f.data.copyOf()
                for (y in 50 until 80) for (x in ox until ox + 24) d[y * w + x] = 1000.toShort()
                BayerFrame(w, h, d)
            } else f
        }
        val (merged, _) = accumulate(frames)
        val reference = frames.first()

        var worst = 0
        for (y in 50 until 80) for (x in 40 until 40 + 4 * 12 + 24) {
            worst = maxOf(worst, abs(merged.at(x, y) - reference.at(x, y)))
        }
        println("raw worst ghost deviation = $worst codes")
        // Plain averaging of 4 intruders at 1000 would move a pixel by hundreds.
        assertThat(worst).isLessThan(120)
    }

    @Test
    fun `merged output stays within the sensor range`() {
        val clean = TestBayer.scene(w, h)
        val frames = (0 until 4).map { TestBayer.addNoise(clean, 12f, seed = 180 + it) }
        val (merged, _) = accumulate(frames)
        for (v in merged.data) {
            val i = v.toInt() and 0xFFFF
            assertThat(i).isAtMost(TestBayer.PROFILE.whiteLevel)
            assertThat(i).isAtLeast(0)
        }
    }

    @Test
    fun `merging is linear so scene brightness is preserved`() {
        val clean = TestBayer.scene(w, h)
        val frames = (0 until 6).map { TestBayer.addNoise(clean, 10f, seed = 200 + it) }
        val (merged, _) = accumulate(frames)

        // Raw is linear, so the merged mean must match the clean mean. A gamma
        // or transfer-function error would bias this.
        var cleanSum = 0.0; var mergedSum = 0.0; var n = 0
        for (y in 12 until h - 12) for (x in 12 until w - 12) {
            cleanSum += clean.at(x, y); mergedSum += merged.at(x, y); n++
        }
        val cm = cleanSum / n; val mm = mergedSum / n
        println("linearity: clean mean=$cm merged mean=$mm")
        assertThat(abs(mm - cm)).isLessThan(3.0)
    }
}
