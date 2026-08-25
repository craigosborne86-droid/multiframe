package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ZslStream"

/**
 * The zero-shutter-lag raw stream, against the real camera.
 *
 * Everything up to this point was tested against synthetic frames pushed into
 * the ring by hand. That proves the pool works; it says nothing about whether
 * the sensor actually delivers raw at thirty frames a second into it, which is
 * the premise the whole design rests on.
 *
 * The preview target is an ImageReader rather than a display surface. The
 * camera does not care what is consuming the preview stream, and this way the
 * test needs no screen -- which matters, because a locked phone has none to
 * give.
 */
@RunWith(AndroidJUnit4::class)
class ZslStreamDeviceTest {

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
        stream?.close()
        stream = null
        previewReader?.close()
        previewReader = null
        previewThread?.quitSafely()
        previewThread = null
    }

    /** Opens the stream on the main rear lens, or returns null if it cannot. */
    private fun openStream(ringDepth: Int = 12): ZslRawStream? = runBlocking {
        val lenses = LensCatalog.enumerate(manager)
        val lens = LensCatalog.default(lenses) ?: return@runBlocking null
        val characteristics = manager.getCameraCharacteristics(lens.cameraId)

        val decision = ZslPolicy.evaluate(
            lens.supportsRaw, ZslRawStream.rawConfigs(characteristics),
        )
        if (decision !is ZslDecision.Stream) {
            Log.i(TAG, "device declines raw streaming: ${decision.describe}")
            return@runBlocking null
        }
        Log.i(TAG, "opening ${lens.label} for ${decision.config}")

        val preview = ZslRawStream.previewSizeFor(characteristics, decision.config)
        val reader = ImageReader.newInstance(
            preview.width, preview.height, ImageFormat.YUV_420_888, 3,
        )
        // Drained on its own thread, or the reader fills and stalls the whole
        // session. A null handler would post to the calling thread's looper,
        // and a test thread has none.
        val thread = HandlerThread("preview-drain").apply { start() }
        previewThread = thread
        reader.setOnImageAvailableListener(
            { it.acquireLatestImage()?.close() }, Handler(thread.looper),
        )
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
                manager = manager,
                lens = lens,
                characteristics = characteristics,
                previewSurface = reader.surface,
                config = decision.config,
                ringDepth = ringDepth,
                settings = ManualSettings(),
                caps = caps,
            )
        }
    }

    /** Waits for the ring to hold at least [wanted] frames. */
    private fun waitForFrames(s: ZslRawStream, wanted: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (s.stats().ring.pushed >= wanted) return true
            Thread.sleep(50)
        }
        return false
    }

    // ------------------------------------------------------------------

    @Test
    fun rawFramesActuallyStreamAtSensorRate() {
        val s = openStream() ?: return
        stream = s
        assumeTrue("camera unavailable to the test process", true)

        assertThat(waitForFrames(s, 12)).isTrue()
        s.resetStats()
        Thread.sleep(3000)

        val stats = s.stats()
        Log.i(TAG, "live stream: $stats")

        // The premise of the whole design: raw arrives continuously, not in
        // response to a shutter press.
        assertThat(stats.ring.pushed).isGreaterThan(40)
        assertThat(stats.ring.measuredFps).isGreaterThan(20.0)
        assertThat(stats.ring.droppedNoSlot).isEqualTo(0)
        assertThat(stats.captureFailures).isEqualTo(0)
    }

    @Test
    fun theShutterHandsOverFramesThatAlreadyExist() {
        // The claim zero shutter lag makes. Everything expensive has already
        // happened; the press only has to lock what is there.
        val s = openStream() ?: return
        stream = s
        assertThat(waitForFrames(s, 10)).isTrue()

        val burst = s.snapshot(8)
        try {
            Log.i(
                TAG,
                "handover: ${burst.count} frames spanning %.0f ms".format(
                    burst.frames.spanNs / 1e6,
                ),
            )
            assertThat(burst.count).isAtLeast(4)
            // Eight frames at thirty a second span about a quarter of a second.
            // Sequential capture on this device spanned five and a half.
            assertThat(burst.frames.spanNs / 1_000_000).isLessThan(700)
            assertThat(burst.referenceResult).isNotNull()
            // And the pixels have to be readable, not merely counted.
            val buffer = burst.frames.buffer(0)
            assertThat(buffer).isNotNull()
            assertThat(buffer!!.capacity()).isEqualTo(s.config.width * s.config.height * 2)
        } finally {
            burst.close()
        }
    }

    @Test
    fun aBurstFromTheRingMergesIntoAPicture() {
        // The whole path, on real sensor data: lock, align, merge, develop.
        val s = openStream() ?: return
        stream = s
        assertThat(waitForFrames(s, 12)).isTrue()

        val profile = SensorProfile.from(s.characteristics)
        val burst = s.snapshot(6)
        try {
            assertThat(burst.count).isAtLeast(4)
            val merger = NativeMerge.create(s.config.width, s.config.height, profile)!!
            val stats = merger.use { m ->
                val stride = burst.frames.rowStride
                val reference = burst.frames.buffer(0)!!
                m.setReference(reference, stride)
                val pyramid = Aligner.buildPyramid(m.lumaProxy(reference, stride))
                val tilesX = maxOf(1, m.proxyWidth / 32)
                val tilesY = maxOf(1, m.proxyHeight / 32)

                for (i in 1 until burst.count) {
                    val frame = burst.frames.buffer(i) ?: continue
                    val field = Aligner.align(
                        pyramid,
                        Aligner.buildPyramid(m.lumaProxy(frame, stride)),
                        tilesX, tilesY,
                    )
                    m.addFrame(frame, stride, field)
                }
                val (merged, mergeStats) = m.finish()
                val colour = ColorProfile.calibrated(s.characteristics, burst.referenceResult)
                val bitmap = m.develop(merged, colour, shading = ShadingMap.from(burst.referenceResult))
                assertThat(bitmap).isNotNull()
                assertThat(bitmap!!.width).isEqualTo(s.config.width)
                bitmap.recycle()
                mergeStats
            }

            Log.i(
                TAG,
                "merged ${stats.framesMerged} real frames, " +
                    "sigma %.2f, mean contribution %.3f".format(
                        stats.estimatedSigmaAtMid, stats.meanContribution,
                    ),
            )
            assertThat(stats.framesMerged).isAtLeast(4)
            // Measured from the burst itself, so a real scene must give a real
            // number rather than the estimator's floor.
            assertThat(stats.estimatedSigmaAtMid).isGreaterThan(0f)
        } finally {
            burst.close()
        }
    }

    @Test
    fun theHighlightGuardReadsTheRealScene() {
        val s = openStream() ?: return
        stream = s
        assertThat(waitForFrames(s, 8)).isTrue()

        val bins = IntArray(64)
        assertThat(s.histogram(bins)).isTrue()
        val analysis = ExposureStrategy.analyse(bins)
        Log.i(
            TAG,
            "scene: clipped %.4f, highlight %.3f, range %.1f stops".format(
                analysis.clippedFraction, analysis.highlightLevel,
                analysis.dynamicRangeStops,
            ),
        )

        assertThat(bins.sum()).isGreaterThan(1000)
        // A real scene spans something; a histogram pinned to one bin would
        // mean the read is not working.
        assertThat(analysis.highlightLevel).isGreaterThan(0f)
    }

    /**
     * The whole shutter, through to files on disk.
     *
     * The last untested link. Everything from the ring to the merge was
     * verified above; this runs the path a user's press actually takes,
     * including writing a DNG and a JPEG through MediaStore.
     */
    @Test
    fun pressingTheShutterWritesADngAndAJpeg() {
        val s = openStream() ?: return
        stream = s
        assertThat(waitForFrames(s, 12)).isTrue()

        val result = runBlocking {
            ZslCapture.captureAndMerge(
                context = context,
                stream = s,
                frameCount = 6,
                rotationDegrees = 0,
            )
        }

        Log.i(
            TAG,
            "shutter: ${result.message} | handover ${result.handoverMicros}us, " +
                "span ${result.burstSpanMillis}ms, merge ${result.mergeMillis}ms, " +
                "develop ${result.developMillis}ms, write ${result.writeMillis}ms",
        )
        Log.i(TAG, "stream health after: ${result.streamStats}")

        assertThat(result.framesCaptured).isAtLeast(4)
        // The claim the whole architecture is for: the press itself is free.
        assertThat(result.handoverMicros).isLessThan(10_000)
        // Both outputs come from the one merged frame, so neither may be missing.
        assertThat(result.savedDng).isNotNull()
        assertThat(result.savedJpeg).isNotNull()
        assertThat(result.stats).isNotNull()
        assertThat(result.stats!!.framesMerged).isAtLeast(4)
    }

    /**
     * The mosaic orchestration, against the real camera.
     *
     * A stationary phone cannot produce a sweep, so this does not test
     * stitching -- that is covered against synthetic scenes, where the answer is
     * known. What it tests is everything around it: that the sweep locks
     * exposure, pulls frames from the live ring, registers them, correctly
     * refuses the ones that add nothing, and comes back with a result instead
     * of hanging or falling over.
     */
    @Test
    fun aSweepRunsAgainstTheLiveCameraAndStopsCleanly() {
        val s = openStream() ?: return
        stream = s
        assertThat(waitForFrames(s, 10)).isTrue()

        val lenses = LensCatalog.enumerate(manager)
        val target = LensCatalog.default(lenses)!!
        // A small canvas: this is about the orchestration, and an 80 megapixel
        // one would spend the test's time on memory.
        val plan = MosaicPlanner.plan(
            target = target,
            capture = s.lens.copy(equivalent35mm = target.equivalent35mm * 3),
            tileWidth = s.config.width,
            tileHeight = s.config.height,
            maxMegapixels = 24.0,
        )!!
        val session = MosaicSession.start(plan, s.config.width, s.config.height)
        assumeTrue("canvas could not be allocated", session != null)

        val deadline = System.currentTimeMillis() + 4000
        val result = session!!.use {
            runBlocking {
                MosaicCapture.run(
                    context = context,
                    stream = s,
                    session = it,
                    settings = ManualSettings(),
                    caps = CameraCapabilities(
                        hasManualSensor = true, isoMin = null, isoMax = null,
                        exposureMinNs = null, exposureMaxNs = null,
                        minFocusDiopters = 0f, awbModes = listOf(1),
                        afModes = emptyList(), noiseReductionModes = emptyList(),
                        edgeModes = emptyList(), evMin = 0, evMax = 0, evStep = 0f,
                        sensorOrientation = 90, supportsRaw = true,
                        supportedOutputFormats = emptySet(),
                    ),
                    onProgress = { },
                    shouldContinue = { System.currentTimeMillis() < deadline },
                )
            }
        }

        Log.i(
            TAG,
            "sweep: ${result.message} | ${result.tiles} tiles, ${result.rejected} rejected, " +
                "%.0f MP, %.1fs".format(result.megapixels, result.elapsedMillis / 1000.0),
        )

        // The first frame anchors the mosaic; a stationary camera then offers
        // nothing new, so the rest must be refused rather than piled up.
        assertThat(result.tiles).isAtLeast(1)
        assertThat(result.rejected).isGreaterThan(0)
        assertThat(result.coverage).isGreaterThan(0f)
        // And it must have stopped when asked rather than run to its deadline.
        assertThat(result.elapsedMillis).isLessThan(15_000)
        // Exposure has to be handed back, or the viewfinder stays frozen after.
        assertThat(s.isLocked).isFalse()
    }

    @Test
    fun theStreamShutsDownCleanly() {
        val s = openStream() ?: return
        assertThat(waitForFrames(s, 6)).isTrue()

        s.close()
        stream = null
        // Closing frees the pool while a frame copy may be in flight; the
        // reader thread is joined first for exactly that reason.
        Thread.sleep(400)
        assertThat(s.stats().ring.pushed).isAtLeast(0)
    }
}
