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

    /**
     * The patchwork test.
     *
     * Two neighbouring tiles matching at different offsets is ordinary -- it is
     * what a tiled aligner is *for* -- and until the tiles overlapped, the
     * merge changed from one displacement to the other between one sensor
     * column and the next. On a real photograph that draws a grid, every 64
     * pixels, over the whole frame.
     *
     * The scene is a linear ramp, so a displacement applied to it shows up as a
     * constant bias and nothing else: twelve codes where a tile is displaced by
     * six sensor pixels, none where it is not. The question the test asks is
     * only where those twelve codes are picked up. Across a hard tile boundary
     * they arrive all at once; across a blend they arrive over a tile's width,
     * which cannot exceed one code a sample.
     *
     * Run against the merge as it was before the tiles overlapped, both halves
     * report a worst step of 12.
     */
    @Test
    fun `neighbouring tiles blend rather than step`() {
        assertNoSeam(across = true)
        assertNoSeam(across = false)
    }

    private fun assertNoSeam(across: Boolean) {
        val base = 100
        val slope = 4
        fun truth(x: Int, y: Int) = base + slope * (if (across) x else y)

        val ramp = ShortArray(w * h) { i -> truth(i % w, i / w).toShort() }
        val frame = BayerFrame(w, h, ramp)

        // A noise floor far above anything this ramp can produce, so every
        // sample keeps full weight and the merged value is the plain mean of
        // the two. What is under test is where a displacement is applied, not
        // how much of it survives rejection.
        val acc = BayerAccumulator(
            w, h, TestBayer.PROFILE,
            BayerMergeParams(noiseTolerance = 3f, minNoiseSigma = 40f),
        )
        acc.setReference(frame)

        val tilesX = (w / 2) / 32
        val tilesY = (h / 2) / 32
        val dx = IntArray(tilesX * tilesY)
        val dy = IntArray(tilesX * tilesY)
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val i = ty * tilesX + tx
                val odd = if (across) tx % 2 == 1 else ty % 2 == 1
                // Three proxy pixels, so six sensor pixels, so twelve codes of
                // bias on this ramp once the two frames are averaged.
                if (across) dx[i] = if (odd) 3 else 0 else dy[i] = if (odd) 3 else 0
            }
        }
        // The alternate frame is the reference. Any difference between them is
        // then the displacement and nothing else.
        acc.add(frame, AlignmentField(tilesX, tilesY, dx, dy))
        val (merged, _) = acc.finish()

        // Six sensor pixels of inset, because that is how far a displaced tile
        // reaches past the edge, and a sample with no source is a different
        // effect from the one being measured.
        val inset = 8
        var worstStep = 0
        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        for (y in inset until h - inset) {
            for (x in inset until w - inset) {
                val here = merged.at(x, y) - truth(x, y)
                // The next sample of the same colour along the axis the
                // displacement varies on.
                val next = if (across) merged.at(x + 2, y) - truth(x + 2, y)
                else merged.at(x, y + 2) - truth(x, y + 2)
                lowest = minOf(lowest, here)
                highest = maxOf(highest, here)
                worstStep = maxOf(worstStep, abs(next - here))
            }
        }

        println(
            "seams ${if (across) "across" else "down"}: bias $lowest..$highest codes, " +
                "worst step $worstStep",
        )
        // The field has to have changed something, or a merge that ignored it
        // would pass this by doing nothing at all.
        assertThat(highest - lowest).isAtLeast(8)
        // Twelve codes, taken up over a tile instead of dropped at its edge.
        assertThat(worstStep).isAtMost(2)
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
