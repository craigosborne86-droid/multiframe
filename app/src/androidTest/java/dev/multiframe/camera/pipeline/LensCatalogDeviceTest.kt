package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "LensCatalog"

/**
 * What lenses this device actually has.
 *
 * Reads characteristics only, so it opens no camera and needs no permission.
 * Its job is to prove the catalog describes real hardware correctly, and to
 * record what that hardware is.
 */
@RunWith(AndroidJUnit4::class)
class LensCatalogDeviceTest {

    private val manager: CameraManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Test
    fun everyLensIsDescribedSensibly() {
        val lenses = LensCatalog.enumerate(manager)
        lenses.forEach { Log.i(TAG, "lens $it") }

        assertThat(lenses).isNotEmpty()
        for (lens in lenses) {
            assertThat(lens.cameraId).isNotEmpty()
            assertThat(lens.focalLengthMm).isGreaterThan(0f)
            // Anything outside this is a conversion error, not a real lens.
            assertThat(lens.equivalent35mm).isIn(8..600)
            assertThat(lens.zoomFactor).isGreaterThan(0f)
        }
    }

    @Test
    fun theMainRearCameraIsOneTimesZoom() {
        val lenses = LensCatalog.enumerate(manager)
        val main = LensCatalog.default(lenses)
        Log.i(TAG, "default lens: $main")

        assertThat(main).isNotNull()
        assertThat(main!!.facingBack).isTrue()
        assertThat(main.zoomFactor).isWithin(1e-3f).of(1f)
    }

    @Test
    fun rearLensesAreOrderedWidestFirst() {
        val rear = LensCatalog.rear(LensCatalog.enumerate(manager))
        Log.i(TAG, "rear lenses: " + rear.joinToString { "${it.label}/${it.zoomLabel}" })

        assertThat(rear).isNotEmpty()
        for (i in 1 until rear.size) {
            assertThat(rear[i].equivalent35mm).isAtLeast(rear[i - 1].equivalent35mm)
        }
    }

    @Test
    fun rawCapableLensesAreIdentified() {
        // Which lenses can feed the raw pipeline decides which ones the burst
        // and ZSL paths can be offered on at all.
        val lenses = LensCatalog.enumerate(manager)
        val raw = lenses.filter { it.supportsRaw }
        Log.i(TAG, "RAW-capable: " + raw.joinToString { "${it.label} (id ${it.cameraId})" })

        assertThat(lenses.any { it.supportsRaw }).isTrue()
    }
}
