package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MemoryBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun `a 420 frame is twelve bits per pixel`() {
        assertThat(MemoryBudget.bytesPerFrame(2048, 1536)).isEqualTo(2048L * 1536L * 3 / 2)
    }

    @Test
    fun `a large heap affords the full requested burst`() {
        val n = MemoryBudget.recommendedCapacity(2048, 1536, 512 * mb, desired = 12)
        assertThat(n).isEqualTo(12)
    }

    @Test
    fun `a small heap reduces the burst rather than overcommitting`() {
        val n = MemoryBudget.recommendedCapacity(2048, 1536, 96 * mb, desired = 12)
        println("96MB heap at 2048x1536 -> $n frames")
        assertThat(n).isLessThan(12)
        assertThat(n).isAtLeast(2)
    }

    @Test
    fun `a heap too small even for the merge still returns the minimum`() {
        val n = MemoryBudget.recommendedCapacity(4080, 3072, 32 * mb, desired = 12)
        assertThat(n).isEqualTo(2)
    }

    @Test
    fun `capacity never exceeds what was asked for`() {
        val n = MemoryBudget.recommendedCapacity(640, 480, 1024 * mb, desired = 4)
        assertThat(n).isEqualTo(4)
    }

    @Test
    fun `degenerate dimensions fall back to the minimum`() {
        assertThat(MemoryBudget.recommendedCapacity(0, 0, 512 * mb, desired = 8)).isEqualTo(2)
    }

    @Test
    fun `higher resolution affords fewer frames on the same heap`() {
        val small = MemoryBudget.recommendedCapacity(1280, 960, 192 * mb, desired = 12)
        val large = MemoryBudget.recommendedCapacity(4080, 3072, 192 * mb, desired = 12)
        println("192MB heap: 1280x960 -> $small frames, 4080x3072 -> $large frames")
        assertThat(large).isAtMost(small)
    }

    @Test
    fun `analysis resolution scales down on a small heap`() {
        val big = MemoryBudget.recommendedAnalysisSize(768 * mb)
        val mid = MemoryBudget.recommendedAnalysisSize(256 * mb)
        val small = MemoryBudget.recommendedAnalysisSize(96 * mb)
        val tiny = MemoryBudget.recommendedAnalysisSize(24 * mb)
        println("heap -> analysis size: 768MB=$big  256MB=$mid  96MB=$small  24MB=$tiny")

        assertThat(big.width).isAtLeast(mid.width)
        assertThat(mid.width).isAtLeast(small.width)
        assertThat(small.width).isAtLeast(tiny.width)
    }

    @Test
    fun `the chosen resolution can actually afford a real burst`() {
        for (heap in listOf(96L, 192L, 384L, 768L)) {
            val size = MemoryBudget.recommendedAnalysisSize(heap * mb)
            val frames = MemoryBudget.recommendedCapacity(
                size.width, size.height, heap * mb, desired = 8,
            )
            println("heap ${heap}MB -> ${size.width}x${size.height}, $frames frames")
            // The whole point: whatever we pick must support a genuine merge.
            assertThat(frames).isAtLeast(4)
        }
    }

    @Test
    fun `a tiny heap still returns the smallest ladder entry rather than failing`() {
        val size = MemoryBudget.recommendedAnalysisSize(8 * mb)
        assertThat(size).isEqualTo(MemoryBudget.RESOLUTION_LADDER.last())
    }

    @Test
    fun `resolution is capped for latency even when memory allows more`() {
        val huge = MemoryBudget.recommendedAnalysisSize(2048 * mb)
        assertThat(huge).isEqualTo(MemoryBudget.LATENCY_CAP)

        // Raising the cap lets a large heap go higher.
        val uncapped = MemoryBudget.recommendedAnalysisSize(
            2048 * mb, cap = FrameSize(4080, 3072),
        )
        assertThat(uncapped.width).isGreaterThan(MemoryBudget.LATENCY_CAP.width)
    }
}
