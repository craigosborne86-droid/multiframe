package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "LinearDevelopParity"

/**
 * The native linear develop against the Kotlin reference, on a canvas the
 * native accumulator built.
 *
 * The same shape as DevelopParityTest for the CFA develop, and the same bound:
 * every byte within two codes, the mean well under a quarter. Two codes because
 * the native roll-off is a table (see ShoulderLut in Tone.h) and the display
 * chain quantises its input before the table, so a value that lands on a bin
 * edge can go either way; the reference computes the curve.
 *
 * Every stage the capture path runs is on: shading at half coordinates, white
 * balance, a non-identity matrix, the roll-off, desaturation, contrast, black
 * point, and the sharpen. A parity test with the tone off would say nothing
 * about the tone.
 */
@RunWith(AndroidJUnit4::class)
class LinearDevelopParityTest {

    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 62, 66, 64),
        whiteLevel = 1023,
        cfaPattern = intArrayOf(0, 1, 1, 2),
    )

    /** Daylight-ish gains and a matrix with real off-diagonals. */
    private val color = ColorProfile(
        gains = floatArrayOf(1.9f, 1f, 1.02f, 1.6f),
        matrix = floatArrayOf(
            1.65f, -0.52f, -0.13f,
            -0.20f, 1.55f, -0.35f,
            0.03f, -0.48f, 1.45f,
        ),
    )

    private fun scene(canvasW: Int, canvasH: Int): FloatArray {
        val out = FloatArray(canvasW * canvasH * 3)
        for (j in 0 until canvasH) for (i in 0 until canvasW) {
            val gx = i.toFloat() / canvasW
            val gy = j.toFloat() / canvasH
            val bar = if (j in canvasH / 3 until 2 * canvasH / 3 && (i / 6) % 2 == 0) 220f else 0f
            // Bright enough at the top right to reach the roll-off after the gain.
            val spec = if (i > canvasW * 3 / 4 && j < canvasH / 4) 300f else 0f
            out[(j * canvasW + i) * 3 + 0] = 120f + 380f * gx + bar + spec
            out[(j * canvasW + i) * 3 + 1] = 150f + 420f * gy + bar * 0.8f + spec
            out[(j * canvasW + i) * 3 + 2] = 110f + 300f * (1f - gx) + bar * 0.6f + spec
        }
        return out
    }

    private fun sceneAt(s: FloatArray, canvasW: Int, canvasH: Int, c: Int, u: Float, v: Float): Float {
        val cu = u.coerceIn(0f, (canvasW - 1).toFloat())
        val cv = v.coerceIn(0f, (canvasH - 1).toFloat())
        val i0 = cu.toInt(); val j0 = cv.toInt()
        val i1 = minOf(i0 + 1, canvasW - 1); val j1 = minOf(j0 + 1, canvasH - 1)
        val tx = cu - i0; val ty = cv - j0
        fun at(i: Int, j: Int) = s[(j * canvasW + i) * 3 + c]
        val top = at(i0, j0) + (at(i1, j0) - at(i0, j0)) * tx
        val bot = at(i0, j1) + (at(i1, j1) - at(i0, j1)) * tx
        return top + (bot - top) * ty
    }

    private fun bayerFrame(s: FloatArray, w: Int, h: Int, sx: Float, sy: Float, seed: Int): BayerFrame {
        val rnd = kotlin.random.Random(seed)
        val data = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = Demosaic.colourAt(profile, x, y)
            val v = sceneAt(s, w * 2, h * 2, c, 2f * (x + sx), 2f * (y + sy))
            data[y * w + x] = (v + rnd.nextInt(-3, 4)).toInt().coerceIn(0, 1023).toShort()
        }
        return BayerFrame(w, h, data)
    }

    private fun fieldFor(w: Int, h: Int, sx: Float, sy: Float): AlignmentField {
        val tilesX = maxOf(1, (w / 2) / SubPixelAccumulator.TILE_TARGET)
        val tilesY = maxOf(1, (h / 2) / SubPixelAccumulator.TILE_TARGET)
        val n = tilesX * tilesY
        val px = -sx / 2f; val py = -sy / 2f
        val ix = kotlin.math.round(px).toInt(); val iy = kotlin.math.round(py).toInt()
        return AlignmentField(tilesX, tilesY, IntArray(n) { ix }, IntArray(n) { iy },
            FloatArray(n) { px - ix }, FloatArray(n) { py - iy })
    }

    private fun toBuffer(f: BayerFrame): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(f.data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(f.data)
        buf.rewind()
        return buf
    }

    /** A shading map with a stop of corner falloff, unequal across channels. */
    private fun shadingMap(): ShadingMap {
        val cols = 5
        val rows = 4
        val gains = FloatArray(cols * rows * 4)
        for (r in 0 until rows) for (c in 0 until cols) {
            val dx = (c - (cols - 1) / 2f) / ((cols - 1) / 2f)
            val dy = (r - (rows - 1) / 2f) / ((rows - 1) / 2f)
            val d2 = dx * dx + dy * dy
            for (ch in 0 until 4) {
                val strength = when (ch) { 0 -> 1.1f; 3 -> 1.25f; else -> 1f }
                gains[(r * cols + c) * 4 + ch] = 1f + strength * 0.5f * d2
            }
        }
        return ShadingMap(cols, rows, gains)
    }

    private fun assertParity(shading: ShadingMap?, params: DevelopParams, label: String) {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val sensorW = 160
        val sensorH = 112
        val s = scene(sensorW * 2, sensorH * 2)
        val ref = bayerFrame(s, sensorW, sensorH, 0f, 0f, 1)
        val shifts = listOf(0.5f to 0f, 0f to 0.5f, 0.5f to 0.5f, 0.3f to -0.7f, -0.6f to 0.2f)
        val alts = shifts.mapIndexed { i, (sx, sy) ->
            bayerFrame(s, sensorW, sensorH, sx, sy, 10 + i) to fieldFor(sensorW, sensorH, sx, sy)
        }

        val native = NativeSubPixel.create(sensorW, sensorH, profile)
        assertThat(native).isNotNull()
        native!!.use { n ->
            val refBuf = toBuffer(ref)
            assertThat(n.setReference(refBuf, sensorW * 2)).isTrue()
            val bufs = alts.map { (frame, field) ->
                val b = toBuffer(frame)
                assertThat(n.addFrame(b, sensorW * 2, field)).isTrue()
                b
            }
            val r = n.finish()
            assertThat(r).isNotNull()
            val canvas = ShortArray(r!!.width * r.height * 3)
            r.rgb16.asShortBuffer().get(canvas)
            bufs.size

            // The reference, from the native canvas: this test is about the
            // develop, not the accumulation, which SubPixelParityTest covers.
            //
            // Compared twice. Once with the sharpen off, which is the develop
            // chain proper and is held to two codes. Once as the capture runs
            // it, sharpen on, which is held to four: the mask compares detail
            // against a threshold of 3.5, so a one-code difference in luma
            // upstream can decide a pixel is sharpened on one side and not on
            // the other, and the shift it then does or does not get is
            // 3.5 * 0.55 -- two codes. That is the operator's own sensitivity,
            // not drift in it, and it is bounded here as such: two from the
            // chain, two from the flip, and the flips have to be rare.
            val soft = params.copy(sharpen = Sharpen.Params(amount = 0f))
            compare(n, r.width, r.height, soft, shading, "$label, unsharpened", worstAllowed = 2, rareAbove = 2)
            if (params.sharpen.enabled) {
                compare(n, r.width, r.height, params, shading, label, worstAllowed = 4, rareAbove = 2)
            }
            DeviceKind.warnIfNotAPhone(TAG)
        }
    }

    private fun compare(
        n: NativeSubPixel, width: Int, height: Int, params: DevelopParams, shading: ShadingMap?,
        label: String, worstAllowed: Int, rareAbove: Int,
    ) {
        val r = n.finish()!!
        val canvas = ShortArray(width * height * 3)
        r.rgb16.asShortBuffer().get(canvas)
        val expected = LinearDeveloper.develop(canvas, width, height, profile, color, params, shading)
        val got = n.developToArray(profile, color, params, shading)
        assertThat(got).isNotNull()
        assertThat(got!!.size).isEqualTo(width * height * 3)

        var worst = 0
        var sum = 0L
        val histogram = IntArray(6)
        for (i in 0 until width * height) {
            val e = expected[i]
            val er = (e shr 16) and 0xFF
            val eg = (e shr 8) and 0xFF
            val eb = e and 0xFF
            val gr = got[i * 3].toInt() and 0xFF
            val gg = got[i * 3 + 1].toInt() and 0xFF
            val gb = got[i * 3 + 2].toInt() and 0xFF
            for (d in intArrayOf(kotlin.math.abs(er - gr), kotlin.math.abs(eg - gg), kotlin.math.abs(eb - gb))) {
                if (d > worst) worst = d
                sum += d
                histogram[minOf(d, 5)]++
            }
        }
        val values = width * height * 3
        val mean = sum.toDouble() / values
        val above = (rareAbove + 1..5).sumOf { histogram[it] }
        Log.i(TAG, "$label: %dx%d, worst %d, mean %.4f, exact %d / 1: %d / 2: %d / 3: %d / 4: %d / more: %d".format(
            width, height, worst, mean, histogram[0], histogram[1], histogram[2], histogram[3], histogram[4], histogram[5]))
        // The picture is a real one: not black, not clipped, and it reached
        // the roll-off somewhere.
        val brightest = (0 until width * height).maxOf { (expected[it] shr 8) and 0xFF }
        assertThat(brightest).isGreaterThan(200)

        assertThat(worst).isAtMost(worstAllowed)
        assertThat(mean).isLessThan(0.25)
        // Fewer than one value in a thousand past the chain's own bound.
        assertThat(above.toDouble() / values).isLessThan(0.001)
    }

    @Test
    fun theWholeChainAgrees() =
        assertParity(shadingMap(), DevelopParams(), "shading + tone + sharpen")

    @Test
    fun withoutShadingItAgrees() =
        assertParity(null, DevelopParams(), "tone + sharpen")

    @Test
    fun withAFixedExposureItAgrees() =
        assertParity(shadingMap(), DevelopParams(exposureGain = 1.7f), "fixed gain")

    @Test
    fun withTheToneOffOnlyTheArithmeticRemains() {
        // No table, no curve: the two should now agree exactly or to one code
        // of -ffast-math drift. Says whether the tone stages are where the
        // two codes above come from.
        assertParity(
            shadingMap(),
            DevelopParams(
                exposureGain = 1.2f, shoulderKnee = 1f, contrast = 0f,
                highlightDesaturation = 0f, blackPoint = 0f,
                sharpen = Sharpen.Params(amount = 0f),
            ),
            "arithmetic only",
        )
    }
}
