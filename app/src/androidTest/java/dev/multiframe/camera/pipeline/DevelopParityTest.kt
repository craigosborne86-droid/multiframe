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
