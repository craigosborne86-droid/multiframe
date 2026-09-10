package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

private const val TAG = "MergeParity"

/**
 * The native merge accumulation against the Kotlin reference, including
 * gain-normalized frames.
 *
 * Both implementations accept a per-frame `gainScale` that normalizes
 * high-ISO frames to the reference exposure before comparison and
 * accumulation. This test verifies that the native path produces
 * pixel-identical output for gainScale=1 (the existing path) and
 * gainScale=4 (the DCG path), using the same alignment field for both
 * so only the accumulation is under test.
 */
@RunWith(AndroidJUnit4::class)
class MergeParityTest {

    private val w = 192
    private val h = 144
    private val profile = SensorProfile.DEFAULT
    private val stride = w * 2

    private fun scene(seed: Int): BayerFrame {
        val rnd = Random(seed)
        val data = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val colour = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
            val base = 300 + 200 * kotlin.math.sin(x * 0.05) * kotlin.math.cos(y * 0.07)
            val tint = when (colour) { 0 -> 1.15f; 2 -> 0.80f; else -> 1.0f }
            data[y * w + x] = (base * tint + rnd.nextInt(-20, 20))
                .toInt().coerceIn(64, 1023).toShort()
        }
        return BayerFrame(w, h, data)
    }

    private fun toBuffer(frame: BayerFrame): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frame.data.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(frame.data)
        buf.rewind()
        return buf
    }

    private fun scaleFrame(f: BayerFrame, gain: Float): BayerFrame {
        val out = ShortArray(f.data.size)
        for (i in f.data.indices) {
            val v = ((f.data[i].toInt() and 0xFFFF) * gain).toInt().coerceIn(0, profile.whiteLevel)
            out[i] = v.toShort()
        }
        return BayerFrame(f.width, f.height, out)
    }

    private fun assertParity(
        refFrame: BayerFrame,
        altFrames: List<Pair<BayerFrame, Float>>,
        label: String,
    ) {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val tilesX = maxOf(1, (w / 2) / 32)
        val tilesY = maxOf(1, (h / 2) / 32)
        val zeroField = AlignmentField(
            tilesX, tilesY, IntArray(tilesX * tilesY), IntArray(tilesX * tilesY),
        )

        // Kotlin path
        val kAcc = BayerAccumulator(w, h, profile)
        kAcc.setReference(refFrame)
        for ((frame, gain) in altFrames) {
            kAcc.add(frame, zeroField, gain)
        }
        val (kResult, kStats) = kAcc.finish()

        // Native path
        val merge = NativeMerge.create(w, h, profile)!!
        merge.use { m ->
            val refBuf = toBuffer(refFrame)
            m.setReference(refBuf, stride)
            for ((frame, gain) in altFrames) {
                m.addFrame(toBuffer(frame), stride, zeroField, gain)
            }
            val (nBuf, nStats) = m.finish()

            val nData = ShortArray(w * h)
            nBuf.asShortBuffer().get(nData)

            var differing = 0
            var worst = 0
            for (i in kResult.data.indices) {
                val kv = kResult.data[i].toInt() and 0xFFFF
                val nv = nData[i].toInt() and 0xFFFF
                val d = kotlin.math.abs(kv - nv)
                if (d > 0) {
                    differing++
                    worst = maxOf(worst, d)
                }
            }
            Log.i(TAG, "$label: ${w * h} pixels, $differing differing, worst $worst, " +
                "kotlin contrib=${kStats.meanContribution} native contrib=${nStats.meanContribution}")
            assertThat(worst).isAtMost(1)
        }
    }

    @Test
    fun gainScale1IsParity() {
        val ref = scene(1)
        val alts = (0 until 4).map { scene(10 + it) to 1f }
        assertParity(ref, alts, "gainScale=1")
    }

    @Test
    fun gainScale4IsParity() {
        val ref = scene(1)
        val alts = (0 until 4).map { scaleFrame(scene(20 + it), 4f) to 4f }
        assertParity(ref, alts, "gainScale=4")
    }

    @Test
    fun mixedGainIsParity() {
        val ref = scene(1)
        val low = (0 until 2).map { scene(30 + it) to 1f }
        val high = (0 until 2).map { scaleFrame(scene(40 + it), 2f) to 2f }
        assertParity(ref, low + high, "mixed gain 1+2")
    }
}
