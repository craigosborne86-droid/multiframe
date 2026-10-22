package dev.multiframe.camera.pipeline

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * JUMBF boxes (ISO/IEC 19566-5), the container C2PA manifests are stored in.
 *
 * A box is `LBox` (four bytes, big-endian, the whole box's length), `TBox`
 * (four ASCII bytes) and a payload. A superbox (`jumb`) holds a description
 * box (`jumd`: a 16-byte type UUID, a toggles byte and a null-terminated
 * label) followed by its content boxes.
 */
object Jumbf {

    /** C2PA's box type UUIDs share this tail: `-0011-0010-8000-00AA00389B71`. */
    private fun c2paType(fourCc: String): UUID {
        val b = fourCc.toByteArray(Charsets.US_ASCII)
        val high = ((b[0].toLong() and 0xFF) shl 56) or ((b[1].toLong() and 0xFF) shl 48) or
            ((b[2].toLong() and 0xFF) shl 40) or ((b[3].toLong() and 0xFF) shl 32) or 0x0011_0010L
        return UUID(high, 0x8000_00AA_0038_9B71uL.toLong())
    }

    val MANIFEST_STORE: UUID = c2paType("c2pa")
    val MANIFEST: UUID = c2paType("c2ma")
    val ASSERTION_STORE: UUID = c2paType("c2as")
    val CLAIM: UUID = c2paType("c2cl")
    val SIGNATURE: UUID = c2paType("c2cs")
    val CBOR_CONTENT: UUID = c2paType("cbor")

    fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + payload.size)
        out.putInt(8 + payload.size)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(payload)
        return out.array()
    }

    /** A description box: type UUID, toggles 0x03 (requestable, label present), label. */
    fun description(type: UUID, label: String): ByteArray {
        val out = ByteArrayOutputStream()
        val uuid = ByteBuffer.allocate(16).putLong(type.mostSignificantBits).putLong(type.leastSignificantBits).array()
        out.write(uuid)
        out.write(0x03)
        out.write(label.toByteArray(Charsets.UTF_8))
        out.write(0)
        return box("jumd", out.toByteArray())
    }

    fun superbox(type: UUID, label: String, vararg contents: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(description(type, label))
        contents.forEach { out.write(it) }
        return box("jumb", out.toByteArray())
    }

    /** A labelled superbox holding one CBOR content box: how C2PA stores assertions, claims and signatures. */
    fun cborSuperbox(type: UUID, label: String, cbor: ByteArray): ByteArray =
        superbox(type, label, box("cbor", cbor))

    /**
     * The bytes a hashed URI covers: the superbox's contents, without its own
     * eight-byte LBox/TBox header (C2PA 2.x §8.4, hashing of JUMBF boxes).
     */
    fun hashedContents(superbox: ByteArray): ByteArray = superbox.copyOfRange(8, superbox.size)
}
