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
import kotlin.math.floor
import kotlin.math.max

/**
 * Whether the 2x canvas recovers detail beyond enlargement, measured on the desk.
 *
 * Runs bursts through the unchanged production path compiled for the host
 * (tools/iq_forensics/build_host.py): the proxy, `Aligner.alignNative`, the
 * native Bayer merge and `NativeSubPixel`, with the fields borrowed for flat
 * tiles exactly as `ZslCapture` borrows them. It writes linear outputs only --
 * the canvas in sensor units, the 1x merge and the single reference frame
 * through the production demosaic -- and leaves enlargement, metrics, crops
 * and the A/A statistics to tools/iq_forensics/sr_evidence_analyze.py, so the
 * comparison never depends on a develop the two paths do not share.
 *
 * Two inputs, both opt-in, neither in the default suite:
 *
 * - `-Pmf.sr.synthetic=<dir>` from sr_evidence_targets.py: known chart, known
 *   shifts, known noise. Writes the estimated-alignment and known-alignment
 *   variants of the merge and the canvas, so alignment and reconstruction
 *   are scored apart, and disjoint half-burst runs for the coherence measure.
 * - `-Pmf.sr.real=<dir>` of replay.properties (prepare_replay.py): archived
 *   Pixel bursts. Reference is the newest frame, as in production. Writes
 *   inspection windows (`-Pmf.sr.rois=x,y,w,h;...`, sensor pixels) of the full
 *   burst and of two disjoint halves -- the halves are the A/A null.
 *
 * Both write to `-Pmf.sr.out`. The canvas is forced even where the coverage
 * gate would decline it -- the gate is recorded, not obeyed -- because a
 * false accept or reject is part of what is being measured. Production never
 * calls this.
 */
class SuperResEvidenceBench {

    private class Burst(
        val width: Int,
        val height: Int,
        val profile: SensorProfile,
        val frames: List<ByteBuffer>,
        /** Light per frame, ISO x exposure; ratios are the gain scales. */
        val light: DoubleArray,
    ) {
        val stride get() = width * 2
        val tilesX get() = max(1, (width / 2) / SubPixelAccumulator.TILE_TARGET)
        val tilesY get() = max(1, (height / 2) / SubPixelAccumulator.TILE_TARGET)
        val black get() = (0 until 4).map { profile.blackAt(it and 1, it shr 1) }.average().toFloat()
        val range get() = profile.range.toFloat()
        fun gain(i: Int, ref: Int) = (light[i] / light[ref]).toFloat()
        fun shorts(buffer: ByteBuffer) = ShortArray(width * height).also {
            buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
    }

    /** A rectangle in sensor pixels, written with [margin] pixels of context. */
    private data class Roi(val x: Int, val y: Int, val w: Int, val h: Int, val margin: Int) {
        val x0 get() = x - margin
        val y0 get() = y - margin
        val cw get() = w + 2 * margin
        val ch get() = h + 2 * margin
    }

    private class Canvas(val rgb: ShortArray, val width: Int, val height: Int, val holes: FloatArray,
                         val acceptable: Boolean, val contribution: Float)

    @Test fun synthetic() {
        val root = System.getProperty("mf.sr.synthetic")?.let(::File)
        assumeTrue("requires sr_evidence_targets.py output", root?.isDirectory == true)
        assertTrue("build tools/iq_forensics/build_host.py and set mf.host.lib", NativeMerge.isAvailable())
        val out = File(requireNotNull(System.getProperty("mf.sr.out")), "synthetic").apply { mkdirs() }
        val inputs = File(root, "bursts").listFiles()!!.filter { File(it, "synthetic.properties").isFile }
            .sortedBy { it.name }
        val only = System.getProperty("mf.sr.only")
        for (input in inputs) if (only == null || input.name.contains(only)) {
            runSynthetic(input, File(out, input.name).apply { mkdirs() })
        }
    }

    @Test fun real() {
        val root = System.getProperty("mf.sr.real")?.let(::File)
        assumeTrue("requires replay.properties from prepare_replay.py", root?.isDirectory == true)
        assertTrue("build tools/iq_forensics/build_host.py and set mf.host.lib", NativeMerge.isAvailable())
        val out = File(requireNotNull(System.getProperty("mf.sr.out")), "real").apply { mkdirs() }
        val inputs = root!!.walkTopDown().filter { it.name == "replay.properties" }.sortedBy { it.path }.toList()
        require(inputs.isNotEmpty())
        inputs.forEachIndexed { i, input ->
            // Archive and shot: two archives both have a regime0-repeat0.
            val shot = requireNotNull(input.parentFile)
            runReal(input, File(out, "${shot.parentFile.name}-${shot.name}").apply { mkdirs() }, determinism = i == 0)
        }
    }

    private fun runSynthetic(input: File, out: File) {
        val p = Properties().apply { File(input, "synthetic.properties").inputStream().use { load(it) } }
        val w = p.getProperty("width").toInt(); val h = p.getProperty("height").toInt()
        val n = p.getProperty("frames").toInt()
        val black = p.getProperty("black").toInt()
        val profile = SensorProfile(IntArray(4) { black }, p.getProperty("white").toInt(),
            p.getProperty("cfa").split(',').map(String::toInt).toIntArray())
        val frames = List(n) { i ->
            val bytes = File(input, "frame-%02d.raw16".format(i)).readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes); rewind() }
        }
        val burst = Burst(w, h, profile, frames, DoubleArray(n) { 1.0 })
        val full = Roi(0, 0, w, h, 0)
        val all = (1 until n).toList()
        val summary = StringBuilder()

        val estimated = align(burst, 0, all)
        val known = all.map { i ->
            val dx = p.getProperty("frame.$i.dxSensor").toFloat() / 2
            val dy = p.getProperty("frame.$i.dySensor").toFloat() / 2
            val ix = floor(dx + .5f).toInt(); val iy = floor(dy + .5f).toInt()
            val tiles = burst.tilesX * burst.tilesY
            AlignmentField(burst.tilesX, burst.tilesY, IntArray(tiles) { ix }, IntArray(tiles) { iy },
                FloatArray(tiles) { dx - ix }, FloatArray(tiles) { dy - iy })
        }
        File(out, "alignment.csv").bufferedWriter().use { csv ->
            csv.appendLine("frame,tile,tilesX,dxSensorEstimated,dySensorEstimated,dxSensorTrue,dySensorTrue,fitted")
            for ((k, i) in all.withIndex()) {
                val f = estimated[k].withFlatTilesBorrowed()
                for (t in f.dx.indices) csv.appendLine("$i,$t,${f.tilesX},${2 * (f.dx[t] + f.fracDx[t])}," +
                    "${2 * (f.dy[t] + f.fracDy[t])},${p.getProperty("frame.$i.dxSensor")},${p.getProperty("frame.$i.dySensor")}," +
                    "${estimated[k].hasSubPixelAt(t)}")
            }
        }

        writeDemosaic(File(out, "single.f32"), burst, burst.shorts(frames[0]), full)
        writeDemosaic(File(out, "merge-est.f32"), burst, burst.shorts(merge(burst, 0, all, estimated)), full)
        writeDemosaic(File(out, "merge-known.f32"), burst, burst.shorts(merge(burst, 0, all, known)), full)
        // Diagnostic: the estimate, with only the tiles it got wrong by more
        // than half a sensor pixel replaced by the truth. The distance from
        // here to sr-est is what gross (integer) errors cost; from here to
        // sr-known, what the fractions still cost.
        val borrowedEstimate = estimated.map { it.withFlatTilesBorrowed() }
        val grossOracle = borrowedEstimate.mapIndexed { k, f ->
            val t = known[k]
            val bad = f.dx.indices.filter { i ->
                kotlin.math.hypot(2.0 * (f.dx[i] + f.fracDx[i] - t.dx[i] - t.fracDx[i]),
                    2.0 * (f.dy[i] + f.fracDy[i] - t.dy[i] - t.fracDy[i])) > 0.5
            }.toSet()
            AlignmentField(f.tilesX, f.tilesY, IntArray(f.dx.size) { if (it in bad) t.dx[it] else f.dx[it] },
                IntArray(f.dy.size) { if (it in bad) t.dy[it] else f.dy[it] },
                FloatArray(f.dx.size) { if (it in bad) t.fracDx[it] else f.fracDx[it] },
                FloatArray(f.dy.size) { if (it in bad) t.fracDy[it] else f.fracDy[it] })
        }
        for ((name, fields) in listOf("sr-est" to borrowedEstimate, "sr-known" to known, "sr-gross-oracle" to grossOracle)) {
            val prediction = SubPixelCoverage.predict(fields, profile.cfaPattern)
            val canvas = superRes(burst, 0, all, fields)
            writeCanvas(File(out, "$name.f32"), burst, canvas, full)
            summary.appendLine("$name.preGate=${prediction.worthAttempting}")
            summary.appendLine("$name.predictedHoles=${prediction.perChannel.joinToString()}")
            summary.appendLine("$name.measuredHoles=${canvas.holes.joinToString()}")
            summary.appendLine("$name.postGate=${canvas.acceptable}")
            summary.appendLine("$name.meanContribution=${canvas.contribution}")
            if (name == "sr-est") {
                // Determinism: the same input twice must give the same canvas,
                // or no difference this bench reports means anything.
                val again = superRes(burst, 0, all, fields)
                summary.appendLine("$name.repeatIdentical=${again.rgb.contentEquals(canvas.rgb)}")
            }
        }
        // Disjoint halves, each with its own reference and its own alignment:
        // independent noise, independent sampling phases, for the coherence
        // measure the real bursts depend on. Only where there are frames to halve.
        if (n >= 16 && p.getProperty("noise") != "none") writeHalves(burst, out, full, refA = 0, refB = 1)
        File(out, "summary.properties").writeText(summary.toString())
        println("${input.name}: ${summary.lines().filter { "Gate" in it || "repeat" in it }.joinToString(" ")}")
    }

    private fun runReal(input: File, out: File, determinism: Boolean) {
        val p = Properties().apply { input.inputStream().use { load(it) } }
        fun ints(key: String) = p.getProperty(key).split(',').map(String::toInt).toIntArray()
        fun floats(key: String) = p.getProperty(key).split(',').map(String::toFloat).toFloatArray()
        val w = p.getProperty("width").toInt(); val h = p.getProperty("height").toInt()
        val n = p.getProperty("frames").toInt()
        val profile = SensorProfile(ints("black"), p.getProperty("white").toInt(), ints("cfa"))
        val frames = (0 until n).map { i ->
            RandomAccessFile(p.getProperty("frame.$i.path"), "r").use { file ->
                require(file.length() == w * h * 2L)
                // Direct and owned: the production calls require direct buffers.
                val mapped = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN).apply { put(mapped); rewind() }
            }
        }
        val burst = Burst(w, h, profile, frames, DoubleArray(n) { p.getProperty("frame.$it.light").toDouble() })
        val rois = (System.getProperty("mf.sr.rois")?.split(';')?.map { s ->
            val v = s.split(',').map(String::toInt); Roi(v[0], v[1], v[2], v[3], MARGIN)
        } ?: defaultRois(w, h))

        // Colour for display only: the analysis applies WB and this matrix to
        // every method alike. Same solve as BurstReplayBench.
        val calibrationBytes = File(p.getProperty("calibration")).readBytes()
        val illuminants = requireNotNull(DngMetadata.readIlluminants(calibrationBytes))
        val calibration = requireNotNull(DngMetadata.readCalibration(calibrationBytes)).copy(
            illuminant1Kelvin = ColorScience.illuminantKelvin(illuminants.first),
            illuminant2Kelvin = ColorScience.illuminantKelvin(illuminants.second))
        val neutral = floats("neutral"); val wb = floats("wb")
        val matrix = ColorScience.balancedCameraToSrgb(calibration, neutral, ColorScience.solveBlend(calibration, neutral), wb)

        val ref = n - 1
        val all = (0 until n).filter { it != ref }
        val fields = align(burst, ref, all)
        val borrowed = fields.map { it.withFlatTilesBorrowed() }
        val prediction = SubPixelCoverage.predict(borrowed, profile.cfaPattern)
        val summary = StringBuilder()
        summary.appendLine("iso=${p.getProperty("iso")}")
        summary.appendLine("frames=$n")
        summary.appendLine("reference=$ref")
        summary.appendLine("wb=${wb.joinToString()}")
        summary.appendLine("matrix=${matrix.m.joinToString()}")
        summary.appendLine("rois=${rois.joinToString(";") { "${it.x},${it.y},${it.w},${it.h}" }}")
        summary.appendLine("margin=$MARGIN")
        summary.appendLine("sr.preGate=${prediction.worthAttempting}")
        summary.appendLine("sr.predictedHoles=${prediction.perChannel.joinToString()}")
        File(out, "alignment.csv").bufferedWriter().use { csv ->
            csv.appendLine("frame,tile,tilesX,dxSensor,dySensor,fitted")
            for ((k, i) in all.withIndex()) for (t in borrowed[k].dx.indices) {
                val f = borrowed[k]
                csv.appendLine("$i,$t,${f.tilesX},${2 * (f.dx[t] + f.fracDx[t])},${2 * (f.dy[t] + f.fracDy[t])},${fields[k].hasSubPixelAt(t)}")
            }
        }

        val single = burst.shorts(frames[ref])
        val merged = burst.shorts(merge(burst, ref, all, fields))
        val canvas = superRes(burst, ref, all, borrowed)
        summary.appendLine("sr.measuredHoles=${canvas.holes.joinToString()}")
        summary.appendLine("sr.postGate=${canvas.acceptable}")
        summary.appendLine("sr.meanContribution=${canvas.contribution}")
        if (determinism) summary.appendLine("sr.repeatIdentical=${superRes(burst, ref, all, borrowed).rgb.contentEquals(canvas.rgb)}")
        rois.forEachIndexed { r, roi ->
            writeDemosaic(File(out, "roi$r-single.f32"), burst, single, roi)
            writeDemosaic(File(out, "roi$r-merge.f32"), burst, merged, roi)
            writeCanvas(File(out, "roi$r-sr.f32"), burst, canvas, roi)
        }
        // The A/A null: the same method on two disjoint halves of the burst,
        // each half's reference its own newest frame.
        val a = (0 until n).filter { (ref - it) % 2 == 0 }
        val b = (0 until n).filter { (ref - it) % 2 == 1 }
        for ((tag, members) in listOf("A" to a, "B" to b)) {
            val r = members.last(); val alts = members.filter { it != r }
            val f = align(burst, r, alts)
            val m = burst.shorts(merge(burst, r, alts, f))
            val c = superRes(burst, r, alts, f.map { it.withFlatTilesBorrowed() })
            summary.appendLine("half$tag.frames=${members.joinToString()}")
            summary.appendLine("half$tag.measuredHoles=${c.holes.joinToString()}")
            summary.appendLine("half$tag.postGate=${c.acceptable}")
            rois.forEachIndexed { k, roi ->
                writeDemosaic(File(out, "roi$k-merge$tag.f32"), burst, m, roi)
                writeCanvas(File(out, "roi$k-sr$tag.f32"), burst, c, roi)
            }
        }
        File(out, "summary.properties").writeText(summary.toString())
        println("${input.parentFile.name}: iso ${p.getProperty("iso")} preGate ${prediction.worthAttempting} postGate ${canvas.acceptable}")
    }

    private fun writeHalves(burst: Burst, out: File, roi: Roi, refA: Int, refB: Int) {
        val n = burst.frames.size
        for ((tag, r) in listOf("A" to refA, "B" to refB)) {
            val alts = (0 until n).filter { it % 2 == r % 2 && it != r }
            val f = align(burst, r, alts)
            writeDemosaic(File(out, "merge-est$tag.f32"), burst, burst.shorts(merge(burst, r, alts, f)), roi)
            writeCanvas(File(out, "sr-est$tag.f32"), burst, superRes(burst, r, alts, f.map { it.withFlatTilesBorrowed() }), roi)
        }
    }

    /** The production alignment: luma proxy, native tile search, tiles of 32 proxy pixels. */
    private fun align(burst: Burst, ref: Int, alts: List<Int>): List<AlignmentField> =
        requireNotNull(NativeMerge.create(burst.width, burst.height, burst.profile)).use { m ->
            val reference = m.lumaProxy(burst.frames[ref], burst.stride)
            alts.map { i ->
                requireNotNull(Aligner.alignNative(reference, m.lumaProxy(burst.frames[i], burst.stride, burst.gain(i, ref)),
                    burst.tilesX, burst.tilesY))
            }
        }

    private fun merge(burst: Burst, ref: Int, alts: List<Int>, fields: List<AlignmentField>): ByteBuffer =
        requireNotNull(NativeMerge.create(burst.width, burst.height, burst.profile)).use { m ->
            m.setReference(burst.frames[ref], burst.stride)
            alts.forEachIndexed { k, i -> m.addFrame(burst.frames[i], burst.stride, fields[k], burst.gain(i, ref)) }
            m.finish().first
        }

    private fun superRes(burst: Burst, ref: Int, alts: List<Int>, fields: List<AlignmentField>): Canvas =
        requireNotNull(NativeSubPixel.create(burst.width, burst.height, burst.profile)).use { sr ->
            assertTrue(sr.setReference(burst.frames[ref], burst.stride))
            alts.forEachIndexed { k, i -> assertTrue(sr.addFrame(burst.frames[i], burst.stride, fields[k], burst.gain(i, ref))) }
            val result = requireNotNull(sr.finish())
            val rgb = ShortArray(result.width * result.height * 3)
            result.rgb16.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(rgb)
            Canvas(rgb, result.width, result.height, result.interiorHolesPerChannel, result.acceptable, result.meanContribution)
        }

    /** The production demosaic, neutral colour: (raw - black) / range per channel, [roi] with its margin. */
    private fun writeDemosaic(file: File, burst: Burst, raw: ShortArray, roi: Roi) {
        val frame = BayerFrame(burst.width, burst.height, raw)
        val values = FloatArray(roi.cw * roi.ch * 3); val pixel = FloatArray(3)
        for (y in 0 until roi.ch) for (x in 0 until roi.cw) {
            Demosaic.pixel(frame, burst.profile, ColorProfile.NEUTRAL, roi.x0 + x, roi.y0 + y, pixel)
            pixel.copyInto(values, (y * roi.cw + x) * 3)
        }
        writeFloats(file, values)
    }

    /** The canvas over the same physical window as [roi]: cells 2*x0 until 2*(x0+cw), on the same scale. */
    private fun writeCanvas(file: File, burst: Burst, canvas: Canvas, roi: Roi) {
        val cw = 2 * roi.cw; val ch = 2 * roi.ch
        val values = FloatArray(cw * ch * 3)
        val black = burst.black; val range = burst.range
        for (y in 0 until ch) for (x in 0 until cw) for (c in 0 until 3) {
            val v = canvas.rgb[((2 * roi.y0 + y) * canvas.width + 2 * roi.x0 + x) * 3 + c].toInt() and 0xFFFF
            values[(y * cw + x) * 3 + c] = ((v - black) / range).coerceAtLeast(0f)
        }
        writeFloats(file, values)
    }

    private fun defaultRois(w: Int, h: Int): List<Roi> {
        val size = 320
        val xs = (0 until 4).map { 240 + it * (w - 480 - size) / 3 }
        val ys = (0 until 3).map { 300 + it * (h - 600 - size) / 2 }
        return ys.flatMap { y -> xs.map { x -> Roi(x, y, size, size, MARGIN) } }
    }

    private fun writeFloats(file: File, values: FloatArray) {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        file.outputStream().use { it.write(bytes.array()) }
    }

    private companion object {
        /** Context around each window, sensor pixels: the Lanczos-3 support and then some. */
        const val MARGIN = 8
    }
}
