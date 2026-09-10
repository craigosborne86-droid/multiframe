package dev.multiframe.camera.pipeline

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Splitting a full-frame pass across the cores.
 *
 * Every per-pixel pass over a twelve megapixel frame is the same shape of
 * problem: rows are independent, there are far more of them than there are
 * cores, and doing them one at a time wastes seven eighths of the phone.
 *
 * This was a private helper inside [RawDeveloper], which was fine while the
 * develop was the only thing sweeping the whole frame. It is here now because
 * the optics corrections do too, and they were measured on the phone taking
 * **3.38 seconds** of a 3.54 second develop -- 1.53s for chromatic aberration
 * and 1.85s for undistortion -- against a budget of 800ms for the entire
 * capture-to-review path. Both were single-threaded Kotlin loops sitting
 * alongside a develop that is native, vectorised and banded.
 *
 * One pool, shared, rather than one per object: these passes run in sequence on
 * the same frame and never compete, so a second pool would only add threads for
 * the scheduler to move between.
 */
internal object Bands {

    /**
     * At least two, so a single-core device still works, and no more than the
     * cores available, since every task here is compute-bound and adding
     * threads past that only adds context switches.
     */
    val threads = max(2, Runtime.getRuntime().availableProcessors())

    private val pool = Executors.newFixedThreadPool(threads)

    /**
     * Runs [body] over contiguous row bands of [height], in parallel, and
     * returns once every band is finished.
     *
     * The body is handed a half-open range. Bands are contiguous rather than
     * interleaved because a pass reading its own neighbourhood gets far better
     * cache behaviour from a block of rows than from every eighth one.
     */
    inline fun rows(height: Int, crossinline body: (Int, Int) -> Unit) {
        if (height <= 0) return
        // Not worth the dispatch for a handful of rows, and the unit tests are
        // full of tiny images.
        if (height < threads * 4) {
            body(0, height)
            return
        }
        val band = (height + threads - 1) / threads
        val tasks = ArrayList<Callable<Unit>>(threads)
        for (t in 0 until threads) {
            val start = t * band
            val end = min(start + band, height)
            if (start >= end) continue
            tasks.add(Callable { body(start, end) })
        }
        submit(tasks)
    }

    /**
     * Runs [body] over contiguous slices of [count] independent items.
     *
     * The chromatic aberration search uses this: its work is a list of
     * candidate scales rather than a list of rows, and each is an independent
     * sweep over the same sample set.
     */
    inline fun slices(count: Int, crossinline body: (Int, Int) -> Unit) {
        if (count <= 0) return
        if (count < threads * 2) {
            body(0, count)
            return
        }
        val slice = (count + threads - 1) / threads
        val tasks = ArrayList<Callable<Unit>>(threads)
        for (t in 0 until threads) {
            val start = t * slice
            val end = min(start + slice, count)
            if (start >= end) continue
            tasks.add(Callable { body(start, end) })
        }
        submit(tasks)
    }

    /** Published so the inline functions above can reach the private pool. */
    fun submit(tasks: List<Callable<Unit>>) {
        pool.invokeAll(tasks).forEach { it.get() }
    }
}
