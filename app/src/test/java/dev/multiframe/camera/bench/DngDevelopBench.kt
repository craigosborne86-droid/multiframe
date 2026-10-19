package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.BilateralGrid
import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.Optics
import dev.multiframe.camera.pipeline.RawDeveloper
import dev.multiframe.camera.pipeline.Sharpen
import dev.multiframe.camera.pipeline.Homography
import dev.multiframe.camera.pipeline.MosaicAssembler
import dev.multiframe.camera.pipeline.Plane
import dev.multiframe.camera.pipeline.FeatureMatcher
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The offline develop bench: real DNGs from the phone, developed on the desk
 * through the Kotlin reference pipeline (`RawDeveloper.develop`, which the
 * native develop is held to within two codes), one JPEG per variant, so a
 * tone change is judged on real photographs in seconds rather than after a
 * build, an install and a walk outside.
 *
 * Skipped unless run with the directories named:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*DngDevelopBench*' \
 *         -Pmf.dng.dir=/path/to/dngs -Pmf.out.dir=/path/to/out \
 *         -Pmf.variants=current,indigo -Pmf.scale=half
 *
 * `mf.scale` is `half` (every other CFA cell, for tone) or `full`;
 * `mf.crop=x,y,w,h` develops the full-resolution frame, then crops for detail.
 * Optics follows chroma/defringe/sharpen, exactly as RawBurstCapture.
 * Crop coordinates refer to the corrected, valid, unrotated full-resolution image.
 */
class DngDevelopBench {

    companion object {
        /** The renderings under comparison. `current` is what the app ships. */
        val VARIANTS: Map<String, DevelopParams> = linkedMapOf(
            // What the app ships: DevelopParams' defaults with the LOCAL TONE control on.
            "current" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON),
            // Same pipeline with only sensor-clipping recovery disabled.
            "beforeclip" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON),
            // Earlier calibration: no shadow lift or purity compression.
            "cal1" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, purityThreshold = 0f),
            // The rendering of the eighth field test, kept so the change can be seen.
            "old" to DevelopParams(localTone = 0.5f, highlightTarget = 0.62f, contrast = 0.30f, purityThreshold = 0f,
                sharpen = Sharpen.Params(amount = 0.55f, threshold = 3.5f, maxShift = 28f)),
            "nolocal" to DevelopParams(localTone = 0f, shadowLift = 0f),
            // Around the shipped placement: a third of a stop darker, the toe deeper, no sharpen.
            "darker" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON, highlightTarget = 0.36f),
            "toe" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON, blackPoint = 0.02f, contrast = 0.45f),
            "nosharp" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON, sharpen = Sharpen.Params(amount = 0f)),
            "unbounded" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON, meteredGain = -1f),
            // The old rendering without the meter's bound: what a DNG written before
            // BaselineExposure carried the pull would have looked like on the phone.
            "oldunbounded" to DevelopParams(localTone = 0.5f, highlightTarget = 0.62f, contrast = 0.30f, meteredGain = -1f, purityThreshold = 0f,
                sharpen = Sharpen.Params(amount = 0.55f, threshold = 3.5f, maxShift = 28f)),
        ) + tuningVariants()

        /** Production's final gather and valid-region crop, then optional inspection crop. */
        fun finish(pixels: IntArray, width: Int, height: Int, optics: Optics.Params, crop: List<Int>? = null): Image {
            val scale = Optics.apply(pixels, width, height, optics)
            val model = optics.distortion?.takeIf { !it.isIdentity }
            val inset = if (model == null && scale.isIdentity) dev.multiframe.camera.pipeline.LensDistortion.Inset.NONE
                else dev.multiframe.camera.pipeline.LensDistortion.validInset(model, width, height, scale)
            val validW = inset.width(width)
            val validH = inset.height(height)
            val box = crop ?: listOf(0, 0, validW, validH)
            require(box.size == 4) { "mf.crop must be x,y,w,h" }
            val (x, y, w, h) = box
            require(x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= validW && y.toLong() + h <= validH) {
                "crop $box outside corrected ${validW}x${validH} frame"
            }
            val out = IntArray(w * h)
            for (row in 0 until h) System.arraycopy(pixels, (inset.top + y + row) * width + inset.left + x, out, row * w, w)
            return Image(out, w, h)
        }

        /** Around the shipped operator and the purity compression, for the next calibration. */
        private fun tuningVariants(): Map<String, DevelopParams> {
            val on = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON)
            val out = linkedMapOf<String, DevelopParams>()
            out["nolift"] = on.copy(shadowLift = 0f)
            for ((k, m) in listOf(0.8f to 3f, 1f to 3.5f, 1.2f to 3.5f)) {
                out["lift${(k * 10).toInt()}m${(m * 10).toInt()}"] = on.copy(shadowLift = k, shadowLiftMax = m)
            }
            out["nopurity"] = on.copy(purityThreshold = 0f)
            for ((t, c) in listOf(0.65f to 0.85f, 0.7f to 0.8f, 0.75f to 0.9f)) {
                out["pu${(t * 100).toInt()}c${(c * 100).toInt()}"] = on.copy(purityThreshold = t, purityCeiling = c)
            }
            return out
        }
    }

    @Test
    fun developTheDngsOnTheDesk() {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.dng.dir; the bench is not run", dir != null && dir.isDirectory)
        val out = File(System.getProperty("mf.out.dir") ?: File(dir!!, "out").path).apply { mkdirs() }
        val wanted = System.getProperty("mf.variants")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("current")
        val scale = System.getProperty("mf.scale") ?: "half"
        val crop = System.getProperty("mf.crop")?.split(',')?.map { it.trim().toInt() }

        val files = dir!!.listFiles { f -> !f.name.startsWith("._") && f.extension.equals("dng", ignoreCase = true) && (dir.name != "september" || f.name.startsWith("MF_202609")) }!!.sortedBy { it.name }
        println("bench: ${files.size} DNGs, variants $wanted, scale $scale${crop?.let { ", crop $it" } ?: ""}")
        for (file in files) {
            val whole = DngFile.read(file)
            val dng = when {
                crop != null -> whole
                scale == "full" -> whole
                else -> whole.decimated()
            }
            println("${dng.name}: ${dng.frame.width}x${dng.frame.height}, ISO ${dng.iso}, 1/${if (dng.exposureNs > 0) 1e9 / dng.exposureNs else 0.0}s, " +
                "baseline %+.2f EV, black %s white %d cfa %s, scene %s K, shading %s".format(
                    dng.baselineExposure, dng.sensor.blackLevel.joinToString("/"), dng.sensor.whiteLevel,
                    dng.sensor.cfaPattern.joinToString(""), dng.sceneKelvin?.toString() ?: "?",
                    dng.shading?.let { "${it.columns}x${it.rows}" } ?: "none"))
            for (variant in wanted) {
                val params = VARIANTS[variant] ?: error("no variant $variant; have ${VARIANTS.keys}")
                val t0 = System.currentTimeMillis()
                val optics = Optics.Params(
                    distortion = BenchLenses.modelFor(whole, dng.frame.width, dng.frame.height),
                    correctAberration = true, lensLabel = "bench",
                    // Estimate each variant independently: order must not change the result.
                    lensId = null,
                )
                // The meter's intent, as the DNG carries it: BaselineExposure is
                // the guard's pull undone, so two to it is the metered gain. A
                // variant that sets meteredGain itself (negative: unbounded) keeps it.
                val metered = if (params.meteredGain != 0f) params.meteredGain.coerceAtLeast(0f)
                else Math.pow(2.0, dng.baselineExposure.toDouble()).toFloat()
                val stages = if (System.getProperty("mf.stages") == "true")
                    DevelopStageDumps(File(out, "${dng.name}__$variant-stages"), dng.frame.width, dng.frame.height)
                        .also { it.raw(dng.frame) } else null
                val pixels = RawDeveloper.develop(
                    dev.multiframe.camera.pipeline.BayerFrame(dng.frame.width, dng.frame.height, dng.frame.data.copyOf()),
                    dng.sensor, dng.color,
                    params.adaptedTo(dng.iso).copy(optics = null, meteredGain = metered), dng.shading,
                    recoverSensorSaturation = variant != "beforeclip",
                    observer = stages,
                )
                stages?.finish()
                val corrected = finish(pixels, dng.frame.width, dng.frame.height, optics, crop)
                val ms = System.currentTimeMillis() - t0
                val suffix = crop?.let { "_crop" } ?: if (scale == "full") "" else "_half"
                val target = File(out, "${dng.name}__$variant$suffix.jpg")
                writeJpeg(corrected.pixels, corrected.width, corrected.height, target)
                println("  $variant: ${ms}ms, ${corrected.width}x${corrected.height} -> ${target.name}")
                if (System.getProperty("mf.mosaic") == "true") {
                    val result = mosaicAlignmentCase(corrected.pixels, corrected.width, corrected.height)
                    File(out, "${dng.name}__$variant-mosaic.txt").writeText(result + "\n")
                    println("  mosaic: $result")
                }
            }
        }
    }

    data class Image(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Real-DNG overlap case: three neighbours agree, while one previously
     * placed neighbour carries a 90-pixel attitude error. The next frame must
     * follow the image features rather than averaging that error into the grid.
     */
    private fun mosaicAlignmentCase(pixels: IntArray, width: Int, height: Int): String {
        val tileW = 640
        val tileH = 520
        val scale = 4
        require(width >= 1240 && height >= 1020) { "mosaic bench needs a 1240x1020 developed scene" }
        fun proxy(sx: Int, sy: Int): Plane {
            val data = ByteArray((tileW / scale) * (tileH / scale))
            for (y in 0 until tileH / scale) for (x in 0 until tileW / scale) {
                val rgb = pixels[(sy + y * scale) * width + sx + x * scale]
                val luma = ((rgb shr 16 and 255) * 0.2126f +
                    (rgb shr 8 and 255) * 0.7152f + (rgb and 255) * 0.0722f).toInt()
                data[y * (tileW / scale) + x] = luma.toByte()
            }
            return Plane(tileW / scale, tileH / scale, data)
        }
        val a = MosaicAssembler(1600, 1200, tileW, tileH)
        val anchor = a.anchor(proxy(200, 200)).transform
        val base = anchor.apply(0f, 0f)
        fun place(sx: Int, sy: Int, dx: Int, dy: Int) {
            check(a.place(proxy(sx, sy), Homography.translation(
                (base[0] + dx).toDouble(), (base[1] + dy).toDouble(),
            ), 20, 1f) != null)
        }
        place(600, 200, 490, 0) // an erroneous but still nearby neighbour
        place(200, 500, 0, 300)
        place(450, 380, 250, 180)
        val target = proxy(600, 500)
        println("mosaic matches: " + listOf(200 to 200, 600 to 200, 200 to 500, 450 to 380).map { (x, y) ->
            FeatureMatcher.register(FeatureMatcher.extract(proxy(x, y)), FeatureMatcher.extract(target))?.let {
                "${it.inliers.size}/%.2f".format(it.inlierRatio)
            } ?: "0"
        })
        val located = a.locate(target, scale.toFloat())
            ?: return "unregistrable: ${a.lastFailure}"
        val origin = located.transform.apply(0f, 0f)
        val error = kotlin.math.hypot(origin[0] - (base[0] + 400f), origin[1] - (base[1] + 300f))
        return "origin=(%.2f,%.2f) error=%.2fpx inliers=%d ratio=%.2f".format(
            origin[0], origin[1], error, located.inliers, located.inlierRatio,
        )
    }

    private fun writeJpeg(argb: IntArray, width: Int, height: Int, target: File) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.93f
        }
        ImageIO.createImageOutputStream(target).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
    }
}
