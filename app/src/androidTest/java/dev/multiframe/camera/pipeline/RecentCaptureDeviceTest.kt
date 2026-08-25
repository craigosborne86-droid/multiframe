package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "RecentCapture"

/**
 * Finding the last photograph, against the real MediaStore.
 *
 * The earlier capture tests write real DNGs and JPEGs into the app's folder, so
 * by the time this runs there is something to find.
 */
@RunWith(AndroidJUnit4::class)
class RecentCaptureDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRememberedCaptureIsFoundAgain() {
        // Written at save time rather than queried, so it needs no permission
        // to read the photo library and always names the app's own image.
        val bitmap = android.graphics.Bitmap.createBitmap(
            32, 32, android.graphics.Bitmap.Config.ARGB_8888,
        ).apply { eraseColor(android.graphics.Color.rgb(90, 140, 200)) }
        val name = "MF_test_${System.currentTimeMillis()}_zsl_8f.jpg"

        val uri = ImageSaver.saveJpeg(context, bitmap, name)
        bitmap.recycle()
        assertThat(uri).isNotNull()

        val shot = RecentCapture.latest(context)
        Log.i(TAG, "remembered: ${shot?.displayName}")

        assertThat(shot).isNotNull()
        assertThat(shot!!.displayName).isEqualTo(name)
        assertThat(RecentCapture.isOurs(shot.displayName)).isTrue()
        assertThat(RecentCapture.describe(shot.displayName)).isEqualTo("zero shutter lag")

        // Tidy up after itself rather than leaving test images in the gallery.
        context.contentResolver.delete(uri!!, null, null)
    }

    @Test
    fun aDeletedCaptureIsForgottenRatherThanShown() {
        // The user may well delete the picture from their gallery. A thumbnail
        // pointing at nothing would fail when tapped rather than simply not
        // being there.
        val bitmap = android.graphics.Bitmap.createBitmap(
            16, 16, android.graphics.Bitmap.Config.ARGB_8888,
        ).apply { eraseColor(android.graphics.Color.WHITE) }
        val uri = ImageSaver.saveJpeg(
            context, bitmap, "MF_test_${System.currentTimeMillis()}_merged_4f.jpg",
        )!!
        bitmap.recycle()
        assertThat(RecentCapture.latest(context)).isNotNull()

        context.contentResolver.delete(uri, null, null)

        assertThat(RecentCapture.latest(context)).isNull()
    }

    @Test
    fun nothingRememberedIsNotAnError() {
        RecentCapture.forget(context)

        assertThat(RecentCapture.latest(context)).isNull()
    }
}
