package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.multiframe.camera.bench.DngFile
import dev.multiframe.camera.bench.TiffEntries
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteBuffer.wrap
import java.nio.ByteOrder
import java.nio.channels.Channels
import kotlin.math.abs

/**
 * Our writer against `DngCreator`'s files: every archived September DNG is
 * read, its [DngRecord] rebuilt from its own tags, written again by
 * [DngWriter], and the two files compared tag by tag and sample by sample.
 *
 * Every tag must come back with the same type, count and value bytes, and
 * the raw byte for byte, except the intended differences of REFERENCE §3.9:
 *
 * - DNGVersion, 1.4.0.0 there and 1.7.1.0 here;
 * - StripOffsets, because our image starts on a sixteen-byte boundary after
 *   a directory laid out in our own order -- the strips they point to are
 *   compared instead.
 *
 * And the two opcode lists, rebuilt from their decoded values by
 * [DngOpcodes] as production builds them from the camera: the lens shading
 * byte for byte, and the WarpRectilinear's zoom within its one step.
 *
 * Skipped without the fixtures (they are not versioned):
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*DngWriterParityTest*' \
 *         -Pmf.dng.dir=/path/to/app/src/test/resources/dng_samples/september
 */
class DngWriterParityTest {

    private val intended = setOf(50706, TiffDirectory.TAG_STRIP_OFFSETS)

    private fun fixtures(): List<File> {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.dng.dir; the parity is not run", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.startsWith("MF_202609") && f.name.endsWith(".dng") }!!.sortedBy { it.name }
        assumeTrue("no September DNGs in $dir", files.isNotEmpty())
        return files
    }

    private fun rewrite(theirs: TiffEntries): ByteArray {
        val record = theirs.record()
        val raw = ByteBuffer.allocateDirect(record.width * record.height * 2).order(ByteOrder.LITTLE_ENDIAN)
        raw.put(theirs.image()).clear()
        val out = ByteArrayOutputStream(record.width * record.height * 2 + 65536)
        Channels.newChannel(out).use { DngWriter.write(it, record, raw) }
        return out.toByteArray()
    }

    @Test
    fun `every September DNG rewritten by our writer says what DngCreator's said`() {
        var files = 0
        var tagsCompared = 0
        for (file in fixtures()) {
            val theirs = TiffEntries.read(file.readBytes())
            val ours = TiffEntries.read(rewrite(theirs))
            assertWithMessage(file.name).that(ours.tags).isEqualTo(theirs.tags)
            for (tag in theirs.tags - intended) {
                val a = theirs[tag]!!; val b = ours[tag]!!
                assertWithMessage("${file.name} tag $tag type").that(b.type).isEqualTo(a.type)
                assertWithMessage("${file.name} tag $tag count").that(b.count).isEqualTo(a.count)
                assertWithMessage("${file.name} tag $tag value").that(b.value).isEqualTo(a.value)
                tagsCompared++
            }
            assertThat(theirs.ints(50706)!!.toList()).containsExactly(1L, 4L, 0L, 0L).inOrder()
            assertThat(ours.ints(50706)!!.toList()).containsExactly(1L, 7L, 1L, 0L).inOrder()
            assertWithMessage("${file.name} raw").that(ours.image()).isEqualTo(theirs.image())
            files++
        }
        println("DngWriterParity: $files files, $tagsCompared tags identical, raw identical")
    }

    @Test
    fun `the lens shading opcodes are rebuilt byte for byte from the shading they carry`() {
        for (file in fixtures().take(MAX_DECODED)) {
            val theirs = TiffEntries.read(file.readBytes())
            val dng = DngFile.read(file)
            val rebuilt = DngOpcodes.gainMaps(dng.shading!!, dng.sensor.cfaPattern, dng.frame.width, dng.frame.height)
            assertWithMessage(file.name).that(rebuilt).isEqualTo(theirs[51009]!!.value)
        }
    }

    /**
     * The warp, from a lens model that reproduces DngCreator's coefficients
     * before its zoom: a focal length equal to the centre-to-corner distance
     * (so `m = 1`) and `k_i = kr_i / kr0`. What is left to test is the zoom
     * and the encoding.
     */
    @Test
    fun `the warp's zoom is DngCreator's or one step larger`() {
        var same = 0
        var oneStep = 0
        for (file in fixtures()) {
            val theirs = TiffEntries.read(file.readBytes())
            val op = theirs[51022]!!.value
            val b = wrap(op).order(ByteOrder.BIG_ENDIAN)
            assertThat(b.getInt(0)).isEqualTo(1)
            assertThat(b.getInt(4)).isEqualTo(1) // WarpRectilinear
            val v = DoubleArray(8) { b.getDouble(24 + 8 * it) }
            val w = theirs.ints(256)!![0].toInt(); val h = theirs.ints(257)!![0].toInt()
            val cx = (v[6] * w).toFloat(); val cy = (v[7] * h).toFloat()
            val d = maxOf(maxOf(Math.hypot(cx.toDouble(), cy.toDouble()), Math.hypot(w - cx.toDouble(), cy.toDouble())),
                maxOf(Math.hypot(cx.toDouble(), h - cy.toDouble()), Math.hypot(w - cx.toDouble(), h - cy.toDouble())))
            val k = floatArrayOf((v[1] / v[0]).toFloat(), (v[2] / v[0]).toFloat(), (v[3] / v[0]).toFloat(), 0f, 0f)
            val ours = DngOpcodes.warpCoefficients(k, d.toFloat(), cx, cy, w, h)!!
            val step = ours[0] - v[0]
            assertWithMessage(file.name).that(step).isAtLeast(-1e-6)
            assertWithMessage(file.name).that(step).isAtMost(0.002 + 1e-6)
            if (abs(step) < 1e-6) same++ else oneStep++
            for (i in 1..3) assertThat(ours[i] / ours[0]).isWithin(1e-5 * maxOf(1.0, abs(v[i] / v[0]))).of(v[i] / v[0])
            assertThat(ours[6]).isWithin(1e-6).of(v[6])
            assertThat(ours[7]).isWithin(1e-6).of(v[7])
            if (abs(step) < 1e-6) {
                // The same zoom: then the opcode list is DngCreator's to the float the centre was rounded to.
                val list = DngOpcodes.warpRectilinear(ours)
                assertThat(list.size).isEqualTo(op.size)
                assertThat(list.copyOfRange(0, 24)).isEqualTo(op.copyOfRange(0, 24))
            }
        }
        println("DngWriterParity warp: zoom identical on $same, one step larger on $oneStep")
    }

    private companion object {
        /** DngFile decodes the whole raw; a handful prove the shading encoding. */
        const val MAX_DECODED = 12
    }
}
