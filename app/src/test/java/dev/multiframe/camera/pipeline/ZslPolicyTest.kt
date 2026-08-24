package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ZSL decision, tested against capability profiles this development device
 * does not have.
 *
 * Streaming raw continuously is a bet on the hardware: a camera that reports a
 * stall duration will stutter its viewfinder if raw rides along on every frame.
 * These are the cases where the answer has to be "no" even though raw capture
 * itself works perfectly well.
 */
class ZslPolicyTest {

    private val pixel9ProXl = RawStreamConfig(
        width = 4080, height = 3072,
        minFrameDurationNs = 33_333_000L,
        stallDurationNs = 0L,
    )

    @Test
    fun `zero stall raw at 30fps engages the ring`() {
        val decision = ZslPolicy.evaluate(supportsRaw = true, configs = listOf(pixel9ProXl))

        assertThat(decision).isInstanceOf(ZslDecision.Stream::class.java)
        assertThat((decision as ZslDecision.Stream).config.width).isEqualTo(4080)
        assertThat(decision.engaged).isTrue()
    }

    @Test
    fun `a stalling sensor falls back to sequential capture`() {
        // Many devices can produce a raw frame but only by blocking the other
        // streams while they do it. Streaming there would stutter the preview.
        val stalling = pixel9ProXl.copy(stallDurationNs = 33_000_000L)

        val decision = ZslPolicy.evaluate(supportsRaw = true, configs = listOf(stalling))

        assertThat(decision).isInstanceOf(ZslDecision.Fallback::class.java)
        assertThat((decision as ZslDecision.Fallback).reason).contains("stall")
        assertThat(decision.engaged).isFalse()
    }

    @Test
    fun `no raw capability falls back`() {
        val decision = ZslPolicy.evaluate(supportsRaw = false, configs = listOf(pixel9ProXl))

        assertThat(decision).isInstanceOf(ZslDecision.Fallback::class.java)
        assertThat((decision as ZslDecision.Fallback).reason).contains("RAW")
    }

    @Test
    fun `no raw output sizes falls back`() {
        val decision = ZslPolicy.evaluate(supportsRaw = true, configs = emptyList())

        assertThat(decision).isInstanceOf(ZslDecision.Fallback::class.java)
    }

    @Test
    fun `raw that streams too slowly is not zero shutter lag`() {
        // 4 fps with no stall is still a raw stream, but a burst spanning two
        // seconds defeats the point of pre-capturing it.
        val slow = pixel9ProXl.copy(minFrameDurationNs = 250_000_000L)

        val decision = ZslPolicy.evaluate(supportsRaw = true, configs = listOf(slow))

        assertThat(decision).isInstanceOf(ZslDecision.Fallback::class.java)
        assertThat((decision as ZslDecision.Fallback).reason).contains("fps")
    }

    @Test
    fun `the largest zero stall configuration wins`() {
        val small = RawStreamConfig(2040, 1536, 33_333_000L, 0L)
        val large = RawStreamConfig(4080, 3072, 33_333_000L, 0L)

        val decision = ZslPolicy.evaluate(true, listOf(small, large))

        assertThat((decision as ZslDecision.Stream).config).isEqualTo(large)
    }

    @Test
    fun `a smaller size is chosen when only the full sensor stalls`() {
        // The realistic portability case: full-resolution raw stalls, but a
        // binned mode streams freely. Half resolution beats no ZSL at all.
        val fullStalls = RawStreamConfig(4080, 3072, 33_333_000L, 20_000_000L)
        val binnedStreams = RawStreamConfig(2040, 1536, 33_333_000L, 0L)

        val decision = ZslPolicy.evaluate(true, listOf(fullStalls, binnedStreams))

        assertThat((decision as ZslDecision.Stream).config).isEqualTo(binnedStreams)
    }

    @Test
    fun `a zero minimum frame duration is not treated as infinitely fast`() {
        // Some devices report 0 rather than an honest duration. Dividing by it
        // would claim infinite fps, so it must not qualify.
        val unreported = pixel9ProXl.copy(minFrameDurationNs = 0L)

        val decision = ZslPolicy.evaluate(true, listOf(unreported))

        assertThat(decision).isInstanceOf(ZslDecision.Fallback::class.java)
    }
}

/**
 * Ring sizing.
 *
 * The pool is mmap'd outside the Dalvik heap, so the 256 MB cap does not bound
 * it -- physical memory does. At 4080x3072 a slot is 25 MB, so a full 32-deep
 * ring reserves 800 MB and is only appropriate on a device with room for it.
 */
class RawRingBudgetTest {

    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `a flagship affords the full ring`() {
        val capacity = RawRingBudget.capacityFor(
            width = 4080, height = 3072,
            availableRamBytes = 8 * gb, totalRamBytes = 16 * gb,
            desired = 32,
        )

        assertThat(capacity).isEqualTo(32)
    }

    @Test
    fun `capacity never exceeds what was asked for`() {
        val capacity = RawRingBudget.capacityFor(
            4080, 3072, 8 * gb, 16 * gb, desired = 12,
        )

        assertThat(capacity).isEqualTo(12)
    }

    @Test
    fun `a mid range device gets a shorter ring rather than none`() {
        // 6 GB total, 1.5 GB free: min(1.5G*0.35, 6G*0.20) = 537 MB, less the
        // 125 MB the merge itself needs, is room for about 16 slots.
        val capacity = RawRingBudget.capacityFor(
            4080, 3072, (1.5 * gb).toLong(), 6 * gb, desired = 32,
        )

        assertThat(capacity).isAtLeast(RawRingBudget.MIN_CAPACITY)
        assertThat(capacity).isLessThan(32)
    }

    @Test
    fun `a low memory device is refused outright`() {
        // Returning a small ring here would be worse than refusing: the pool
        // would thrash and the sequential path is a working camera.
        val capacity = RawRingBudget.capacityFor(
            4080, 3072, availableRamBytes = 300L * 1024 * 1024,
            totalRamBytes = 2 * gb, desired = 32,
        )

        assertThat(capacity).isEqualTo(0)
    }

    @Test
    fun `depth leaves the writer room beyond the burst`() {
        // A ring with no spare slots would stall the camera the instant a merge
        // locked it, so depth always exceeds the burst it serves.
        assertThat(RawRingBudget.depthForBurst(8)).isGreaterThan(8)
        assertThat(RawRingBudget.burstForDepth(RawRingBudget.depthForBurst(8)))
            .isAtLeast(8)
    }

    @Test
    fun `depth is capped at the maximum ring`() {
        assertThat(RawRingBudget.depthForBurst(32)).isEqualTo(RawRingBudget.MAX_CAPACITY)
        assertThat(RawRingBudget.burstForDepth(RawRingBudget.MAX_CAPACITY))
            .isEqualTo(RawRingBudget.MAX_CAPACITY - RawRingBudget.WRITE_HEADROOM)
    }

    @Test
    fun `a smaller raw size affords a deeper ring on the same device`() {
        val full = RawRingBudget.capacityFor(4080, 3072, 1 * gb, 6 * gb, 32)
        val binned = RawRingBudget.capacityFor(2040, 1536, 1 * gb, 6 * gb, 32)

        assertThat(binned).isGreaterThan(full)
    }

    @Test
    fun `frame bytes match a 16 bit CFA`() {
        assertThat(RawRingBudget.frameBytes(4080, 3072)).isEqualTo(4080L * 3072L * 2L)
    }
}
