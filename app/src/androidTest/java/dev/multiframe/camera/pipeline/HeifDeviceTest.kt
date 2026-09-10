package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "HeifDevice"

/**
 * Whether this phone can actually write a HEIF, and what it costs.
 *
 * ### Why this has to be measured rather than assumed
 *
 * `Bitmap.CompressFormat.HEIC` is not in the SDK this project compiles against
 * -- checked across android-36.1, 37.0 and 37.1, none of which declare it --
 * so [HeifEncoder] resolves it from the runtime's own enum instead. That makes
 * "can this device write HEIF" a genuinely open question that only a device can
 * answer, and one whose answer decides whether the format control is offered at
 * all.
 *
 * This test does not assert that HEIF works. It asserts that whatever the
 * answer is, the code agrees with it -- and it logs the answer, which is the
 * part a person reads.
 */
@RunWith(AndroidJUnit4::class)
class HeifDeviceTest {

    private val width = 1024
    private val height = 768

    /**
     * A gradient with some structure in it. A flat fill would compress to
     * almost nothing and say nothing useful about the size comparison.
     */
    private fun scene(): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x * 255 / width)
                val g = (y * 255 / height)
                val b = ((x + y) * 255 / (width + height))
                // A few hard edges, so the encoder has something to work at.
                val edge = if ((x / 64 + y / 64) % 2 == 0) 40 else 0
                pixels[y * width + x] = (0xFF shl 24) or
                    (((r + edge).coerceAtMost(255)) shl 16) or
                    (((g + edge).coerceAtMost(255)) shl 8) or
                    ((b + edge).coerceAtMost(255))
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun encodeToTemp(name: String, write: (java.io.FileDescriptor) -> Boolean): Long {
        val file = File.createTempFile(name, null)
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                if (!write(raf.fd)) return -1L
                raf.length()
            }
        } finally {
            file.delete()
        }
    }

    /**
     * The finding. Reports what this device does, and holds the code to
     * agreeing with it either way.
     */
    /**
     * What this runtime actually offers, by name.
     *
     * Not decoration. `HEIC` is documented as available from API 30 and is
     * absent here on API 37, which is surprising enough that the next person to
     * look at this needs the evidence rather than the conclusion -- and if the
     * format exists under some other name, this is what will show it.
     */
    @Test
    fun theAvailableCompressFormatsAreRecorded() {
        val names = Bitmap.CompressFormat.entries.map { it.name }
        Log.i(TAG, "CompressFormat on API ${android.os.Build.VERSION.SDK_INT}: $names")
        // JPEG is the one thing that must be there; the whole fallback rests
        // on it.
        assertThat(names).contains("JPEG")
    }

    @Test
    fun thisDeviceEitherWritesHeifOrSaysItCannot() {
        val available = HeifEncoder.isAvailable
        Log.i(TAG, "HEIC compress format available: $available")

        val bitmap = scene()
        val size = encodeToTemp("heif") { fd ->
            HeifEncoder.encodeToFd(bitmap, fd, 95)
        }

        if (available) {
            // If the runtime says it has the format, it has to produce a file.
            Log.i(TAG, "HEIF wrote $size bytes for ${width}x$height")
            assertThat(size).isGreaterThan(0L)
        } else {
            // And if it does not, the encoder must decline rather than throw
            // or write something that is not a HEIF.
            Log.i(TAG, "HEIF unavailable on this device; JPEG is the only path")
            assertThat(size).isEqualTo(-1L)
        }
        bitmap.recycle()
    }

    /**
     * The reason to have HEIF at all: it should be substantially smaller than
     * the JPEG at comparable quality. Skipped where the device has no encoder.
     */
    @Test
    fun heifIsSmallerThanJpegAtTheSameQuality() {
        if (!HeifEncoder.isAvailable) {
            Log.i(TAG, "no HEIF on this device; nothing to compare")
            return
        }

        val bitmap = scene()
        val heif = encodeToTemp("heif") { fd -> HeifEncoder.encodeToFd(bitmap, fd, 95) }
        val jpeg = encodeToTemp("jpeg") { fd ->
            java.io.FileOutputStream(fd).let { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
                ok
            }
        }
        bitmap.recycle()

        Log.i(
            TAG,
            "HEIF %d bytes, JPEG %d bytes, ratio %.2f".format(
                heif, jpeg, heif.toDouble() / jpeg,
            ),
        )
        assertThat(heif).isGreaterThan(0L)
        assertThat(jpeg).isGreaterThan(0L)
        // The claim in the PRD is roughly half. Held to "smaller", because the
        // ratio depends on the scene and a synthetic gradient is not a
        // photograph -- what would be a real finding is HEIF coming out larger.
        assertThat(heif).isLessThan(jpeg)
    }

    /** Odd dimensions are refused before anything is created. */
    @Test
    fun oddDimensionsAreDeclinedWithoutWriting() {
        val odd = Bitmap.createBitmap(101, 99, Bitmap.Config.ARGB_8888)
        val size = encodeToTemp("odd") { fd -> HeifEncoder.encodeToFd(odd, fd, 95) }
        assertThat(size).isEqualTo(-1L)
        odd.recycle()
    }
}
