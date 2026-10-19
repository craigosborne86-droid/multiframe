package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.sqrt

/** Opt-in full-develop audit of frozen merge outputs. Never changes capture or merge. */
class NoiseDevelopAcceptanceBench {
    private data class Region(val name: String, val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Measurement(
        val case: String, val syntheticIsoSetting: Int, val variant: String, val stage: String,
        val region: String, val samples: Int, val falseChromaRmsCodes: Double,
        val meanLuminanceCodes: Double, val luminanceStdCodes: Double,
        val channelRangeAtLeast8Fraction: Double,
    ) {
        fun csv() = listOf(case, syntheticIsoSetting, variant, stage, region, samples,
            falseChromaRmsCodes, meanLuminanceCodes, luminanceStdCodes,
            channelRangeAtLeast8Fraction).joinToString(",")

        fun json() = """{"case":"$case","syntheticIsoSetting":$syntheticIsoSetting,"variant":"$variant","stage":"$stage","region":"$region","samples":$samples,"falseChromaRmsCodes":$falseChromaRmsCodes,"meanLuminanceCodes":$meanLuminanceCodes,"luminanceStdCodes":$luminanceStdCodes,"channelRangeAtLeast8Fraction":$channelRangeAtLeast8Fraction}"""
    }

    @Test fun developFrozenNoiseVariantsThroughFinalDisplayChain() {
        val inputs = System.getProperty("mf.burst.dir")?.let(::File)
        val output = System.getProperty("mf.burst.out")?.let(::File)
        assumeTrue("requires explicit frozen-input and evidence-output directories", inputs != null && output != null)
        val root = inputs!!
        require(root.isDirectory)
        val out = output!!.apply { mkdirs() }
        val corpus = File(requireNotNull(root.parentFile?.parentFile), "corpus/synthetic-known-motion")
        val regions = listOf(
            Region("detail", 48, 20, 248, 344),
            Region("flat", 366, 246, 128, 120),
            Region("object", 350, 20, 140, 168),
        )
        val variants = listOf("single", "native-baseline", "native-metadata")
        val stages = listOf(DevelopObserver.DisplayStage.POST_CHROMA, DevelopObserver.DisplayStage.POST_SHARPEN)
        val measurements = ArrayList<Measurement>()
        val provenance = ArrayList<String>()

        for (case in listOf("diverse-low", "moving-object", "static-high")) {
            val propertiesFile = File(corpus, "$case/synthetic.properties")
            val p = Properties().apply { propertiesFile.inputStream().use { load(it) } }
            fun int(key: String) = p.getProperty(key).toInt()
            val width = int("width"); val height = int("height")
            val sensor = SensorProfile(IntArray(4) { int("black") }, int("white"),
                p.getProperty("cfa").split(',').map(String::toInt).toIntArray())
            require(regions.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= width && it.y + it.height <= height })
            val isoSetting = if (case == "static-high") 6022 else 24
            val params = DevelopParams(exposureGain = 1f).adaptedTo(isoSetting)
            val caseOut = File(out, case).apply { mkdirs() }
            val images = HashMap<Pair<String, DevelopObserver.DisplayStage>, BufferedImage>()
            provenance.add("$case,synthetic.properties,${sha256(propertiesFile)},${propertiesFile.absolutePath}")

            for (variant in variants) {
                val source = File(root, "$case/$variant.raw16")
                val before = sha256(source)
                val bytes = source.readBytes()
                require(bytes.size == width * height * 2)
                val raw = ShortArray(width * height)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw)
                val captured = HashMap<DevelopObserver.DisplayStage, IntArray>()
                val observer = object : DevelopObserver {
                    override fun linear(stage: DevelopObserver.LinearStage, x: Int, y: Int, rgb: FloatArray) = Unit
                    override fun display(stage: DevelopObserver.DisplayStage, pixels: IntArray, width: Int, height: Int) {
                        if (stage in stages) captured[stage] = pixels.copyOf()
                    }
                }
                val final = RawDeveloper.develop(BayerFrame(width, height, raw), sensor,
                    ColorProfile.NEUTRAL, params, observer = observer)
                assertArrayEquals("final observer must capture actual develop output", final,
                    captured.getValue(DevelopObserver.DisplayStage.POST_SHARPEN))
                require(before == sha256(source)) { "frozen input changed during develop" }
                provenance.add("$case,$variant,$before,${source.absolutePath}")

                for (stage in stages) {
                    val pixels = captured.getValue(stage)
                    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                    image.setRGB(0, 0, width, height, pixels, 0, width)
                    images[variant to stage] = image
                    ImageIO.write(image, "png", File(caseOut, "$variant-${stage.name}.png"))
                    for (region in regions) {
                        val crop = image.getSubimage(region.x, region.y, region.width, region.height)
                        ImageIO.write(crop, "png", File(caseOut, "$variant-${stage.name}-${region.name}.png"))
                        measurements.add(measure(case, isoSetting, variant, stage.name, region, pixels, width))
                    }
                }
                // Fixed host JPEG preview; PNGs retain actual stage codes and remain authoritative.
                jpeg95(images.getValue(variant to DevelopObserver.DisplayStage.POST_SHARPEN),
                    File(caseOut, "$variant-final-host-jpeg95.jpg"))
            }

            for (stage in stages) for (region in regions) {
                val montage = BufferedImage(region.width * variants.size, region.height + 28, BufferedImage.TYPE_INT_RGB)
                val graphics = montage.createGraphics()
                graphics.color = Color.BLACK; graphics.fillRect(0, 0, montage.width, montage.height)
                graphics.color = Color.WHITE
                for ((i, variant) in variants.withIndex()) {
                    val x = i * region.width
                    graphics.drawString(variant, x + 4, 18)
                    graphics.drawImage(images.getValue(variant to stage).getSubimage(region.x, region.y,
                        region.width, region.height), x, 28, null)
                }
                graphics.dispose()
                ImageIO.write(montage, "png", File(caseOut, "${stage.name}-${region.name}-comparison.png"))
            }
            println("$case: fixed gain 1, synthetic ISO setting $isoSetting; actual full develop stages saved")
        }

        File(out, "metrics.csv").writeText("case,syntheticIsoSetting,variant,stage,region,samples,falseChromaRmsCodes,meanLuminanceCodes,luminanceStdCodes,channelRangeAtLeast8Fraction\n" +
            measurements.joinToString("\n") { it.csv() } + "\n")
        File(out, "metrics.json").writeText(measurements.joinToString(",\n", "[\n", "\n]\n") { it.json() })
        File(out, "input-sha256.csv").writeText("case,input,sha256,path\n" + provenance.joinToString("\n") + "\n")
        File(out, "regions.csv").writeText("region,x,y,width,height\n" + regions.joinToString("\n") {
            "${it.name},${it.x},${it.y},${it.width},${it.height}" } + "\n")
        File(out, "README.md").writeText("""
            Frozen single/native-baseline/native-metadata RAW outputs, developed as complete frames through actual RawDeveloper.
            Unit white balance and identity matrix (ColorProfile.NEUTRAL); sensor dimensions, levels and CFA from the original synthetic.properties.
            DevelopParams(exposureGain=1f).adaptedTo(24) for diverse-low/moving-object and adaptedTo(6022) for static-high.
            These ISO numbers select SYNTHETIC rendering settings; they are not measured sensor ISO. Every other parameter is unchanged default.
            POST_CHROMA and POST_SHARPEN full PNGs and crops preserve actual 8-bit display codes. Crops use the existing synthetic detail/flat/object regions and follow full-frame processing.
            falseChromaRmsCodes = sqrt(mean over pixels and RGB channels of (channel - per-pixel RGB mean)^2), in 8-bit display codes.
            Luminance uses 0.2126 R + 0.7152 G + 0.0722 B; luminanceStdCodes is population spatial standard deviation in display codes.
            The analytic target is monochrome; the flat region is uniform. False chroma and flat variation therefore audit synthetic error/noise after tone and quantisation, not photographic SNR or MTF.
            channelRangeAtLeast8Fraction reports the fraction with max(R,G,B)-min(R,G,B) >= 8 display codes.
            Host JPEG previews use ImageIO at explicit quality 0.95, not the production native JPEG codec. PNG stage metrics are authoritative.
            No alignment, merge, input generation, exposure measurement, metadata association, lens shading or production activation occurs here.
            Input hashes are checked before and after each develop. This bench measures the final-display tradeoff; it does not invent a quality threshold or claim live photographic acceptance.
        """.trimIndent() + "\n")
    }

    private fun measure(case: String, iso: Int, variant: String, stage: String, region: Region,
                        pixels: IntArray, width: Int): Measurement {
        var chromaSquared = 0.0; var sumY = 0.0; var squareY = 0.0; var coloured = 0
        for (y in region.y until region.y + region.height) for (x in region.x until region.x + region.width) {
            val p = pixels[y * width + x]
            val r = (p shr 16 and 255).toDouble(); val g = (p shr 8 and 255).toDouble(); val b = (p and 255).toDouble()
            val mean = (r + g + b) / 3.0
            chromaSquared += ((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3.0
            val luminance = .2126 * r + .7152 * g + .0722 * b
            sumY += luminance; squareY += luminance * luminance
            if (maxOf(r, g, b) - minOf(r, g, b) >= 8) coloured++
        }
        val n = region.width * region.height
        return Measurement(case, iso, variant, stage, region.name, n, sqrt(chromaSquared / n),
            sumY / n, sqrt(maxOf(0.0, squareY / n - (sumY / n) * (sumY / n))), coloured.toDouble() / n)
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun jpeg95(image: BufferedImage, file: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(file).use { output ->
            writer.output = output
            val p = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .95f
            }
            writer.write(null, javax.imageio.IIOImage(image, null, null), p)
        }
        writer.dispose()
    }
}
