package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow

/**
 * What the user chose in Labs for one photograph's analysis, handed down the
 * develop. Everything defaults to the deterministic path; a model is present
 * only where it is bundled *and* the user turned it on. See [AnalysisModels].
 */
data class AnalysisChoices(
    /** Learned white balance; null keeps the camera's own. */
    val awb: AnalysisModel? = null,
    /** How the merge's reference frame is chosen. */
    val reference: ReferenceChoice = ReferenceChoice.NEWEST,
    /** The sharpness model, under [ReferenceChoice.MODEL]. */
    val frameModel: AnalysisModel? = null,
    /** Which regions take how much of the local operator's earned lift. */
    val regionTone: RegionToneMode = RegionToneMode.OFF,
    /** The region model, under [RegionToneMode.MODEL]. */
    val regionModel: AnalysisModel? = null,
    /** The person mask, for the two uses below. */
    val person: AnalysisModel? = null,
    /** Moving people come from the reference frame alone instead of the merge. */
    val personMerge: Boolean = false,
    /** The simulated background blur (Labs). */
    val portrait: Boolean = false,
    /** Save training pairs where this photograph measured a label. */
    val collect: Boolean = false,
    /** The gyro's blur over an exposure, in sensor pixels; null without a gyro reading. For the frame task's labels. */
    val blurPixels: ((timestampNs: Long, exposureNs: Long) -> Float?)? = null,
    /** Models that acted before the shutter -- the subject model's metering -- for the photograph's report. */
    val upstream: List<AnalysisUse> = emptyList(),
) {
    companion object {
        val NONE = AnalysisChoices()
    }
}

enum class ReferenceChoice { NEWEST, SHARPEST, MODEL }

enum class RegionToneMode { OFF, MEASURED, MODEL }

/**
 * A raw CFA buffer taken down to a small grid of camera-RGB cells: each cell
 * the mean of its sites' black-subtracted codes over the range, per colour,
 * with no white balance. The input every picture-shaped model and every
 * colour classifier here starts from. Samples every [step]th 2×2 block, which
 * at a 256-cell width still reads several blocks per cell of a 4000-pixel
 * frame.
 */
object CfaThumbnail {

    fun cells(
        buffer: ByteBuffer, rowStride: Int, width: Int, height: Int,
        profile: SensorProfile, ow: Int, oh: Int, step: Int = 2,
    ): FloatArray {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sum = FloatArray(ow * oh * 3)
        val n = IntArray(ow * oh)
        val black = IntArray(4) { profile.blackAt(it and 1, it shr 1) }
        val lo = black.min()
        val inv = 1f / max(1, profile.whiteLevel - lo)
        val cfa = profile.cfaPattern
        var by = 0
        while (by + 1 < height) {
            val cy = (by * oh / height).coerceAtMost(oh - 1)
            var bx = 0
            while (bx + 1 < width) {
                val cx = (bx * ow / width).coerceAtMost(ow - 1)
                val c = cy * ow + cx
                var g = 0f
                for (site in 0 until 4) {
                    val x = bx + (site and 1); val y = by + (site shr 1)
                    val v = ((b.getShort(y * rowStride + x * 2).toInt() and 0xFFFF) - black[site]) * inv
                    when (cfa[site]) {
                        0 -> sum[c * 3] += v
                        2 -> sum[c * 3 + 2] += v
                        else -> g += v
                    }
                }
                sum[c * 3 + 1] += g * 0.5f
                n[c]++
                bx += 2 * step
            }
            by += 2 * step
        }
        for (c in 0 until ow * oh) if (n[c] > 0) for (k in 0 until 3) sum[c * 3 + k] = max(0f, sum[c * 3 + k] / n[c])
        return sum
    }

    /**
     * Camera-RGB cells balanced by [color]'s gains and taken through its
     * matrix: linear sRGB, as the develop sees it before the tone curve.
     */
    fun linearSrgb(cells: FloatArray, color: ColorProfile): FloatArray {
        val m = color.matrix
        val gr = color.gains[0]; val gg = (color.gains[1] + color.gains[2]) * 0.5f; val gb = color.gains[3]
        val out = FloatArray(cells.size)
        for (i in 0 until cells.size / 3) {
            val r = cells[i * 3] * gr; val g = cells[i * 3 + 1] * gg; val b = cells[i * 3 + 2] * gb
            out[i * 3] = max(0f, m[0] * r + m[1] * g + m[2] * b)
            out[i * 3 + 1] = max(0f, m[3] * r + m[4] * g + m[5] * b)
            out[i * 3 + 2] = max(0f, m[6] * r + m[7] * g + m[8] * b)
        }
        return out
    }

    /**
     * The picture-model input: linear sRGB stretched so its luminance's 99th
     * percentile is 1, then gamma-encoded (1/2.2), clipped to 0..1. The
     * training export applies the same to its images.
     */
    fun modelInput(linear: FloatArray): FloatArray {
        val count = linear.size / 3
        val luma = FloatArray(count) { BilateralGrid.luminance(linear[it * 3], linear[it * 3 + 1], linear[it * 3 + 2]) }
        val sorted = luma.copyOf().also { it.sort() }
        val p99 = max(1e-6f, sorted[(count * 0.99).toInt().coerceAtMost(count - 1)])
        return FloatArray(linear.size) { (linear[it] / p99).coerceIn(0f, 1f).pow(1f / 2.2f) }
    }
}
