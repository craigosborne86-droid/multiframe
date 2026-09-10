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

    /** Most recent low-ISO result, for [latestExposure] during DCG. */
    private var lastLowResult: TotalCaptureResult? = null

    private val buffersLost = AtomicLong(0)
    private val captureFailures = AtomicLong(0)

    @Volatile
    private var closed = false

    /**
     * Whether the stream is alternating between two ISO levels.
     *
     * Set through [setDcg] rather than directly, because changing it needs
     * to rebuild the repeating request -- an idle flag flip with no rebuild
     * would leave the old request running until something else happened to
     * call [applySettings].
     */
    @Volatile
    var dcg: Boolean = false
        private set

    /**
     * Last ISO the low-ISO (AE-driven) request converged to.
     * Zero before the first low-ISO result arrives.
     */
    @Volatile
    private var convergedIso = 0

    /** Last exposure time from a low-ISO result, in nanoseconds. */
    @Volatile
    private var convergedExposureNs = 0L

    /**
     * Exposure compensation the highlight guard has asked for, overriding what
     * the user's settings would otherwise request.
     */
    @Volatile
    private var evOverride: Int? = null

    /** Stops of exposure currently being given up to protect highlights. */
    @Volatile
    var pullStops: Float = 0f
        private set

    private val sensorProfile by lazy { SensorProfile.from(characteristics) }

    /** The sensor's full readout rectangle, which metering coordinates are in. */
    private val activeArray: SensorRect by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?.let { SensorRect.from(it) }
            ?: SensorRect(0, 0, config.width, config.height)
    }

    /**
     * Whether the device takes a zoom ratio directly.
     *
     * Preferred over cropping the sensor by hand: the camera can then use the
     * whole multi-camera system to satisfy the request, and metering
     * coordinates stay in one system instead of moving with the crop.
     */
    private val zoomRange by lazy {
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    }

    @Volatile
    private var focusRegion: SensorRect? = null

    @Volatile
    private var zoomRatio: Float = 1f

    /**
     * Whether exposure and white balance are pinned.
     *
     * A mosaic needs this. Auto-exposure drifting between tiles leaves each one
     * a slightly different brightness, and no amount of feathering hides a
     * brightness difference that runs the whole length of a seam -- the blend
     * turns a hard line into a soft gradient, which is if anything more visible
     * across a clear sky. Auto white balance drifting is worse, because it
     * shifts colour rather than level.
     */
    @Volatile
    private var locked = false
    private val histogramBins = IntArray(64)
    private var lastSettings: ManualSettings? = null
    private var lastCaps: CameraCapabilities? = null

    val ringCapacity: Int get() = ring.capacity
    val ringReservedBytes: Long get() = ring.reservedBytes

    /** Frames a shutter press could take right now. */
    fun readyFrames(): Int = if (closed) 0 else ring.readyCount()

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

    /**
     * Re-reads the scene and protects the highlights if it needs to.
     *
     * Has to run while streaming rather than at the shutter. In a
     * zero-shutter-lag camera the frames already exist when the button is
     * pressed, so an exposure decision taken then applies to the *next*
     * photograph. Continuously is the only moment that affects this one.
     *
     * Returns what it saw, or null when it declined to act -- no frames yet, or
     * the user has taken manual control of exposure, in which case second
     * guessing them would be wrong.
     */
    fun protectHighlights(
        frameCount: Int,
        caps: CameraCapabilities,
        settings: ManualSettings,
    ): SceneAnalysis? {
        if (closed) return null
        if (settings.manualExposureActive(caps)) return null
        if (!ring.histogramNewest(histogramBins, sensorProfile)) return null

        val analysis = ExposureStrategy.analyse(histogramBins)
        val index = ExposureStrategy.recommendedEvIndex(analysis, frameCount, caps)
        if (index != evOverride) {
            evOverride = index
            pullStops = index * caps.evStep
            Log.i(
                TAG,
                "highlight guard: clipped %.3f, pulling %.2f stops (EV index %d)".format(
                    analysis.clippedFraction, pullStops, index,
                ),
            )
            applySettings(settings, caps)
        }
        return analysis
    }

    /**
     * Focuses and meters on a point, given as a fraction of the displayed image.
     *
     * Sends a one-shot autofocus trigger as well as updating the repeating
     * request. Without the trigger the region changes but nothing re-focuses
     * until the scene happens to move, which reads as the tap having done
     * nothing.
     */
    fun focusAt(
        normalisedX: Float,
        normalisedY: Float,
        settings: ManualSettings,
        caps: CameraCapabilities,
    ) {
        if (closed) return
        val orientation = caps.sensorOrientation
        focusRegion = TouchFocus.regionAt(normalisedX, normalisedY, orientation, activeArray)
        applySettings(settings, caps)

        runCatching {
            val trigger = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                focusRegion?.let {
                    val rect = arrayOf(TouchFocus.meteringRectangle(it))
                    set(CaptureRequest.CONTROL_AF_REGIONS, rect)
                    set(CaptureRequest.CONTROL_AE_REGIONS, rect)
                }
                set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START,
                )
            }.build()
            session.capture(trigger, null, Handler(cameraThread.looper))
        }.onFailure { Log.w(TAG, "focus trigger failed", it) }
    }

    /** Clears any tapped focus point, returning to whole-frame metering. */
    fun clearFocusPoint(settings: ManualSettings, caps: CameraCapabilities) {
        if (closed || focusRegion == null) return
        focusRegion = null
        applySettings(settings, caps)
    }

    /**
     * Pins exposure, white balance and focus for the duration of a sweep.
     *
     * Focus too: a mosaic of a distant scene should stay at one focus distance,
     * and refocusing between tiles changes magnification slightly as well as
     * sharpness, which registration then has to absorb.
     */
    fun lockForSweep(settings: ManualSettings, caps: CameraCapabilities) {
        if (closed || locked) return
        locked = true
        Log.i(TAG, "locking exposure, white balance and focus for a sweep")
        applySettings(settings, caps)
    }

    fun unlock(settings: ManualSettings, caps: CameraCapabilities) {
        if (closed || !locked) return
        locked = false
        applySettings(settings, caps)
    }

    val isLocked: Boolean get() = locked

    /** Digital zoom on top of whichever lens is selected. */
    fun setZoom(ratio: Float, settings: ManualSettings, caps: CameraCapabilities) {
        if (closed) return
        val max = zoomRange?.upper ?: MAX_FALLBACK_ZOOM
        val clamped = ratio.coerceIn(1f, max)
        if (kotlin.math.abs(clamped - zoomRatio) < 0.01f) return
        zoomRatio = clamped
        applySettings(settings, caps)
    }

    val zoom: Float get() = zoomRatio
    val maxZoom: Float get() = zoomRange?.upper ?: MAX_FALLBACK_ZOOM

    /**
     * Histograms the newest frame, for the live display.
     *
     * The same in-place read the highlight guard uses, so showing a histogram
     * costs the ring nothing and consumes no frames the shutter might want.
     */
    fun histogram(bins: IntArray, stride: Int = 12): Boolean {
        if (closed) return false
        return ring.histogramNewest(bins, sensorProfile, stride)
    }

    /**
     * A small luma view of the newest frame, for focus peaking.
     *
     * From the sensor rather than the preview, and read in place so it costs
     * the ring nothing and consumes no frames the shutter might want.
     */
    fun luma(out: ByteArray, width: Int, height: Int): Boolean {
        if (closed) return false
        return ring.lumaNewest(out, width, height, sensorProfile)
    }

    /** Releases the guard's hold on exposure, back to what the user asked for. */
    fun clearHighlightProtection(caps: CameraCapabilities, settings: ManualSettings) {
        if (closed || evOverride == null) return
        evOverride = null
        pullStops = 0f
        applySettings(settings, caps)
    }

    /**
     * Engages or disengages dual conversion gain on this stream.
     *
     * When enabled, the repeating request becomes a two-element burst that
     * alternates between the current auto-exposure (or manual) ISO and a
     * high-ISO frame at [DCG_RATIO] times higher. When disabled, the stream
     * falls back to a single repeating request identical to what it was
     * before DCG existed.
     */
    fun setDcg(enabled: Boolean, settings: ManualSettings, caps: CameraCapabilities) {
        if (dcg == enabled) return
        dcg = enabled
        if (!enabled) {
            convergedIso = 0
            convergedExposureNs = 0
        }
        applySettings(settings, caps)
    }

    /** Applies manual settings by rebuilding the repeating request. */
    fun applySettings(settings: ManualSettings, caps: CameraCapabilities) {
        if (closed) return
        lastSettings = settings
        lastCaps = caps
        submitRepeating(settings, caps)
    }

    private fun submitRepeating(settings: ManualSettings, caps: CameraCapabilities) {
        try {
            val low = buildRequest(settings, caps)
            val high = if (dcg) buildHighRequest(settings, caps) else null
            val handler = Handler(cameraThread.looper)
            if (high != null) {
                session.setRepeatingBurst(
                    listOf(low, high), captureCallback, handler,
                )
            } else {
                session.setRepeatingRequest(low, captureCallback, handler)
            }
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
        return newestLocked()
    }

    /** The most recent result in the ring. Caller must hold [resultLock]. */
    private fun newestLocked(): TotalCaptureResult? {
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

    /**
     * What the sensor last reported, as (ISO, exposure time in nanoseconds).
     *
     * For the viewfinder's readout, which otherwise has no source while this
     * stream is running. The readout is fed from the CameraX preview's
     * repeating request, and engaging this ring unbinds CameraX -- so the
     * numbers went blank in the one mode the app is actually for, while the
     * histogram beside them kept working because it reads this ring directly.
     *
     * The results are already here, kept for the DNG writer, which needs the
     * metadata of the frame it is writing. This is the same ring read for a
     * cheaper purpose. Null before the session has completed a frame, and on
     * a device that reports neither key.
     *
     * When DCG is active, returns the low-ISO result only -- the high-ISO
     * frame's boosted sensitivity is an implementation detail, not what the
     * photographer set or the scene demanded.
     */
    fun latestExposure(): Pair<Int, Long>? {
        val result = synchronized(resultLock) {
            if (dcg) lastLowResult else newestLocked()
        } ?: return null
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        return iso to exposureNs
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

                if (dcg && request.tag == TAG_LOW) lastLowResult = result
            }

            if (dcg && request.tag == TAG_LOW) trackConvergence(result)
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

    /**
     * Updates [convergedIso] and [convergedExposureNs] from a low-ISO result,
     * and rebuilds the burst when the ISO changes by more than 10%.
     *
     * Called on the camera thread from the capture callback, so the rebuild
     * runs on the same thread the session expects.
     */
    private fun trackConvergence(result: TotalCaptureResult) {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val prev = convergedIso
        convergedIso = iso
        convergedExposureNs = exposure

        val firstConvergence = prev <= 0
        val significantChange = prev > 0 &&
            kotlin.math.abs(iso - prev).toFloat() / prev > ISO_REBUILD_THRESHOLD
        if (firstConvergence || significantChange) {
            val s = lastSettings ?: return
            val c = lastCaps ?: return
            submitRepeating(s, c)
        }
    }

    /**
     * A builder carrying everything two requests have in common: targets,
     * frame rate, ISP suppression, focus, white balance, zoom, metering
     * regions and lens shading. Exposure is left to the caller.
     */
    private fun createBaseBuilder(
        settings: ManualSettings,
        caps: CameraCapabilities,
    ): CaptureRequest.Builder {
        // ZERO_SHUTTER_LAG is the template that tells the HAL frames are being
        // retained rather than previewed and discarded. Not every device offers
        // it, so PREVIEW is the fallback.
        val builder = runCatching {
            device.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
        }.getOrElse {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        }

        builder.addTarget(previewSurface)
        builder.addTarget(reader.surface)

        // Pin the frame rate. Left to itself, auto-exposure lengthens the frame
        // duration in dim light, which is precisely where a tight burst window
        // matters most; the extra noise that costs is what the merge is for.
        fpsRange()?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        if (settings.suppressIspProcessing) {
            if (caps.canDisableNoiseReduction()) {
                builder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                )
            }
            if (caps.canDisableEdgeEnhancement()) {
                builder.set(
                    CaptureRequest.EDGE_MODE,
                    android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF,
                )
            }
        }

        if (settings.manualFocus && caps.hasManualFocus) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                settings.effectiveFocusDiopters(caps),
            )
        } else {
            caps.autoAfMode()?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
        }

        builder.set(CaptureRequest.CONTROL_AWB_MODE, settings.effectiveAwbMode(caps))

        // Reported only when requested, and raw is uncorrected by definition.
        builder.set(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            android.hardware.camera2.CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON,
        )

        focusRegion?.let {
            val rect = arrayOf(TouchFocus.meteringRectangle(it))
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
        }

        if (zoomRatio > 1.001f) {
            val range = zoomRange
            if (range != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            } else {
                // Older path: crop the sensor by hand. Kept because zoom ratio
                // is optional in the spec and a device without it should still
                // zoom rather than silently ignoring the gesture.
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    TouchFocus.cropForZoom(activeArray, zoomRatio, MAX_FALLBACK_ZOOM).toRect(),
                )
            }
        }

        return builder
    }

    private fun buildRequest(
        settings: ManualSettings,
        caps: CameraCapabilities,
    ): CaptureRequest {
        val builder = createBaseBuilder(settings, caps)

        if (locked) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        if (settings.manualExposureActive(caps)) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF,
            )
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.effectiveIso(caps))
            val exposure = settings.effectiveExposureTimeNs(caps)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            // Frame duration must cover the exposure or the stream slows to it.
            builder.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(exposure, config.minFrameDurationNs),
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON,
            )
            if (caps.supportsExposureCompensation) {
                // The guard's decision wins over the user's baseline
                // compensation while it is engaged, because it is a measured
                // response to this scene rather than a standing preference.
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    evOverride ?: settings.effectiveEvIndex(caps),
                )
            }
        }

        if (dcg) builder.setTag(TAG_LOW)
        return builder.build()
    }

    /**
     * The high-ISO half of a DCG burst.
     *
     * Uses the converged exposure from the low-ISO request (or the user's
     * manual values) and multiplies the ISO by [DCG_RATIO]. Returns null
     * when the source values are not yet known -- which is only the first
     * few frames after DCG is enabled in auto-exposure mode, because the
     * stream needs at least one low-ISO result to know what to multiply.
     */
    private fun buildHighRequest(
        settings: ManualSettings,
        caps: CameraCapabilities,
    ): CaptureRequest? {
        val baseIso: Int
        val baseExposureNs: Long

        if (settings.manualExposureActive(caps)) {
            baseIso = settings.effectiveIso(caps)
            baseExposureNs = settings.effectiveExposureTimeNs(caps)
        } else {
            if (convergedIso <= 0 || convergedExposureNs <= 0) return null
            baseIso = convergedIso
            baseExposureNs = convergedExposureNs
        }

        val highIso = (baseIso * DCG_RATIO).coerceAtMost(caps.isoMax ?: Int.MAX_VALUE)

        val builder = createBaseBuilder(settings, caps)

        if (locked) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF,
        )
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, highIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, baseExposureNs)
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            maxOf(baseExposureNs, config.minFrameDurationNs),
        )

        builder.setTag(TAG_HIGH)
        return builder.build()
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

        /** Used only when the device reports no zoom ratio range of its own. */
        private const val MAX_FALLBACK_ZOOM = 8f

        /** High-ISO frame uses this multiple of the low-ISO value. */
        const val DCG_RATIO = 4

        private const val TAG_LOW = "low"
        private const val TAG_HIGH = "high"

        /** Rebuild the burst when the converged ISO changes by more than this. */
        private const val ISO_REBUILD_THRESHOLD = 0.1f

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
         * What an attempt to open the camera came to.
         *
         * A nullable `CameraDevice` could not say this. Both "the camera is
         * busy, try again in a moment" and "the permission has been taken
         * away" arrived as `null`, so the retry loop treated them the same and
         * spent three more attempts and 750ms waiting for a permission to come
         * back, which is not a thing that happens.
         */
        private sealed interface CameraOpen {
            data class Opened(val device: CameraDevice) : CameraOpen

            /** Not free yet, or lost to a higher-priority client. Worth waiting. */
            data object Busy : CameraOpen

            /** Permission is gone. Waiting cannot help. */
            data object Denied : CameraOpen
        }

        /**
         * Opens the camera, retrying briefly.
         *
         * CameraX releases the device asynchronously, so an open issued
         * immediately after `unbindAll()` can legitimately find the camera
         * still in use. That is a race to wait out, not a reason to decide the
         * hardware cannot stream raw -- which is what a single failed attempt
         * would look like to the caller.
         *
         * Only that race is worth waiting out. A revoked permission is not a
         * race, and [CameraOpen.Denied] returns immediately rather than
         * sleeping through the remaining attempts.
         */
        private suspend fun openDeviceWithRetry(
            manager: CameraManager,
            cameraId: String,
            handler: Handler,
        ): CameraDevice? {
            repeat(OPEN_ATTEMPTS) { attempt ->
                // A throw from `openCamera` -- CameraAccessException, or a bad
                // id -- is still caught here and treated as an attempt that
                // failed, exactly as before.
                val result = runCatching { openDevice(manager, cameraId, handler) }
                    .onFailure { Log.w(TAG, "camera open attempt ${attempt + 1} threw", it) }
                    .getOrNull()

                when (result) {
                    is CameraOpen.Opened -> return result.device
                    CameraOpen.Denied -> return null
                    CameraOpen.Busy, null -> {
                        if (attempt < OPEN_ATTEMPTS - 1) {
                            Log.i(TAG, "camera still busy, retrying in ${OPEN_RETRY_MS}ms")
                            delay(OPEN_RETRY_MS)
                        }
                    }
                }
            }
            return null
        }

        private suspend fun openDevice(
            manager: CameraManager,
            cameraId: String,
            handler: Handler,
        ): CameraOpen = suspendCancellableCoroutine { cont ->
            // Camera permission is checked before the viewfinder is shown, but
            // it can be revoked while this process is still alive -- and
            // `openCamera` reports that by throwing rather than through the
            // state callback, so none of the paths below would run.
            //
            // The caller's `runCatching` would catch it, and the app does not
            // crash today. But a guarantee held two frames up is not one a
            // reader can see, and not one AndroidX lint can see either -- this
            // was the project's single MissingPermission error. Catching it
            // here makes the contract local, and lets the outcome be named
            // rather than flattened into a null the retry loop then waits on.
            try {
                manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (cont.isActive) {
                                cont.resume(CameraOpen.Opened(camera))
                            } else {
                                camera.close()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (cont.isActive) cont.resume(CameraOpen.Busy)
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "camera open error $error")
                            camera.close()
                            if (cont.isActive) cont.resume(CameraOpen.Busy)
                        }
                    },
                    handler,
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "camera permission was revoked while opening", e)
                if (cont.isActive) cont.resume(CameraOpen.Denied)
            }
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
