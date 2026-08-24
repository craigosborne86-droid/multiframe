package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tuning for the merge and tone stages.
 *
 * [exposureGain] compensates the deliberate underexposure used at capture time.
 * [shoulderKnee] is where the highlight roll-off begins; everything below it is
 * left linear, which keeps the result restrained rather than the heavily
 * local-tone-mapped look typical of phone cameras.
 */
data class MergeParams(
    val exposureGain: Float = 2.0f,
    val shoulderKnee: Float = 0.70f,
    /**
     * How many estimated noise sigma a frame may differ from the reference
     * before it starts losing weight. Larger keeps more frames (better noise
     * reduction), smaller rejects motion more aggressively (less ghosting).
     */
    val noiseTolerance: Float = 3.0f,
    /** Floor on estimated noise, so a near-noiseless burst cannot divide by zero. */
    val minNoiseSigma: Float = 0.002f,
)

data class MergeStats(
    val framesUsed: Int,
    val meanContribution: Float,
    val alignMillis: Long,
    val mergeMillis: Long,
)

class MergeResult(val bitmap: Bitmap, val stats: MergeStats)

/**
 * Merge output before tone mapping and RGB conversion. [luma] is in linear
 * light, normalised to 0..1 before the exposure gain is applied.
 */
class MergedPlanes(
    val width: Int,
    val height: Int,
    val luma: FloatArray,
    val u: ByteArray,
    val v: ByteArray,
    val stats: MergeStats,
)

object Merger {

    private const val TILE_TARGET_PX = 32

    // sRGB-ish encode/decode tables. Merging is done in linear light, which is
    // where averaging is photometrically correct.
    private val toLinear = FloatArray(256) { (it / 255f).pow(2.2f) }
    private val toGamma = IntArray(4096) {
        val v = (it / 4095f).pow(1f / 2.2f)
        (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private val threads = max(2, Runtime.getRuntime().availableProcessors())
    private val pool = Executors.newFixedThreadPool(threads)

    fun tileGrid(frame: YuvFrame): Pair<Int, Int> =
        max(1, frame.width / TILE_TARGET_PX) to max(1, frame.height / TILE_TARGET_PX)

    /**
     * Aligns every frame after the first onto the first, then merges.
     * When [mergeEnabled] is false only the reference frame is developed, so an
     * A/B comparison isolates the merge rather than the tone curve.
     */
    fun process(
        frames: List<YuvFrame>,
        mergeEnabled: Boolean,
        params: MergeParams = MergeParams(),
    ): MergeResult {
        val planes = mergePlanes(frames, mergeEnabled, params)
        return MergeResult(develop(planes, params), planes.stats)
    }

    /** Pure merge, no Android graphics dependency. */
    fun mergePlanes(
        frames: List<YuvFrame>,
        mergeEnabled: Boolean,
        params: MergeParams = MergeParams(),
    ): MergedPlanes {
        require(frames.isNotEmpty()) { "no frames" }
        val ref = frames.first()
        val w = ref.width
        val h = ref.height

        val alignStart = System.currentTimeMillis()
        val fields = ArrayList<AlignmentField>()
        if (mergeEnabled && frames.size > 1) {
            val (tx, ty) = tileGrid(ref)
            val refPyramid = Aligner.buildPyramid(ref.lumaPlane())
            for (i in 1 until frames.size) {
                val altPyramid = Aligner.buildPyramid(frames[i].lumaPlane())
                fields.add(Aligner.align(refPyramid, altPyramid, tx, ty))
            }
        }
        val alignMillis = System.currentTimeMillis() - alignStart

        val mergeStart = System.currentTimeMillis()
        val noiseVar = estimateNoiseVariance(ref, frames, fields, w, h, params)
        val lumaAcc = FloatArray(w * h)
        val weightAcc = FloatArray(w * h)
        val contribution = FloatArray(threads)

        parallelRows(h) { yStart, yEnd, slot ->
            var localContrib = 0f
            var localCount = 0
            for (y in yStart until yEnd) {
                val rowBase = y * w
                for (x in 0 until w) {
                    val refLin = toLinear[ref.y[rowBase + x].toInt() and 0xFF]
                    lumaAcc[rowBase + x] = refLin
                    weightAcc[rowBase + x] = 1f
                }
            }
            for (f in fields.indices) {
                val alt = frames[f + 1]
                val field = fields[f]
                val tileW = w.toFloat() / field.tilesX
                val tileH = h.toFloat() / field.tilesY
                for (y in yStart until yEnd) {
                    val rowBase = y * w
                    val fy = (y / tileH) - 0.5f
                    val ty0 = fy.toInt().coerceIn(0, field.tilesY - 1)
                    val ty1 = (ty0 + 1).coerceAtMost(field.tilesY - 1)
                    val wy = (fy - ty0).coerceIn(0f, 1f)

                    for (x in 0 until w) {
                        val fx = (x / tileW) - 0.5f
                        val tx0 = fx.toInt().coerceIn(0, field.tilesX - 1)
                        val tx1 = (tx0 + 1).coerceAtMost(field.tilesX - 1)
                        val wx = (fx - tx0).coerceIn(0f, 1f)

                        val i00 = ty0 * field.tilesX + tx0
                        val i01 = ty0 * field.tilesX + tx1
                        val i10 = ty1 * field.tilesX + tx0
                        val i11 = ty1 * field.tilesX + tx1

                        val odx = bilerp(
                            field.dx[i00].toFloat(), field.dx[i01].toFloat(),
                            field.dx[i10].toFloat(), field.dx[i11].toFloat(), wx, wy,
                        )
                        val ody = bilerp(
                            field.dy[i00].toFloat(), field.dy[i01].toFloat(),
                            field.dy[i10].toFloat(), field.dy[i11].toFloat(), wx, wy,
                        )

                        val sampled = sampleBilinear(alt.y, w, h, x + odx, y + ody)
                        val altLin = toLinear[sampled]
                        val refLin = toLinear[ref.y[rowBase + x].toInt() and 0xFF]

                        // Robustness against a signal-dependent noise model.
                        // Shot noise grows with signal, so a fixed threshold reads
                        // high-ISO noise as subject motion and throws away frames
                        // that should have merged. Differences inside the expected
                        // noise envelope keep full weight; past it the weight falls
                        // off as the variance ratio, which is what kills ghosts.
                        val diff = altLin - refLin
                        val d2 = diff * diff
                        val n2 = noiseVar[binOf(refLin)]
                        val weight = if (d2 <= n2) 1f else n2 / d2

                        lumaAcc[rowBase + x] += altLin * weight
                        weightAcc[rowBase + x] += weight
                        localContrib += weight
                        localCount++
                    }
                }
            }
            if (localCount > 0) contribution[slot] = localContrib / localCount
        }

        // Chroma: same offsets at half resolution, plain average is sufficient.
        val cw = ref.chromaWidth
        val chh = ref.chromaHeight
        val uOut = ByteArray(cw * chh)
        val vOut = ByteArray(cw * chh)
        mergeChroma(frames, fields, ref, cw, chh, uOut, vOut)

        val normalised = FloatArray(w * h)
        parallelRows(h) { yStart, yEnd, _ ->
            for (y in yStart until yEnd) {
                val rowBase = y * w
                for (x in 0 until w) {
                    val i = rowBase + x
                    normalised[i] = lumaAcc[i] / weightAcc[i]
                }
            }
        }
        val mergeMillis = System.currentTimeMillis() - mergeStart

        return MergedPlanes(
            w, h, normalised, uOut, vOut,
            MergeStats(
                framesUsed = if (mergeEnabled) frames.size else 1,
                meanContribution = contribution.average().toFloat(),
                alignMillis = alignMillis,
                mergeMillis = mergeMillis,
            ),
        )
    }

    /** Tone maps linear luma and converts to an ARGB bitmap. */
    fun develop(planes: MergedPlanes, params: MergeParams): Bitmap {
        val w = planes.width
        val h = planes.height
        val cw = (w + 1) / 2
        val pixels = IntArray(w * h)

        parallelRows(h) { yStart, yEnd, _ ->
            for (y in yStart until yEnd) {
                val rowBase = y * w
                val cRow = (y / 2) * cw
                for (x in 0 until w) {
                    val i = rowBase + x
                    var lin = planes.luma[i] * params.exposureGain
                    lin = shoulder(lin, params.shoulderKnee)
                    val yv = toGamma[(lin * 4095f).toInt().coerceIn(0, 4095)]

                    val ci = cRow + (x / 2)
                    val uu = (planes.u[ci].toInt() and 0xFF) - 128
                    val vv = (planes.v[ci].toInt() and 0xFF) - 128

                    val r = (yv + 1.402f * vv).toInt().coerceIn(0, 255)
                    val g = (yv - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
                    val b = (yv + 1.772f * uu).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private const val NOISE_BINS = 16
    private const val NOISE_SAMPLE_STRIDE = 8
    private const val MAD_TO_SIGMA = 1.4826f

    fun binOf(linear: Float): Int =
        (linear * NOISE_BINS).toInt().coerceIn(0, NOISE_BINS - 1)

    /**
     * Estimates noise as a function of signal level directly from the burst,
     * so no ISO or exposure metadata is needed and the model adapts to whatever
     * the sensor is actually doing.
     *
     * Frame-to-frame differences in a static scene are noise. Taking a median
     * per brightness bin makes the estimate robust to the minority of pixels
     * that genuinely moved. Returns the squared tolerance per bin.
     */
    private fun estimateNoiseVariance(
        ref: YuvFrame,
        frames: List<YuvFrame>,
        fields: List<AlignmentField>,
        w: Int,
        h: Int,
        params: MergeParams,
    ): FloatArray {
        val floorVar = (params.noiseTolerance * params.minNoiseSigma).let { it * it }
        if (fields.isEmpty()) return FloatArray(NOISE_BINS) { floorVar }

        val samples = Array(NOISE_BINS) { ArrayList<Float>(512) }

        for (f in fields.indices) {
            val alt = frames[f + 1]
            val field = fields[f]
            val tileW = w.toFloat() / field.tilesX
            val tileH = h.toFloat() / field.tilesY

            var y = NOISE_SAMPLE_STRIDE
            while (y < h - NOISE_SAMPLE_STRIDE) {
                val ty = (y / tileH).toInt().coerceIn(0, field.tilesY - 1)
                var x = NOISE_SAMPLE_STRIDE
                while (x < w - NOISE_SAMPLE_STRIDE) {
                    val tx = (x / tileW).toInt().coerceIn(0, field.tilesX - 1)
                    val idx = ty * field.tilesX + tx
                    val sampled = sampleBilinear(
                        alt.y, w, h,
                        (x + field.dx[idx]).toFloat(),
                        (y + field.dy[idx]).toFloat(),
                    )
                    val refLin = toLinear[ref.y[y * w + x].toInt() and 0xFF]
                    val bin = binOf(refLin)
                    if (samples[bin].size < 4000) {
                        samples[bin].add(kotlin.math.abs(toLinear[sampled] - refLin))
                    }
                    x += NOISE_SAMPLE_STRIDE
                }
                y += NOISE_SAMPLE_STRIDE
            }
        }

        val out = FloatArray(NOISE_BINS)
        for (b in 0 until NOISE_BINS) {
            val list = samples[b]
            val sigma = if (list.size < 16) {
                params.minNoiseSigma
            } else {
                list.sort()
                val median = list[list.size / 2]
                // Difference of two noisy samples, so divide out the sqrt(2).
                (median * MAD_TO_SIGMA / 1.4142f).coerceAtLeast(params.minNoiseSigma)
            }
            val tol = params.noiseTolerance * sigma
            out[b] = tol * tol
        }

        // Fill bins that saw too few samples from their nearest populated neighbour.
        for (b in 0 until NOISE_BINS) {
            if (samples[b].size >= 16) continue
            var best = -1
            for (o in 1 until NOISE_BINS) {
                if (b - o >= 0 && samples[b - o].size >= 16) { best = b - o; break }
                if (b + o < NOISE_BINS && samples[b + o].size >= 16) { best = b + o; break }
            }
            if (best >= 0) out[b] = out[best]
        }
        return out
    }

    /** Exposed for tests: linear-light value of an 8-bit code. */
    fun linearOf(code: Int): Float = toLinear[code]

    private fun mergeChroma(
        frames: List<YuvFrame>,
        fields: List<AlignmentField>,
        ref: YuvFrame,
        cw: Int,
        chh: Int,
        uOut: ByteArray,
        vOut: ByteArray,
    ) {
        for (i in 0 until cw * chh) {
            var uSum = (ref.u[i].toInt() and 0xFF).toFloat()
            var vSum = (ref.v[i].toInt() and 0xFF).toFloat()
            var n = 1f
            for (f in fields.indices) {
                val alt = frames[f + 1]
                if (i < alt.u.size && i < alt.v.size) {
                    uSum += (alt.u[i].toInt() and 0xFF).toFloat()
                    vSum += (alt.v[i].toInt() and 0xFF).toFloat()
                    n += 1f
                }
            }
            uOut[i] = (uSum / n).toInt().coerceIn(0, 255).toByte()
            vOut[i] = (vSum / n).toInt().coerceIn(0, 255).toByte()
        }
    }

    /** Gentle highlight roll-off. Linear below the knee, exponential above it. */
    private fun shoulder(x: Float, knee: Float): Float {
        if (x <= knee) return x
        val headroom = 1f - knee
        return knee + headroom * (1f - exp(-(x - knee) / headroom))
    }

    private fun bilerp(v00: Float, v01: Float, v10: Float, v11: Float, wx: Float, wy: Float): Float {
        val top = v00 + (v01 - v00) * wx
        val bottom = v10 + (v11 - v10) * wx
        return top + (bottom - top) * wy
    }

    private fun sampleBilinear(data: ByteArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val cx = min(max(fx, 0f), (w - 1).toFloat())
        val cy = min(max(fy, 0f), (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = cx - x0
        val ay = cy - y0

        val p00 = data[y0 * w + x0].toInt() and 0xFF
        val p01 = data[y0 * w + x1].toInt() and 0xFF
        val p10 = data[y1 * w + x0].toInt() and 0xFF
        val p11 = data[y1 * w + x1].toInt() and 0xFF

        val top = p00 + (p01 - p00) * ax
        val bottom = p10 + (p11 - p10) * ax
        return (top + (bottom - top) * ay).toInt().coerceIn(0, 255)
    }

    private inline fun parallelRows(height: Int, crossinline body: (Int, Int, Int) -> Unit) {
        val band = (height + threads - 1) / threads
        val tasks = ArrayList<Callable<Unit>>(threads)
        for (t in 0 until threads) {
            val start = t * band
            val end = min(start + band, height)
            if (start >= end) continue
            tasks.add(Callable { body(start, end, t) })
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }
}
