package dev.multiframe.camera.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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

    // The tile grid the aligner actually searched on, which is a *truncating*
    // division of the proxy: `Aligner.align` takes `tileW = ref.width / tilesX`
    // and matches tile tx over `[tx * tileW, (tx + 1) * tileW)`. This used to
    // divide in floating point instead, and at 4080x3064 that put the merge's
    // idea of a tile 32.38 proxy columns wide against the aligner's 32 -- so by
    // the right-hand edge a displacement was being applied 24 proxy columns
    // (48 sensor columns) from the content it had been measured on. Deriving
    // the grid the same way the search does removes the drift by construction.
    private val tilesX = maxOf(1, (width / 2) / TILE_TARGET)
    private val tilesY = maxOf(1, (height / 2) / TILE_TARGET)
    private val tileW = maxOf(1, (width / 2) / tilesX)
    private val tileH = maxOf(1, (height / 2) / tilesY)

    // Blending weights for the overlapped tiles; see [raisedCosine].
    private val winFarX = raisedCosine(tileW)
    private val winFarY = raisedCosine(tileH)

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
    fun add(frame: BayerFrame, gainScale: Float = 1f) {
        val pyramid = refPyramid ?: error("setReference first")
        require(frame.width == width && frame.height == height)

        add(
            frame,
            Aligner.align(pyramid, Aligner.buildPyramid(lumaProxy(frame, gainScale)), tilesX, tilesY),
            gainScale,
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
     *
     * **Tiles overlap by half and are blended, and that is what stops the
     * merge coming out in visible squares.** Each sample lies between four tile
     * centres and takes a contribution from all four, weighted by a separable
     * modified raised cosine that sums to exactly one -- the arrangement in
     * Hasinoff et al. 2016 and its independent reproduction by Monod, Delon and
     * Veit (IPOL 2021, section 4.4), which states the reason plainly: adjacent
     * stacks are merged independently, from different content, with different
     * noise estimates and different alignment errors, so continuity between
     * them is not guaranteed.
     *
     * Before this, a sample took the whole of one tile's displacement and none
     * of its neighbour's. Two failures followed from that, and the second is
     * the one that shows on a tripod:
     *
     *  - neighbouring tiles disagreeing by one proxy pixel put a two-pixel step
     *    across the seam, every 64 sensor pixels, over the whole frame;
     *  - and even where every tile is *right*, a tile that kept less of the
     *    alternate frame is a tile that denoised less, so the seam is visible
     *    as a change of grain with no change of content at all.
     *
     * The paper overlaps the tiles for the *search* as well, at four times the
     * alignment cost. That is not affordable here -- the search is 83% of the
     * merge as it stands -- so the search still runs on the non-overlapping
     * grid and only the blend overlaps. The cost of that shortcut is that
     * between two tile centres a displacement is being applied half a tile from
     * where it was matched; the robustness weight is what absorbs it, since a
     * sample that disagrees with the reference past the noise floor loses its
     * weight and the pixel falls back on the reference. The failure is less
     * denoising in that band, not a ghost -- and never a seam.
     */
    fun add(frame: BayerFrame, field: AlignmentField, gainScale: Float = 1f) {
        val ref = reference ?: error("setReference first")
        require(frame.width == width && frame.height == height)
        require(field.tilesX == tilesX && field.tilesY == tilesY) {
            "field is ${field.tilesX}x${field.tilesY}, accumulator is ${tilesX}x$tilesY"
        }

        val invGain = 1f / gainScale
        if (noiseVar == null) noiseVar = estimateNoise(ref, frame, field, invGain)
        val nv = noiseVar!!

        var localContribution = 0.0

        for (y in 0 until height) {
            // Half-resolution tile row, since alignment ran on the proxy. The
            // offset by half a tile puts the sample between two tile *centres*
            // rather than inside one tile's extent, which is what makes the
            // pair below the two tiles the sample lies between.
            val py = y / 2 + tileH / 2
            val tyB = (py / tileH).coerceIn(0, tilesY - 1)
            val tyA = (py / tileH - 1).coerceIn(0, tilesY - 1)
            val wyB = winFarY[py % tileH]
            val wyA = 1f - wyB
            val rowBase = y * width

            for (x in 0 until width) {
                val px = x / 2 + tileW / 2
                val txB = (px / tileW).coerceIn(0, tilesX - 1)
                val txA = (px / tileW - 1).coerceIn(0, tilesX - 1)
                val wxB = winFarX[px % tileW]
                val wxA = 1f - wxB

                val refV = (ref.data[rowBase + x].toInt() and 0xFFFF).toFloat()
                val n2 = nv[binOf(refV)]

                var acc = 0f
                var accW = 0f
                for (j in 0..1) {
                    val ty = if (j == 0) tyA else tyB
                    val wy = if (j == 0) wyA else wyB
                    for (i in 0..1) {
                        val idx = ty * tilesX + (if (i == 0) txA else txB)
                        val win = wy * (if (i == 0) wxA else wxB)

                        // Doubling a proxy offset always yields an even shift,
                        // which keeps every sample on its own colour plane.
                        // Blending the four *samples* rather than the four
                        // displacements is what preserves that: an interpolated
                        // displacement would be fractional, and half of those
                        // are odd.
                        val sx = x + field.dx[idx] * 2
                        val sy = y + field.dy[idx] * 2
                        if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue

                        val rawAlt = (frame.data[sy * width + sx].toInt() and 0xFFFF).toFloat()
                        if (invGain < 1f && rawAlt >= profile.whiteLevel.toFloat()) continue
                        val altV = rawAlt * invGain
                        val d = altV - refV
                        val d2 = d * d
                        val w = if (d2 <= n2) 1f else n2 / d2

                        acc += altV * w * win
                        accW += w * win
                    }
                }

                sum[rowBase + x] += acc
                weight[rowBase + x] += accW
                localContribution += accW
            }
        }

        contributionSum += localContribution
        // Every pixel counts, including one whose four sources all fell outside
        // the frame. It used to count only the pixels that had a source, which
        // made the denominator depend on which of four samples landed where --
        // a figure the native side would have had to reproduce exactly to stay
        // in parity, for a readout quoted as a whole percent. A pixel with no
        // source kept nothing, and now says so.
        contributionCount += width.toLong() * height
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
    private fun lumaProxy(frame: BayerFrame, gainScale: Float = 1f): Plane {
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
                val norm = ((s - black) / (range * 4f * gainScale)).coerceIn(0f, 1f)
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
        invGain: Float = 1f,
    ): FloatArray {
        val samples = Array(NOISE_BINS) { ArrayList<Float>(256) }

        // Nearest tile centre, which on this grid is just the tile the sample
        // falls in. No blending here: this is a median over thousands of
        // samples a bin and a seam in the sampling cannot bias it.
        var y = 2
        while (y < height - 2) {
            val ty = ((y / 2) / tileH).coerceIn(0, tilesY - 1)
            var x = 2
            while (x < width - 2) {
                val tx = ((x / 2) / tileW).coerceIn(0, tilesX - 1)
                val idx = ty * tilesX + tx
                val sx = x + field.dx[idx] * 2
                val sy = y + field.dy[idx] * 2
                if (sx in 0 until width && sy in 0 until height) {
                    val rawAlt = alt.data[sy * width + sx].toInt() and 0xFFFF
                    if (rawAlt >= profile.whiteLevel) { x += SAMPLE_STRIDE; continue }
                    val refV = (ref.data[y * width + x].toInt() and 0xFFFF).toFloat()
                    val altV = rawAlt.toFloat() * invGain
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
        /**
         * Weight owed to the *further* of the two tile centres a sample lies
         * between, tabulated for a sample `k` past the nearer one.
         *
         * The modified raised cosine of Hasinoff et al. 2016,
         * `w(u) = 1/2 - 1/2 cos(2 pi (u + 1/2) / n)` over a window of
         * `n = 2 * tile` samples. Windows that overlap by half sit half a
         * period apart, and `cos(t + pi) = -cos(t)` then makes the pair sum to
         * exactly one -- which is why the nearer tile's weight is taken as
         * `1 - this` rather than tabulated separately, and why the total weight
         * at a sample is one whatever the arithmetic does. A blend whose
         * weights did not sum to one would change the brightness it blended.
         *
         * The half-sample offset in `u` is the "modified" part: it keeps the
         * window off zero at both ends, so a tile still says something about
         * the samples at the far edge of its reach.
         */
        private fun raisedCosine(tile: Int) = FloatArray(tile) { k ->
            (0.5 - 0.5 * cos(PI * (k + 0.5) / tile)).toFloat()
        }

        private const val TILE_TARGET = 32
        private const val NOISE_BINS = 16
        private const val SAMPLE_STRIDE = 8
        private const val MAD_TO_SIGMA = 1.4826f
    }
}
