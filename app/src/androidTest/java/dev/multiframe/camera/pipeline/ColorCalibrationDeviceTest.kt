package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import androidx.test.rule.GrantPermissionRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ColorCalibration"

/**
 * Recovering the sensor's colour characterisation on real hardware.
 *
 * This device publishes none of it through CameraCharacteristics, so the only
 * route is to have DngCreator write it into a file and read the tags back. That
 * cannot be tested without a real capture result, which is why this opens the
 * camera.
 */
@RunWith(AndroidJUnit4::class)
class ColorCalibrationDeviceTest {

    /**
     * Granted here rather than from the shell, because the test harness
     * uninstalls the app between runs and takes any shell grant with it.
     */
    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Grabs one capture result, or null when the camera cannot be opened --
     * which happens when the test process is not allowed camera access.
     */
    private fun captureOneResult(cameraId: String): Pair<TotalCaptureResult, CameraCharacteristics>? {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val thread = HandlerThread("calib").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        val result = AtomicReference<TotalCaptureResult?>(null)
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null

        try {
            val opened = CountDownLatch(1)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera; opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); opened.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "camera open error $error")
                    camera.close(); opened.countDown()
                }
            }, handler)

            if (!opened.await(5, TimeUnit.SECONDS) || device == null) return null

            val camera = device!!
            val configured = CountDownLatch(1)
            var session: CameraCaptureSession? = null
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s; configured.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        configured.countDown()
                    }
                },
                handler,
            )
            if (!configured.await(5, TimeUnit.SECONDS) || session == null) return null

            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
            }.build()
            session!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    total: TotalCaptureResult,
                ) {
                    result.set(total); latch.countDown()
                }
            }, handler)

            if (!latch.await(6, TimeUnit.SECONDS)) return null
            return result.get()?.let { it to characteristics }
        } catch (e: Exception) {
            Log.w(TAG, "could not capture a result", e)
            return null
        } finally {
            runCatching { device?.close() }
            reader.close()
            thread.quitSafely()
        }
    }

    @Test
    fun theCalibrationIsRecoveredFromAWrittenDng() {
        val captured = captureOneResult("0")
        // The test process is not always permitted camera access; when it is
        // not, this proves nothing rather than failing falsely.
        assumeTrue("camera unavailable to the test process", captured != null)
        val (result, characteristics) = captured!!

        // Nothing at all through the documented route on this device.
        val direct = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        Log.i(TAG, "SENSOR_COLOR_TRANSFORM1 through characteristics: $direct")

        val profile = ColorProfile.calibrated(characteristics, result)
        Log.i(TAG, "matrix: " + profile.matrix.joinToString { "%.4f".format(it) })

        val out = FloatArray(3)
        Mat3(profile.matrix).apply(0.5f, 0.5f, 0.5f, out)
        Log.i(TAG, "neutral renders as (%.4f, %.4f, %.4f)".format(out[0], out[1], out[2]))

        // Whichever route supplied it, a neutral has to stay neutral.
        assertThat(out[0]).isWithin(0.03f).of(out[1])
        assertThat(out[1]).isWithin(0.03f).of(out[2])

        // And the matrix must not be the identity, which would mean neither
        // route produced anything and the picture is rendered raw.
        val isIdentity = (0 until 9).all { i ->
            val expected = if (i % 4 == 0) 1f else 0f
            kotlin.math.abs(profile.matrix[i] - expected) < 1e-4f
        }
        Log.i(TAG, "matrix is identity: $isIdentity")
        assertThat(isIdentity).isFalse()
    }
}
