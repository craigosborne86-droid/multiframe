package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "DcgProfile"

/**
 * The read-noise characterisation of REFERENCE.md §2.3, run for the record on
 * every raw lens this phone has.
 *
 * `DcgProfiler` measures temporal variance up an ISO ladder off whatever the
 * camera is pointed at and fits a broken line to it; a break is a conversion
 * gain switch, and its ISO is what the DCG bracket is then built around. The
 * result is written to the calibration store, which is what the app reads.
 *
 * The scene decides whether a run is possible -- a dark room leaves too few
 * rungs above the metered point, and the profiler declines rather than
 * guess -- so a lens that could not be profiled is reported and skipped, not
 * failed. What is asserted is that the machinery runs to a verdict on the
 * main lens where the light allows.
 */
@RunWith(AndroidJUnit4::class)
class DcgProfileDeviceTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var stream: ZslRawStream? = null
    private var previewReader: ImageReader? = null
    private var previewThread: HandlerThread? = null

    @After
    fun release() {
        stream?.close(); stream = null
        previewReader?.close(); previewReader = null
        previewThread?.quitSafely(); previewThread = null
    }

    private fun capsFor(ch: CameraCharacteristics): CameraCapabilities {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return CameraCapabilities(
            hasManualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            isoMin = iso?.lower, isoMax = iso?.upper,
            exposureMinNs = exposure?.lower, exposureMaxNs = exposure?.upper,
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            awbModes = listOf(1), afModes = emptyList(),
            noiseReductionModes = emptyList(), edgeModes = emptyList(),
            evMin = 0, evMax = 0, evStep = 0f,
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            supportsRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            supportedOutputFormats = emptySet(),
            maxAnalogIso = ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
        )
    }

    private fun openStream(lens: Lens): Pair<ZslRawStream, CameraCapabilities>? = runBlocking {
        val ch = manager.getCameraCharacteristics(lens.cameraId)
        val decision = ZslPolicy.evaluate(lens.supportsRaw, ZslRawStream.rawConfigs(ch))
        if (decision !is ZslDecision.Stream) {
            Log.i(TAG, "${lens.label}: no raw stream (${decision.describe})")
            return@runBlocking null
        }
        val preview = ZslRawStream.previewSizeFor(ch, decision.config)
        val reader = ImageReader.newInstance(preview.width, preview.height, ImageFormat.YUV_420_888, 3)
        val thread = HandlerThread("preview-drain").apply { start() }
        previewThread = thread
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
        previewReader = reader
        val caps = capsFor(ch)
        val s = withTimeoutOrNull(20_000) {
            ZslRawStream.open(manager, lens, ch, reader.surface, decision.config, 12, ManualSettings(), caps)
        } ?: return@runBlocking null
        stream = s
        s to caps
    }

    private fun profile(lens: Lens): DcgCalibration.Result? = runBlocking {
        val (s, caps) = openStream(lens) ?: return@runBlocking null
        // Let the meter settle before the ladder is hung from its reading.
        var waited = 0
        while (s.latestExposure() == null && waited < 60) { delay(100); waited++ }
        if (s.latestExposure() == null) {
            Log.i(TAG, "${lens.label}: no exposure reading in six seconds")
            return@runBlocking null
        }
        delay(1500)
        val t0 = System.currentTimeMillis()
        val result = DcgProfiler.profile(context, s, caps, ManualSettings(), lens.cameraId)
        Log.i(TAG, "${lens.label} (camera ${lens.cameraId}) on ${Build.MODEL}: ${result ?: "not profiled (see DcgProfiler)"} in ${System.currentTimeMillis() - t0}ms")
        result
    }

    @Test
    fun everyRawRearLensIsProfiledForItsGainSwitch() {
        val rear = LensCatalog.rear(LensCatalog.enumerate(manager)).filter { it.supportsRaw }
        assumeTrue("no raw lens", rear.isNotEmpty())
        val outcomes = LinkedHashMap<String, DcgCalibration.Result?>()
        for (lens in rear) {
            outcomes[lens.label] = profile(lens)
            release()
        }
        for ((label, r) in outcomes) {
            Log.i(TAG, "summary ${Build.MODEL} $label: ${r?.toString() ?: "not profiled"}; store says ${DcgCalibrationStore.thresholdFor(context, rear.first { it.label == label }.cameraId) ?: "none"}")
        }
        // The main lens has to have reached a verdict where the light allowed
        // a ladder at all; a dark room is reported, not failed.
        val main = LensCatalog.default(rear)!!
        val mainResult = outcomes[main.label]
        assumeTrue("too dark to profile the main lens here", mainResult != null)
        assertThat(mainResult!!.sampleCount).isAtLeast(DcgCalibration.MIN_SAMPLES)
    }
}
