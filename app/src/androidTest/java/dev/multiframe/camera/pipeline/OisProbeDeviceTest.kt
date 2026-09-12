package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "OisProbe"

/**
 * Whether this device will hand over its optical stabiliser's readings.
 *
 * `docs/REFERENCE.md` §3.3.2 plans to seed the alignment search from
 * `STATISTICS_OIS_SAMPLES`. The key is optional in the specification, and a
 * lens with OIS is not obliged to report through it. So before anything is
 * built on it, this asks -- per lens, on the phone -- and records what comes
 * back: whether the mode exists, whether samples arrive when it is on, how
 * many per frame, and what they look like.
 *
 * The magnitude matters as much as the presence. A sample stream that reads
 * zero on every frame is a stabiliser that is not reporting, and the seed would
 * be a constant zero -- which is what the search already starts from.
 */
@RunWith(AndroidJUnit4::class)
class OisProbeDeviceTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var stream: ZslRawStream? = null
    private var previewReader: ImageReader? = null
    private var previewThread: HandlerThread? = null

    @After
    fun tearDown() {
        stream?.close()
        stream = null
        previewReader?.close()
        previewReader = null
        previewThread?.quitSafely()
        previewThread = null
    }

    /**
     * What each rear lens advertises. Cheap, no stream needed, and the first
     * thing to know: a lens without the mode cannot be seeded whatever else
     * is true.
     */
    @Test
    fun theOisDataModesAreRecordedPerLens() {
        val lenses = LensCatalog.enumerate(manager)
        assertThat(lenses).isNotEmpty()

        for (lens in LensCatalog.rear(lenses)) {
            val ch = manager.getCameraCharacteristics(lens.cameraId)
            val modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)
            val stab = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val hasOis = stab?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val reportsOis = modes?.contains(CameraMetadata.STATISTICS_OIS_DATA_MODE_ON) == true
            Log.i(
                TAG,
                "%s (id %s): OIS hardware %b, OIS data mode %s".format(
                    lens.label, lens.cameraId, hasOis,
                    if (reportsOis) "available" else "absent (modes=${modes?.toList()})",
                ),
            )
        }
    }

    /**
     * The finding: with the mode on, do samples arrive, and are they nonzero?
     *
     * Opened on the default lens, which on this phone is the main camera and
     * has OIS. Reads the capture results the ring holds after a few seconds of
     * streaming and reports the sample count and the spread of shifts.
     */
    @Test
    fun theStabiliserEitherReportsOrItDoesNot() {
        val s = openStream() ?: run {
            Log.i(TAG, "no raw stream available; cannot probe")
            return
        }
        stream = s

        val ch = s.characteristics
        val modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)
        val available = modes?.contains(CameraMetadata.STATISTICS_OIS_DATA_MODE_ON) == true
        Log.i(TAG, "OIS data mode on ${s.lens.label}: ${if (available) "available" else "absent"}")
        if (!available) return

        // Let the ring fill under the mode.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && s.stats().ring.pushed < 12) {
            Thread.sleep(50)
        }

        s.snapshot(8).use { burst ->
            var framesWithSamples = 0
            var totalSamples = 0
            var maxShift = 0f
            var sumAbsX = 0.0
            var sumAbsY = 0.0

            for (i in 0 until burst.count) {
                val result = burst.results[i] ?: continue
                val mode = result.get(CaptureResult.STATISTICS_OIS_DATA_MODE)
                val samples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES)
                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L

                if (samples == null || samples.isEmpty()) {
                    Log.i(TAG, "frame $i: mode=$mode, no OIS samples")
                    continue
                }
                framesWithSamples++
                totalSamples += samples.size

                // Sample timestamps relative to the frame, so the spread across
                // the exposure is visible.
                val first = samples.first()
                val last = samples.last()
                Log.i(
                    TAG,
                    "frame %d: %d samples over %.1fms (exposure %.1fms), first (%.2f,%.2f) last (%.2f,%.2f)".format(
                        i, samples.size,
                        (last.timestamp - first.timestamp) / 1e6,
                        exposure / 1e6,
                        first.xshift, first.yshift, last.xshift, last.yshift,
                    ),
                )
                for (smp in samples) {
                    sumAbsX += kotlin.math.abs(smp.xshift)
                    sumAbsY += kotlin.math.abs(smp.yshift)
                    maxShift = maxOf(maxShift, kotlin.math.abs(smp.xshift), kotlin.math.abs(smp.yshift))
                }
                if (ts == 0L) Log.i(TAG, "frame $i has no SENSOR_TIMESTAMP")
            }

            Log.i(
                TAG,
                "%d of %d frames carried OIS samples, %d samples total, mean |shift| (%.3f, %.3f) px, max %.2f px".format(
                    framesWithSamples, burst.count, totalSamples,
                    if (totalSamples > 0) sumAbsX / totalSamples else 0.0,
                    if (totalSamples > 0) sumAbsY / totalSamples else 0.0,
                    maxShift,
                ),
            )

            // Recorded, not required. Both answers decide the design.
            if (framesWithSamples > 0) {
                assertThat(totalSamples).isGreaterThan(0)
            } else {
                Log.i(TAG, "mode advertised but no samples delivered -- cannot seed from OIS on this lens")
            }
        }
    }

    private fun openStream(ringDepth: Int = 12): ZslRawStream? = runBlocking {
        val lenses = LensCatalog.enumerate(manager)
        val lens = LensCatalog.default(lenses) ?: return@runBlocking null
        val characteristics = manager.getCameraCharacteristics(lens.cameraId)

        val decision = ZslPolicy.evaluate(lens.supportsRaw, ZslRawStream.rawConfigs(characteristics))
        if (decision !is ZslDecision.Stream) return@runBlocking null

        val preview = ZslRawStream.previewSizeFor(characteristics, decision.config)
        val reader = ImageReader.newInstance(preview.width, preview.height, ImageFormat.YUV_420_888, 3)
        val thread = HandlerThread("preview-drain").apply { start() }
        previewThread = thread
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
        previewReader = reader

        val caps = CameraCapabilities(
            hasManualSensor = true, isoMin = null, isoMax = null,
            exposureMinNs = null, exposureMaxNs = null, minFocusDiopters = 0f,
            awbModes = listOf(1), afModes = emptyList(),
            noiseReductionModes = emptyList(), edgeModes = emptyList(),
            evMin = 0, evMax = 0, evStep = 0f,
            sensorOrientation = 90, supportsRaw = true,
            supportedOutputFormats = emptySet(),
        )

        withTimeoutOrNull(20_000) {
            ZslRawStream.open(
                manager = manager, lens = lens, characteristics = characteristics,
                previewSurface = reader.surface, config = decision.config,
                ringDepth = ringDepth, settings = ManualSettings(), caps = caps,
            )
        }
    }
}
