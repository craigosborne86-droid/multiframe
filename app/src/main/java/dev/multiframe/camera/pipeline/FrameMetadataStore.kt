package dev.multiframe.camera.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exact sensor-timestamp association across independently ordered image/result callbacks.
 * A retained snapshot pins metadata, not pixels. Missing results stay missing until their
 * own callback arrives; another frame's metadata is never substituted. Completed tickets
 * survive cache eviction while a shot waits in the develop queue.
 */
internal class FrameMetadataStore<T : Any>(private val capacity: Int) {
    init { require(capacity > 0) }

    private val recent = LinkedHashMap<Long, T>()
    private val pending = LinkedHashSet<Ticket>()

    @Synchronized
    fun put(timestampNs: Long, value: T) {
        require(timestampNs > 0)
        // A TotalCaptureResult is final. Do not replace an already associated result.
        recent.putIfAbsent(timestampNs, value)
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val ticket = iterator.next()
            ticket.accept(timestampNs, value)
            if (ticket.isComplete()) {
                ticket.completion.complete(true)
                iterator.remove()
            }
        }
        while (recent.size > capacity) recent.remove(recent.keys.first())
    }

    @Synchronized
    fun retain(timestampsNs: LongArray): Ticket {
        require(timestampsNs.all { it > 0 } && timestampsNs.distinct().size == timestampsNs.size)
        val ticket = Ticket(timestampsNs.copyOf())
        if (ticket.isComplete()) ticket.completion.complete(true) else pending.add(ticket)
        return ticket
    }

    inner class Ticket internal constructor(private val timestamps: LongArray) : AutoCloseable {
        private val values = timestamps.map { recent[it] }.toMutableList()
        internal val completion = CompletableDeferred<Boolean>()

        internal fun accept(timestamp: Long, result: T) {
            val index = timestamps.indexOf(timestamp)
            if (index >= 0 && values[index] == null) values[index] = result
        }

        internal fun isComplete(): Boolean = values.all { it != null }

        fun values(): List<T?> = synchronized(this@FrameMetadataStore) { values.toList() }

        /** Suspends only the develop worker, never the shutter or camera callback. */
        suspend fun awaitComplete(timeoutMillis: Long): Boolean {
            require(timeoutMillis > 0)
            return withTimeoutOrNull(timeoutMillis) { completion.await() } == true
        }

        override fun close() = synchronized(this@FrameMetadataStore) {
            pending.remove(this)
            completion.complete(false)
            Unit
        }
    }
}
