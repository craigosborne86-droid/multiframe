package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "JpegParity"

/**
 * The strip encoder must produce the same photograph the framework does.
 *
 * It is held to `Bitmap.compress` the way every other native stage here is held
 * to its Kotlin reference. The two are not required to produce identical files
 * -- they are different code taking the same settings -- but they are required
 * to produce the same picture, so the comparison is made after decoding.
 *
 * What this is really guarding is the stitching. A JPEG assembled out of
 * separately encoded strips is a format-level trick: a wrong restart interval,
 * a marker in the wrong place, a header describing one strip rather than the
 * whole image, and the result is either undecodable or -- much worse -- decodes
 * into something subtly wrong. Several of the cases below exist because that
 * second kind of failure would otherwise look like success.
 */
@RunWith(AndroidJUnit4::class)
class JpegEncodeParityTest {

    /** Detail at several scales, so the encoder has something to work with. */
    private fun scene(w: Int, h: Int): Bitmap {
        val rnd = Random(11)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fine = 30 * kotlin.math.sin(x * 0.4) * kotlin.math.sin(y * 0.35)
                val mid = 70 * kotlin.math.sin(x * 0.03 + y * 0.02)
                fun j(v: Double) = (v + rnd.nextInt(-3, 3)).toInt().coerceIn(0, 255)
                px[y * w + x] = Color.rgb(
                    j(140 + mid + fine), j(126 + mid * 0.7), j(120 - mid * 0.5 + fine),
                )
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /**
     * Compares two decoded images and returns the worst and mean channel
     * difference. Both have been through the same lossy transform, so the
     * question is whether they went through the *same* one.
     */
    private fun compare(a: Bitmap, b: Bitmap): Pair<Int, Double> {
        var worst = 0
        var sum = 0L
        var n = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs(((p shr shift) and 0xFF) - ((q shr shift) and 0xFF))
                    if (d > worst) worst = d
                    sum += d
                    n++
                }
            }
        }
        return worst to sum.toDouble() / n
    }

    private fun framework(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(1 shl 20)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Sizes chosen to break a careless stitch.
     *
     * A minimum coded unit is sixteen pixels square once chroma is subsampled,
     * so an image whose height is not a multiple of sixteen has a final strip
     * that is short, and one whose width is not a multiple of sixteen has a
     * partial MCU column in every row. 640x480 is clean; the others are not,
     * and 641x481 is not even even.
     */
    @Test
    fun stripEncodeMatchesTheFramework() {
        for ((w, h) in listOf(640 to 480, 641 to 481, 700 to 393, 320 to 17)) {
            val bitmap = scene(w, h)
            val expected = decode(framework(bitmap, 95))
            assertThat(expected).isNotNull()

            // More strips than there are MCU rows in the short case, so the
            // clamp that stops empty strips is exercised too.
            for (threads in intArrayOf(1, 2, 3, 8)) {
                val bytes = NativeJpeg.encodeToArray(bitmap, 95, threads)
                assertThat(bytes).isNotNull()
                val actual = decode(bytes!!)
                assertThat(actual).isNotNull()
                assertThat(actual!!.width).isEqualTo(w)
                assertThat(actual.height).isEqualTo(h)

                val (worst, mean) = compare(expected!!, actual)
                Log.i(
                    TAG,
                    "${w}x$h, $threads strips: worst $worst, mean %.3f, ".format(mean) +
                        "${bytes.size / 1024} KB against ${framework(bitmap, 95).size / 1024} KB",
                )
                // The same quantisation tables and the same subsampling, so the
                // coefficients agree and only the decoder's rounding is left.
                assertThat(worst).isAtMost(2)
                assertThat(mean).isLessThan(0.05)
                actual.recycle()
            }
            expected!!.recycle()
            bitmap.recycle()
        }
    }

    /**
     * The number of strips must not change the picture.
     *
     * If it does, the stitching depends on where the seams fall, which is
     * exactly the bug this whole approach risks. Encoded one strip at a time
     * there are no seams at all, so that is the reference.
     */
    @Test
    fun theSeamsDoNotShow() {
        val bitmap = scene(512, 512)
        val single = decode(NativeJpeg.encodeToArray(bitmap, 92, 1)!!)!!
        for (threads in intArrayOf(2, 4, 5, 8, 16)) {
            val many = decode(NativeJpeg.encodeToArray(bitmap, 92, threads)!!)!!
            val (worst, mean) = compare(single, many)
            Log.i(TAG, "512x512 seam check, $threads strips: worst $worst, mean %.4f".format(mean))
            // Not "close": identical. The same blocks, the same coefficients,
            // only divided between threads differently.
            assertThat(worst).isEqualTo(0)
            many.recycle()
        }
        single.recycle()
        bitmap.recycle()
    }

    /** Quality has to reach libjpeg, or the setting silently does nothing. */
    @Test
    fun qualityChangesTheFileSize() {
        val bitmap = scene(512, 512)
        val sizes = intArrayOf(95, 85, 70).map {
            NativeJpeg.encodeToArray(bitmap, it, 4)!!.size
        }
        Log.i(TAG, "sizes at q95/q85/q70: ${sizes.joinToString(", ")}")
        assertThat(sizes[0]).isGreaterThan(sizes[1])
        assertThat(sizes[1]).isGreaterThan(sizes[2])
        bitmap.recycle()
    }
}
