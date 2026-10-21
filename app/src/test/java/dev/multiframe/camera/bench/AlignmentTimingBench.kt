package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Properties
import kotlin.math.max

/**
 * Host wall time of `Aligner.alignNative` over archived real bursts, for an A/B
 * of two builds of the native library.
 *
 * One JVM loads one library, so a comparison is a sequence of runs, each with
 * `-Pmf.host.lib` naming one build and `-Pmf.align.label` saying which: run
 * them interleaved (A B A B ...) and judge B against A only once the A runs
 * fail to separate from each other. The proxies are built before the clock
 * starts; every alternate of every burst is aligned onto its newest frame, as
 * `ZslCapture` does, once to warm up and then [REPS] times.
 *
 * ARM64 macOS timings are not phone timings; the ratio between builds is the
 * evidence, not the milliseconds.
 *
 * `-Pmf.align.timing=<dir of replay.properties> -Pmf.align.label=<name>
 * -Pmf.sr.out=<dir> -Pmf.host.lib=<dir>`
 */
class AlignmentTimingBench {

    @Test fun timeAlignment() {
        val root = System.getProperty("mf.align.timing")?.let(::File)
        assumeTrue("requires replay.properties from prepare_replay.py", root?.isDirectory == true)
        assertTrue("build tools/iq_forensics/build_host.py and set mf.host.lib", NativeMerge.isAvailable())
        val label = System.getProperty("mf.align.label") ?: "unlabelled"
        val out = File(requireNotNull(System.getProperty("mf.sr.out")), "timing").apply { mkdirs() }

        val bursts = root!!.walkTopDown().filter { it.name == "replay.properties" }.sortedBy { it.path }.map { load(it) }.toList()
        require(bursts.isNotEmpty())
        fun once(): Long {
            val t = System.nanoTime()
            for (b in bursts) for (alt in b.alts) requireNotNull(Aligner.alignNative(b.ref, alt, b.tilesX, b.tilesY))
            return System.nanoTime() - t
        }
        once()
        val reps = LongArray(REPS) { once() }
        val pairs = bursts.sumOf { it.alts.size }
        File(out, "timing-$label-${System.currentTimeMillis()}.txt").writeText(
            "label=$label\npairs=$pairs\nlib=${System.getProperty("java.library.path")}\n" +
                "repsMs=${reps.joinToString(",") { "%.3f".format(it / 1e6) }}\n")
        println("$label: $pairs alignments, per rep ms ${reps.map { "%.1f".format(it / 1e6) }}")
    }

    private class Burst(val ref: Plane, val alts: List<Plane>, val tilesX: Int, val tilesY: Int)

    private fun load(input: File): Burst {
        val p = Properties().apply { input.inputStream().use { load(it) } }
        fun ints(key: String) = p.getProperty(key).split(',').map(String::toInt).toIntArray()
        val w = p.getProperty("width").toInt(); val h = p.getProperty("height").toInt()
        val n = p.getProperty("frames").toInt()
        val profile = SensorProfile(ints("black"), p.getProperty("white").toInt(), ints("cfa"))
        val light = DoubleArray(n) { p.getProperty("frame.$it.light").toDouble() }
        val ref = n - 1
        return requireNotNull(NativeMerge.create(w, h, profile)).use { m ->
            fun proxy(i: Int): Plane = RandomAccessFile(p.getProperty("frame.$i.path"), "r").use { file ->
                val mapped = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                val buffer = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN).apply { put(mapped); rewind() }
                m.lumaProxy(buffer, w * 2, (light[i] / light[ref]).toFloat())
            }
            Burst(proxy(ref), (0 until n).filter { it != ref }.map(::proxy),
                max(1, m.proxyWidth / 32), max(1, m.proxyHeight / 32))
        }
    }

    private companion object {
        const val REPS = 5
    }
}
