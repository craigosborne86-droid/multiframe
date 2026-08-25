package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reading camera profile tags out of a DNG.
 *
 * Tested against TIFFs built here rather than against a real file, so the
 * awkward cases -- big-endian, a missing tag, a truncated file, a hostile
 * offset -- can all be produced deliberately. A parser that reads past the end
 * of a buffer on malformed input is a real problem, and camera files are not
 * always well formed.
 */
class DngMetadataTest {

    /** Builds a minimal TIFF carrying the given rational-matrix tags. */
    private fun tiff(
        matrices: Map<Int, FloatArray>,
        shorts: Map<Int, Int> = emptyMap(),
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): ByteArray {
        val entryCount = matrices.size + shorts.size
        val headerSize = 8
        val ifdSize = 2 + entryCount * 12 + 4
        val dataStart = headerSize + ifdSize

        val data = ByteArrayOutputStream()
        val offsets = HashMap<Int, Int>()
        var cursor = dataStart
        for ((tag, values) in matrices) {
            offsets[tag] = cursor
            val buf = ByteBuffer.allocate(values.size * 8).order(order)
            for (v in values) {
                // Stored as a rational over a fixed denominator, as DNG does.
                buf.putInt((v * 10000f).toInt())
                buf.putInt(10000)
            }
            data.write(buf.array())
            cursor += values.size * 8
        }

        val out = ByteBuffer.allocate(dataStart + data.size()).order(order)
        out.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        out.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        out.putShort(42)
        out.putInt(headerSize)

        out.putShort(entryCount.toShort())
        for ((tag, values) in matrices) {
            out.putShort(tag.toShort())
            out.putShort(DngMetadata.RATIONAL.toShort())
            out.putInt(values.size)
            out.putInt(offsets.getValue(tag))
        }
        for ((tag, value) in shorts) {
            out.putShort(tag.toShort())
            out.putShort(DngMetadata.SHORT.toShort())
            out.putInt(1)
            // Small values live in the field itself rather than at an offset.
            out.putInt(value)
        }
        out.putInt(0)
        out.put(data.toByteArray())
        return out.array()
    }

    private val colour1 = floatArrayOf(
        0.80f, -0.26f, -0.06f, -0.32f, 1.18f, 0.10f, -0.03f, 0.14f, 0.70f,
    )
    private val colour2 = floatArrayOf(
        0.72f, -0.20f, -0.08f, -0.28f, 1.12f, 0.14f, -0.05f, 0.18f, 0.62f,
    )
    private val forward1 = floatArrayOf(
        0.58f, 0.26f, 0.12f, 0.26f, 0.70f, 0.04f, 0.03f, 0.05f, 0.74f,
    )

    private fun tag(name: String) = DngMetadata.tagFor(name)

    // ------------------------------------------------------------------

    @Test
    fun `matrices come back with the values that went in`() {
        val bytes = tiff(
            mapOf(
                tag("colorMatrix1") to colour1,
                tag("colorMatrix2") to colour2,
                tag("forwardMatrix1") to forward1,
            )
        )

        val calibration = DngMetadata.readCalibration(bytes)!!

        for (i in 0 until 9) {
            assertThat(calibration.colorMatrix1.m[i]).isWithin(1e-3f).of(colour1[i])
            assertThat(calibration.colorMatrix2.m[i]).isWithin(1e-3f).of(colour2[i])
            assertThat(calibration.forward1!!.m[i]).isWithin(1e-3f).of(forward1[i])
        }
    }

    @Test
    fun `big-endian files are read too`() {
        // DNG permits either byte order and cameras genuinely differ.
        val bytes = tiff(mapOf(tag("colorMatrix1") to colour1), order = ByteOrder.BIG_ENDIAN)

        val calibration = DngMetadata.readCalibration(bytes)!!

        assertThat(calibration.colorMatrix1.m[0]).isWithin(1e-3f).of(colour1[0])
    }

    @Test
    fun `a missing second matrix falls back to the first`() {
        // A single-illuminant profile is legal, and interpolating between a
        // matrix and nothing would produce nonsense.
        val bytes = tiff(mapOf(tag("colorMatrix1") to colour1))

        val calibration = DngMetadata.readCalibration(bytes)!!

        for (i in 0 until 9) {
            assertThat(calibration.colorMatrix2.m[i]).isWithin(1e-3f).of(colour1[i])
        }
        assertThat(calibration.forward1).isNull()
    }

    @Test
    fun `absent calibration transforms default to identity`() {
        val bytes = tiff(mapOf(tag("colorMatrix1") to colour1))

        val calibration = DngMetadata.readCalibration(bytes)!!

        assertThat(calibration.calibration1.m.toList())
            .isEqualTo(Mat3.IDENTITY.m.toList())
    }

    @Test
    fun `illuminants are read from the short fields`() {
        val bytes = tiff(
            mapOf(tag("colorMatrix1") to colour1),
            shorts = mapOf(tag("illuminant1") to 17, tag("illuminant2") to 21),
        )

        val illuminants = DngMetadata.readIlluminants(bytes)

        // 17 is Standard Illuminant A, 21 is D65.
        assertThat(illuminants).isEqualTo(17 to 21)
    }

    // ------------------------------------------------------------------
    // Malformed input, which is the point of testing a parser.
    // ------------------------------------------------------------------

    @Test
    fun `a file with no colour matrix yields nothing rather than guessing`() {
        val bytes = tiff(mapOf(tag("forwardMatrix1") to forward1))

        assertThat(DngMetadata.readCalibration(bytes)).isNull()
    }

    @Test
    fun `something that is not a TIFF is refused`() {
        assertThat(DngMetadata.readCalibration(ByteArray(0))).isNull()
        assertThat(DngMetadata.readCalibration(byteArrayOf(1, 2, 3, 4))).isNull()
        assertThat(
            DngMetadata.readCalibration("not a tiff at all, really".toByteArray())
        ).isNull()
    }

    @Test
    fun `a truncated file does not read past its end`() {
        // The failure that matters. Camera files are not always well formed,
        // and a parser that walks off the end is a crash in the capture path.
        val full = tiff(
            mapOf(tag("colorMatrix1") to colour1, tag("colorMatrix2") to colour2)
        )

        for (length in listOf(8, 12, 20, 40, full.size / 2, full.size - 8, full.size - 1)) {
            val truncated = full.copyOf(length)
            // Must return something or nothing, but never throw.
            DngMetadata.readCalibration(truncated)
            DngMetadata.readIlluminants(truncated)
        }
    }

    @Test
    fun `an offset pointing outside the file is refused`() {
        val bytes = tiff(mapOf(tag("colorMatrix1") to colour1))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // The first entry's value offset sits twelve bytes into the IFD.
        buffer.putInt(8 + 2 + 8, 0x7FFFFFF0)

        assertThat(DngMetadata.readCalibration(bytes)).isNull()
    }

    @Test
    fun `a negative element count is refused`() {
        val bytes = tiff(mapOf(tag("colorMatrix1") to colour1))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8 + 2 + 4, -9)

        assertThat(DngMetadata.readCalibration(bytes)).isNull()
    }

    @Test
    fun `a zero denominator becomes zero rather than infinity`() {
        // A rational with a zero denominator appears in real files, and letting
        // it become infinity poisons the whole matrix.
        val bytes = tiff(mapOf(tag("colorMatrix1") to colour1))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataStart = 8 + 2 + 12 + 4
        buffer.putInt(dataStart + 4, 0)

        val calibration = DngMetadata.readCalibration(bytes)!!

        assertThat(calibration.colorMatrix1.m[0]).isEqualTo(0f)
        assertThat(calibration.colorMatrix1.m[0].isFinite()).isTrue()
    }
}
