package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

private const val TAG = "SharpenSpeed"

/**
 * What capture sharpening costs, at the size that actually ships.
 *
 * It existed for a long time without anyone knowing. Splitting the develop
 * timer found it at 85-129 ms -- about a third of the native develop, and the
 * second largest item in a capture -- which nothing had ever reported, because
 * "develop" was one number and sharpening was inside it.
 *
 * Measuring it through a capture does not work well. A capture runs the camera,
 * the merge, a DNG writer on another thread and a MediaStore publish, all
 * contending for the same cores, and the sharpen figure it yields swings by a
 * factor of two between neighbouring shots. That spread is wide enough to hide
 * anything short of a very large change. This runs the pass on its own, on a
 * fixed picture, several times, so that what is left is the pass.
 *
 * **The scene matters.** Sharpening skips every pixel whose local detail falls
 * below the threshold, so its cost depends on how much of the image has detail
 * in it. Flat synthetic gradients would skip nearly everything and report a
 * figure no capture would ever see; heavy noise would sharpen everything and
 * report one no capture would see either. This is the same detail-without-noise
 * scene the JPEG speed test uses, and for the same reason.
 */
@RunWith(AndroidJUnit4::class)
class SharpenSpeedDeviceTest {

    private val width = 4080
    private val height = 3072

    private fun scene(w: Int, h: Int): Bitmap {
        val rnd = Random(5)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fine = 26 * kotlin.math.sin(x * 0.09) * kotlin.math.sin(y * 0.07)
                val mid = 60 * kotlin.math.sin(x * 0.012 + y * 0.004)
                fun j(v: Double) = (v + rnd.nextInt(-2, 2)).toInt().coerceIn(0, 255)
                px[y * w + x] = Color.rgb(
                    j(132 + mid + fine + 40 * kotlin.math.cos(y * 0.006)),
                    j(128 + mid * 0.8 + fine),
                    j(124 + mid * 0.6 - fine + 30 * kotlin.math.sin(x * 0.004)),
                )
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun sharpeningAtCaptureSize() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val pristine = scene(width, height)
        val working = pristine.copy(Bitmap.Config.ARGB_8888, true)
        val params = Sharpen.Params()

        // The first call through pays for the luminance scratch being faulted
        // in, which is a one-off the capture path pays once a boot.
        assertThat(NativeMerge.sharpenInPlace(working, params)).isTrue()

        val canvas = Canvas(working)
        val times = LongArray(ROUNDS)
        for (i in 0 until ROUNDS) {
            // Sharpening now writes over the pixels it reads, so without this
            // every round would sharpen an image the previous round had already
            // sharpened -- a different picture each time, with a different
            // number of pixels above the threshold. Restored outside the clock.
            canvas.drawBitmap(pristine, 0f, 0f, null)
            val started = System.nanoTime()
            NativeMerge.sharpenInPlace(working, params)
            times[i] = (System.nanoTime() - started) / 1_000_000
        }
        working.recycle()
        pristine.recycle()

        times.sort()
        Log.i(
            TAG,
            "sharpen at ${width}x$height: median ${times[ROUNDS / 2]}ms, " +
                "${times.first()}-${times.last()}ms over $ROUNDS rounds",
        )

        // Not a regression threshold on the number, which would fail on a warm
        // phone for reasons that have nothing to do with the code. Only that
        // the pass ran and returned in a time a capture could survive.
        assertThat(times[ROUNDS / 2]).isGreaterThan(0)
        assertThat(times[ROUNDS / 2]).isLessThan(1_000)
    }

    private companion object {
        const val ROUNDS = 9
    }
}
