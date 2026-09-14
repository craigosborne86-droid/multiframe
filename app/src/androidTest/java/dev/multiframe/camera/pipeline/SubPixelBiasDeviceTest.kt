package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "SubPixelBias"

/**
 * What the sub-pixel refinement actually recovers, across the fraction.
 *
 * The 2x canvas places every sample by the aligner's fraction, so a bias in
 * the fraction is a bias in where fifty million samples land. This sweeps a
 * known shift from zero to a pixel in tenths, through the real proxy and the
 * real search at the sensor's real width, and logs recovered against true.
 * The assertion is loose on purpose -- it is the curve that is the finding.
 */
@RunWith(AndroidJUnit4::class)
class SubPixelBiasDeviceTest {

    private val sensorW = 4080
    private val sensorH = 768
    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 64, 64, 64),
        whiteLevel = 1023,
        cfaPattern = intArrayOf(0, 1, 1, 2),
    )

    /** Texture at several scales and no periodicity a SAD search could mistake. */
    private fun sceneAt(c: Int, x: Float, y: Float): Float {
        val texture = 50f * (
            kotlin.math.sin(x * 0.0173f + y * 0.0091f) +
                kotlin.math.sin(x * 0.0047f - y * 0.0212f) +
                0.8f * kotlin.math.sin(x * 0.061f + y * 0.043f) +
                0.8f * kotlin.math.sin(x * 0.29f - y * 0.17f) +
                0.7f * kotlin.math.sin(x * 0.11f + y * 0.31f)
            )
        val tint = when (c) { 0 -> 0.55f; 2 -> 0.65f; else -> 1f }
        return 64f + (300f + 200f * (x / sensorW) + texture) * tint
    }

    private fun frame(sx: Float, sy: Float, seed: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(sensorW * sensorH * 2).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        val rnd = Random(seed)
        for (y in 0 until sensorH) for (x in 0 until sensorW) {
            val c = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
            val v = sceneAt(c, x + sx, y + sy) + rnd.nextInt(-2, 3)
            shorts.put((v.toInt().coerceIn(0, 1023)).toShort())
        }
        buf.rewind()
        return buf
    }

    @Test
    fun theRecoveredFractionIsSweptAgainstTheTruth() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val stride = sensorW * 2
        val ref = frame(0f, 0f, 1)
        val merger = NativeMerge.create(sensorW, sensorH, profile)!!
        val results = ArrayList<Pair<Float, Float>>()
        merger.use { m ->
            m.setReference(ref, stride)
            val refProxy = m.lumaProxy(ref, stride)
            val tilesX = maxOf(1, m.proxyWidth / 32)
            val tilesY = maxOf(1, m.proxyHeight / 32)
            for (tenth in 0..10) {
                val sx = tenth / 10f
                val alt = frame(sx, 0.3f, 10 + tenth)
                val proxy = m.lumaProxy(alt, stride)
                val f = Aligner.alignNative(refProxy, proxy, tilesX, tilesY)!!
                // Median over the tiles, so one confused tile does not speak
                // for the frame.
                val found = FloatArray(f.dx.size) { t -> -(f.dx[t] + f.fracDx[t]) * 2f }
                found.sort()
                val median = found[found.size / 2]
                val foundY = FloatArray(f.dy.size) { t -> -(f.dy[t] + f.fracDy[t]) * 2f }
                foundY.sort()
                Log.i(TAG, "true x %.2f -> recovered %.3f (y true 0.30 -> %.3f); tiles within 0.15: %d of %d".format(
                    sx, median, foundY[foundY.size / 2], found.count { abs(it - sx) < 0.15f }, found.size))
                results.add(sx to median)
            }
        }
        DeviceKind.warnIfNotAPhone(TAG)
        // Loose: the integer part must be right everywhere and the fraction
        // must move with the truth. The bias, if any, is in the log.
        for ((truth, found) in results) assertThat(abs(found - truth)).isLessThan(0.5f)
        val monotone = results.zipWithNext().all { (a, b) -> b.second >= a.second - 0.05f }
        assertThat(monotone).isTrue()
    }
}
