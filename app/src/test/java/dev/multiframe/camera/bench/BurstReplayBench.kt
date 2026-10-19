package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/** Opt-in replay of frozen real RAW buffers through the unchanged ARM64 production JNI.
 * Host latency is diagnostic only. No arbitrary-photo MTF or spatial-variance SNR claims.
 */
class BurstReplayBench {
    private external fun readWeights(handle: Long, output: FloatArray)
    private external fun readResolved(handle: Long, output: FloatArray)

    @Test fun replayFrozenBursts() {
        val root = System.getProperty("mf.burst.dir")?.let(::File)
        assumeTrue("requires a frozen RAW archive", root?.isDirectory == true)
        assertTrue("build tools/iq_forensics/build_host.py and set mf.host.lib", NativeMerge.isAvailable())
        val output = File(requireNotNull(System.getProperty("mf.burst.out"))).apply { mkdirs() }
        val inputs = root!!.walkTopDown().filter { it.name == "replay.properties" }.sortedBy { it.path }.toList()
        require(inputs.isNotEmpty())
        for (input in inputs) replay(input, File(output, requireNotNull(input.parentFile).name).apply { mkdirs() })
    }

    private fun replay(input: File, out: File) {
        val p = Properties().apply { input.inputStream().use { load(it) } }
        fun ints(key: String) = p.getProperty(key).split(',').map(String::toInt).toIntArray()
        fun floats(key: String) = p.getProperty(key).split(',').map(String::toFloat).toFloatArray()
        val width = p.getProperty("width").toInt(); val height = p.getProperty("height").toInt()
        val n = width * height; val count = p.getProperty("frames").toInt()
        val profile = SensorProfile(ints("black"), p.getProperty("white").toInt(), ints("cfa"))
        val metadataNoise = System.getProperty("mf.burst.noise") == "metadata"
        val noiseModels = if (metadataNoise) List(count) { i -> requireNotNull(RawNoiseModel.fromNormalized(
            p.getProperty("frame.$i.noiseProfile").split(',').map(String::toDouble).toDoubleArray(),
            floats("frame.$i.black"), p.getProperty("frame.$i.white").toInt())) } else List(count) { null }
        val buffers = (0 until count).map { i ->
            RandomAccessFile(p.getProperty("frame.$i.path"), "r").use { file ->
                require(file.length() == n * 2L)
                file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        val calibrationBytes = File(p.getProperty("calibration")).readBytes()
        val illuminants = requireNotNull(DngMetadata.readIlluminants(calibrationBytes))
        val calibration = requireNotNull(DngMetadata.readCalibration(calibrationBytes)).copy(
            illuminant1Kelvin = ColorScience.illuminantKelvin(illuminants.first),
            illuminant2Kelvin = ColorScience.illuminantKelvin(illuminants.second))
        val neutral = floats("neutral"); val wb = floats("wb")
        val matrix = ColorScience.balancedCameraToSrgb(calibration, neutral,
            ColorScience.solveBlend(calibration, neutral), wb)
        val color = ColorProfile(wb, matrix.m)
        val columns = p.getProperty("shadingColumns").toInt(); val rows = p.getProperty("shadingRows").toInt()
        val rggb = floats("shadingRggb")
        // Freeze the observed baseline bug and the metadata-defined candidate separately.
        // Camera2 specifies R/Geven/Godd/B, while the renderer consumes CFA-site order.
        val legacyShading = ShadingMap(columns, rows, rggb)
        val siteShading = ShadingMap(columns, rows, FloatArray(rggb.size) { i ->
            val site = i % 4
            val channel = when (profile.cfaPattern[site]) { 0 -> 0; 2 -> 3; else -> if (site < 2) 1 else 2 }
            rggb[i / 4 * 4 + channel]
        })
        val light = DoubleArray(count) { p.getProperty("frame.$it.light").toDouble() }
        val baselineLight = DoubleArray(count) { p.getProperty("frame.$it.baselineLight", light[it].toString()).toDouble() }
        val fields = ArrayList<AlignmentField>()
        val gains = FloatArray(count) { (light[it] / light[0]).toFloat() }
        val start = System.nanoTime()
        val merged: ByteBuffer
        var rounded: ByteBuffer? = null
        val stats: BayerMergeStats
        requireNotNull(NativeMerge.create(width, height, profile)).use { merger ->
            val handle = NativeMerge::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(merger)
            merger.setReference(buffers[0], width * 2, noiseModels[0])
            val ref = merger.lumaProxy(buffers[0], width * 2)
            var previous = FloatArray(n); var current = FloatArray(n)
            readWeights(handle, previous)
            val squares = FloatArray(n) { previous[it] * previous[it] }
            File(out, "alignment.csv").bufferedWriter().use { alignment ->
                alignment.appendLine("frame,tile,tilesX,tilesY,dxProxy,dyProxy,fracDxProxy,fracDyProxy")
                File(out, "residuals.csv").bufferedWriter().use { residuals ->
                    residuals.appendLine("frame,integerMaeProxyCodes,fractionalMaeProxyCodes,samples")
                    for (i in 1 until count) {
                        val proxy = merger.lumaProxy(buffers[i], width * 2, gains[i])
                        val field = requireNotNull(Aligner.alignNative(ref, proxy,
                            max(1, ref.width / 32), max(1, ref.height / 32)))
                        fields.add(field)
                        for (t in field.dx.indices) alignment.appendLine(
                            "$i,$t,${field.tilesX},${field.tilesY},${field.dx[t]},${field.dy[t]},${field.fracDx[t]},${field.fracDy[t]}")
                        residuals.appendLine("$i,${residual(ref, proxy, field).joinToString(",")}")
                        merger.addFrame(buffers[i], width * 2, field, gains[i], noiseModels[i])
                        readWeights(handle, current)
                        for (j in 0 until n) { val delta = current[j] - previous[j]; squares[j] += delta * delta }
                        val swap = previous; previous = current; current = swap
                    }
                }
            }
            if (System.getProperty("mf.burst.quant") == "true") {
                readResolved(handle, current)
                writeFloats(File(out, "prequant.f32"), current)
                rounded = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN).also { target ->
                    for (v in current) target.putShort(kotlin.math.floor(v + 0.5f).toInt().coerceIn(0, profile.whiteLevel).toShort())
                    target.rewind()
                }
                writeBuffer(File(out, "nearest.raw16"), rounded!!)
            }
            val result = merger.finish(); merged = result.first; stats = result.second
            writeFloats(File(out, "sum-weights.f32"), previous)
            writeFloats(File(out, "sum-squared-weights.f32"), squares)
            for (j in 0 until n) current[j] = previous[j] * previous[j] / squares[j].coerceAtLeast(1e-20f)
            writeFloats(File(out, "effective-frames.f32"), current)
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        writeBuffer(File(out, "native-exact.raw16"), merged)
        File(out, "summary.txt").writeText("frames=$count\nmetadataNoise=$metadataNoise\nmeanWeight=${stats.meanContribution}\nsigmaMidDn=${stats.estimatedSigmaAtMid}\nhostMergeWithDiagnosticsMs=$millis\n")
        if (!baselineLight.contentEquals(light)) {
            requireNotNull(NativeMerge.create(width, height, profile)).use { m ->
                m.setReference(buffers[0], width * 2)
                val ref = m.lumaProxy(buffers[0], width * 2)
                for (i in 1 until count) {
                    val gain = (baselineLight[i] / baselineLight[0]).toFloat()
                    val alt = m.lumaProxy(buffers[i], width * 2, gain)
                    val field = requireNotNull(Aligner.alignNative(ref, alt, max(1, ref.width / 32), max(1, ref.height / 32)))
                    m.addFrame(buffers[i], width * 2, field, gain)
                }
                writeBuffer(File(out, "native-baseline-neighbour.raw16"), m.finish().first)
            }
        }
        val params = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON)
            .adaptedTo(p.getProperty("iso").toInt()).copy(meteredGain = 1f)
        fun render(buffer: ByteBuffer, name: String, shading: ShadingMap, dump: Boolean = false) {
            val raw = ShortArray(n); buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw)
            val frame = BayerFrame(width, height, raw)
            val observer = if (dump) DevelopStageDumps(File(out, "$name-stages"), width, height).also { it.raw(frame) } else null
            val rgb = RawDeveloper.develop(frame, profile, color, params, shading, observer = observer)
            observer?.finish()
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, width, height, rgb, 0, width)
            ImageIO.write(image, "jpg", File(out, "$name.jpg"))
        }
        if (rounded == null && !metadataNoise) {
            render(buffers[0], "single-legacy-shading", legacyShading)
            render(merged, "merged-legacy-shading", legacyShading, true)
        }
        render(merged, "merged-site-shading", siteShading, true)
        rounded?.let { render(it, "nearest-site-shading", siteShading, true) }
        val borrowed = fields.map { it.withFlatTilesBorrowed() }
        val prediction = SubPixelCoverage.predict(borrowed, profile.cfaPattern)
        File(out, "phase.txt").writeText("predictedHolesRGB=${prediction.perChannel.joinToString()}\nworthAttempting=${prediction.worthAttempting}\n")
        if (System.getProperty("mf.burst.sr") == "true" && prediction.worthAttempting) {
            requireNotNull(NativeSubPixel.create(width, height, profile)).use { sr ->
                assertTrue(sr.setReference(buffers[0], width * 2))
                for (i in 1 until count) assertTrue(sr.addFrame(buffers[i], width * 2, borrowed[i - 1], gains[i]))
                val result = requireNotNull(sr.finish())
                writeBuffer(File(out, "superres.rgb16"), result.rgb16)
                // Full-frame reference develop before any inspection crop.
                // This host-only diagnostic JPEG precedes optics; ImageIO is
                // not the production libjpeg-turbo encoder.
                val canvas = ShortArray(result.width * result.height * 3)
                result.rgb16.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(canvas)
                val costs = File(out, "superres-host-render-cost.csv")
                costs.writeText("variant,chromaRadius,canvasScale,developMillis,encodeMillis,totalMillis,heapBeforeBytes,heapAfterDevelopBytes,heapAfterImageBytes,heapAfterEncodeBytes\n")
                val canvasScale = result.width / width
                val sourceChecksum = canvas.contentHashCode()
                fun renderCanvas(name: String, rendering: DevelopParams) {
                    val runtime = Runtime.getRuntime()
                    fun heapUsed() = runtime.totalMemory() - runtime.freeMemory()
                    val before = heapUsed()
                    val tDevelop = System.nanoTime()
                    val display = LinearDeveloper.develop(canvas, result.width, result.height,
                        profile, color, rendering, siteShading)
                    val developMillis = (System.nanoTime() - tDevelop) / 1_000_000.0
                    val afterDevelop = heapUsed()
                    val tEncode = System.nanoTime()
                    val image = BufferedImage(result.width, result.height, BufferedImage.TYPE_INT_RGB)
                    image.setRGB(0, 0, result.width, result.height, display, 0, result.width)
                    val afterImage = heapUsed()
                    assertTrue(ImageIO.write(image, "jpg", File(out, "$name.jpg")))
                    val encodeMillis = (System.nanoTime() - tEncode) / 1_000_000.0
                    costs.appendText("$name,${rendering.chroma.radius},$canvasScale,$developMillis,$encodeMillis,${developMillis + encodeMillis},$before,$afterDevelop,$afterImage,${heapUsed()}\n")
                    assertTrue("reference develop must leave the shared RAW canvas unchanged",
                        sourceChecksum == canvas.contentHashCode())
                }
                // Separate scopes release the display/image arrays before the
                // next full-frame render; source canvas and other params agree.
                renderCanvas("superres-host-reference", params)
                val tGc = System.nanoTime()
                System.gc()
                File(out, "superres-host-render-memory.txt").writeText(
                    "HOST ONLY: JVM heap snapshots are not peak RSS. Sequential rendering GC ${(System.nanoTime() - tGc) / 1_000_000.0}ms; shared canvas checksum $sourceChecksum.\n",
                )
                renderCanvas("superres-host-chroma-scaled", params.copy(
                    chroma = params.chroma.copy(radius = params.chroma.radius * canvasScale),
                ))
                File(out, "phase.txt").appendText("actualHolesRGB=${result.interiorHolesPerChannel.joinToString()}\naccepted=${result.acceptable}\n")
            }
        }
        input.copyTo(File(out, "replay.properties"), overwrite = true)
        println("${requireNotNull(input.parentFile).name}: $count frames, mean weight ${stats.meanContribution}, sigma ${stats.estimatedSigmaAtMid}; host diagnostics ${millis}ms; SR ${prediction.worthAttempting}")
    }

    /** Independent residual sampling, proxy codes, not optical resolution or noise. */
    private fun residual(ref: Plane, alt: Plane, f: AlignmentField): List<Double> {
        var integer = 0.0; var fractional = 0.0; var count = 0
        val tileW = ref.width / f.tilesX; val tileH = ref.height / f.tilesY
        fun at(x: Int, y: Int) = alt.data[y * alt.width + x].toInt() and 255
        for (y in 8 until ref.height - 8 step 7) for (x in 8 until ref.width - 8 step 7) {
            val t = minOf(y / tileH, f.tilesY - 1) * f.tilesX + minOf(x / tileW, f.tilesX - 1)
            val ax = x + f.dx[t]; val ay = y + f.dy[t]
            val fx = ax + f.fracDx[t]; val fy = ay + f.fracDy[t]
            val ix = kotlin.math.floor(fx).toInt(); val iy = kotlin.math.floor(fy).toInt()
            if (ix < 0 || iy < 0 || ix + 1 >= alt.width || iy + 1 >= alt.height || ax !in 0 until alt.width || ay !in 0 until alt.height) continue
            val tx = fx - ix; val ty = fy - iy
            val v = (at(ix, iy) * (1 - tx) + at(ix + 1, iy) * tx) * (1 - ty) +
                (at(ix, iy + 1) * (1 - tx) + at(ix + 1, iy + 1) * tx) * ty
            val r = ref.data[y * ref.width + x].toInt() and 255
            integer += abs(at(ax, ay) - r); fractional += abs(v - r); count++
        }
        return listOf(integer / count, fractional / count, count.toDouble())
    }

    private fun writeBuffer(file: File, buffer: ByteBuffer) {
        file.outputStream().channel.use { ch ->
            val view = buffer.duplicate().apply { clear() }
            while (view.hasRemaining()) ch.write(view)
        }
    }

    private fun writeFloats(file: File, values: FloatArray) {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        writeBuffer(file, bytes)
    }
}
