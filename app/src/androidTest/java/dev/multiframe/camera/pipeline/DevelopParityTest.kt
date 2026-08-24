package dev.multiframe.camera.pipeline

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

    @Test
    fun nativeKeepsMeasuredSamplesLikeKotlinDoes() {
        // The property that distinguishes the new demosaic from the old gather,
        // checked on the implementation that actually runs.
        val frame = scene()
        val neutral = ColorProfile.NEUTRAL
        val flatMatrix = ColorProfile(
            gains = floatArrayOf(1f, 1f, 1f, 1f),
            matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )
        val merger = NativeMerge.create(width, height, profile)!!
        // A gain of 1 with no shoulder engagement keeps the encode monotonic, so
        // an averaged sample would show as a different code than a kept one.
        val bitmap = merger.use {
            it.develop(directBufferOf(frame), flatMatrix, DevelopParams(exposureGain = 1f))
        }!!

        var matches = 0
        val out = FloatArray(3)
        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                val colour = Demosaic.colourAt(profile, x, y)
                Demosaic.pixel(frame, profile, neutral, x, y, out)
                val native = when (colour) {
                    0 -> Color.red(bitmap.getPixel(x, y))
                    1 -> Color.green(bitmap.getPixel(x, y))
                    else -> Color.blue(bitmap.getPixel(x, y))
                }
                val measured = Demosaic.sample(frame, profile, neutral, x, y)
                // Both should encode the same measured value at this site.
                val encoded = srgb8(RawDeveloper.shoulder(measured, 0.70f))
                if (abs(native - encoded) <= 2) matches++
            }
        }
        bitmap.recycle()

        val total = (height - 8) * (width - 8)
        Log.i(TAG, "native kept the measured sample at $matches of $total sites")
        assertThat(matches).isAtLeast((total * 0.97).toInt())
    }

    private fun srgb8(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val e = if (c <= 0.0031308f) c * 12.92f
        else 1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        return (e * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
