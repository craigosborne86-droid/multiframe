package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

/**
 * Sub-pixel accumulation against a scene whose truth is known.
 *
 * This is the test the algorithm exists to pass. A high-resolution scene is
 * rendered, sampled through a simulated Bayer grid at four sub-pixel offsets
 * -- the classic quad shift that tiles the half-pixel positions -- and
 * accumulated. The result is compared against the scene it was sampled from.
 * The recovered detail is measured rather than a matter of opinion.
 */
class SubPixelAccumulatorTest {

    private val sensorW = 64
    private val sensorH = 48
    private val canvasW = sensorW * 2
    private val canvasH = sensorH * 2

    /** A 10-bit sensor with an RGGB pattern and a black level of 64. */
    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 64, 64, 64),
        whiteLevel = 1023,
        cfaPattern = intArrayOf(0, 1, 1, 2),
    )

    /**
     * The scene, at canvas resolution, as linear RGB. Structure at every
     * scale: a slow gradient, a set of bars at the sensor's Nyquist that a
     * single Bayer frame aliases, and distinct colour so a channel swap cannot
     * hide.
     *
     * ### Why the bars are two sensor pixels wide and not finer
     *
     * A bar period of [barPeriod] canvas cells is two sensor pixels: the
     * sensor's Nyquist, the canonical super-resolution target. A single frame
     * aliases it -- red and blue, sampled every two sensor pixels, see it as a
     * flat field or a beat -- and sub-pixel shifts resolve it. That is the
     * claim this path makes and the case it is tested on.
     *
     * Finer than that -- bars at three canvas cells, a sensor pixel and a
     * half -- is below the *reference's* per-colour Nyquist, and the reference
     * cannot say what it expects there. The robustness weight then reads the
     * alternates' legitimate fine detail as disagreement and attenuates it.
     * The detail survives (see `detailFinerThanASensorPixelSurvives`, which
     * uses the finer bars deliberately) but at reduced contrast and with fill
     * where every sample was rejected. That is a documented limit of comparing
     * against a bandwidth-limited reference, not a placement error, and it is
     * kept out of the placement tests so they measure placement.
     */
    private val barPeriod = 4

    private fun scene(period: Int = barPeriod): FloatArray {
        val out = FloatArray(canvasW * canvasH * 3)
        for (j in 0 until canvasH) {
            for (i in 0 until canvasW) {
                val gx = i.toFloat() / canvasW
                val gy = j.toFloat() / canvasH
                val bar = if (j in canvasH / 3 until 2 * canvasH / 3 && (i / period) % 2 == 0) 200f else 0f
                val r = 300f + 400f * gx + bar
                val g = 350f + 300f * gy + bar * 0.8f
                val b = 250f + 350f * (1f - gx) + bar * 0.6f
                out[(j * canvasW + i) * 3 + 0] = r
                out[(j * canvasW + i) * 3 + 1] = g
                out[(j * canvasW + i) * 3 + 2] = b
            }
        }
        return out
    }

    /** The scene value of [channel] at a fractional canvas position, bilinear. */
    private fun sceneAt(s: FloatArray, channel: Int, u: Float, v: Float): Float {
        val cu = u.coerceIn(0f, (canvasW - 1).toFloat())
        val cv = v.coerceIn(0f, (canvasH - 1).toFloat())
        val i0 = cu.toInt()
        val j0 = cv.toInt()
        val i1 = minOf(i0 + 1, canvasW - 1)
        val j1 = minOf(j0 + 1, canvasH - 1)
        val tx = cu - i0
        val ty = cv - j0
        fun at(i: Int, j: Int) = s[(j * canvasW + i) * 3 + channel]
        val top = at(i0, j0) + (at(i1, j0) - at(i0, j0)) * tx
        val bot = at(i0, j1) + (at(i1, j1) - at(i0, j1)) * tx
        return top + (bot - top) * ty
    }

    /**
     * A Bayer frame of the scene, with the sensor displaced by ([sx], [sy])
     * sensor pixels. Sensor pixel (x, y) sees the scene at canvas position
     * 2 * (x + sx, y + sy), and reads only its own CFA colour.
     */
    private fun bayerFrame(s: FloatArray, sx: Float, sy: Float): BayerFrame {
        val data = ShortArray(sensorW * sensorH)
        for (y in 0 until sensorH) {
            for (x in 0 until sensorW) {
                val c = Demosaic.colourAt(profile, x, y)
                val v = sceneAt(s, c, 2f * (x + sx), 2f * (y + sy))
                data[y * sensorW + x] = v.toInt().coerceIn(0, 1023).toShort()
            }
        }
        return BayerFrame(sensorW, sensorH, data)
    }

    /**
     * An alignment field describing a uniform shift of ([sx], [sy]) sensor
     * pixels, split into integer and fraction in proxy units the way the
     * aligner would report it.
     *
     * The convention, from BayerAccumulator: for reference pixel x, the alt
     * sample is at x + dx*2. A frame displaced by +sx sees the scene shifted
     * left, so the scene content at reference x is found at alt x - sx. Hence
     * dx*2 = -sx.
     */
    private fun fieldFor(sx: Float, sy: Float): AlignmentField {
        val tilesX = maxOf(1, (sensorW / 2) / SubPixelAccumulator.TILE_TARGET)
        val tilesY = maxOf(1, (sensorH / 2) / SubPixelAccumulator.TILE_TARGET)
        val n = tilesX * tilesY
        val proxyX = -sx / 2f
        val proxyY = -sy / 2f
        val ix = kotlin.math.round(proxyX).toInt()
        val iy = kotlin.math.round(proxyY).toInt()
        return AlignmentField(
            tilesX, tilesY,
            IntArray(n) { ix }, IntArray(n) { iy },
            FloatArray(n) { proxyX - ix }, FloatArray(n) { proxyY - iy },
        )
    }

    /**
     * The scene as the kernel would see it: convolved with the same Gaussian
     * the accumulator places samples with. A comparison against the raw scene
     * conflates two things -- whether the samples landed where they should,
     * and how much a sigma-0.5 kernel softens a hard edge. The second is a
     * known, documented property of the kernel choice, not an error in the
     * placement. Comparing against the blurred truth isolates the first.
     */
    private fun blurredScene(s: FloatArray): FloatArray {
        val sigma = SubPixelAccumulator.SIGMA
        val radius = SubPixelAccumulator.RADIUS
        val inv2s2 = 1f / (2f * sigma * sigma)
        val out = FloatArray(s.size)
        for (j in 0 until canvasH) {
            for (i in 0 until canvasW) {
                for (c in 0 until 3) {
                    var acc = 0f
                    var wsum = 0f
                    val j0 = kotlin.math.floor(j - radius).toInt().coerceAtLeast(0)
                    val j1 = kotlin.math.ceil(j + radius).toInt().coerceAtMost(canvasH - 1)
                    val i0 = kotlin.math.floor(i - radius).toInt().coerceAtLeast(0)
                    val i1 = kotlin.math.ceil(i + radius).toInt().coerceAtMost(canvasW - 1)
                    for (jj in j0..j1) for (ii in i0..i1) {
                        val d2 = ((ii - i) * (ii - i) + (jj - j) * (jj - j)).toFloat()
                        if (d2 > radius * radius) continue
                        val k = kotlin.math.exp(-d2 * inv2s2)
                        acc += k * s[(jj * canvasW + ii) * 3 + c]
                        wsum += k
                    }
                    out[(j * canvasW + i) * 3 + c] = acc / wsum
                }
            }
        }
        return out
    }

    private fun rms(a: FloatArray, b: FloatArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val d = (a[i] - b[i]).toDouble()
            sum += d * d
        }
        return sqrt(sum / count)
    }

    /** Interior of the canvas, clear of the border the kernel cannot fill. */
    private fun interior(rgb: FloatArray, truth: FloatArray, margin: Int = 4): Pair<FloatArray, FloatArray> {
        val a = ArrayList<Float>()
        val b = ArrayList<Float>()
        for (j in margin until canvasH - margin) {
            for (i in margin until canvasW - margin) {
                for (c in 0 until 3) {
                    a.add(rgb[(j * canvasW + i) * 3 + c])
                    b.add(truth[(j * canvasW + i) * 3 + c])
                }
            }
        }
        return a.toFloatArray() to b.toFloatArray()
    }

    /** RMS of one channel over the interior. */
    private fun channelRms(rgb: FloatArray, truth: FloatArray, channel: Int, margin: Int = 4): Double {
        var sum = 0.0
        var n = 0
        for (j in margin until canvasH - margin) for (i in margin until canvasW - margin) {
            val idx = (j * canvasW + i) * 3 + channel
            val d = (rgb[idx] - truth[idx]).toDouble()
            sum += d * d
            n++
        }
        return sqrt(sum / n)
    }

    /**
     * Frames at every sub-pixel offset a channel needs. Red and blue are
     * sampled every two sensor pixels -- every four canvas cells -- so half-
     * pixel shifts reach only two of the four positions; 0, ½, 1 and 1½ reach
     * all of them. Sixteen frames, and every canvas cell of every channel is
     * sampled directly.
     */
    private fun fullLattice(s: FloatArray, acc: SubPixelAccumulator) {
        acc.setReference(bayerFrame(s, 0f, 0f))
        for (sy in listOf(0f, 0.5f, 1f, 1.5f)) for (sx in listOf(0f, 0.5f, 1f, 1.5f)) {
            if (sx == 0f && sy == 0f) continue
            acc.add(bayerFrame(s, sx, sy), fieldFor(sx, sy))
        }
    }

    // --- the claim ---------------------------------------------------------

    /**
     * With every canvas cell directly sampled in every channel, the
     * accumulation is a measurement everywhere and the only error left is the
     * kernel's softening. Against the kernel-blurred truth that should be
     * small, and the hole fraction should be near zero.
     */
    @Test
    fun aFullLatticeRecoversTheSceneWithoutHoles() {
        val s = scene()
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        fullLattice(s, acc)
        val result = acc.finish()

        assertThat(result.framesMerged).isEqualTo(16)
        // The reported fraction counts the border, where a shifted frame's
        // samples fall off the canvas and nothing replaces them. A few percent
        // on a 128x96 canvas is that border. The interior is the claim.
        assertThat(result.interiorHoleFraction).isEqualTo(0f)

        val blurred = blurredScene(s)
        val errs = (0 until 3).map { channelRms(result.rgb, blurred, it) }
        println("full lattice: holes total=%.4f interior=%.4f  rms R=%.1f G=%.1f B=%.1f  contrib=%.3f".format(
            result.holeFraction, result.interiorHoleFraction, errs[0], errs[1], errs[2], result.meanContribution))
        for (e in errs) assertThat(e).isLessThan(15.0)
    }

    /**
     * Four frames at the quad-shift offsets -- (0,0), (½,0), (0,½), (½,½).
     *
     * This is the classic super-resolution burst, and it covers *green*
     * completely: green is sampled every sensor pixel, every two canvas cells,
     * and half-pixel shifts reach both. Red and blue are sampled every two
     * sensor pixels, and the same shifts reach only half their canvas cells.
     * The rest are filled from neighbours, and on a gradient that fill carries
     * error. So the channels are held separately: green tight, red and blue
     * to what a fill can do.
     *
     * A real burst has neither four frames nor exact half-pixel steps -- it
     * has eight to thirty at whatever a hand does -- and covers red and blue
     * statistically. This test pins what the geometry guarantees.
     */
    @Test
    fun aQuadShiftedBurstCoversGreenAndFillsRedAndBlue() {
        val s = scene()
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(bayerFrame(s, 0f, 0f))
        acc.add(bayerFrame(s, 0.5f, 0f), fieldFor(0.5f, 0f))
        acc.add(bayerFrame(s, 0f, 0.5f), fieldFor(0f, 0.5f))
        acc.add(bayerFrame(s, 0.5f, 0.5f), fieldFor(0.5f, 0.5f))
        val result = acc.finish()

        assertThat(result.width).isEqualTo(canvasW)
        assertThat(result.height).isEqualTo(canvasH)
        assertThat(result.framesMerged).isEqualTo(4)

        val blurred = blurredScene(s)
        val gErr = channelRms(result.rgb, blurred, 1)
        val rErr = channelRms(result.rgb, blurred, 0)
        val bErr = channelRms(result.rgb, blurred, 2)
        println("quad shift: holes total=%.4f interior=%.4f  rms R=%.1f G=%.1f B=%.1f  contrib=%.3f".format(
            result.holeFraction, result.interiorHoleFraction, rErr, gErr, bErr, result.meanContribution))

        // Green: every cell reached by a sample or its immediate neighbour.
        // Red and blue: a quarter of cells reached by nothing, and filled.
        // Filled cells on a gradient carry error, and at bar edges the fill
        // takes a value from the wrong side. So green is held tighter than
        // red and blue, and the gap between them is the finding: coverage,
        // not the algorithm, separates the channels.
        assertThat(gErr).isLessThan(rErr)
        assertThat(gErr).isLessThan(bErr)
        // The hole fraction is what the geometry says: about a quarter of red
        // and blue cells, none of green, so about a sixth over three channels.
        assertThat(result.interiorHoleFraction).isWithin(0.08f).of(1f / 6f)
    }

    /**
     * Detail *below* the sensor's Nyquist: bars every 3 canvas cells, a sensor
     * pixel and a half. This is past what the reference can represent per
     * colour, so the robustness weight attenuates it -- see the note on
     * [barPeriod]. The detail should nonetheless survive with most of its
     * contrast, which is what separates attenuation from loss.
     */
    @Test
    fun detailFinerThanASensorPixelSurvives() {
        val s = scene(period = 3)
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(bayerFrame(s, 0f, 0f))
        acc.add(bayerFrame(s, 0.5f, 0f), fieldFor(0.5f, 0f))
        acc.add(bayerFrame(s, 0f, 0.5f), fieldFor(0f, 0.5f))
        acc.add(bayerFrame(s, 0.5f, 0.5f), fieldFor(0.5f, 0.5f))
        val rgb = acc.finish().rgb

        // Contrast of the green channel along a row through the bars: the
        // difference between bar and gap cells, against the truth's.
        val j = canvasH / 2
        var gotContrast = 0.0
        var trueContrast = 0.0
        var n = 0
        for (i in 6 until canvasW - 6) {
            val a = (j * canvasW + i) * 3 + 1
            val b = (j * canvasW + i + 3) * 3 + 1
            gotContrast += kotlin.math.abs(rgb[a] - rgb[b])
            trueContrast += kotlin.math.abs(s[a] - s[b])
            n++
        }
        gotContrast /= n
        trueContrast /= n
        // At least half the true contrast survives. The kernel softens and the
        // robustness attenuates, but a path that lost the bars would read near
        // zero here.
        assertThat(gotContrast).isGreaterThan(trueContrast * 0.5)
    }

    /**
     * Detail *at* the sensor's Nyquist: bars two sensor pixels wide. One frame
     * aliases this; the burst resolves it. The canonical case, and the
     * contrast should come through nearly whole.
     */
    @Test
    fun detailAtTheSensorsNyquistIsResolved() {
        val s = scene()
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        fullLattice(s, acc)
        val rgb = acc.finish().rgb

        val j = canvasH / 2
        var gotContrast = 0.0
        var trueContrast = 0.0
        var n = 0
        for (i in 8 until canvasW - 8) {
            val a = (j * canvasW + i) * 3 + 1
            val b = (j * canvasW + i + barPeriod) * 3 + 1
            gotContrast += kotlin.math.abs(rgb[a] - rgb[b])
            trueContrast += kotlin.math.abs(s[a] - s[b])
            n++
        }
        gotContrast /= n
        trueContrast /= n
        assertThat(gotContrast).isGreaterThan(trueContrast * 0.8)
    }

    // --- the null ----------------------------------------------------------

    /**
     * A burst with no sub-pixel diversity -- every frame at the same offset --
     * is the tripod case. Each channel's cells are sampled at exactly the
     * positions one frame samples them, and the rest are holes. The hole
     * fraction is the honest measurement of that, and it has to be high.
     */
    @Test
    fun aBurstWithoutDiversityReportsItsHoles() {
        val s = scene()
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(bayerFrame(s, 0f, 0f))
        acc.add(bayerFrame(s, 0f, 0f), fieldFor(0f, 0f))
        acc.add(bayerFrame(s, 0f, 0f), fieldFor(0f, 0f))
        val result = acc.finish()

        // A Bayer frame at 2x fills, per channel, one cell in eight for red and
        // blue and one in four for green, before the kernel spreads them. The
        // kernel reaches the immediate neighbours, so the fraction is lower
        // than the raw sampling gap -- but with no diversity it stays well
        // above what the quad shift achieves.
        assertThat(result.holeFraction).isGreaterThan(0.15f)
    }

    /** And the quad shift's hole fraction is far below the tripod's. */
    @Test
    fun diversityIsWhatClosesTheHoles() {
        val s = scene()

        val still = SubPixelAccumulator(sensorW, sensorH, profile)
        still.setReference(bayerFrame(s, 0f, 0f))
        still.add(bayerFrame(s, 0f, 0f), fieldFor(0f, 0f))
        val stillHoles = still.finish().holeFraction

        val moved = SubPixelAccumulator(sensorW, sensorH, profile)
        moved.setReference(bayerFrame(s, 0f, 0f))
        moved.add(bayerFrame(s, 0.5f, 0.5f), fieldFor(0.5f, 0.5f))
        val movedHoles = moved.finish().holeFraction

        assertThat(movedHoles).isLessThan(stillHoles)
    }

    // --- invariants --------------------------------------------------------

    /**
     * The reference always contributes. Alts that disagree with it everywhere
     * are rejected by the robustness weight, and what remains is the
     * reference -- a valid picture, never one built from rejected samples.
     */
    @Test
    fun theReferenceIsNeverDiscounted() {
        val s = scene()
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(bayerFrame(s, 0f, 0f))

        // A frame of the wrong scene entirely.
        val wrong = ShortArray(sensorW * sensorH) { i -> ((i * 37) % 900 + 64).toShort() }
        acc.add(BayerFrame(sensorW, sensorH, wrong), fieldFor(0f, 0f))
        val result = acc.finish()

        // Low mean contribution: the wrong frame was mostly rejected.
        assertThat(result.meanContribution).isLessThan(0.3f)

        // And at the reference's own positions, the reference's value is what
        // dominates.
        val ref = bayerFrame(s, 0f, 0f)
        var agree = 0
        var total = 0
        for (y in 4 until sensorH - 4 step 3) {
            for (x in 4 until sensorW - 4 step 3) {
                val c = Demosaic.colourAt(profile, x, y)
                val refV = (ref.data[y * sensorW + x].toInt() and 0xFFFF).toFloat()
                val got = result.rgb[((2 * y) * canvasW + 2 * x) * 3 + c]
                if (kotlin.math.abs(got - refV) < 60f) agree++
                total++
            }
        }
        assertThat(agree.toFloat() / total).isGreaterThan(0.85f)
    }

    /**
     * Gain normalisation, as in the ordinary merge. A frame at four times the
     * ISO reads four times the value; divided by its gainScale it lands on the
     * reference's scale and the merge sees one exposure.
     */
    @Test
    fun aHighGainFrameIsNormalisedBeforeItIsPlaced() {
        // A darker scene, so a doubled frame has room under white. The bars
        // are left out: this is about the scale, not the detail.
        val s = FloatArray(canvasW * canvasH * 3)
        for (j in 0 until canvasH) for (i in 0 until canvasW) {
            val gx = i.toFloat() / canvasW
            val gy = j.toFloat() / canvasH
            s[(j * canvasW + i) * 3 + 0] = 120f + 200f * gx
            s[(j * canvasW + i) * 3 + 1] = 140f + 150f * gy
            s[(j * canvasW + i) * 3 + 2] = 100f + 180f * (1f - gx)
        }

        val gain = 2f
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(bayerFrame(s, 0f, 0f))
        // The full lattice, every frame boosted and declared. Full coverage
        // so no fill error muddies the comparison.
        for (sy in listOf(0f, 0.5f, 1f, 1.5f)) for (sx in listOf(0f, 0.5f, 1f, 1.5f)) {
            if (sx == 0f && sy == 0f) continue
            val base = bayerFrame(s, sx, sy)
            val boosted = BayerFrame(sensorW, sensorH, ShortArray(base.data.size) { i ->
                ((base.data[i].toInt() and 0xFFFF) * gain).toInt().coerceAtMost(1023).toShort()
            })
            acc.add(boosted, fieldFor(sx, sy), gainScale = gain)
        }
        val result = acc.finish()

        // The normalised frames agree with the scene and are accepted -- a
        // frame on the wrong scale would be rejected wholesale.
        assertThat(result.meanContribution).isGreaterThan(0.7f)

        val blurred = blurredScene(s)
        for (c in 0 until 3) {
            assertThat(channelRms(result.rgb, blurred, c)).isLessThan(15.0)
        }
    }

    /** The same frame, *not* normalised, is what a wrong gainScale would look like. */
    @Test
    fun anUnnormalisedHighGainFrameIsRejected() {
        val s = scene()
        val base = bayerFrame(s, 0.5f, 0.5f)
        val boosted = BayerFrame(sensorW, sensorH, ShortArray(base.data.size) { i ->
            ((base.data[i].toInt() and 0xFFFF) * 2).coerceAtMost(1023).toShort()
        })

        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(bayerFrame(s, 0f, 0f))
        acc.add(boosted, fieldFor(0.5f, 0.5f), gainScale = 1f)
        val result = acc.finish()

        // Twice the value everywhere is far outside the noise tolerance.
        assertThat(result.meanContribution).isLessThan(0.3f)
    }

    @Test
    fun theCanvasIsTwiceTheSensor() {
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        assertThat(acc.width).isEqualTo(2 * sensorW)
        assertThat(acc.height).isEqualTo(2 * sensorH)
    }

    @Test
    fun addingBeforeAReferenceIsAnError() {
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        try {
            acc.add(bayerFrame(scene(), 0f, 0f), fieldFor(0f, 0f))
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("setReference")
        }
    }
}
