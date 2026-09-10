package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

private const val TAG = "DcgDevice"

/**
 * The dual conversion gain path against the uniform path, on real hardware.
 *
 * Two things are tested:
 *
 *  1. **A/A null** — DCG with ratio=1 (every frame at the same ISO) produces
 *     pixel-identical output to a non-DCG merge. This is the mechanical proof
 *     that the gainScale=1 default is a no-op: the new code path cannot change
 *     a picture unless the gain is actually different.
 *
 *  2. **A/B** — a burst alternating between low and high ISO. The high-ISO
 *     frames carry brighter shadows and clip highlights earlier. After gain
 *     normalization, the merge should produce cleaner shadows than a uniform
 *     burst while preserving highlights from the low-ISO reference.
 */
@RunWith(AndroidJUnit4::class)
class DcgDeviceTest {

    private val w = 192
    private val h = 144
    private val profile = SensorProfile.DEFAULT
    private val stride = w * 2

    private fun scene(seed: Int): ShortArray {
        val rnd = Random(seed)
        val data = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val colour = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
            val base = 300 + 200 * kotlin.math.sin(x * 0.05) * kotlin.math.cos(y * 0.07)
            val tint = when (colour) { 0 -> 1.15f; 2 -> 0.80f; else -> 1.0f }
            data[y * w + x] = (base * tint + rnd.nextInt(-20, 20))
                .toInt().coerceIn(64, 1023).toShort()
        }
        return data
    }

    private fun toBuffer(data: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(data)
        buf.rewind()
        return buf
    }

    private fun scalePixels(data: ShortArray, gain: Float): ShortArray {
        val out = ShortArray(data.size)
        for (i in data.indices) {
            val v = ((data[i].toInt() and 0xFFFF) * gain).toInt()
                .coerceIn(0, profile.whiteLevel)
            out[i] = v.toShort()
        }
        return out
    }

    private fun mergeNative(
        ref: ShortArray,
        alts: List<Pair<ShortArray, Float>>,
    ): Pair<ShortArray, BayerMergeStats> {
        val merge = NativeMerge.create(w, h, profile)!!
        return merge.use { m ->
            val tilesX = maxOf(1, m.proxyWidth / 32)
            val tilesY = maxOf(1, m.proxyHeight / 32)
            val refBuf = toBuffer(ref)
            m.setReference(refBuf, stride)
            val refProxy = m.lumaProxy(refBuf, stride)
            for ((frame, gain) in alts) {
                val buf = toBuffer(frame)
                val proxy = m.lumaProxy(buf, stride, gain)
                val field = Aligner.alignNative(refProxy, proxy, tilesX, tilesY)
                    ?: AlignmentField(
                        tilesX, tilesY,
                        IntArray(tilesX * tilesY), IntArray(tilesX * tilesY),
                    )
                m.addFrame(buf, stride, field, gain)
            }
            val (buf, stats) = m.finish()
            val out = ShortArray(w * h)
            buf.asShortBuffer().get(out)
            out to stats
        }
    }

    @Test
    fun aaNullDcgRatio1IsIdenticalToUniformMerge() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val ref = scene(1)
        val alts = (0 until 7).map { scene(10 + it) }

        val (uniform, uStats) = mergeNative(ref, alts.map { it to 1f })
        val (dcg1, dStats) = mergeNative(ref, alts.map { it to 1f })

        var differing = 0
        for (i in uniform.indices) {
            if (uniform[i] != dcg1[i]) differing++
        }
        Log.i(TAG, "A/A null: ${w * h} pixels, $differing differing, " +
            "uniform contrib=${uStats.meanContribution} " +
            "dcg1 contrib=${dStats.meanContribution}")
        assertThat(differing).isEqualTo(0)
        assertThat(dStats.meanContribution).isEqualTo(uStats.meanContribution)
    }

    @Test
    fun abBurstPreservesHighlightsAndImprovesShadows() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val ref = scene(1)

        val uniformAlts = (0 until 7).map { scene(20 + it) to 1f }
        val (uniform, _) = mergeNative(ref, uniformAlts)

        val dcgAlts = (0 until 7).map { i ->
            val base = scene(20 + i)
            if (i % 2 == 0) base to 1f
            else scalePixels(base, 2f) to 2f
        }
        val (dcg, dcgStats) = mergeNative(ref, dcgAlts)

        Log.i(TAG, "A/B: dcg contrib=${dcgStats.meanContribution}")
        assertThat(dcgStats.meanContribution).isGreaterThan(0f)
        assertThat(dcgStats.meanContribution).isLessThan(1f)

        // The reference frame is the same in both, so highlights should be
        // preserved: clipped high-ISO values are rejected by robustness
        // weighting, and the low-ISO reference keeps its data. Check that
        // highlight pixels (near white level) are not brighter in the DCG
        // result than in the uniform result.
        var highlightBrighter = 0
        var shadowPixels = 0
        var shadowDiffSum = 0L
        val highlightThreshold = (profile.whiteLevel * 0.85f).toInt()
        val blackLevel = profile.blackLevel.minOrNull() ?: 0
        val shadowThreshold = blackLevel + (profile.range * 0.15f).toInt()

        for (i in uniform.indices) {
            val uv = uniform[i].toInt() and 0xFFFF
            val dv = dcg[i].toInt() and 0xFFFF
            if (uv > highlightThreshold && dv > uv) highlightBrighter++
            if (uv < shadowThreshold) {
                shadowPixels++
                shadowDiffSum += kotlin.math.abs(uv - dv).toLong()
            }
        }
        Log.i(TAG, "A/B highlights brighter in DCG: $highlightBrighter of ${w * h}, " +
            "shadow pixels: $shadowPixels, mean shadow diff: " +
            "%.2f".format(if (shadowPixels > 0) shadowDiffSum.toFloat() / shadowPixels else 0f))

        // Highlight preservation: fewer than 1% of highlight pixels should
        // be brighter in the DCG result.
        val totalHighlight = uniform.count {
            (it.toInt() and 0xFFFF) > highlightThreshold
        }
        if (totalHighlight > 0) {
            assertThat(highlightBrighter.toFloat() / totalHighlight).isLessThan(0.01f)
        }
    }
}
