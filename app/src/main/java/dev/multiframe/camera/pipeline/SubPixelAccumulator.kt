package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The result of a sub-pixel accumulation: full colour at twice the sensor's
 * resolution, and what it took to get there.
 */
class SubPixelResult(
    /** Canvas width, twice the sensor's. */
    val width: Int,
    val height: Int,
    /** Linear RGB, interleaved, `width * height * 3` floats in sensor units. */
    val rgb: FloatArray,
    /**
     * Cells that ended with no sample of some channel and were filled from a
     * neighbour, as a fraction of all cell-channels. The honest measure of
     * whether the burst had the sub-pixel diversity this path depends on.
     */
    val holeFraction: Float,
    /**
     * The same, over the interior only -- a border of [INTERIOR_MARGIN] cells
     * excluded. A shifted frame's samples fall off the canvas at the edge and
     * nothing replaces them; that is geometry, not a lack of diversity, and
     * this figure is the one that speaks to the burst.
     */
    val interiorHoleFraction: Float,
    /** Mean robustness weight over every placed alt sample. */
    val meanContribution: Float,
    val framesMerged: Int,
) {
    companion object {
        const val INTERIOR_MARGIN = 4
    }
}

/**
 * The measurement demosaicing throws away, kept.
 *
 * ### What this does differently
 *
 * A Bayer sensor measures one colour per photosite. The ordinary path merges
 * the burst on that grid and then invents the other two colours at every site
 * by interpolating neighbours -- as well as a linear method can, but two thirds
 * of every output pixel is still a guess.
 *
 * Between frames the sensor moves a fraction of a photosite, so a site that
 * measured green in one frame sits over a slightly different part of the scene
 * in the next, and across a burst red, green and blue samples land all over
 * the scene at sub-pixel spacing. The missing colours were measured. This
 * accumulates the raw samples directly onto a double-resolution canvas at their
 * measured sub-pixel positions, per channel, and the picture that falls out
 * has real colour at every cell. No demosaic.
 *
 * ### What it needs, and where it gets it
 *
 * Sub-pixel alignment. `AlignmentField.fracDx`/`fracDy` are the parabolic
 * refinement of the integer search, and this is the one consumer of them: the
 * ordinary merge cannot spend a fraction because an odd shift in the CFA
 * domain lands a red sample on a green site. Here each channel has its own
 * canvas, so a fraction is not a parity problem -- it is the whole point.
 *
 * ### What it keeps from the ordinary merge
 *
 * Three things, unchanged: gain normalisation so a DCG burst accumulates on one
 * scale; robustness weighting so a sample that disagrees with the reference
 * contributes little; and the reference frame's guaranteed contribution, so no
 * cell is composed entirely of rejected samples. The robustness comparison is
 * made at the *integer*-aligned reference position, exactly as the ordinary
 * merge makes it, which keeps the two samples on the same colour plane. The
 * fraction is spent on placement alone.
 *
 * ### This is the reference
 *
 * Kotlin, whole-canvas, no strips. Simple enough to be read and trusted, and
 * what the unit tests cover. The native implementation processes the canvas in
 * strips for memory and is held to this within the parity bound.
 */
class SubPixelAccumulator(
    /** Sensor dimensions. The canvas is twice each. */
    private val sensorWidth: Int,
    private val sensorHeight: Int,
    private val profile: SensorProfile,
    private val params: BayerMergeParams = BayerMergeParams(),
) {
    val width = sensorWidth * 2
    val height = sensorHeight * 2

    // Per channel: weighted sum and weight. Three channels, two planes each.
    private val acc = Array(3) { FloatArray(width * height) }
    private val wsum = Array(3) { FloatArray(width * height) }

    private var reference: BayerFrame? = null
    private var noiseVar: FloatArray? = null
    private var merged = 0
    private var contributionSum = 0.0
    private var contributionCount = 0L

    // The tile grid, derived exactly as the aligner derives it. See the note
    // on this in BayerAccumulator: a floating-point division here drifted the
    // merge's idea of a tile from the aligner's by the right-hand edge.
    private val tilesX = maxOf(1, (sensorWidth / 2) / TILE_TARGET)
    private val tilesY = maxOf(1, (sensorHeight / 2) / TILE_TARGET)
    private val tileW = maxOf(1, (sensorWidth / 2) / tilesX)
    private val tileH = maxOf(1, (sensorHeight / 2) / tilesY)

    /**
     * The reference frame. Every sample lands at exactly twice its sensor
     * position with weight one: this is the guaranteed contribution.
     */
    fun setReference(frame: BayerFrame) {
        require(frame.width == sensorWidth && frame.height == sensorHeight)
        reference = frame
        for (y in 0 until sensorHeight) {
            for (x in 0 until sensorWidth) {
                val c = Demosaic.colourAt(profile, x, y)
                val v = (frame.data[y * sensorWidth + x].toInt() and 0xFFFF).toFloat()
                splat(c, 2f * x, 2f * y, v, 1f)
            }
        }
        merged = 1
    }

    /**
     * An alternate frame, placed at its sub-pixel positions.
     *
     * [field] must be aligned onto the reference's proxy. [gainScale] is the
     * ratio of this frame's ISO to the reference's; samples are divided by it
     * before comparison and accumulation, so a DCG burst lands on one scale.
     */
    fun add(frame: BayerFrame, field: AlignmentField, gainScale: Float = 1f) {
        val ref = reference ?: error("setReference first")
        require(frame.width == sensorWidth && frame.height == sensorHeight)
        val invGain = 1f / gainScale

        if (noiseVar == null) noiseVar = estimateNoise(ref, frame, field, invGain)
        val nv = noiseVar!!

        var localContribution = 0.0
        var localCount = 0L

        for (ay in 0 until sensorHeight) {
            val ty = ((ay / 2) / tileH).coerceIn(0, tilesY - 1)
            for (ax in 0 until sensorWidth) {
                val tx = ((ax / 2) / tileW).coerceIn(0, tilesX - 1)
                val idx = ty * tilesX + tx

                // The sub-pixel displacement of this tile: dx + fracDx in proxy
                // pixels, twice that in sensor pixels. Where in the reference's
                // frame this alt sample's content sits.
                val dispX = (field.dx[idx] + field.fracDx[idx]) * 2f
                val dispY = (field.dy[idx] + field.fracDy[idx]) * 2f
                val fx = ax - dispX
                val fy = ay - dispY
                if (fx < 0f || fy < 0f || fx > sensorWidth - 1f || fy > sensorHeight - 1f) continue

                val rawAlt = (frame.data[ay * sensorWidth + ax].toInt() and 0xFFFF).toFloat()
                // A clipped high-ISO sample carries no information about the
                // scene above the clip; the reference has it.
                if (invGain < 1f && rawAlt >= profile.whiteLevel.toFloat()) continue
                val altV = rawAlt * invGain

                // The robustness comparison, against the reference *at the
                // fractional position*, interpolated on this sample's own colour
                // lattice. Not at the integer alignment: a half-pixel shift at a
                // bar edge means the alt legitimately saw the bar and the
                // reference's integer-aligned site did not, and comparing them
                // rejects precisely the sample that carries the sub-pixel
                // information. The ordinary merge can compare at the integer
                // because it only ever places at the integer; this places at
                // the fraction and must ask the reference what it expects there.
                sampleReference(ref, ax, ay, fx, fy, referenceSample)
                val refV = referenceSample.value
                val d = altV - refV
                val d2 = d * d
                // Tolerance: the noise, plus what the reference does not know
                // about this position. Where the reference is flat the second
                // term vanishes and this is the ordinary merge's test; where
                // it has an edge the tolerance opens by half the step, and a
                // sample that reads the step is kept.
                val unc = referenceSample.uncertainty
                val n2 = nv[binOf(refV)] + unc * unc
                val r = if (d2 <= n2) 1f else n2 / d2

                // Placement on the canvas: twice the reference-frame position.
                val u = 2f * fx
                val v = 2f * fy

                val c = Demosaic.colourAt(profile, ax, ay)
                splat(c, u, v, altV, r)

                localContribution += r
                localCount++
            }
        }

        contributionSum += localContribution
        contributionCount += localCount
        merged++
    }

    /**
     * The reference's value at a fractional sensor position, on the colour
     * lattice the sample at ([ax], [ay]) belongs to.
     *
     * Same-colour sites in a Bayer mosaic form a lattice of period two in each
     * axis -- red at (even, even), blue at (odd, odd), and green as two such
     * lattices, one per diagonal. A sample's own lattice is the one with its
     * parity. This interpolates the reference bilinearly across the four
     * lattice sites surrounding ([fx], [fy]), which is the reference's best
     * statement of what that colour is at that sub-pixel position.
     */
    private fun referenceSameColourAt(ref: BayerFrame, ax: Int, ay: Int, fx: Float, fy: Float): Float {
        val s = referenceSample
        sampleReference(ref, ax, ay, fx, fy, s)
        return s.value
    }

    /** The reference's interpolated value and what it does not know about it. */
    private class ReferenceSample {
        var value = 0f
        /**
         * Half the total variation across the lattice cell the position lies
         * in. A linear interpolation of a step edge is wrong by up to this
         * much at the midpoint, and a sample there that reads the true step
         * is not an outlier.
         */
        var uncertainty = 0f
    }

    private val referenceSample = ReferenceSample()

    /**
     * Interpolates the reference on the sample's own colour lattice at a
     * fractional position, and estimates how far the interpolation could be
     * from the truth there.
     *
     * The uncertainty is the part that matters for robustness. The reference
     * samples each colour every two sensor pixels. Between two of its sites
     * the scene can do anything -- in particular, step -- and a bilinear ramp
     * across the gap says the midpoint is the average. An alternate frame
     * shifted half a pixel sees the true value at that midpoint, and if the
     * scene stepped there the two disagree by half the step. That is not
     * noise and not motion; it is the reference not having sampled there. The
     * robustness tolerance is widened by this so the alternate is kept, which
     * is the whole point: it is carrying the information the reference lacks.
     */
    private fun sampleReference(ref: BayerFrame, ax: Int, ay: Int, fx: Float, fy: Float, out: ReferenceSample) {
        val px = ax and 1
        val py = ay and 1
        // Largest lattice site at or below the position, on this parity.
        var x0 = kotlin.math.floor(fx).toInt()
        if ((x0 and 1) != px) x0--
        var y0 = kotlin.math.floor(fy).toInt()
        if ((y0 and 1) != py) y0--
        val x1 = x0 + 2
        val y1 = y0 + 2
        val tx = (fx - x0) / 2f
        val ty = (fy - y0) / 2f

        fun at(x: Int, y: Int): Float {
            val cx = x.coerceIn(px, sensorWidth - 1 - ((sensorWidth - 1 - px) and 1))
            val cy = y.coerceIn(py, sensorHeight - 1 - ((sensorHeight - 1 - py) and 1))
            return (ref.data[cy * sensorWidth + cx].toInt() and 0xFFFF).toFloat()
        }
        val v00 = at(x0, y0)
        val v10 = at(x1, y0)
        val v01 = at(x0, y1)
        val v11 = at(x1, y1)

        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        out.value = top + (bottom - top) * ty

        // Total variation across the cell, halved. Both axes, since the edge
        // could run either way.
        val varX = maxOf(abs(v10 - v00), abs(v11 - v01))
        val varY = maxOf(abs(v01 - v00), abs(v11 - v10))
        out.uncertainty = 0.5f * maxOf(varX, varY)
    }

    /**
     * Spreads one sample over the canvas cells within the kernel's reach.
     *
     * The kernel is a Gaussian of sigma [SIGMA] canvas cells, truncated at
     * [RADIUS]. Simple, with no parameter to tune per scene, and it cannot
     * introduce structure that was not measured. A sample at (u, v) reaches the
     * cells whose centres lie within the radius -- three per axis in the
     * general case, so nine cells.
     */
    private fun splat(channel: Int, u: Float, v: Float, value: Float, robustness: Float) {
        val a = acc[channel]
        val w = wsum[channel]
        val i0 = kotlin.math.floor(u - RADIUS).toInt().coerceAtLeast(0)
        val i1 = kotlin.math.ceil(u + RADIUS).toInt().coerceAtMost(width - 1)
        val j0 = kotlin.math.floor(v - RADIUS).toInt().coerceAtLeast(0)
        val j1 = kotlin.math.ceil(v + RADIUS).toInt().coerceAtMost(height - 1)

        for (j in j0..j1) {
            val dy = j - v
            for (i in i0..i1) {
                val dx = i - u
                val d2 = dx * dx + dy * dy
                if (d2 > RADIUS * RADIUS) continue
                val k = exp(-d2 * INV_TWO_SIGMA2)
                val wt = k * robustness
                val cell = j * width + i
                a[cell] += wt * value
                w[cell] += wt
            }
        }
    }

    /**
     * Resolves the canvas, fills what the burst did not reach, and reports.
     *
     * A cell whose weight for some channel is below [HOLE_THRESHOLD] received
     * no meaningful sample of that colour. It is filled from the nearest cell
     * that did -- the one place in this path where a value is inferred rather
     * than measured, confined to cells the burst genuinely did not reach and
     * counted, because a rising hole fraction is the signal that the burst
     * lacked the diversity this depends on.
     */
    fun finish(): SubPixelResult {
        val rgb = FloatArray(width * height * 3)
        var holes = 0L
        var interiorHoles = 0L
        val cells = width.toLong() * height
        val m = SubPixelResult.INTERIOR_MARGIN
        val interiorCells = (width - 2L * m).coerceAtLeast(0) * (height - 2L * m).coerceAtLeast(0)

        for (c in 0 until 3) {
            val a = acc[c]
            val w = wsum[c]
            for (j in 0 until height) {
                val inside = j >= m && j < height - m
                for (i in 0 until width) {
                    val cell = j * width + i
                    if (w[cell] >= HOLE_THRESHOLD) {
                        rgb[cell * 3 + c] = a[cell] / w[cell]
                    } else {
                        rgb[cell * 3 + c] = Float.NaN
                        holes++
                        if (inside && i >= m && i < width - m) interiorHoles++
                    }
                }
            }
        }

        // Fill. NaN marks a hole; the nearest resolved cell of the same channel
        // within the search radius supplies the value.
        for (c in 0 until 3) {
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val cell = j * width + i
                    if (!rgb[cell * 3 + c].isNaN()) continue
                    rgb[cell * 3 + c] = nearestResolved(rgb, c, i, j)
                }
            }
        }

        val mean = if (contributionCount > 0) (contributionSum / contributionCount).toFloat() else 0f
        return SubPixelResult(
            width, height, rgb,
            holeFraction = (holes.toDouble() / (cells * 3)).toFloat(),
            interiorHoleFraction = if (interiorCells > 0) {
                (interiorHoles.toDouble() / (interiorCells * 3)).toFloat()
            } else 0f,
            meanContribution = mean,
            framesMerged = merged,
        )
    }

    /**
     * The nearest cell of [channel] that resolved, searched outward in rings.
     * Falls back to the sensor's black level if nothing within reach did --
     * which would mean a channel with no samples anywhere near, and is not a
     * case a real burst produces.
     */
    private fun nearestResolved(rgb: FloatArray, channel: Int, i: Int, j: Int): Float {
        for (r in 1..FILL_RADIUS) {
            for (dj in -r..r) {
                val jj = j + dj
                if (jj < 0 || jj >= height) continue
                for (di in -r..r) {
                    if (abs(di) != r && abs(dj) != r) continue
                    val ii = i + di
                    if (ii < 0 || ii >= width) continue
                    val v = rgb[(jj * width + ii) * 3 + channel]
                    if (!v.isNaN()) return v
                }
            }
        }
        // The black level is per CFA position, not per colour; the minimum
        // across positions is the darkest a sensor reads and a fair floor.
        return (profile.blackLevel.minOrNull() ?: 0).toFloat()
    }

    /**
     * Noise against signal level, from the difference between alternate
     * samples and the reference *at their fractional positions*.
     *
     * The same comparison the robustness weight makes, and it has to be: the
     * tolerance this produces is what that weight is measured against, and a
     * tolerance estimated at the integer alignment would describe a different
     * difference -- one that includes the sub-pixel shift itself as though it
     * were noise, and is too loose for the fractional comparison by exactly
     * the amount the shift contributes.
     */
    private fun estimateNoise(
        ref: BayerFrame,
        alt: BayerFrame,
        field: AlignmentField,
        invGain: Float,
    ): FloatArray {
        val samples = Array(NOISE_BINS) { ArrayList<Float>(256) }
        var ay = 2
        while (ay < sensorHeight - 2) {
            val ty = ((ay / 2) / tileH).coerceIn(0, tilesY - 1)
            var ax = 2
            while (ax < sensorWidth - 2) {
                val tx = ((ax / 2) / tileW).coerceIn(0, tilesX - 1)
                val idx = ty * tilesX + tx
                val fx = ax - (field.dx[idx] + field.fracDx[idx]) * 2f
                val fy = ay - (field.dy[idx] + field.fracDy[idx]) * 2f
                if (fx >= 1f && fy >= 1f && fx <= sensorWidth - 2f && fy <= sensorHeight - 2f) {
                    val rawAlt = alt.data[ay * sensorWidth + ax].toInt() and 0xFFFF
                    if (rawAlt < profile.whiteLevel) {
                        sampleReference(ref, ax, ay, fx, fy, referenceSample)
                        // Only where the reference is flat enough to be a fair
                        // witness. At an edge the difference includes the
                        // interpolation's own error, which is not noise, and
                        // letting it into the estimate would loosen the
                        // tolerance for the whole frame.
                        if (referenceSample.uncertainty <= FLAT_FOR_NOISE) {
                            val refV = referenceSample.value
                            val altV = rawAlt.toFloat() * invGain
                            val b = binOf(refV)
                            if (samples[b].size < 3000) samples[b].add(abs(altV - refV))
                        }
                    }
                }
                ax += SAMPLE_STRIDE
            }
            ay += SAMPLE_STRIDE
        }

        val out = FloatArray(NOISE_BINS)
        for (b in 0 until NOISE_BINS) {
            val list = samples[b]
            val sigma = if (list.size < 16) params.minNoiseSigma else {
                list.sort()
                (list[list.size / 2] * MAD_TO_SIGMA / 1.4142f).coerceAtLeast(params.minNoiseSigma)
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
        ((value / (profile.whiteLevel + 1).toFloat()) * NOISE_BINS).toInt().coerceIn(0, NOISE_BINS - 1)

    companion object {
        const val TILE_TARGET = 32
        const val NOISE_BINS = 16
        const val SAMPLE_STRIDE = 7
        const val MAD_TO_SIGMA = 1.4826f

        /** Kernel width, in canvas cells. Half a cell: tight enough to keep detail. */
        const val SIGMA = 0.5f
        /** Truncation, at two sigma. */
        const val RADIUS = 1.0f
        private const val INV_TWO_SIGMA2 = 1f / (2f * SIGMA * SIGMA)

        /**
         * Below this total weight a cell is a hole. A single reference sample
         * at its own position contributes about 1.0 to the cell it lands on
         * and 0.37 to each cardinal neighbour, so a cell no sample was placed
         * near sits well under this.
         */
        const val HOLE_THRESHOLD = 0.05f

        /** How far the fill looks. Beyond this a hole is not a sampling gap. */
        const val FILL_RADIUS = 3

        /**
         * A reference cell whose total variation is under this, in sensor
         * counts, is flat enough that the difference across it is noise and
         * not interpolation error. Sampled for the noise estimate; cells above
         * it are not. Two percent of a 10-bit range.
         */
        const val FLAT_FOR_NOISE = 20f
    }
}
