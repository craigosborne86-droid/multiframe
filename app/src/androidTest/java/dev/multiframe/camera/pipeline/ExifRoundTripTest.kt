package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ExifRoundTrip"

/**
 * Whether the tags this app writes survive being written.
 *
 * Separated from the capture test so a missing tag can be attributed: either
 * the value never arrived from the camera, or it did and the writing lost it.
 */
@RunWith(AndroidJUnit4::class)
class ExifRoundTripTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Writes one value in a given format and reports what comes back. */
    private fun probe(tag: String, value: String): String? {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GRAY) }
        val uri = ImageSaver.saveJpeg(
            context, bitmap, "MF_probe_${System.nanoTime()}_zsl_1f.jpg",
        )!!
        bitmap.recycle()
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { d ->
            val exif = ExifInterface(d.fileDescriptor)
            exif.setAttribute(tag, value)
            exif.saveAttributes()
        }
        val read = context.contentResolver.openFileDescriptor(uri, "r")!!.use { d ->
            ExifInterface(d.fileDescriptor).getAttribute(tag)
        }
        context.contentResolver.delete(uri, null, null)
        return read
    }

    /**
     * Pins an undocumented inconsistency in the platform that this app depends
     * on.
     *
     * ExifInterface wants a decimal for exposure and aperture and a rational
     * for focal length, and silently discards a value in the other form rather
     * than rejecting it. Every focal length was missing from every photograph
     * until this was found by probing rather than reasoning.
     *
     * If a future Android makes these consistent, this test fails and says so,
     * which is better than the tags quietly disappearing again.
     */
    @Test
    fun exifInterfaceWantsDifferentFormsForDifferentTags() {
        assertThat(probe(ExifInterface.TAG_EXPOSURE_TIME, "0.004")).isEqualTo("0.004")
        assertThat(probe(ExifInterface.TAG_EXPOSURE_TIME, "4/1000")).isNull()

        assertThat(probe(ExifInterface.TAG_F_NUMBER, "1.7")).isEqualTo("1.7")
        assertThat(probe(ExifInterface.TAG_F_NUMBER, "170/100")).isNull()

        assertThat(probe(ExifInterface.TAG_FOCAL_LENGTH, "690/100")).isEqualTo("690/100")
        assertThat(probe(ExifInterface.TAG_FOCAL_LENGTH, "6.9")).isNull()
    }

    /**
     * Every tag survives being written to a real file.
     *
     * Separated from the capture test so a missing tag can be attributed:
     * either the value never arrived from the camera, or it did and the writing
     * lost it. That distinction is what found the format problem -- the
     * framework's ExifInterface silently discards rational tags written as
     * "1/250" and keeps the same value written as "0.004".
     */
    @Test
    fun everyTagSurvivesTheRoundTrip() {
        val metadata = CaptureMetadata(
            exposureTimeNs = 4_000_000L,      // 1/250
            iso = 400,
            apertureF = 1.7f,
            focalLengthMm = 6.9f,
            equivalent35mm = 24,
            frames = 8,
            takenAtMillis = System.currentTimeMillis(),
            lensLabel = "24mm",
        )
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GRAY) }
        // Through the saver rather than by writing afterwards, which is the
        // order the capture paths use and the only one MediaStore sees.
        val uri = ImageSaver.saveJpeg(
            context, bitmap, "MF_exif_${System.currentTimeMillis()}_zsl_4f.jpg",
            metadata = metadata,
        )!!
        bitmap.recycle()

        context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
            val exif = ExifInterface(descriptor!!.fileDescriptor)
            @Suppress("DEPRECATION")
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
            val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            Log.i(
                TAG,
                "read back: exposure=$exposure iso=$iso aperture=$aperture focal=$focal",
            )

            assertThat(iso).isEqualTo("400")
            // The rational tags, which were silently dropped before.
            assertThat(exposure).isNotNull()
            assertThat(exposure!!.toDouble()).isWithin(1e-5).of(0.004)
            assertThat(aperture!!.toDouble()).isWithin(0.01).of(1.7)
            // A rational, so it reads back in the form it was written.
            assertThat(focal).isEqualTo("690/100")
            assertThat(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                .isEqualTo(ExifWriter.SOFTWARE)
        }
        context.contentResolver.delete(uri, null, null)
    }
}
