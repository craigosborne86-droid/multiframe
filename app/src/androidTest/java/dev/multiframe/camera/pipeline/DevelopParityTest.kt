package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "DevelopParity"

/**
 * The native developer and the Kotlin one must produce the same picture.
 *
 * They are separate implementations of the same gradient-corrected demosaic and
 * tone path: native is what actually runs, Kotlin is the fallback and the one
 * the unit tests cover. That split is only safe while the two agree, and a
 * divergence would be invisible -- the fallback almost never runs, so a native
 * bug would ship and a Kotlin bug would hide. This is what makes the unit tests
 * meaningful evidence about production behaviour.
 */
@RunWith(AndroidJUnit4::class)
class DevelopParityTest {

    private val width = 64
    private val height = 48
    private val profile = SensorProfile.DEFAULT
    private val fixedGain = DevelopParams(exposureGain = 3.5f)

    /** A scene with edges, colour and noise, so every kernel branch is used. */
    private fun scene(): BayerFrame {
        val rnd = Random(7)
        val data = ShortArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val colour = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val base = when {
                    x < width / 3 -> 180
                    x < 2 * width / 3 -> 620
                    else -> 300
                }
                val tint = when (colour) {
                    0 -> 1.15f
                    2 -> 0.80f
                    else -> 1.0f
                }
                val v = base * tint + (y * 3) + rnd.nextInt(-18, 18)
                data[y * width + x] = v.toInt().coerceIn(64, 1023).toShort()
            }
        }
        return BayerFrame(width, height, data)
    }

    private fun directBufferOf(frame: BayerFrame): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(frame.data.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(frame.data)
        buffer.rewind()
        return buffer
    }

    /**
     * A larger scene, for the merge.
     *
     * The develop fixtures are 64x48, which is too small to merge honestly: the
     * noise estimator samples every eighth pixel and bins by brightness, and at
     * that size it has about three samples a bin and falls back to its floor --
     * a trap this log has already been caught by once. At 320x240 it has real
     * statistics, and the tile grid is 5x3 rather than the degenerate 1x1.
     */
    private fun mergeScene(w: Int, h: Int, seed: Int): BayerFrame {
        val rnd = Random(seed)
        val data = ShortArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val colour = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val base = 200 + 300 * kotlin.math.sin(x * 0.02) * kotlin.math.cos(y * 0.03)
                val tint = when (colour) {
                    0 -> 1.15f
                    2 -> 0.80f
                    else -> 1.0f
                }
                val v = base * tint + rnd.nextInt(-40, 40)
                data[y * w + x] = v.toInt().coerceIn(64, 1023).toShort()
            }
        }
        return BayerFrame(w, h, data)
    }

    /** The same scene displaced, which is what a hand-held burst produces. */
    private fun shiftedFrame(src: BayerFrame, sx: Int, sy: Int): BayerFrame {
        val out = ShortArray(src.width * src.height)
        for (y in 0 until src.height) {
            val ay = (y + sy).coerceIn(0, src.height - 1)
            for (x in 0 until src.width) {
                val ax = (x + sx).coerceIn(0, src.width - 1)
                out[y * src.width + x] = src.data[ay * src.width + ax]
            }
        }
        return BayerFrame(src.width, src.height, out)
    }

    /**
     * The merge itself, native against Kotlin.
     *
     * This was the gap. Develop, sharpening, shading and alignment are each
     * pinned to a Kotlin reference; the accumulation at the centre of the app
     * was not, and the only thing comparing the two implementations was a test
     * of the noise figure they report rather than the pixels they produce.
     *
     * Both merge the same pair of frames. The displacement is handed to the
     * native side rather than recomputed, because the two aligners already
     * agree exactly and this test is about the accumulation, not the search.
     */
    @Test
    fun nativeAndKotlinMergeAgree() {
        val w = 320
        val h = 240
        val reference = mergeScene(w, h, 11)
        val alternate = shiftedFrame(reference, 4, 2)

        val kotlinMerger = BayerAccumulator(w, h, profile)
        kotlinMerger.setReference(reference)
        kotlinMerger.add(alternate)
        val (kotlinFrame, kotlinStats) = kotlinMerger.finish()

        val native = NativeMerge.create(w, h, profile)
        assertThat(native).isNotNull()
        val stride = w * 2
        val refBuffer = directBufferOf(reference)
        val altBuffer = directBufferOf(alternate)

        native!!.setReference(refBuffer, stride)
        val tilesX = maxOf(1, native.proxyWidth / 32)
        val tilesY = maxOf(1, native.proxyHeight / 32)
        val field = Aligner.align(
            Aligner.buildPyramid(native.lumaProxy(refBuffer, stride)),
            Aligner.buildPyramid(native.lumaProxy(altBuffer, stride)),
            tilesX, tilesY,
        )
        native.addFrame(altBuffer, stride, field)
        val (mergedBuffer, nativeStats) = native.finish()

        val nativeData = ShortArray(w * h)
        mergedBuffer.rewind()
        mergedBuffer.asShortBuffer().get(nativeData)

        var worst = 0
        var differing = 0
        for (i in nativeData.indices) {
            val a = nativeData[i].toInt() and 0xFFFF
            val b = kotlinFrame.data[i].toInt() and 0xFFFF
            val delta = kotlin.math.abs(a - b)
            if (delta != 0) differing++
            if (delta > worst) worst = delta
        }
        Log.i(
            TAG,
            "merge parity: worst $worst, $differing of ${nativeData.size} differing; " +
                "native sigma %.2f vs kotlin %.2f".format(
                    nativeStats.estimatedSigmaAtMid, kotlinStats.estimatedSigmaAtMid,
                ),
        )

        native.close()
        assertThat(nativeStats.framesMerged).isEqualTo(kotlinStats.framesMerged)
        // Both accumulate the same float sums in the same order and divide at
        // the end, so anything above a rounding step means the two are doing
        // different arithmetic rather than the same arithmetic differently.
        assertThat(worst).isAtMost(1)
    }

    /**
     * The same accumulation, over displacements that differ from tile to tile.
     *
     * The test above hands the burst a single shift, which every tile shares.
     * That leaves the run structure of the native loop untested in the one way
     * it can go wrong: it walks a row as a sequence of runs of constant tile
     * column, and a boundary drawn one pixel out would take a pixel's
     * displacement from its neighbour. With one displacement across the image
     * that mistake is invisible, because the neighbour's is the same.
     *
     * So both implementations are handed the same field, made rather than
     * searched for. It includes shifts that push a run off each edge, and one
     * tile displaced clean out of the frame -- displacements no aligner would
     * return, which is the point: this compares the accumulation, not the
     * search.
     */
    @Test
    fun nativeAndKotlinMergeAgreeOnAVaryingField() {
        // Several widths, because the native loop walks each run of constant
        // tile column four pixels at a time and finishes the remainder one at a
        // time. At 320 the runs come out 64 wide and the remainder is almost
        // always empty, so a mistake at that boundary would never show. The
        // other two widths put the tile edges off the multiple of four and
        // exercise remainders of one, two and three.
        for (w in intArrayOf(320, 322, 326)) {
            assertVaryingFieldParity(w, 240)
        }
    }

    private fun assertVaryingFieldParity(w: Int, h: Int) {
        val reference = mergeScene(w, h, 11)
        // Independently noisy rather than a copy, so the weighting is exercised
        // in both directions instead of every pixel landing inside tolerance.
        val alternate = mergeScene(w, h, 23)

        val native = NativeMerge.create(w, h, profile)
        assertThat(native).isNotNull()
        val tilesX = maxOf(1, native!!.proxyWidth / 32)
        val tilesY = maxOf(1, native.proxyHeight / 32)
        val dx = IntArray(tilesX * tilesY)
        val dy = IntArray(tilesX * tilesY)
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val i = ty * tilesX + tx
                dx[i] = ((tx * 2 + ty) % 5) - 2
                dy[i] = ((tx + ty * 2) % 5) - 2
            }
        }
        // One tile that contributes nothing at all, which is the whole-run
        // rejection rather than the per-pixel one.
        dy[0] = h
        val field = AlignmentField(tilesX, tilesY, dx, dy)

        val kotlinMerger = BayerAccumulator(w, h, profile)
        kotlinMerger.setReference(reference)
        kotlinMerger.add(alternate, field)
        val (kotlinFrame, kotlinStats) = kotlinMerger.finish()

        val stride = w * 2
        native.setReference(directBufferOf(reference), stride)
        native.addFrame(directBufferOf(alternate), stride, field)
        val (mergedBuffer, nativeStats) = native.finish()

        val nativeData = ShortArray(w * h)
        mergedBuffer.rewind()
        mergedBuffer.asShortBuffer().get(nativeData)

        var worst = 0
        var differing = 0
        for (i in nativeData.indices) {
            val delta = abs(
                (nativeData[i].toInt() and 0xFFFF) - (kotlinFrame.data[i].toInt() and 0xFFFF),
            )
            if (delta != 0) differing++
            if (delta > worst) worst = delta
        }
        Log.i(
            TAG,
            "varying-field merge parity at ${w}x$h: worst $worst, $differing of " +
                "${nativeData.size} differing; contribution native %.4f vs kotlin %.4f".format(
                    nativeStats.meanContribution, kotlinStats.meanContribution,
                ),
        )

        native.close()
        // The mean contribution divides one running total by another, so it
        // only agrees if the same pixels were skipped as well as the same
        // arithmetic done on the ones that were not.
        assertThat(nativeStats.meanContribution)
            .isWithin(1e-4f).of(kotlinStats.meanContribution)
        assertThat(worst).isAtMost(1)
    }

    @Test
    fun nativeAndKotlinDevelopAgree() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val frame = scene()
        val color = ColorProfile(
            gains = floatArrayOf(1.9f, 1f, 1f, 1.6f),
            matrix = floatArrayOf(1.7f, -0.6f, -0.1f, -0.2f, 1.5f, -0.3f, 0f, -0.4f, 1.4f),
        )

        val expected = RawDeveloper.develop(frame, profile, color, fixedGain)

        val merger = NativeMerge.create(width, height, profile)
        assertThat(merger).isNotNull()
        val bitmap = merger!!.use { it.develop(directBufferOf(frame), color, fixedGain) }
        assertThat(bitmap).isNotNull()

        var worst = 0
        var sum = 0L
        var count = 0
        // Interior only: both use the same border fallback, but clamping at the
        // very edge differs by a pixel and is not what is being compared.
        for (y in 3 until height - 3) {
            for (x in 3 until width - 3) {
                val a = expected[y * width + x]
                val b = bitmap!!.getPixel(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    val da = (a shr shift) and 0xFF
                    val db = when (shift) {
                        16 -> Color.red(b)
                        8 -> Color.green(b)
                        else -> Color.blue(b)
                    }
                    val d = abs(da - db)
                    if (d > worst) worst = d
                    sum += d.toLong()
                    count++
                }
            }
        }
        bitmap!!.recycle()

        val mean = sum.toDouble() / count
        Log.i(TAG, "native vs Kotlin develop: worst %d/255, mean %.3f".format(worst, mean))

        // Both are float maths in a different order, so exact equality is not
        // the claim. A demosaic that had actually diverged would be off by tens.
        assertThat(worst).isAtMost(2)
        assertThat(mean).isLessThan(0.25)
    }

    /**
     * Defective sites are corrected identically in both implementations.
     *
     * They are found before shading and white balance in native, and on the CFA
     * in place in Kotlin, precisely so the comparison happens in the same
     * domain. If those diverged, a hot pixel would be replaced in one path and
     * not the other, and the fallback would produce a visibly different picture.
     */
    @Test
    fun hotPixelCorrectionAgreesAndActuallyRuns() {
        val w = 96
        val h = 72
        val profile = SensorProfile.DEFAULT
        // Dim, so the difference the correction makes is visible. At normal
        // exposure both the defect and its replacement clip at 255 and the
        // comparison shows nothing.
        val dim = fixedGain.copy(exposureGain = 0.5f)
        val data = ShortArray(w * h) { 420.toShort() }
        // A scattering of stuck-bright sites, kept well apart so none shields
        // another.
        val defects = listOf(20 to 20, 40 to 30, 60 to 50, 30 to 60)
        for ((x, y) in defects) data[y * w + x] = 1010.toShort()
        val frame = BayerFrame(w, h, data)

        val corrected = RawDeveloper.develop(
            BayerFrame(w, h, data.copyOf()), profile, ColorProfile.NEUTRAL, dim,
        )
        val merger = NativeMerge.create(w, h, profile)!!
        val nativeBitmap = merger.use {
            it.develop(directBufferOf(frame), ColorProfile.NEUTRAL, dim)
        }!!

        var worst = 0
        for (y in 3 until h - 3) {
            for (x in 3 until w - 3) {
                val a = corrected[y * w + x]
                val b = nativeBitmap.getPixel(x, y)
                worst = maxOf(
                    worst,
                    abs(((a shr 16) and 0xFF) - Color.red(b)),
                    abs(((a shr 8) and 0xFF) - Color.green(b)),
                )
            }
        }
        Log.i(TAG, "hot pixel parity: worst $worst/255")
        assertThat(worst).isAtMost(2)

        // And it has to actually have happened: with correction off the defect
        // is still there, so the two renders must differ at that site.
        val untouched = NativeMerge.create(w, h, profile)!!.use {
            it.develop(
                directBufferOf(BayerFrame(w, h, ShortArray(w * h) { i ->
                    if (defects.any { (dx, dy) -> dy * w + dx == i }) 1010 else 420
                }.map { v -> v.toShort() }.toShortArray())),
                ColorProfile.NEUTRAL,
                dim.copy(hotPixelThreshold = 0f),
            )
        }!!
        val (dx, dy) = defects.first()
        val withCorrection = Color.green(nativeBitmap.getPixel(dx, dy))
        val without = Color.green(untouched.getPixel(dx, dy))
        Log.i(TAG, "defective site: uncorrected $without, corrected $withCorrection")
        nativeBitmap.recycle()
        untouched.recycle()

        assertThat(without).isGreaterThan(withCorrection + 20)
    }

    /**
     * Sharpening actually runs in the binary that ships.
     *
     * The parity test cannot show this: if sharpening were silently disabled in
     * both implementations they would still agree perfectly. This guards the
     * wiring rather than the arithmetic.
     */
    @Test
    fun nativeSharpeningRunsAndRespectsItsThreshold() {
        val w = 96
        val h = 72
        val profile = SensorProfile.DEFAULT
        // A hard vertical edge, which is what sharpening acts on.
        val stepped = BayerFrame(w, h, ShortArray(w * h) { i ->
            if ((i % w) < w / 2) 250.toShort() else 700.toShort()
        })

        fun renderWith(params: DevelopParams): Bitmap {
            val merger = NativeMerge.create(w, h, profile)!!
            return merger.use {
                it.develop(directBufferOf(stepped), ColorProfile.NEUTRAL, params)!!
            }
        }

        val soft = renderWith(fixedGain.copy(sharpen = Sharpen.Params(amount = 0f)))
        val sharp = renderWith(fixedGain)

        fun contrastAcrossEdge(bitmap: Bitmap): Int {
            val row = h / 2
            return Color.green(bitmap.getPixel(w / 2, row)) -
                Color.green(bitmap.getPixel(w / 2 - 1, row))
        }

        val before = contrastAcrossEdge(soft)
        val after = contrastAcrossEdge(sharp)
        Log.i(TAG, "native sharpening: edge contrast $before -> $after")

        assertThat(after).isGreaterThan(before)

        // And a flat frame must come through untouched, or the threshold is
        // not doing its job and every sky gets its noise amplified.
        val flat = BayerFrame(w, h, ShortArray(w * h) { 500.toShort() })
        val flatSoft = NativeMerge.create(w, h, profile)!!.use {
            it.develop(directBufferOf(flat), ColorProfile.NEUTRAL,
                fixedGain.copy(sharpen = Sharpen.Params(amount = 0f)))!!
        }
        val flatSharp = NativeMerge.create(w, h, profile)!!.use {
            it.develop(directBufferOf(flat), ColorProfile.NEUTRAL, fixedGain)!!
        }
        var differing = 0
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                if (flatSoft.getPixel(x, y) != flatSharp.getPixel(x, y)) differing++
            }
        }
        Log.i(TAG, "flat frame pixels changed by sharpening: $differing")
        assertThat(differing).isEqualTo(0)

        soft.recycle(); sharp.recycle(); flatSoft.recycle(); flatSharp.recycle()
    }

    /**
     * The merge reports the noise it actually measured.
     *
     * This was reading zero, which is worse than useless: it looked like a
     * measurement and was a placeholder, so every claim about merge quality
     * rested on nothing.
     */
    @Test
    fun theMergeReportsTheNoiseItMeasured() {
        // Large and flat at mid grey on purpose. The estimator samples every
        // eighth pixel and bins by brightness, so a small frame spread across
        // sixteen bins has too few samples in any one of them and falls back to
        // its floor -- which is what a 64x48 frame did, reporting the same
        // figure for a quiet burst and a violently noisy one.
        val w = 256
        val h = 192
        val profile = SensorProfile.DEFAULT
        val flat = BayerFrame(w, h, ShortArray(w * h) { 512.toShort() })

        fun sigmaOver(amplitude: Int): Float {
            val merger = NativeMerge.create(w, h, profile)!!
            return merger.use { m ->
                m.setReference(directBufferOf(flat), w * 2)
                val rnd = kotlin.random.Random(5)
                repeat(2) {
                    val noisy = ShortArray(w * h) {
                        (512 + rnd.nextInt(-amplitude, amplitude + 1))
                            .coerceIn(0, 1023).toShort()
                    }
                    m.addFrame(
                        directBufferOf(BayerFrame(w, h, noisy)),
                        w * 2,
                        AlignmentField(1, 1, IntArray(1), IntArray(1)),
                    )
                }
                m.finish().second.estimatedSigmaAtMid
            }
        }

        val quiet = sigmaOver(4)
        val loud = sigmaOver(40)
        Log.i(TAG, "measured sigma: quiet burst %.2f, noisy burst %.2f".format(quiet, loud))

        assertThat(quiet).isGreaterThan(0f)
        // Ten times the noise has to read as substantially noisier, or the
        // number is not measuring anything.
        assertThat(loud).isGreaterThan(quiet * 3f)
    }

    @Test
    fun nativeAndKotlinAgreeWithLensShadingApplied() {
        // Shading is applied to the raw sample before white balance and
        // demosaic, in two separate implementations. A disagreement here would
        // show as corners that differ between the native path and the fallback.
        val frame = scene()
        val color = ColorProfile(
            gains = floatArrayOf(1.8f, 1f, 1f, 1.5f),
            matrix = ColorProfile.NEUTRAL.matrix,
        )

        val columns = 5
        val rows = 5
        val gains = FloatArray(columns * rows * 4)
        val cx = (columns - 1) / 2f
        val cy = (rows - 1) / 2f
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val dx = c - cx
                val dy = r - cy
                val t = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() /
                    kotlin.math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
                for (channel in 0 until 4) {
                    // Channel-dependent, as real falloff is: corners are a
                    // different colour as well as darker.
                    gains[(r * columns + c) * 4 + channel] =
                        1f + (0.9f + 0.2f * channel) * t * t
                }
            }
        }
        val shading = ShadingMap(columns, rows, gains)

        val expected = RawDeveloper.develop(frame, profile, color, fixedGain, shading)
        val merger = NativeMerge.create(width, height, profile)!!
        val bitmap = merger.use {
            it.develop(directBufferOf(frame), color, fixedGain, shading)
        }!!

        var worst = 0
        for (y in 3 until height - 3) {
            for (x in 3 until width - 3) {
                val a = expected[y * width + x]
                val b = bitmap.getPixel(x, y)
                worst = maxOf(
                    worst,
                    abs(((a shr 16) and 0xFF) - Color.red(b)),
                    abs(((a shr 8) and 0xFF) - Color.green(b)),
                    abs((a and 0xFF) - Color.blue(b)),
                )
            }
        }
        bitmap.recycle()
        Log.i(TAG, "native vs Kotlin with shading: worst $worst/255")

        assertThat(worst).isAtMost(2)
    }

    @Test
    fun shadingActuallyBrightensTheCorners() {
        // Guards the wiring rather than the maths: if the map were dropped on
        // the way to native, the parity test above would still pass.
        // Deliberately dim, so the correction has somewhere to go. At normal
        // exposure the corner is already near clipping and a doubled gain only
        // moves it a few codes, which would demonstrate nothing.
        val frame = BayerFrame(width, height, ShortArray(width * height) { 200.toShort() })
        val dim = DevelopParams(exposureGain = 0.5f)
        val columns = 3
        val rows = 3
        val gains = FloatArray(columns * rows * 4) { 1f }
        for (channel in 0 until 4) {
            // Only the bottom-right cell is boosted.
            gains[(2 * columns + 2) * 4 + channel] = 2.0f
        }
        val shading = ShadingMap(columns, rows, gains)

        val merger = NativeMerge.create(width, height, profile)!!
        val plain = merger.use {
            it.develop(directBufferOf(frame), ColorProfile.NEUTRAL, dim)
        }!!
        val merger2 = NativeMerge.create(width, height, profile)!!
        val corrected = merger2.use {
            it.develop(directBufferOf(frame), ColorProfile.NEUTRAL, dim, shading)
        }!!

        val plainCorner = Color.green(plain.getPixel(width - 5, height - 5))
        val correctedCorner = Color.green(corrected.getPixel(width - 5, height - 5))
        val plainCentre = Color.green(plain.getPixel(width / 2, height / 2))
        val correctedCentre = Color.green(corrected.getPixel(width / 2, height / 2))
        Log.i(
            TAG,
            "shading: corner $plainCorner -> $correctedCorner, " +
                "centre $plainCentre -> $correctedCentre",
        )
        plain.recycle()
        corrected.recycle()

        // A doubled linear gain is a full stop, which is not a doubled output
        // code: the gamma encode and the S-curve both compress it. Asserted as
        // a proportion so the threshold means something rather than being a
        // number that happened to pass.
        assertThat(correctedCorner.toFloat()).isGreaterThan(plainCorner * 1.25f)
        assertThat(correctedCentre).isEqualTo(plainCentre)
    }

    /**
     * The rendering curve, measured on the binary that actually runs.
     *
     * Measured against the same render with contrast switched off, rather than
     * as an absolute slope. The composite slope from sensor code to output byte
     * is dominated by the sRGB encode, which is steepest near black by
     * construction, so an absolute measurement says nothing about the S-curve
     * sitting on top of it. The difference between the two renders is the
     * curve's actual contribution.
     */
    @Test
    fun nativeRenderingCurveIsMonotonicAndAddsMidtoneContrast() {
        fun ramp(params: DevelopParams): IntArray {
            val levels = (80..1000 step 20).toList()
            return IntArray(levels.size) { i ->
                val flat = BayerFrame(width, height, ShortArray(width * height) {
                    levels[i].toShort()
                })
                val merger = NativeMerge.create(width, height, profile)!!
                val bitmap = merger.use {
                    it.develop(directBufferOf(flat), ColorProfile.NEUTRAL, params)
                }!!
                val v = Color.green(bitmap.getPixel(width / 2, height / 2))
                bitmap.recycle()
                v
            }
        }

        val curved = ramp(fixedGain)
        val flat = ramp(fixedGain.copy(contrast = 0f))

        // A curve that ever descends turns a smooth sky into bands.
        for (i in 1 until curved.size) {
            assertThat(curved[i]).isAtLeast(curved[i - 1])
        }

        var darkened = 0
        var brightened = 0
        for (i in curved.indices) {
            if (flat[i] in 25..105 && curved[i] < flat[i]) darkened++
            if (flat[i] in 150..235 && curved[i] > flat[i]) brightened++
        }
        Log.i(
            TAG,
            "S-curve: $darkened shadow steps darkened, $brightened highlight steps lifted",
        )

        // The definition of an S: separation bought in the midtones by
        // spending it at both ends.
        assertThat(darkened).isGreaterThan(0)
        assertThat(brightened).isGreaterThan(0)
        assertThat(curved.first()).isLessThan(curved.last())
    }
}
