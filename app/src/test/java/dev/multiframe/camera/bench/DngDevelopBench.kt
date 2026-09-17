package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.Optics
import dev.multiframe.camera.pipeline.RawDeveloper
import dev.multiframe.camera.pipeline.Sharpen
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
 * `mf.crop=x,y,w,h` develops one full-resolution window instead, for detail.
 * The variants are named in [VARIANTS]; the lens shading in the DNG's opcode
 * list is applied, the optics correction is not (it does not change tone).
 */
class DngDevelopBench {

    companion object {
        /** The renderings under comparison. `current` is what the app ships. */
        val VARIANTS: Map<String, DevelopParams> = linkedMapOf(
            // What the app ships: DevelopParams' defaults with the LOCAL TONE control on.
            "current" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON),
            // The rendering of the eighth field test, kept so the change can be seen.
            "old" to DevelopParams(localTone = 0.5f, highlightTarget = 0.62f, contrast = 0.30f,
                sharpen = Sharpen.Params(amount = 0.55f, threshold = 3.5f, maxShift = 28f)),
            "nolocal" to DevelopParams(localTone = 0f),
            // Around the shipped placement: a third of a stop darker, the toe deeper, no sharpen.
            "darker" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, highlightTarget = 0.36f),
            "toe" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, blackPoint = 0.02f, contrast = 0.45f),
            "nosharp" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, sharpen = Sharpen.Params(amount = 0f)),
            "unbounded" to DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, meteredGain = -1f),
            // The old rendering without the meter's bound: what a DNG written before
            // BaselineExposure carried the pull would have looked like on the phone.
            "oldunbounded" to DevelopParams(localTone = 0.5f, highlightTarget = 0.62f, contrast = 0.30f, meteredGain = -1f,
                sharpen = Sharpen.Params(amount = 0.55f, threshold = 3.5f, maxShift = 28f)),
        )
    }

    @Test
    fun developTheDngsOnTheDesk() {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.dng.dir; the bench is not run", dir != null && dir.isDirectory)
        val out = File(System.getProperty("mf.out.dir") ?: File(dir!!, "out").path).apply { mkdirs() }
        val wanted = System.getProperty("mf.variants")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: VARIANTS.keys.toList()
        val scale = System.getProperty("mf.scale") ?: "half"
        val crop = System.getProperty("mf.crop")?.split(',')?.map { it.trim().toInt() }

        val files = dir!!.listFiles { f -> f.extension.equals("dng", ignoreCase = true) }!!.sortedBy { it.name }
        println("bench: ${files.size} DNGs, variants $wanted, scale $scale${crop?.let { ", crop $it" } ?: ""}")
        for (file in files) {
            val whole = DngFile.read(file)
            val dng = when {
                crop != null -> whole.crop(crop[0], crop[1], crop[2], crop[3])
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
                // The optics the phone applies: the lens's geometry, from the
                // development devices' probe records (REFERENCE §2.4) keyed by
                // the DNG's focal length, and the aberration measured from this
                // frame as the phone measures it. Whole frames only; a crop is
                // not in the coordinates the model was written in.
                val optics = if (crop == null) Optics.Params(
                    distortion = BenchLenses.modelFor(whole, dng.frame.width, dng.frame.height),
                    correctAberration = true, lensLabel = "bench", lensId = "bench-${whole.focalMm}",
                ) else null
                // The meter's intent, as the DNG carries it: BaselineExposure is
                // the guard's pull undone, so two to it is the metered gain. A
                // variant that sets meteredGain itself (negative: unbounded) keeps it.
                val metered = if (params.meteredGain != 0f) params.meteredGain.coerceAtLeast(0f)
                else Math.pow(2.0, dng.baselineExposure.toDouble()).toFloat()
                val pixels = RawDeveloper.develop(
                    dng.frame, dng.sensor, dng.color,
                    params.copy(optics = optics ?: params.optics, meteredGain = metered), dng.shading,
                )
                val ms = System.currentTimeMillis() - t0
                val suffix = crop?.let { "_crop" } ?: if (scale == "full") "" else "_half"
                val target = File(out, "${dng.name}__$variant$suffix.jpg")
                writeJpeg(pixels, dng.frame.width, dng.frame.height, target)
                println("  $variant: ${ms}ms -> ${target.name}")
            }
        }
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

/**
 * The geometric models of the two development devices' main lenses, from the
 * probe records in REFERENCE.md §2.4 -- the DNG does not carry the model in
 * the form `LensDistortion.Model` takes -- picked by the DNG's focal length
 * and scaled to the frame being developed. Null for a lens not on record,
 * which develops without the geometry (the tone is the same; edges keep the
 * demosaic's texture the resample would have smoothed).
 */
object BenchLenses {
    private class Record(val focalMm: Float, val arrayW: Int, val arrayH: Int, val intrinsics: FloatArray, val distortion: FloatArray)

    private val records = listOf(
        // Pixel 11 Pro 24mm.
        Record(6.9f, 4080, 3064, floatArrayOf(2833.28f, 2833.28f, 2048.94f, 1538.04f, 0f), floatArrayOf(0.09295f, -0.15067f, 0.09918f, 0f, 0f)),
        // Pixel 9 Pro XL 24mm.
        Record(6.9f, 4080, 3072, floatArrayOf(2852.81f, 2852.81f, 2044.85f, 1537.71f, 0f), floatArrayOf(0.09004f, -0.15054f, 0.10187f, 0f, 0f)),
    )

    fun modelFor(dng: DngFile, width: Int, height: Int): dev.multiframe.camera.pipeline.LensDistortion.Model? {
        // [dng] is the whole frame, so its size is the array's.
        val r = records.firstOrNull {
            kotlin.math.abs(it.focalMm - dng.focalMm) < 0.2f && it.arrayW == dng.frame.width && it.arrayH == dng.frame.height
        } ?: return null
        val model = dev.multiframe.camera.pipeline.LensDistortion.Model(
            fx = r.intrinsics[0], fy = r.intrinsics[1], cx = r.intrinsics[2], cy = r.intrinsics[3], skew = r.intrinsics[4],
            k1 = r.distortion[0], k2 = r.distortion[1], k3 = r.distortion[2], p1 = r.distortion[3], p2 = r.distortion[4],
        ).scaledTo(r.arrayW, r.arrayH, width, height)
        return dev.multiframe.camera.pipeline.LensDistortion.limitStretch(model, width, height)
    }
}
