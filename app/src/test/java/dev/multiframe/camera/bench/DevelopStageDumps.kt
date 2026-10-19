package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.BayerFrame
import dev.multiframe.camera.pipeline.DevelopObserver
import dev.multiframe.camera.pipeline.DevelopObserver.LinearStage
import dev.multiframe.camera.pipeline.DevelopObserver.DisplayStage
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.sqrt

/** Full-frame processing, bounded diagnostic storage. Crops are in unwarped sensor coordinates. */
internal class DevelopStageDumps(private val dir: File, private val width: Int, private val height: Int) : DevelopObserver {
    private data class Region(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)
    private val side = minOf(512, width, height) / 2 * 2
    private val regions = listOf(
        Region("top-left", 0, 0, side, side),
        Region("top-right", width - side, 0, side, side),
        Region("centre", (width - side) / 4 * 2, (height - side) / 4 * 2, side, side),
        Region("bottom-left", 0, height - side, side, side),
        Region("bottom-right", width - side, height - side, side, side),
    )
    private val planes = LinearStage.entries.associateWith { regions.map { FloatArray(it.w * it.h * 3) } }
    private val measurements = StringBuilder("stage,region,domain,meanR,meanG,meanB,sdY,sdRminusG,sdBminusG\n")

    init {
        dir.mkdirs()
        File(dir, "regions.csv").writeText("region,x,y,width,height\n" + regions.joinToString("\n") { "${it.name},${it.x},${it.y},${it.w},${it.h}" } + "\n")
        File(dir, "README.txt").writeText("PFM stores actual float linear values, including values outside display gamut. PNG previews of linear stages use sRGB encoding only (no auto brightness or tone). Display stages store actual 8-bit output. Metrics are scene variation, NOT noise/SNR/MTF. Regions are cropped after processing the full frame.\n")
    }

    override fun linear(stage: LinearStage, x: Int, y: Int, rgb: FloatArray) {
        for ((i, r) in regions.withIndex()) if (x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h) {
            val at = ((y - r.y) * r.w + x - r.x) * 3
            val dst = planes.getValue(stage)[i]
            dst[at] = rgb[0]; dst[at + 1] = rgb[1]; dst[at + 2] = rgb[2]
        }
    }

    override fun display(stage: DisplayStage, pixels: IntArray, width: Int, height: Int) {
        for (r in regions) {
            val crop = IntArray(r.w * r.h)
            val rgb = FloatArray(crop.size * 3)
            for (y in 0 until r.h) for (x in 0 until r.w) {
                val at = y * r.w + x
                val p = pixels[(y + r.y) * width + x + r.x]; crop[at] = p
                rgb[at * 3] = (p shr 16 and 255) / 255f
                rgb[at * 3 + 1] = (p shr 8 and 255) / 255f
                rgb[at * 3 + 2] = (p and 255) / 255f
            }
            png(File(dir, "${stage.name}-${r.name}.png"), r, crop)
            measure(stage.name, r, "display_0_1", rgb)
        }
    }

    fun raw(frame: BayerFrame) {
        for (r in regions) {
            val bytes = ByteBuffer.allocate(r.w * r.h * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until r.h) for (x in 0 until r.w) bytes.putShort(frame.data[(r.y + y) * width + r.x + x])
            File(dir, "MERGED_CFA-${r.name}.raw16").writeBytes(bytes.array())
        }
    }

    fun finish() {
        for (stage in LinearStage.entries) for ((i, r) in regions.withIndex()) {
            val rgb = planes.getValue(stage)[i]
            File(dir, "${stage.name}-${r.name}.pfm").outputStream().buffered().use { out ->
                out.write("PF\n${r.w} ${r.h}\n-1.0\n".toByteArray())
                val row = ByteBuffer.allocate(r.w * 12).order(ByteOrder.LITTLE_ENDIAN)
                for (y in r.h - 1 downTo 0) {
                    row.clear()
                    for (x in 0 until r.w * 3) row.putFloat(rgb[y * r.w * 3 + x])
                    out.write(row.array())
                }
            }
            fun encode(v: Float): Int {
                val x = v.coerceIn(0f, 1f)
                val srgb = if (x <= .0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - .055f
                return (srgb * 255f + .5f).toInt().coerceIn(0, 255)
            }
            val pixels = IntArray(r.w * r.h) { j -> (0xff shl 24) or (encode(rgb[j * 3]) shl 16) or
                (encode(rgb[j * 3 + 1]) shl 8) or encode(rgb[j * 3 + 2]) }
            png(File(dir, "${stage.name}-${r.name}.png"), r, pixels)
            measure(stage.name, r, "linear", rgb)
        }
        File(dir, "variation.csv").writeText(measurements.toString())
    }

    private fun png(file: File, region: Region, pixels: IntArray) {
        val image = BufferedImage(region.w, region.h, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, region.w, region.h, pixels, 0, region.w)
        ImageIO.write(image, "png", file)
    }

    private fun measure(stage: String, region: Region, domain: String, rgb: FloatArray) {
        val n = rgb.size / 3; val sum = DoubleArray(6); val squares = DoubleArray(6)
        for (i in 0 until n) {
            val r = rgb[i * 3].toDouble(); val g = rgb[i * 3 + 1].toDouble(); val b = rgb[i * 3 + 2].toDouble()
            val values = doubleArrayOf(r, g, b, .2126 * r + .7152 * g + .0722 * b, r - g, b - g)
            for (c in values.indices) { sum[c] += values[c]; squares[c] += values[c] * values[c] }
        }
        val means = (0..2).map { sum[it] / n }
        val sd = (3..5).map { sqrt(maxOf(0.0, squares[it] / n - (sum[it] / n).pow(2))) }
        measurements.appendLine("$stage,${region.name},$domain," + (means + sd).joinToString(","))
    }
}
