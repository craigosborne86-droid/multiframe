package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/**
 * The native chroma smoothing held to the Kotlin reference, on a speckled
 * frame with edges in it, within the pipeline's two codes.
 */
@RunWith(AndroidJUnit4::class)
class ChromaSmoothParityTest {

    private val tag = "ChromaParity"

    private fun speckled(w: Int, h: Int): IntArray {
        val rnd = Random(3)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // A gradient with a hard edge, and independent colour speckle.
            val base = if (x < w / 2) 60 + y * 100 / h else 180 - y * 60 / h
            val r = (base + rnd.nextInt(-25, 26)).coerceIn(0, 255)
            val g = (base + rnd.nextInt(-25, 26)).coerceIn(0, 255)
            val b = (base + rnd.nextInt(-25, 26)).coerceIn(0, 255)
            Color.argb(255, r, g, b)
        }
    }

    @Test
    fun nativeAndKotlinChromaSmoothingAgree() {
        // Taller than one band, so the rolling halo between bands is exercised.
        val w = 640
        val h = 600
        val pixels = speckled(w, h)
        val expected = pixels.copyOf()
        ChromaSmooth.apply(expected, w, h)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val merger = NativeMerge.create(64, 64, SensorProfile.DEFAULT)!!
        merger.use { assertThat(it.chromaSmooth(bitmap, ChromaSmooth.Params())).isTrue() }
        val actual = IntArray(w * h)
        bitmap.getPixels(actual, 0, w, 0, 0, w, h)
        bitmap.recycle()

        var worst = 0
        var differing = 0
        var moved = 0
        for (i in pixels.indices) {
            if (expected[i] != pixels[i]) moved++
            val a = expected[i]
            val b = actual[i]
            val d = maxOf(
                abs(Color.red(a) - Color.red(b)),
                abs(Color.green(a) - Color.green(b)),
                abs(Color.blue(a) - Color.blue(b)),
            )
            if (d > 0) differing++
            if (d > worst) worst = d
        }
        Log.i(tag, "chroma parity: worst $worst/255, $differing of ${pixels.size} pixels differ; the pass moved $moved")
        // The pass did something, and the two did the same thing.
        assertThat(moved).isGreaterThan(pixels.size / 2)
        assertThat(worst).isAtMost(2)
    }
}
