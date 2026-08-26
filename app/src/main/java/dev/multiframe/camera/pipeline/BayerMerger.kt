package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

data class BayerMergeParams(
    /** How many estimated noise sigma before a frame loses weight. */
    val noiseTolerance: Float = 3.0f,
    val minNoiseSigma: Float = 0.5f,
)

data class BayerMergeStats(
    val framesMerged: Int,
    val meanContribution: Float,
    val estimatedSigmaAtMid: Float,
)

/**
 * Merges raw CFA frames in the sensor's own linear domain.
 *
 * Two things make this different from the YUV merge:
 *
 * 1. Raw data is linear, so averaging is photometrically correct with no
 *    transfer function assumed. The YUV path has to guess sRGB gamma and
 *    BT.601 range; here there is nothing to guess.
 * 2. Alignment offsets must be even. An odd offset would land a red sample on a
 *    green one and destroy colour. Alignment therefore runs on a half-resolution
 *    luma proxy built from each 2x2 CFA cell, so doubling its offsets yields
 *    even full-resolution offsets by construction.
 *
 * Frames are accumulated one at a time so a burst never has to be resident all
 * at once, which matters when a single frame is 25 MB.
 */
class BayerAccumulator(
    val width: Int,
    val height: Int,
    private val profile: SensorProfile,
    private val params: BayerMergeParams = BayerMergeParams(),
) {

    // Two full-resolution float buffers at 12.5 MP is 100 MB of Java heap, so
    // they are released as soon as the merge is finished rather than lingering
    // while the render buffers are allocated.
    private var sum = FloatArray(width * height)
    private var weight = FloatArray(width * height)

    private var reference: BayerFrame? = null
    private var refPyramid: List<Plane>? = null
    private var released = false
    private var noiseVar: FloatArray? = null

    private var merged = 0
    private var contributionSum = 0.0
    private var contributionCount = 0L

    private val tilesX = maxOf(1, (width / 2) / TILE_TARGET)
    private val tilesY = maxOf(1, (height / 2) / TILE_TARGET)

    fun setReference(frame: BayerFrame) {
        require(frame.width == width && frame.height == height)
        reference = frame
        refPyramid = Aligner.buildPyramid(lumaProxy(frame))
        for (i in sum.indices) {
            sum[i] = (frame.data[i].toInt() and 0xFFFF).toFloat()
            weight[i] = 1f
        }
        merged = 1
    }

    /** Aligns [frame] onto the reference and merges it. */
    fun add(frame: BayerFrame) {
        val pyramid = refPyramid ?: error("setReference first")
        require(frame.width == width && frame.height == height)

        add(
            frame,
            Aligner.align(pyramid, Aligner.buildPyramid(lumaProxy(frame)), tilesX, tilesY),
        )
    }

    /**
     * Merges [frame] against an alignment already found.
     *
     * The search and the accumulation are separable, and the native side has
     * always taken them apart -- `nAddFrame` is handed a field. Splitting this
     * one the same way lets a test hand both implementations the same
     * displacements, including displacements no aligner would return, which is
     * the only way to compare the accumulation rather than the search.
     */
    fun add(frame: BayerFrame, field: AlignmentField) {
        val ref = reference ?: error("setReference first")
        require(frame.width == width && frame.height == height)

        if (noiseVar == null) noiseVar = estimateNoise(ref, frame, field)
        val nv = noiseVar!!

        val tileW = (width / 2).toFloat() / tilesX
        val tileH = (height / 2).toFloat() / tilesY
        var localContribution = 0.0
        var localCount = 0L

        for (y in 0 until height) {
            // Half-resolution tile row, since alignment ran on the proxy.
            val hy = y / 2
            val ty = (hy / tileH).toInt().coerceIn(0, tilesY - 1)
            val rowBase = y * width

            for (x in 0 until width) {
                val hx = x / 2
                val tx = (hx / tileW).toInt().coerceIn(0, tilesX - 1)
                val idx = ty * tilesX + tx

                // Doubling a proxy offset always yields an even shift, which
                // keeps every sample on its own colour plane.
                val sx = x + field.dx[idx] * 2
                val sy = y + field.dy[idx] * 2
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue

                val refV = (ref.data[rowBase + x].toInt() and 0xFFFF).toFloat()
                val altV = (frame.data[sy * width + sx].toInt() and 0xFFFF).toFloat()

                val d = altV - refV
                val n2 = nv[binOf(refV)]
                val d2 = d * d
                val w = if (d2 <= n2) 1f else n2 / d2

                sum[rowBase + x] += altV * w
                weight[rowBase + x] += w
                localContribution += w
                localCount++
            }
        }

        contributionSum += localContribution
        contributionCount += localCount
        merged++
    }

    /**
     * Frees the accumulation buffers, the reference frame and its pyramid.
     * Call once [finish] has produced the merged frame; nothing else is usable
     * afterwards.
     */
    fun release() {
        if (released) return
        released = true
        sum = FloatArray(0)
        weight = FloatArray(0)
        reference = null
        refPyramid = null
    }

    fun finish(): Pair<BayerFrame, BayerMergeStats> {
        check(!released) { "accumulator already released" }
        val out = ShortArray(width * height)
        val ceiling = profile.whiteLevel
        for (i in out.indices) {
            val v = (sum[i] / weight[i]).toInt().coerceIn(0, ceiling)
            out[i] = v.toShort()
        }
        val stats = BayerMergeStats(
            framesMerged = merged,
            meanContribution = if (contributionCount == 0L) 0f
            else (contributionSum / contributionCount).toFloat(),
            estimatedSigmaAtMid = noiseVar?.let {
                sqrt(it[NOISE_BINS / 2].toDouble()).toFloat() / params.noiseTolerance
            } ?: 0f,
        )
        return BayerFrame(width, height, out) to stats
    }

    /**
     * Half-resolution greyscale from each 2x2 CFA cell. A cell always holds one
     * red, one blue and two green samples whatever the pattern order, so their
     * mean is a valid luma proxy without needing to know the pattern.
     */
    private fun lumaProxy(frame: BayerFrame): Plane {
        val w = width / 2
        val h = height / 2
        val out = ByteArray(w * h)
        val range = profile.range.toFloat()
        for (y in 0 until h) {
            val r0 = (y * 2) * width
            val r1 = r0 + width
            for (x in 0 until w) {
                val c = x * 2
                val s = (frame.data[r0 + c].toInt() and 0xFFFF) +
                    (frame.data[r0 + c + 1].toInt() and 0xFFFF) +
                    (frame.data[r1 + c].toInt() and 0xFFFF) +
                    (frame.data[r1 + c + 1].toInt() and 0xFFFF)
                val black = profile.blackAt(c, y * 2) * 4
                val norm = ((s - black) / (range * 4f)).coerceIn(0f, 1f)
                out[y * w + x] = (norm * 255f).toInt().toByte()
            }
        }
        return Plane(w, h, out)
    }

    /**
     * Estimates noise against signal level from the difference between two
     * aligned frames. In a static scene that difference is noise, and its median
     * per brightness bin is robust to the pixels that genuinely moved.
     */
    private fun estimateNoise(
        ref: BayerFrame,
        alt: BayerFrame,
        field: AlignmentField,
    ): FloatArray {
        val samples = Array(NOISE_BINS) { ArrayList<Float>(256) }
        val tileW = (width / 2).toFloat() / tilesX
        val tileH = (height / 2).toFloat() / tilesY

        var y = 2
        while (y < height - 2) {
            val ty = ((y / 2) / tileH).toInt().coerceIn(0, tilesY - 1)
            var x = 2
            while (x < width - 2) {
                val tx = ((x / 2) / tileW).toInt().coerceIn(0, tilesX - 1)
                val idx = ty * tilesX + tx
                val sx = x + field.dx[idx] * 2
                val sy = y + field.dy[idx] * 2
                if (sx in 0 until width && sy in 0 until height) {
                    val refV = (ref.data[y * width + x].toInt() and 0xFFFF).toFloat()
                    val altV = (alt.data[sy * width + sx].toInt() and 0xFFFF).toFloat()
                    val b = binOf(refV)
                    if (samples[b].size < 3000) samples[b].add(abs(altV - refV))
                }
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }

        val out = FloatArray(NOISE_BINS)
        for (b in 0 until NOISE_BINS) {
            val list = samples[b]
            val sigma = if (list.size < 16) {
                params.minNoiseSigma
            } else {
                list.sort()
                // Median absolute difference of two noisy samples: convert to a
                // per-frame sigma via the Gaussian constant, then the sqrt(2).
                (list[list.size / 2] * MAD_TO_SIGMA / 1.4142f)
                    .coerceAtLeast(params.minNoiseSigma)
            }
            val tol = params.noiseTolerance * sigma
            out[b] = tol * tol
        }
        for (b in 0 until NOISE_BINS) {
            if (samples[b].size >= 16) continue
            for (o in 1 until NOISE_BINS) {
                if (b - o >= 0 && samples[b - o].size >= 16) { out[b] = out[b - o]; break }
                if (b + o < NOISE_BINS && samples[b + o].size >= 16) { out[b] = out[b + o]; break }
            }
        }
        return out
    }

    private fun binOf(value: Float): Int =
        ((value / (profile.whiteLevel + 1).toFloat()) * NOISE_BINS)
            .toInt().coerceIn(0, NOISE_BINS - 1)

    companion object {
        private const val TILE_TARGET = 32
        private const val NOISE_BINS = 16
        private const val SAMPLE_STRIDE = 8
        private const val MAD_TO_SIGMA = 1.4826f
    }
}
