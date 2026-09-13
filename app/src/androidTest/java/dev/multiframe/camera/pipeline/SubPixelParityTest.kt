package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SubPixelParity"

/**
 * The native strip-major accumulator against the Kotlin whole-canvas reference.
 *
 * The two arrange memory differently and must produce the same picture. The
 * interesting case is a canvas taller than one strip, so samples land across
 * strip boundaries and the halo has to do its job: a sample near the boundary
 * contributes to cells on both sides, and a fill near the boundary looks
 * across it. A canvas that fits in one strip would not test either.
 *
 * Held to within one 16-bit count per cell, which is the rounding of a float
 * to the output type plus the last-place drift -ffast-math permits between two
 * arrangements of the same arithmetic.
 */
@RunWith(AndroidJUnit4::class)
class SubPixelParityTest {

    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 64, 64, 64),
        whiteLevel = 1023,
        cfaPattern = intArrayOf(0, 1, 1, 2),
    )

    private fun scene(canvasW: Int, canvasH: Int): FloatArray {
        val out = FloatArray(canvasW * canvasH * 3)
        for (j in 0 until canvasH) for (i in 0 until canvasW) {
            val gx = i.toFloat() / canvasW
            val gy = j.toFloat() / canvasH
            val bar = if (j in canvasH / 3 until 2 * canvasH / 3 && (i / 4) % 2 == 0) 200f else 0f
            out[(j * canvasW + i) * 3 + 0] = 300f + 400f * gx + bar
            out[(j * canvasW + i) * 3 + 1] = 350f + 300f * gy + bar * 0.8f
            out[(j * canvasW + i) * 3 + 2] = 250f + 350f * (1f - gx) + bar * 0.6f
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
            // A little noise, so the robustness path is exercised rather than
            // every sample landing exactly on the reference's prediction.
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

    private fun assertParity(sensorW: Int, sensorH: Int, shifts: List<Pair<Float, Float>>, label: String) {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val s = scene(sensorW * 2, sensorH * 2)
        val ref = bayerFrame(s, sensorW, sensorH, 0f, 0f, 1)
        val alts = shifts.mapIndexed { i, (sx, sy) ->
            bayerFrame(s, sensorW, sensorH, sx, sy, 10 + i) to fieldFor(sensorW, sensorH, sx, sy)
        }

        // Kotlin, whole canvas.
        val kAcc = SubPixelAccumulator(sensorW, sensorH, profile)
        kAcc.setReference(ref)
        for ((frame, field) in alts) kAcc.add(frame, field)
        val kResult = kAcc.finish()

        // Native, in strips. Buffers held until finish returns.
        val native = NativeSubPixel.create(sensorW, sensorH, profile)
        assertThat(native).isNotNull()
        val nResult = native!!.use { n ->
            val refBuf = toBuffer(ref)
            assertThat(n.setReference(refBuf, sensorW * 2)).isTrue()
            val bufs = alts.map { (frame, field) ->
                val b = toBuffer(frame)
                assertThat(n.addFrame(b, sensorW * 2, field)).isTrue()
                b
            }
            val r = n.finish()
            assertThat(r).isNotNull()
            // Copy out before close, since the buffer is the mmap.
            val copy = ShortArray(r!!.width * r.height * 3)
            r.rgb16.asShortBuffer().get(copy)
            bufs.size  // keep alive
            Triple(copy, r.holeFraction, r.interiorHoleFraction) to r
        }
        val (nRgb, nHoles, nInterior) = nResult.first
        val nStats = nResult.second

        var differing = 0
        var worst = 0
        val cells = kResult.width * kResult.height
        for (i in 0 until cells * 3) {
            val kv = kotlin.math.round(kResult.rgb[i]).toInt().coerceIn(0, 65535)
            val nv = nRgb[i].toInt() and 0xFFFF
            val d = kotlin.math.abs(kv - nv)
            if (d > 0) differing++
            if (d > worst) worst = d
        }
        Log.i(TAG, "$label: ${cells} cells, $differing of ${cells * 3} values differ, worst $worst")
        Log.i(TAG, "$label: holes kotlin %.4f/%.4f native %.4f/%.4f, contrib kotlin %.3f native %.3f".format(
            kResult.holeFraction, kResult.interiorHoleFraction, nHoles, nInterior,
            kResult.meanContribution, nStats.meanContribution))
        DeviceKind.warnIfNotAPhone(TAG)

        assertThat(worst).isAtMost(1)
        assertThat(nStats.framesMerged).isEqualTo(kResult.framesMerged)
        // The hole counts are integers on both sides and should agree exactly.
        assertThat(nInterior).isWithin(1e-6f).of(kResult.interiorHoleFraction)
    }

    /** One strip. The simple case. */
    @Test
    fun aSmallCanvasAgrees() =
        assertParity(64, 48, listOf(0.5f to 0f, 0f to 0.5f, 0.5f to 0.5f), "64x48")

    /**
     * Several strips. The canvas is 2*sensorH tall; at 256 rows a strip, a
     * sensor 400 rows tall gives 800 canvas rows and four strips, three
     * boundaries. Samples near each boundary land on both sides.
     */
    @Test
    fun aCanvasSpanningStripsAgrees() =
        assertParity(128, 400, listOf(0.5f to 0f, 0f to 0.5f, 0.5f to 0.5f, 0.25f to 0.75f), "128x400")

    /**
     * The full lattice, across strips. Every cell sampled, so the fill never
     * runs and the comparison is the accumulation alone.
     */
    @Test
    fun aFullLatticeAcrossStripsAgrees() {
        val shifts = ArrayList<Pair<Float, Float>>()
        for (sy in listOf(0f, 0.5f, 1f, 1.5f)) for (sx in listOf(0f, 0.5f, 1f, 1.5f)) {
            if (sx != 0f || sy != 0f) shifts.add(sx to sy)
        }
        assertParity(96, 300, shifts, "96x300 full lattice")
    }

    /** A shift large enough that samples cross a strip boundary by many rows. */
    @Test
    fun aLargeShiftAcrossStripsAgrees() =
        assertParity(96, 300, listOf(3.5f to 6.5f, -2.5f to -4.5f), "96x300 large shift")
}
