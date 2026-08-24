package dev.multiframe.camera.pipeline

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "Multiframe"

/**
 * Frames locked for one shutter press, with the capture metadata that produced
 * each of them.
 *
 * The metadata matters as much as the pixels: DngCreator needs the
 * TotalCaptureResult of the frame it is writing, and white balance comes from
 * the same place. Pairing is by sensor timestamp, because a streaming ring has
 * many results in flight at once and "the last one seen" would be a race.
 */
class ZslBurst internal constructor(
    val frames: RingSnapshot,
    val results: Array<TotalCaptureResult?>,
) : AutoCloseable {
    val count: Int get() = frames.count

    /** Metadata of the reference (newest) frame, which the outputs are written against. */
    val referenceResult: TotalCaptureResult? get() = results.firstOrNull { it != null }

    override fun close() = frames.close()
}

/**
 * Continuous RAW_SENSOR streaming into the native ring.
 *
 * ### Why this is Camera2 rather than CameraX
 *
 * CameraX cannot carry raw on a repeating stream. `ImageAnalysis` emits
 * YUV_420_888 or RGBA_8888 only, and `Camera2Interop` can set request keys but
 * cannot add an output surface to the session CameraX builds. A rolling raw
 * ring therefore needs a session this app owns, with the raw ImageReader as a
 * target of the *repeating* request rather than of one-shot captures.
 *
 * The camera can only have one client, so engaging this stream means CameraX is
 * unbound first and the viewfinder is fed from the preview Surface handed in
 * here. When [ZslPolicy] declines, none of this runs and the CameraX sequential
 * path stays exactly as it was.
 *
 * ### The frame path
 *
 * ImageReader delivers a raw frame on a dedicated thread; it is copied straight
 * from its direct buffer into a native ring slot and closed. No Java array of
 * image data exists at any point, and the copy holds no lock the shutter path
 * needs.
 */
class ZslRawStream private constructor(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val reader: ImageReader,
    private val ring: RawRing,
    private val previewSurface: Surface,
    private val readerThread: HandlerThread,
    private val cameraThread: HandlerThread,
    val config: RawStreamConfig,
    val characteristics: CameraCharacteristics,
    val lens: Lens,
) : AutoCloseable {

    /** Which physical sensor this session's frames are coming from. */
    val lensId: String get() = lens.cameraId

    /** Capture results held by sensor timestamp, in a preallocated ring. */
    private val resultSlots = arrayOfNulls<TotalCaptureResult>(RESULT_RING)
    private val resultStamps = LongArray(RESULT_RING)
    private var resultWrite = 0
    private val resultLock = Any()

    private val buffersLost = AtomicLong(0)
    private val captureFailures = AtomicLong(0)

    @Volatile
    private var closed = false

    val ringCapacity: Int get() = ring.capacity
    val ringReservedBytes: Long get() = ring.reservedBytes

    /** Largest burst this ring can serve while still accepting incoming frames. */
    val maxBurst: Int get() = RawRingBudget.burstForDepth(ring.capacity)

    fun stats(): ZslStreamStats = ZslStreamStats(
        ring = ring.stats(),
        buffersLost = buffersLost.get(),
        captureFailures = captureFailures.get(),
        nominalIntervalNs = config.minFrameDurationNs,
    )

    fun resetStats() {
        ring.resetStats()
        buffersLost.set(0)
        captureFailures.set(0)
    }

    /**
     * The shutter. Locks the newest [count] frames and pairs each with its
     * capture metadata.
     *
     * Nothing is copied and no pixel is touched, so this is bounded by a mutex
     * and a scan of the ring rather than by frame size or burst length.
     */
    fun snapshot(count: Int): ZslBurst {
        // One snapshot at a time: releasing one releases every locked slot, so
        // overlapping bursts would free each other's frames. The UI serialises
        // captures behind its busy flag, which is what makes that safe.
        val frames = ring.lockNewest(count.coerceAtMost(maxBurst))
        val results = arrayOfNulls<TotalCaptureResult>(frames.count)
        for (i in 0 until frames.count) {
            results[i] = findResult(frames.timestampsNs[i])
        }
        return ZslBurst(frames, results)
    }

    /** Applies manual settings by rebuilding the repeating request. */
    fun applySettings(settings: ManualSettings, caps: CameraCapabilities) {
        if (closed) return
        try {
            session.setRepeatingRequest(
                buildRequest(settings, caps), captureCallback, Handler(cameraThread.looper),
            )
        } catch (e: Exception) {
            Log.w(TAG, "ZSL repeating request update failed", e)
        }
    }

    private fun findResult(timestampNs: Long): TotalCaptureResult? = synchronized(resultLock) {
        for (i in resultSlots.indices) {
            if (resultStamps[i] == timestampNs) return resultSlots[i]
        }
        // Metadata for that exact frame has aged out of the ring; the newest
        // result is a better answer than none, and is at most a few frames off.
        var newest: TotalCaptureResult? = null
        var newestStamp = Long.MIN_VALUE
        for (i in resultSlots.indices) {
            if (resultSlots[i] != null && resultStamps[i] > newestStamp) {
                newestStamp = resultStamps[i]
                newest = resultSlots[i]
            }
        }
        return newest
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val stamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(resultLock) {
                resultSlots[resultWrite] = result
                resultStamps[resultWrite] = stamp
                resultWrite = (resultWrite + 1) % RESULT_RING
            }
        }

        override fun onCaptureBufferLost(
            s: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long,
        ) {
            // Definitive proof a frame was lost, as opposed to inferred from a
            // timestamp gap. Counted separately for exactly that reason.
            if (target == reader.surface) buffersLost.incrementAndGet()
        }

        override fun onCaptureFailed(
            s: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            captureFailures.incrementAndGet()
        }
    }

    private fun buildRequest(
        settings: ManualSettings,
        caps: CameraCapabilities,
    ): CaptureRequest {
        // ZERO_SHUTTER_LAG is the template that tells the HAL frames are being
        // retained rather than previewed and discarded. Not every device offers
        // it, so PREVIEW is the fallback.
        val template = runCatching {
            device.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
        }.getOrElse {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        }

        template.addTarget(previewSurface)
        template.addTarget(reader.surface)

        // Pin the frame rate. Left to itself, auto-exposure lengthens the frame
        // duration in dim light, which is precisely where a tight burst window
        // matters most; the extra noise that costs is what the merge is for.
        fpsRange()?.let { template.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        if (settings.suppressIspProcessing) {
            if (caps.canDisableNoiseReduction()) {
                template.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                )
            }
            if (caps.canDisableEdgeEnhancement()) {
                template.set(
                    CaptureRequest.EDGE_MODE,
                    android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF,
                )
            }
        }

        if (settings.manualExposureActive(caps)) {
            template.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF,
            )
            template.set(CaptureRequest.SENSOR_SENSITIVITY, settings.effectiveIso(caps))
            val exposure = settings.effectiveExposureTimeNs(caps)
            template.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            // Frame duration must cover the exposure or the stream slows to it.
            template.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(exposure, config.minFrameDurationNs),
            )
        } else {
            template.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON,
            )
            if (caps.supportsExposureCompensation) {
                template.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    settings.effectiveEvIndex(caps),
                )
            }
        }

        if (settings.manualFocus && caps.hasManualFocus) {
            template.set(
                CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            template.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                settings.effectiveFocusDiopters(caps),
            )
        } else {
            caps.autoAfMode()?.let { template.set(CaptureRequest.CONTROL_AF_MODE, it) }
        }

        template.set(CaptureRequest.CONTROL_AWB_MODE, settings.effectiveAwbMode(caps))
        return template.build()
    }

    /** Prefers a locked 30 fps range over one that is allowed to drop. */
    private fun fpsRange(): Range<Int>? {
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return null
        val target = (config.fps).toInt().coerceAtLeast(1)
        return ranges.firstOrNull { it.lower == target && it.upper == target }
            ?: ranges.filter { it.upper >= target }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        runCatching { device.close() }
        runCatching { reader.close() }

        // The pool is unmapped memory the moment it is closed, and a frame copy
        // may still be running on the reader thread. Stop and join that thread
        // first, or the copy writes into an unmapped page and takes the process
        // with it. Closing the reader does not by itself drain a callback that
        // has already begun.
        readerThread.quitSafely()
        runCatching { readerThread.join(READER_DRAIN_MS) }
        if (readerThread.isAlive) {
            // Leaking the mapping is strictly better than freeing it underneath
            // a live writer; the process is going away in every path that gets
            // here anyway.
            Log.w(TAG, "reader thread did not drain; leaving the pool mapped")
        } else {
            ring.close()
        }

        cameraThread.quitSafely()
        Log.i(TAG, "ZSL raw stream closed")
    }

    companion object {
        /**
         * Capture results retained for timestamp pairing. Deep enough to cover
         * the whole ring plus frames still in flight.
         */
        private const val RESULT_RING = 48

        /** Frames the reader may hold before it starts dropping. */
        private const val READER_IMAGES = 4

        /** How long to wait for an in-flight frame copy before giving up on it. */
        private const val READER_DRAIN_MS = 500L

        private const val OPEN_ATTEMPTS = 4
        private const val OPEN_RETRY_MS = 250L

        /**
         * Opens the camera, allocates the ring and starts streaming.
         *
         * Returns null on any failure, which the caller treats as "fall back to
         * the CameraX sequential path" rather than as a fatal error.
         */
        @SuppressLint("MissingPermission")
        suspend fun open(
            manager: CameraManager,
            lens: Lens,
            characteristics: CameraCharacteristics,
            previewSurface: Surface,
            config: RawStreamConfig,
            ringDepth: Int,
            settings: ManualSettings,
            caps: CameraCapabilities,
        ): ZslRawStream? {
            val ring = RawRing.create(
                config.width, config.height, ringDepth, config.minFrameDurationNs,
            ) ?: run {
                Log.e(TAG, "ZSL declined: ring allocation failed")
                return null
            }

            val cameraThread = HandlerThread("mf-zsl-camera").apply { start() }
            val readerThread = HandlerThread("mf-zsl-reader").apply { start() }
            val cameraHandler = Handler(cameraThread.looper)
            val readerHandler = Handler(readerThread.looper)

            fun bail() {
                ring.close()
                cameraThread.quitSafely()
                readerThread.quitSafely()
            }

            val reader = ImageReader.newInstance(
                config.width, config.height, ImageFormat.RAW_SENSOR, READER_IMAGES,
            )

            // Frames land here at sensor rate. Copy into the pool and release
            // the image immediately: holding one back would starve the reader
            // and stall the stream, which is how a ZSL ring quietly dies.
            // Frames are numbered as they are taken, so a ring snapshot can be
            // traced back to the order they arrived in.
            val delivered = AtomicLong(0)
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireNextImage()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "reader exhausted", e)
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    ring.push(
                        plane.buffer, plane.rowStride, image.timestamp,
                        delivered.incrementAndGet(),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "ring push failed", e)
                } finally {
                    image.close()
                }
            }, readerHandler)

            val device = try {
                // A lens that is a physical member of a logical camera is
                // reached by opening the parent; the outputs then carry the
                // physical id. Only a standalone lens opens by its own id.
                openDeviceWithRetry(manager, lens.openId, cameraHandler)
            } catch (e: Exception) {
                Log.e(TAG, "ZSL declined: camera open failed", e)
                reader.close()
                bail()
                return null
            } ?: run {
                reader.close()
                bail()
                return null
            }

            val executor = Executor { cameraHandler.post(it) }
            val session = try {
                createSession(
                    device, listOf(previewSurface, reader.surface), executor,
                    physicalCameraId = lens.logicalId?.let { lens.cameraId },
                )
            } catch (e: Exception) {
                Log.e(TAG, "ZSL declined: session configuration failed", e)
                device.close()
                reader.close()
                bail()
                return null
            } ?: run {
                device.close()
                reader.close()
                bail()
                return null
            }

            val stream = ZslRawStream(
                device, session, reader, ring, previewSurface,
                readerThread, cameraThread, config, characteristics, lens,
            )

            return try {
                stream.applySettings(settings, caps)
                Log.i(
                    TAG,
                    "ZSL raw stream on ${lens.label}: $config, ring $ringDepth slots " +
                        "(%.0f MB native), burst up to ${stream.maxBurst}".format(
                            ring.reservedBytes / (1024.0 * 1024.0)
                        ),
                )
                stream
            } catch (e: Exception) {
                Log.e(TAG, "ZSL declined: repeating request rejected", e)
                stream.close()
                null
            }
        }

        /**
         * Opens the camera, retrying briefly.
         *
         * CameraX releases the device asynchronously, so an open issued
         * immediately after `unbindAll()` can legitimately find the camera
         * still in use. That is a race to wait out, not a reason to decide the
         * hardware cannot stream raw -- which is what a single failed attempt
         * would look like to the caller.
         */
        private suspend fun openDeviceWithRetry(
            manager: CameraManager,
            cameraId: String,
            handler: Handler,
        ): CameraDevice? {
            repeat(OPEN_ATTEMPTS) { attempt ->
                val device = runCatching { openDevice(manager, cameraId, handler) }
                    .onFailure { Log.w(TAG, "camera open attempt ${attempt + 1} threw", it) }
                    .getOrNull()
                if (device != null) return device
                if (attempt < OPEN_ATTEMPTS - 1) {
                    Log.i(TAG, "camera still busy, retrying in ${OPEN_RETRY_MS}ms")
                    delay(OPEN_RETRY_MS)
                }
            }
            return null
        }

        private suspend fun openDevice(
            manager: CameraManager,
            cameraId: String,
            handler: Handler,
        ): CameraDevice? = suspendCancellableCoroutine { cont ->
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "camera open error $error")
                        camera.close()
                        if (cont.isActive) cont.resume(null)
                    }
                },
                handler,
            )
        }

        private suspend fun createSession(
            device: CameraDevice,
            surfaces: List<Surface>,
            executor: Executor,
            physicalCameraId: String? = null,
        ): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
            val outputs = surfaces.map { surface ->
                OutputConfiguration(surface).apply {
                    // Tagging the output is the only way to say which sensor a
                    // stream should come from. Without it the logical camera
                    // decides, and a raw frame could arrive from a different
                    // lens than the DNG metadata describes.
                    if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
                }
            }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cont.isActive) cont.resume(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "ZSL session configure failed")
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            )
        }

        /**
         * Raw output configurations this camera advertises, as [ZslPolicy] needs
         * them. Kept here so the policy itself stays free of Camera2 types.
         */
        fun rawConfigs(characteristics: CameraCharacteristics): List<RawStreamConfig> {
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return emptyList()
            val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return emptyList()
            return sizes.map { size ->
                RawStreamConfig(
                    width = size.width,
                    height = size.height,
                    minFrameDurationNs = map.getOutputMinFrameDuration(
                        ImageFormat.RAW_SENSOR, size
                    ),
                    stallDurationNs = map.getOutputStallDuration(ImageFormat.RAW_SENSOR, size),
                )
            }
        }

        /**
         * Largest preview size matching the raw stream's aspect ratio, so the
         * viewfinder frames exactly what the DNG will contain.
         */
        fun previewSizeFor(
            characteristics: CameraCharacteristics,
            raw: RawStreamConfig,
            maxWidth: Int = 1920,
        ): Size {
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return Size(1440, 1080)
            val sizes = map.getOutputSizes(SurfaceHolderClass) ?: return Size(1440, 1080)
            val target = raw.width.toDouble() / raw.height
            return sizes
                .filter { it.width <= maxWidth }
                .filter { kotlin.math.abs(it.width.toDouble() / it.height - target) < 0.02 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.filter { it.width <= maxWidth }
                    .maxByOrNull { it.width.toLong() * it.height }
                ?: Size(1440, 1080)
        }

        private val SurfaceHolderClass = android.view.SurfaceHolder::class.java
    }
}

/** Streaming health, as measured rather than assumed. */
data class ZslStreamStats(
    val ring: RingStats,
    val buffersLost: Long,
    val captureFailures: Long,
    val nominalIntervalNs: Long,
) {
    /** Every way a frame can go missing, added up. */
    val totalDropped: Long get() = ring.droppedNoSlot + ring.cameraGaps + buffersLost

    override fun toString(): String =
        "$ring lost=$buffersLost failed=$captureFailures"
}
