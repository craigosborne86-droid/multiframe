package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class FrameMetadataStoreTest {
    private data class Metadata(
        val stamp: Long, val iso: Int, val exposure: Long, val focus: Float,
        val wb: List<Float>, val black: List<Float>, val white: Int, val noise: List<Double>,
    )

    private fun result(stamp: Long) = Metadata(stamp, stamp.toInt(), stamp * 1000, stamp / 100f,
        listOf(stamp / 10f, 1f, 1f, stamp / 20f), List(4) { stamp + it / 4f },
        4095 - stamp.toInt(), listOf(stamp * .0001, stamp * .00001))

    @Test fun reorderedCallbacksPreserveEveryMetadataField() = runBlocking<Unit> {
        val store = FrameMetadataStore<Metadata>(4)
        store.put(20, result(20)) // result before image
        val shot = store.retain(longArrayOf(30, 20, 10))
        assertThat(shot.values()).containsExactly(null, result(20), null).inOrder()
        val waiter = async { shot.awaitComplete(1000) }
        yield()
        store.put(10, result(10)) // older result arrives after newer
        store.put(31, result(31)) // neighbour must not satisfy 30
        assertThat(waiter.isCompleted).isFalse()
        store.put(30, result(30))
        assertThat(waiter.await()).isTrue()
        assertThat(shot.values()).containsExactly(result(30), result(20), result(10)).inOrder()
        shot.close()
    }

    @Test fun missingOrEvictedResultNeverBorrowsANeighbour() = runBlocking<Unit> {
        val store = FrameMetadataStore<Metadata>(2)
        for (stamp in 1L..3L) store.put(stamp, result(stamp))
        store.retain(longArrayOf(1, 4)).use { shot ->
            assertThat(shot.awaitComplete(10)).isFalse()
            assertThat(shot.values()).containsExactly(null, null)
        }
    }

    @Test fun queuedShotsRetainMetadataAfterCacheAndRawSlotReuse() = runBlocking<Unit> {
        val store = FrameMetadataStore<Metadata>(2)
        store.put(1, result(1))
        val first = store.retain(longArrayOf(2, 1))
        val second = store.retain(longArrayOf(3, 2))
        for (stamp in 2L..100L) store.put(stamp, result(stamp))
        assertThat(first.awaitComplete(10)).isTrue()
        assertThat(second.awaitComplete(10)).isTrue()
        assertThat(first.values()).containsExactly(result(2), result(1)).inOrder()
        assertThat(second.values()).containsExactly(result(3), result(2)).inOrder()
        first.close(); second.close()
    }

    @Test fun closingOneShotDoesNotCancelAnotherOrAcceptLateMetadata() = runBlocking<Unit> {
        val store = FrameMetadataStore<Metadata>(2)
        val first = store.retain(longArrayOf(1))
        val second = store.retain(longArrayOf(1))
        first.close(); first.close()
        store.put(1, result(1))
        assertThat(first.awaitComplete(10)).isFalse()
        assertThat(first.values()).containsExactly(null)
        assertThat(second.awaitComplete(10)).isTrue()
        second.close()
    }

    @Test fun duplicateCallbacksCannotReplacePinnedMetadata() = runBlocking<Unit> {
        val store = FrameMetadataStore<Metadata>(2)
        store.put(1, result(1))
        store.put(1, result(1).copy(iso = 9999))
        store.retain(longArrayOf(1)).use { shot ->
            assertThat(shot.awaitComplete(10)).isTrue()
            assertThat(shot.values()).containsExactly(result(1))
        }
    }
}
