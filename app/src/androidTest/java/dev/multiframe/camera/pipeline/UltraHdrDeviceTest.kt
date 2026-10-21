package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "UltraHdrDevice"

/**
 * The Ultra HDR JPEG as the phone writes it: the platform's encoder, then
 * the EXIF written over it by ExifWriter, then MediaStore. The three
 * questions the desk cannot answer -- whether the map survives the EXIF
 * rewrite, whether it decodes back with the ratios it was given, and what
 * the platform encode costs against the strip encoder.
 */
@RunWith(AndroidJUnit4::class)
class UltraHdrDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.rgb(90, 100, 110))
        for (y in h / 4 until h / 2) for (x in w / 4 until w / 2) setPixel(x, y, android.graphics.Color.WHITE)
    }

    /** A quarter-size map with a two-stop patch where the picture is white. */
    private fun map(w: Int, h: Int): HdrGainMap.Encoded {
        val gw = (w + 3) / 4
        val gh = (h + 3) / 4
        val codes = ByteArray(gw * gh)
        for (y in gh / 4 until gh / 2) for (x in gw / 4 until gw / 2) codes[y * gw + x] = 255.toByte()
        return HdrGainMap.Encoded(gw, gh, codes, DevelopParams.HDR_HEADROOM)
    }

    private val metadata = CaptureMetadata(
        exposureTimeNs = 4_000_000L, iso = 100, apertureF = 1.7f, focalLengthMm = 6.9f,
        equivalent35mm = 24, frames = 8, takenAtMillis = System.currentTimeMillis(), lensLabel = "24mm",
    )

    @Test
    fun theGainMapSurvivesTheExifWriteAndDecodesWithItsRatios() {
        assumeTrue("Gainmap is API 34", Build.VERSION.SDK_INT >= 34)
        val w = 1024
        val h = 768
        val bitmap = picture(w, h)
        val uri = ImageSaver.saveJpeg(
            context, bitmap, "MF_uhdr_${System.nanoTime()}_zsl_8f.jpg",
            metadata = metadata, gainMap = map(w, h),
        )!!
        bitmap.recycle()
        try {
            val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
            assertThat(decoded.hasGainmap()).isTrue()
            val gm = decoded.gainmap!!
            assertThat(gm.ratioMax[0]).isWithin(0.01f).of(DevelopParams.HDR_HEADROOM)
            assertThat(gm.ratioMin[0]).isWithin(0.01f).of(1f)
            assertThat(gm.gainmapContents.width).isEqualTo(w / 4)
            Log.i(TAG, "decoded map ${gm.gainmapContents.width}x${gm.gainmapContents.height} ${gm.gainmapContents.config}, " +
                "full HDR at ${gm.displayRatioForFullHdr}")
            decoded.recycle()

            context.contentResolver.openFileDescriptor(uri, "r")!!.use { d ->
                val exif = ExifInterface(d.fileDescriptor)
                assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isEqualTo(Build.MANUFACTURER)
                assertThat(exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)).isEqualTo("100")
                assertThat(exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)).isEqualTo(w.toString())
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun withoutAMapThePictureIsAnOrdinaryJpeg() {
        val bitmap = picture(256, 192)
        val uri = ImageSaver.saveJpeg(context, bitmap, "MF_sdr_${System.nanoTime()}_zsl_8f.jpg", metadata = metadata)!!
        bitmap.recycle()
        try {
            val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
            if (Build.VERSION.SDK_INT >= 34) assertThat(decoded.hasGainmap()).isFalse()
            decoded.recycle()
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /** What the HDR shots pay: the platform's single-threaded encode, with its map, against the strips. Logged, not bounded. */
    @Test
    fun theUltraHdrEncodeCostIsMeasured() {
        assumeTrue("Gainmap is API 34", Build.VERSION.SDK_INT >= 34)
        val w = 4080
        val h = 3072
        val bitmap = picture(w, h)
        val gm = map(w, h)
        val runs = 3
        var hdr = 0L
        var strips = 0L
        repeat(runs) {
            val a = java.io.ByteArrayOutputStream()
            val t0 = System.nanoTime()
            assertThat(UltraHdr.compress(bitmap, gm, 95, a)).isTrue()
            hdr += System.nanoTime() - t0
            bitmap.gainmap = null
            val t1 = System.nanoTime()
            NativeJpeg.encodeToArray(bitmap, 95)
            strips += System.nanoTime() - t1
            Log.i(TAG, "ultra hdr ${a.size()} bytes")
        }
        bitmap.recycle()
        Log.i(TAG, "encode 12.5 MP: ultra hdr %.0f ms, strips %.0f ms".format(hdr / 1e6 / runs, strips / 1e6 / runs))
    }
}
