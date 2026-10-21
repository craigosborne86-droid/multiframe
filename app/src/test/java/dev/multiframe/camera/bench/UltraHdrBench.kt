package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.BayerFrame
import dev.multiframe.camera.pipeline.DevelopObserver
import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.HdrGainMap
import dev.multiframe.camera.pipeline.Optics
import dev.multiframe.camera.pipeline.RawDeveloper
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ShortBuffer
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max

/**
 * The HDR rendition on the desk: real DNGs developed as the app ships them,
 * the gain map measured beside the develop as the capture measures it
 * (`HdrGainMap`, REFERENCE §3.1 step 6b), and both written as one Ultra HDR
 * JPEG for viewing on an HDR screen (Chrome, an Android 14+ phone).
 *
 * Skipped unless run with the directories named:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*UltraHdrBench*' \
 *         -Pmf.hdr.dir=/path/to/dngs -Pmf.hdr.out=/path/to/out \
 *         [-Pmf.hdr.only=MF_20260917_145908,...] [-Pmf.scale=full]
 *
 * For each DNG it writes `<name>__sdr.jpg` (the picture alone),
 * `<name>__uhdr.jpg` (the picture with its map) and `<name>__gainmap.png`,
 * and prints the timings, the sizes, and two checks:
 *
 *  - **SDR identical**: the develop with the HDR rendition asked for is the
 *    develop without it, pixel for pixel.
 *  - **Map error**: the fast measurement (4x4 blocks of the CFA, no local
 *    operator) against the exact one (every pixel's peak after the local
 *    operator, from the develop's own `PRE_TONE` stage, averaged in log2
 *    over the same blocks), in stops: what leaving the lift out and reading
 *    the block mean costs.
 */
class UltraHdrBench {

    @Test
    fun writeUltraHdrJpegsFromTheDngs() {
        val dir = System.getProperty("mf.hdr.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.hdr.dir; the bench is not run", dir != null && dir.isDirectory)
        val out = File(System.getProperty("mf.hdr.out") ?: File(dir!!, "uhdr").path).apply { mkdirs() }
        val only = System.getProperty("mf.hdr.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val full = System.getProperty("mf.scale") == "full"
        val files = dir!!.listFiles { f ->
            !f.name.startsWith("._") && f.extension.equals("dng", ignoreCase = true) &&
                (only == null || only.any { f.name.startsWith(it) })
        }!!.sortedBy { it.name }
        println("uhdr bench: ${files.size} DNGs, ${if (full) "full" else "half"} scale -> $out")
        val headroom = DevelopParams.HDR_HEADROOM
        val report = StringBuilder("name\tsize\tgain\tmaxRatio\tmapPx>1\tsdrIdentical\tmedianErr\tp99Err\tbias\tmeasureMs\tplaceMs\tsdrJpegMs\tmapJpegMs\tsdrBytes\tmapBytes\tuhdrBytes\n")

        for (file in files) {
            val whole = DngFile.read(file)
            val dng = if (full) whole else whole.decimated()
            val w = dng.frame.width
            val h = dng.frame.height
            // What the app ships, as DngDevelopBench's `current`, with the meter's intent from the DNG.
            val metered = Math.pow(2.0, dng.baselineExposure.toDouble()).toFloat()
            // -Pmf.hdr.nolocal=true develops without the local operator, to separate its part in the map error.
            val local = System.getProperty("mf.hdr.nolocal") != "true"
            val params = DevelopParams(
                localTone = if (local) DevelopParams.LOCAL_TONE_ON else 0f,
                shadowLift = if (local) DevelopParams.SHADOW_LIFT_ON else 0f,
            ).adaptedTo(dng.iso).copy(meteredGain = metered)
            val optics = Optics.Params(
                distortion = BenchLenses.modelFor(whole, w, h), correctAberration = true,
                lensLabel = "bench", lensId = null,
            )
            // The develop corrects defects in the frame it is given; the
            // measurement reads the merged CFA as the capture does, untouched.
            val cfa = dng.frame.data.copyOf()
            val gain = RawDeveloper.resolveGain(BayerFrame(w, h, cfa.copyOf()), dng.sensor, dng.color, params)
            val resolved = params.withGain(gain)

            // The exact ratio: every pixel's peak where the shoulder reads it.
            val exactLog2 = FloatArray(w * h)
            val observer = object : DevelopObserver {
                override fun linear(stage: DevelopObserver.LinearStage, x: Int, y: Int, rgb: FloatArray) {
                    if (stage != DevelopObserver.LinearStage.PRE_TONE) return
                    exactLog2[y * w + x] = log2(HdrGainMap.ratio(max(rgb[0], max(rgb[1], rgb[2])), resolved, headroom))
                }
                override fun display(stage: DevelopObserver.DisplayStage, pixels: IntArray, width: Int, height: Int) {}
            }
            val sdr = RawDeveloper.developCropped(
                BayerFrame(w, h, cfa.copyOf()), dng.sensor, dng.color,
                params.copy(optics = optics), dng.shading, observer = observer,
            )
            val sdrWithHdr = RawDeveloper.developCropped(
                BayerFrame(w, h, cfa.copyOf()), dng.sensor, dng.color,
                params.copy(optics = optics, hdrHeadroom = headroom), dng.shading,
            )
            val identical = sdr.pixels.contentEquals(sdrWithHdr.pixels)

            val t0 = System.nanoTime()
            val field = HdrGainMap.measure(ShortBuffer.wrap(cfa), w, h, dng.sensor, dng.color, resolved, dng.shading, headroom)
            val t1 = System.nanoTime()
            val placement = HdrGainMap.Placement(
                sdr.width, sdr.height, 0, inset = sdr.inset,
                distortion = optics.distortion?.takeIf { !it.isIdentity },
            )
            val map = HdrGainMap.encode(field, placement)
            val t2 = System.nanoTime()

            // Fast against exact, block by block in sensor space, where either is over one.
            val errors = ArrayList<Float>()
            var bias = 0f
            for (by in 0 until field.height) for (bx in 0 until field.width) {
                var sum = 0f
                for (dy in 0 until 4) for (dx in 0 until 4) {
                    sum += exactLog2[(min(by * 4 + dy, h - 1)) * w + min(bx * 4 + dx, w - 1)]
                }
                val exact = sum / 16f
                val fast = field.log2[by * field.width + bx]
                if (exact > 0.01f || fast > 0.01f) { errors.add(abs(exact - fast)); bias += fast - exact }
            }
            bias /= max(1, errors.size)
            errors.sort()
            val median = errors.getOrNull(errors.size / 2) ?: 0f
            val p99 = errors.getOrNull((errors.size * 99) / 100) ?: 0f

            val t3 = System.nanoTime()
            val sdrJpeg = jpeg(rgbImage(sdr.pixels, sdr.width, sdr.height))
            val t4 = System.nanoTime()
            val name = dng.name
            File(out, "${name}__sdr.jpg").writeBytes(sdrJpeg)
            var mapBytes = 0
            var uhdrBytes = 0
            var mapMs = 0.0
            var lit = 0
            if (map != null) {
                val grey = BufferedImage(map.width, map.height, BufferedImage.TYPE_BYTE_GRAY)
                grey.raster.setDataElements(0, 0, map.width, map.height, map.codes)
                val t5 = System.nanoTime()
                val mapJpeg = jpeg(grey)
                val uhdr = UltraHdrContainer.assemble(sdrJpeg, mapJpeg, log2(map.maxRatio))
                mapMs = (System.nanoTime() - t5) / 1e6
                // The index must point at the map's SOI, and the map must run to the end.
                val at = UltraHdrContainer.gainMapOffset(uhdr) ?: error("$name: no MPF index")
                check(uhdr[at] == 0xFF.toByte() && uhdr[at + 1] == 0xD8.toByte()) { "$name: MPF offset $at is not a JPEG" }
                File(out, "${name}__uhdr.jpg").writeBytes(uhdr)
                ImageIO.write(grey, "png", File(out, "${name}__gainmap.png"))
                mapBytes = mapJpeg.size
                uhdrBytes = uhdr.size
                lit = map.codes.count { it.toInt() != 0 }
            }
            val line = listOf(
                name, "${sdr.width}x${sdr.height}", "%.2f".format(gain),
                map?.let { "%.2f".format(it.maxRatio) } ?: "none",
                map?.let { "%.1f%%".format(100f * lit / (it.width * it.height)) } ?: "-",
                identical.toString(), "%.3f".format(median), "%.3f".format(p99), "%+.3f".format(bias),
                "%.0f".format((t1 - t0) / 1e6), "%.0f".format((t2 - t1) / 1e6),
                "%.0f".format((t4 - t3) / 1e6), "%.0f".format(mapMs),
                sdrJpeg.size.toString(), mapBytes.toString(), uhdrBytes.toString(),
            ).joinToString("\t")
            println(line)
            report.append(line).append('\n')
            check(identical) { "$name: the SDR picture changed with the HDR rendition on" }
        }
        File(out, "report.tsv").writeText(report.toString())
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    private fun rgbImage(argb: IntArray, width: Int, height: Int) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, argb, 0, width) }

    /** Quality 95, as the app writes. */
    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.95f
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return bytes.toByteArray()
    }
}
