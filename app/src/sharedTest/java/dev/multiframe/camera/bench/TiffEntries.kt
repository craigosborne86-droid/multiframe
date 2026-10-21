package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.DngRecord
import dev.multiframe.camera.pipeline.TiffDirectory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IFD0 of a TIFF read generically, every entry as its type, count and value
 * bytes -- for comparing two DNGs tag by tag, and for rebuilding the
 * [DngRecord] a `DngCreator` file describes so ours can be written from it.
 * Independent of the writer: it reads what is in the file, not what the
 * writer meant.
 */
class TiffEntries private constructor(
    val bytes: ByteArray,
    val order: ByteOrder,
    val entries: Map<Int, TiffDirectory.Entry>,
) {
    val tags: Set<Int> get() = entries.keys

    operator fun get(tag: Int): TiffDirectory.Entry? = entries[tag]

    private fun buf(tag: Int): ByteBuffer? = entries[tag]?.let { ByteBuffer.wrap(it.value).order(order) }

    fun ints(tag: Int): LongArray? = entries[tag]?.let { e ->
        val b = buf(tag)!!
        LongArray(e.count) {
            when (e.type) {
                TiffDirectory.BYTE, TiffDirectory.UNDEFINED -> b.get().toLong() and 0xFF
                TiffDirectory.SHORT -> b.getShort().toLong() and 0xFFFF
                TiffDirectory.LONG -> b.getInt().toLong() and 0xFFFFFFFFL
                else -> error("tag $tag is type ${e.type}")
            }
        }
    }

    fun pairs(tag: Int): LongArray? = entries[tag]?.let { e ->
        val b = buf(tag)!!
        LongArray(e.count * 2) { if (e.type == TiffDirectory.RATIONAL) b.getInt().toLong() and 0xFFFFFFFFL else b.getInt().toLong() }
    }

    fun ascii(tag: Int): String? = entries[tag]?.let { String(it.value, Charsets.US_ASCII).trimEnd('\u0000') }

    fun doubles(tag: Int): DoubleArray? = entries[tag]?.let { e -> val b = buf(tag)!!; DoubleArray(e.count) { b.getDouble() } }

    /** The strips, concatenated: the raw samples as the file holds them. */
    fun image(): ByteArray {
        val offsets = ints(TiffDirectory.TAG_STRIP_OFFSETS)!!
        val counts = ints(TiffDirectory.TAG_STRIP_BYTE_COUNTS)!!
        val out = ByteArray(counts.sum().toInt())
        var at = 0
        for (i in offsets.indices) {
            System.arraycopy(bytes, offsets[i].toInt(), out, at, counts[i].toInt())
            at += counts[i].toInt()
        }
        return out
    }

    /** The record that, written by `DngWriter`, would say what this file says. */
    fun record(): DngRecord {
        val w = ints(256)!![0].toInt()
        val h = ints(257)!![0].toInt()
        return DngRecord(
            width = w, height = h,
            cfaPattern = ints(33422)!!.map { it.toInt() }.toIntArray(),
            blackLevel = pairs(50714)!!,
            whiteLevel = ints(50717)!![0],
            make = ascii(271)!!, model = ascii(272)!!, uniqueCameraModel = ascii(50708)!!, software = ascii(305)!!,
            dateTime = ascii(306)!!,
            orientation = ints(274)!![0].toInt(),
            exposureTime = pairs(33434), fNumber = pairs(33437), iso = ints(34855)?.get(0)?.toInt(), focalLength = pairs(37386),
            colorMatrix1 = pairs(50721), colorMatrix2 = pairs(50722),
            cameraCalibration1 = pairs(50723), cameraCalibration2 = pairs(50724),
            forwardMatrix1 = pairs(50964), forwardMatrix2 = pairs(50965),
            illuminant1 = ints(50778)?.get(0)?.toInt(), illuminant2 = ints(50779)?.get(0)?.toInt(),
            asShotNeutral = pairs(50728),
            baselineExposure = pairs(50730) ?: longArrayOf(0, 1),
            activeArea = ints(50829)!!.map { it.toInt() }.toIntArray(),
            defaultCropOrigin = ints(50719)!!.map { it.toInt() }.toIntArray(),
            defaultCropSize = ints(50720)!!.map { it.toInt() }.toIntArray(),
            noiseProfile = doubles(51041),
            opcodeList2 = entries[51009]?.value,
            opcodeList3 = entries[51022]?.value,
            profileGainTableMap = entries[0xCD2D]?.value,
            profileGainTableMap2 = entries[0xCD40]?.value,
        )
    }

    companion object {
        fun read(bytes: ByteArray): TiffEntries {
            val order = when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> error("not a TIFF")
            }
            val b = ByteBuffer.wrap(bytes).order(order)
            require(b.getShort(2).toInt() == 42) { "not a TIFF" }
            val ifd = b.getInt(4)
            val n = b.getShort(ifd).toInt() and 0xFFFF
            val entries = LinkedHashMap<Int, TiffDirectory.Entry>()
            var previous = -1
            for (i in 0 until n) {
                val at = ifd + 2 + 12 * i
                val tag = b.getShort(at).toInt() and 0xFFFF
                require(tag > previous) { "IFD not sorted at tag $tag" }
                previous = tag
                val type = b.getShort(at + 2).toInt() and 0xFFFF
                val count = b.getInt(at + 4)
                val size = count * TiffDirectory.sizeOf(type)
                val start = if (size <= 4) at + 8 else b.getInt(at + 8)
                if (size > 4) require(start % 2 == 0) { "tag $tag value at odd offset $start" }
                entries[tag] = TiffDirectory.Entry(tag, type, count, bytes.copyOfRange(start, start + size))
            }
            return TiffEntries(bytes, order, entries)
        }
    }
}
