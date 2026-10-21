package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.WritableByteChannel

/**
 * One TIFF image file directory, and the file it heads: the container
 * [DngWriter] writes a DNG into, by hand.
 *
 * ### The file
 *
 * Little-endian ("II"), one IFD, no SubIFDs: the eight-byte header, the
 * directory at offset 8, every value too long for its entry after it, and
 * the image strips last. The strips are the merged CFA as the merge left it
 * in native memory -- little-endian 16-bit, a row per strip -- and are
 * handed to the channel as the buffer they are, so a direct buffer goes to
 * the file without passing through the Java heap.
 *
 * ### The arithmetic
 *
 * TIFF 6.0 §2: an entry is tag, type, count and four bytes that hold the
 * value when it fits and its offset when it does not. Entries are sorted by
 * tag. A value that does not fit begins on a word boundary. The strip
 * offsets depend on how long the directory and its values are, which does
 * not depend on the offsets themselves, so the layout is two passes: measure,
 * then write.
 */
class TiffDirectory {

    /** One entry: its tag, TIFF type, count and the value bytes in file order. */
    class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

    private val entries = sortedMapOf<Int, Entry>()

    val tags: Set<Int> get() = entries.keys

    operator fun get(tag: Int): Entry? = entries[tag]

    fun put(tag: Int, type: Int, count: Int, value: ByteArray): TiffDirectory {
        require(tag in 1..0xFFFF) { "tag $tag" }
        require(value.size == count * sizeOf(type)) { "tag $tag: ${value.size} bytes for $count of type $type" }
        entries[tag] = Entry(tag, type, count, value)
        return this
    }

    fun bytes(tag: Int, vararg values: Int) = put(tag, BYTE, values.size, ByteArray(values.size) { values[it].toByte() })

    fun undefined(tag: Int, value: ByteArray) = put(tag, UNDEFINED, value.size, value)

    /** A NUL-terminated string; an empty one is the single NUL `DngCreator` writes. */
    fun ascii(tag: Int, text: String): TiffDirectory {
        val b = (text + '\u0000').toByteArray(Charsets.US_ASCII)
        return put(tag, ASCII, b.size, b)
    }

    fun shorts(tag: Int, vararg values: Int) = put(tag, SHORT, values.size, encode(values.size * 2) { b -> values.forEach { b.putShort(it.toShort()) } })

    fun longs(tag: Int, vararg values: Long) = put(tag, LONG, values.size, encode(values.size * 4) { b -> values.forEach { b.putInt(it.toInt()) } })

    fun longs(tag: Int, vararg values: Int) = longs(tag, *LongArray(values.size) { values[it].toLong() })

    /** Unsigned rationals, as interleaved numerator, denominator pairs. */
    fun rationals(tag: Int, pairs: LongArray) = fractions(tag, RATIONAL, pairs)

    /** Signed rationals, as interleaved numerator, denominator pairs. */
    fun srationals(tag: Int, pairs: LongArray) = fractions(tag, SRATIONAL, pairs)

    fun doubles(tag: Int, values: DoubleArray) = put(tag, DOUBLE, values.size, encode(values.size * 8) { b -> values.forEach { b.putDouble(it) } })

    private fun fractions(tag: Int, type: Int, pairs: LongArray): TiffDirectory {
        require(pairs.size % 2 == 0)
        return put(tag, type, pairs.size / 2, encode(pairs.size * 4) { b -> pairs.forEach { b.putInt(it.toInt()) } })
    }

    /**
     * Writes the file: header, this directory with its strip offsets
     * filled in, the values, then [rows] strips of [rowBytes] from [image].
     * The directory is assembled on the heap -- tens of kilobytes -- and the
     * image is written from [image] directly.
     */
    fun write(channel: WritableByteChannel, image: ByteBuffer, rows: Int, rowBytes: Int) {
        require(rows > 0 && rowBytes > 0)
        val imageBytes = rows.toLong() * rowBytes
        require(image.capacity() >= imageBytes) { "image buffer holds ${image.capacity()} of $imageBytes bytes" }
        // Placeholders of the right size, so the measure is the layout.
        longs(TAG_STRIP_OFFSETS, *LongArray(rows))
        longs(TAG_STRIP_BYTE_COUNTS, *LongArray(rows) { rowBytes.toLong() })
        longs(TAG_ROWS_PER_STRIP, 1L)
        val header = layout()
        val imageStart = header.size
        require(imageStart + imageBytes <= 0xFFFFFFFFL) { "a classic TIFF ends at 4 GiB" }
        longs(TAG_STRIP_OFFSETS, *LongArray(rows) { imageStart + it.toLong() * rowBytes })
        val written = layout()
        check(written.size == imageStart)
        writeFully(channel, ByteBuffer.wrap(written))
        val pixels = image.duplicate()
        pixels.clear()
        pixels.limit(imageBytes.toInt())
        writeFully(channel, pixels)
    }

    /** The header, directory and values, padded so the image starts on [IMAGE_ALIGN]. */
    internal fun layout(): ByteArray {
        val directoryBytes = 2 + entries.size * 12 + 4
        var valuesEnd = HEADER_BYTES + directoryBytes
        val offsets = HashMap<Int, Int>()
        for (e in entries.values) {
            if (e.value.size <= 4) continue
            valuesEnd += valuesEnd and 1
            offsets[e.tag] = valuesEnd
            valuesEnd += e.value.size
        }
        val total = (valuesEnd + IMAGE_ALIGN - 1) / IMAGE_ALIGN * IMAGE_ALIGN
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(HEADER_BYTES)
        b.putShort(entries.size.toShort())
        for (e in entries.values) {
            b.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            val at = offsets[e.tag]
            if (at == null) {
                b.put(e.value)
                repeat(4 - e.value.size) { b.put(0) }
            } else {
                b.putInt(at)
            }
        }
        b.putInt(0) // no next IFD
        for (e in entries.values) offsets[e.tag]?.let { at -> b.position(at); b.put(e.value) }
        return b.array()
    }

    companion object {
        const val BYTE = 1
        const val ASCII = 2
        const val SHORT = 3
        const val LONG = 4
        const val RATIONAL = 5
        const val UNDEFINED = 7
        const val SRATIONAL = 10
        const val FLOAT = 11
        const val DOUBLE = 12

        const val TAG_STRIP_OFFSETS = 273
        const val TAG_ROWS_PER_STRIP = 278
        const val TAG_STRIP_BYTE_COUNTS = 279

        private const val HEADER_BYTES = 8

        /** Where the image begins: a cache line, which costs at most fifteen bytes. */
        private const val IMAGE_ALIGN = 16

        fun sizeOf(type: Int): Int = when (type) {
            BYTE, ASCII, UNDEFINED -> 1
            SHORT -> 2
            LONG, FLOAT -> 4
            RATIONAL, SRATIONAL, DOUBLE -> 8
            else -> throw IllegalArgumentException("TIFF type $type")
        }

        private inline fun encode(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

        private fun writeFully(channel: WritableByteChannel, b: ByteBuffer) {
            while (b.hasRemaining()) channel.write(b)
        }
    }
}
