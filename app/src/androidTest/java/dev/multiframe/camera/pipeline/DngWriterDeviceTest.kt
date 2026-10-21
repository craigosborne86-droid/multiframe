package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.multiframe.camera.bench.TiffEntries
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "DngWriterDevice"

/**
 * Our record of a real capture against `DngCreator`'s, tag by tag: the half
 * of the parity the desk cannot prove, because it needs the camera's own
 * characteristics and result. [DngWriterParityTest] proves the encoding on
 * the September files; this proves [DngCamera2] reads the same values from
 * the camera that `DngCreator` reads.
 *
 * The intended differences (REFERENCE §3.9): DNGVersion; StripOffsets; the
 * WarpRectilinear's zoom, which may be one 0.002 step larger; DateTime and
 * DateTimeOriginal, which are each writer's own reading of the clock.
 */
@RunWith(AndroidJUnit4::class)
class DngWriterDeviceTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: CameraManager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val intended = setOf(50706, TiffDirectory.TAG_STRIP_OFFSETS, 51022, 306, 36867)

    @Test
    fun ourRecordOfARealCaptureMatchesDngCreatorTagByTag() {
        val rear = manager.cameraIdList.filter { id ->
            runCatching {
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            }.getOrDefault(false)
        }
        assumeTrue("no raw-capable rear camera", rear.isNotEmpty())
        var compared = 0
        for (id in rear) {
            val (result, characteristics) = captureOneResult(id) ?: continue
            val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val w = array.width() / 2 * 2; val h = array.height() / 2 * 2
            val raw = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until w * h) raw.putShort((64 + i % 900).toShort())
            raw.rewind()

            val theirsBytes = ByteArrayOutputStream()
            DngCreator(characteristics, result).use { d ->
                d.setOrientation(6)
                d.writeByteBuffer(theirsBytes, Size(w, h), raw.duplicate(), 0)
            }
            val record = DngCamera2.record(characteristics, result, w, h, orientation = 6)
            assumeTrue("camera $id: the writer declines this camera (DngCreator's fallback)", record != null)
            val oursBytes = ByteArrayOutputStream()
            Channels.newChannel(oursBytes).use { DngWriter.write(it, record!!, raw.duplicate()) }

            val theirs = TiffEntries.read(theirsBytes.toByteArray())
            val ours = TiffEntries.read(oursBytes.toByteArray())
            Log.i(TAG, "camera $id: theirs ${theirs.tags.size} tags, ours ${ours.tags.size}")
            Log.i(TAG, "camera $id: only theirs ${theirs.tags - ours.tags}, only ours ${ours.tags - theirs.tags}")
            assertWithMessage("camera $id tags").that(ours.tags).isEqualTo(theirs.tags)
            for (tag in theirs.tags - intended) {
                val a = theirs[tag]!!; val b = ours[tag]!!
                assertWithMessage("camera $id tag $tag type").that(b.type).isEqualTo(a.type)
                assertWithMessage("camera $id tag $tag count").that(b.count).isEqualTo(a.count)
                assertWithMessage("camera $id tag $tag value").that(b.value).isEqualTo(a.value)
            }
            theirs[51022]?.let { op ->
                val a = ByteBuffer.wrap(op.value).order(ByteOrder.BIG_ENDIAN)
                val b = ByteBuffer.wrap(ours[51022]!!.value).order(ByteOrder.BIG_ENDIAN)
                val step = b.getDouble(24) - a.getDouble(24)
                assertWithMessage("camera $id warp zoom").that(step).isAtLeast(-1e-6)
                assertWithMessage("camera $id warp zoom").that(step).isAtMost(0.002 + 1e-6)
                for (i in 1..7) {
                    val ra = a.getDouble(24 + 8 * i) / a.getDouble(24); val rb = b.getDouble(24 + 8 * i) / b.getDouble(24)
                    assertWithMessage("camera $id warp coefficient $i").that(rb).isWithin(1e-4).of(ra)
                }
            }
            assertThat(ours.image()).isEqualTo(theirs.image())
            compared++
        }
        assumeTrue("no camera could be captured from", compared > 0)
    }

    /** One capture result with the lens shading map on, as the ZSL stream asks for. */
    private fun captureOneResult(cameraId: String): Pair<TotalCaptureResult, CameraCharacteristics>? {
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
        val thread = HandlerThread("dngwriter").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        val result = AtomicReference<TotalCaptureResult?>(null)
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        try {
            val opened = CountDownLatch(1)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.countDown() }
            }, handler)
            if (!opened.await(5, TimeUnit.SECONDS) || device == null) return null
            val camera = device!!
            val configured = CountDownLatch(1)
            var session: CameraCaptureSession? = null
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, handler)
            if (!configured.await(5, TimeUnit.SECONDS) || session == null) return null
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }.build()
            session!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
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
}
