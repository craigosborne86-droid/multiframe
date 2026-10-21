package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.bench.DngFile
import dev.multiframe.camera.bench.TiffEntries
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.file.Files

/**
 * The DNG writer on a file built here: the structure TIFF asks for, the raw
 * as it went in, and the readers the app and the bench already trust --
 * [DngMetadata] and [DngFile] -- reading it back as they read a
 * `DngCreator` file.
 */
class DngWriterTest {

    private val w = 64
    private val h = 48

    private fun matrix(vararg v: Double) = LongArray(18) { i -> if (i % 2 == 0) Math.round(v[i / 2] * 10000) else 10000 }

    private fun record(extra: DngRecord.() -> DngRecord = { this }) = DngRecord(
        width = w, height = h,
        cfaPattern = intArrayOf(2, 1, 1, 0),
        blackLevel = LongArray(8) { if (it % 2 == 0) 6402 else 100 },
        whiteLevel = 1023,
        make = "Google", model = "Pixel 11 Pro", uniqueCameraModel = "Pixel 11 Pro-Google-google",
        software = "test", dateTime = "2026:10:21 12:00:00", orientation = 6,
        exposureTime = longArrayOf(4_000_000, 1_000_000_000), fNumber = longArrayOf(284, 100), iso = 100,
        focalLength = longArrayOf(1952, 100),
        colorMatrix1 = matrix(1.0843, -0.4343, 0.1383, -0.5797, 1.5203, 0.3272, -0.0766, 0.2227, 0.7770),
        colorMatrix2 = matrix(0.7685, -0.1619, -0.0882, -0.6046, 1.4550, 0.1099, -0.1978, 0.3815, 0.4381),
        forwardMatrix1 = matrix(0.7391, 0.1888, 0.0363, 0.2698, 0.8597, -0.1295, -0.0082, -0.5439, 1.3772),
        forwardMatrix2 = matrix(0.7166, 0.1439, 0.1038, 0.2943, 0.7786, -0.0730, 0.0470, -0.4644, 1.2425),
        illuminant1 = 17, illuminant2 = 21,
        asShotNeutral = DngCamera2.neutralPairs(floatArrayOf(0.5f, 1f, 0.66f)),
        baselineExposure = DngRecord.hundredths(1.25f),
        noiseProfile = doubleArrayOf(5e-5, 2e-7, 5.8e-5, 2e-7, 5.6e-5, 2e-7),
    ).extra()

    private fun raw(): ByteBuffer {
        val b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until w * h) b.putShort((64 + (i * 37) % 900).toShort())
        b.position(17) // a caller's cursor somewhere: the writer must not care
        return b
    }

    private fun written(r: DngRecord = record(), pixels: ByteBuffer = raw()): ByteArray {
        val out = ByteArrayOutputStream()
        Channels.newChannel(out).use { DngWriter.write(it, r, pixels) }
        return out.toByteArray()
    }

    @Test
    fun `the file is a little-endian TIFF with one sorted directory`() {
        val bytes = written()
        assertThat(bytes.copyOfRange(0, 4)).isEqualTo(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0))
        val tiff = TiffEntries.read(bytes) // throws on an unsorted directory or an odd value offset
        assertThat(tiff.tags).containsAtLeast(254, 256, 257, 258, 259, 262, 273, 277, 278, 279, 33421, 33422, 50706, 50714, 50717, 50721)
        assertThat(tiff.ints(50706)!!.toList()).containsExactly(1L, 7L, 1L, 0L).inOrder()
        assertThat(tiff.ints(50707)!!.toList()).containsExactly(1L, 1L, 0L, 0L).inOrder()
        val next = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { b -> val n = b.getShort(8).toInt(); b.getInt(8 + 2 + n * 12) }
        assertThat(next).isEqualTo(0)
    }

    @Test
    fun `the raw comes back exactly, a row to a strip, after the metadata`() {
        val pixels = raw()
        val bytes = written(pixels = pixels)
        val tiff = TiffEntries.read(bytes)
        val expected = ByteArray(w * h * 2).also { pixels.duplicate().apply { clear() }.get(it) }
        assertThat(tiff.image()).isEqualTo(expected)
        val offsets = tiff.ints(273)!!
        assertThat(offsets.size).isEqualTo(h)
        assertThat(offsets[0] % 16).isEqualTo(0L)
        assertThat(offsets[0] + w.toLong() * 2 * h).isEqualTo(bytes.size.toLong())
        assertThat(tiff.ints(278)!!.toList()).containsExactly(1L)
        // The caller's buffer is untouched, cursor and all.
        assertThat(pixels.position()).isEqualTo(17)
    }

    @Test
    fun `the readers the app already trusts read it as they read DngCreator's`() {
        val bytes = written()
        val calibration = DngMetadata.readCalibration(bytes)!!
        assertThat(calibration.colorMatrix1.m[0]).isWithin(1e-4f).of(1.0843f)
        assertThat(calibration.forward2!!.m[8]).isWithin(1e-4f).of(1.2425f)
        assertThat(DngMetadata.readIlluminants(bytes)).isEqualTo(17 to 21)
        assertThat(DngMetadata.baselineExposureOffset(bytes)).isNotNull()

        val file = Files.createTempFile("writer", ".dng").toFile()
        try {
            file.writeBytes(bytes)
            val dng = DngFile.read(file)
            assertThat(dng.frame.width).isEqualTo(w)
            assertThat(dng.sensor.cfaPattern.toList()).containsExactly(2, 1, 1, 0).inOrder()
            assertThat(dng.sensor.blackLevel.toList()).containsExactly(64, 64, 64, 64)
            assertThat(dng.baselineExposure).isWithin(1e-6f).of(1.25f)
            assertThat(dng.iso).isEqualTo(100)
            assertThat(dng.frame.data[1].toInt()).isEqualTo(64 + 37)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `optional tags are absent rather than written empty`() {
        val tiff = TiffEntries.read(written(record { copy(colorMatrix2 = null, noiseProfile = null, illuminant2 = null) }))
        assertThat(tiff.tags).containsNoneOf(50722, 51041, 50779, 51009, 51022, ProfileGainTableMap.TAG, ProfileGainTableMap.TAG_2)
    }

    @Test
    fun `a written record reads back as the same record`() {
        val r = record { copy(opcodeList2 = byteArrayOf(0, 0, 0, 0), profileGainTableMap = ByteArray(64 + 4), profileGainTableMap2 = ByteArray(80 + 4)) }
        val back = TiffEntries.read(written(r)).record()
        // DataClass equality is by reference for arrays: compare what was written.
        val again = TiffEntries.read(written(back))
        assertThat(again.bytes).isEqualTo(TiffEntries.read(written(r)).bytes)
    }

    @Test
    fun `a file on disk is written from a direct buffer through its channel`() {
        val file = File.createTempFile("channel", ".dng")
        try {
            java.io.FileOutputStream(file).channel.use { DngWriter.write(it, record(), raw()) }
            assertThat(file.readBytes()).isEqualTo(written())
        } finally {
            file.delete()
        }
    }
}
