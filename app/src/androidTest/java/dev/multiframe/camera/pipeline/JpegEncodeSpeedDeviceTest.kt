package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.random.Random

private const val TAG = "JpegSpeed"

/**
 * What the encode costs, at the size that actually ships.
 *
 * The framework encoder and the strip encoder reach the same library. The
 * difference is that one of them is handed the whole image on one thread and
 * the other cuts it up. This times both on the same bitmap in the same run, so
 * the comparison needs no second install and survives the phone being warm.
 *
 * **The scene matters more than it looks.** An earlier version of this used
 * heavy per-channel noise and reported 407 ms where a real capture logs about
 * 240 -- noise is close to incompressible, and it is the one thing a merged
 * capture from this app does not have. Detail without noise is what the encoder
 * actually sees here.
 */
@RunWith(AndroidJUnit4::class)
class JpegEncodeSpeedDeviceTest {

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
    fun stripsBeatTheFrameworkAtCaptureSize() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val bitmap = scene(width, height)
        val buffer = ByteArrayOutputStream(8 shl 20)

        // Warm both: the first call through either pays for lazy loading.
        buffer.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, buffer)
        val frameworkBytes = buffer.size()
        NativeJpeg.encodeToArray(bitmap, QUALITY, 0)

        val framework = LongArray(ROUNDS)
        val strips = LongArray(ROUNDS)
        var stripBytes = 0
        // Alternated rather than run in blocks, so a phone that warms up over
        // the measurement warms up for both of them equally.
        for (i in 0 until ROUNDS) {
            buffer.reset()
            var t = System.nanoTime()
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, buffer)
            framework[i] = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val out = NativeJpeg.encodeToArray(bitmap, QUALITY, 0)
            strips[i] = (System.nanoTime() - t) / 1_000_000
            assertThat(out).isNotNull()
            stripBytes = out!!.size
        }

        val f = framework.sorted()
        val s = strips.sorted()
        Log.i(
            TAG,
            "${width}x$height q$QUALITY framework: " +
                framework.joinToString(", ") { "${it}ms" } +
                "; median ${f[ROUNDS / 2]}ms  ${frameworkBytes / 1024} KB",
        )
        Log.i(
            TAG,
            "${width}x$height q$QUALITY strips:    " +
                strips.joinToString(", ") { "${it}ms" } +
                "; median ${s[ROUNDS / 2]}ms  ${stripBytes / 1024} KB",
        )
        Log.i(
            TAG,
            "strips are %.1fx faster and %.1f%% larger".format(
                f[ROUNDS / 2].toDouble() / s[ROUNDS / 2],
                100.0 * (stripBytes - frameworkBytes) / frameworkBytes,
            ),
        )

        bitmap.recycle()
        // Deliberately weak. The whole point of the strip encoder is that it is
        // faster, but by how much depends on how many cores the phone feels
        // like giving it, and an emulator has no useful answer at all. What
        // this catches is the strip encoder having quietly stopped being
        // parallel -- or stopped being used.
        assertThat(s[ROUNDS / 2]).isLessThan(f[ROUNDS / 2])
    }

    companion object {
        const val ROUNDS = 5
        const val QUALITY = 95
    }
}
