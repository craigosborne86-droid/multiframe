package dev.multiframe.camera.pipeline

import java.io.ByteArrayOutputStream

/**
 * The CBOR subset Content Credentials need (RFC 8949): unsigned and negative
 * integers, byte and text strings, arrays, maps, tags, null and booleans.
 *
 * Hand-written because the project carries no third-party libraries, and the
 * subset is small. Map keys are written in the order given: C2PA's validator
 * reads maps by key, and the claim is signed as the exact bytes written here,
 * so no canonical re-ordering is needed for it to verify.
 */
object Cbor {

    /** A pre-encoded CBOR item, inserted verbatim. */
    class Raw(val bytes: ByteArray)

    /** A tagged item. */
    class Tagged(val tag: Long, val value: Any?)

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xF6)
            is Boolean -> out.write(if (value) 0xF5 else 0xF4)
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is ByteArray -> { head(out, 2, value.size.toLong()); out.write(value) }
            is String -> { val b = value.toByteArray(Charsets.UTF_8); head(out, 3, b.size.toLong()); out.write(b) }
            is List<*> -> { head(out, 4, value.size.toLong()); value.forEach { write(out, it) } }
            is Map<*, *> -> {
                head(out, 5, value.size.toLong())
                value.forEach { (k, v) -> write(out, k); write(out, v) }
            }
            is Tagged -> { head(out, 6, value.tag); write(out, value.value) }
            is Raw -> out.write(value.bytes)
            else -> error("CBOR cannot encode ${value::class.java.simpleName}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        if (v >= 0) head(out, 0, v) else head(out, 1, -1 - v)
    }

    /** The initial byte and argument, in the shortest form (RFC 8949 §3). */
    private fun head(out: ByteArrayOutputStream, major: Int, arg: Long) {
        val m = major shl 5
        when {
            arg < 24 -> out.write(m or arg.toInt())
            arg <= 0xFF -> { out.write(m or 24); out.write(arg.toInt()) }
            arg <= 0xFFFF -> { out.write(m or 25); for (s in intArrayOf(8, 0)) out.write((arg shr s).toInt() and 0xFF) }
            arg <= 0xFFFFFFFFL -> { out.write(m or 26); for (s in intArrayOf(24, 16, 8, 0)) out.write((arg shr s).toInt() and 0xFF) }
            else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write((arg shr s).toInt() and 0xFF) }
        }
    }
}
