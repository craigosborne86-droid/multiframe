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
