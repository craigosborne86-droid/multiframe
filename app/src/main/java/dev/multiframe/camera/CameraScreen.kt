package dev.multiframe.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as Camera2Request
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import android.app.ActivityManager
import android.view.Surface
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceZOrder
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.multiframe.camera.pipeline.BurstBuffer
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.AppSettings.Companion.reconcile
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.CaptureReadout
import dev.multiframe.camera.pipeline.CaptureModes
import dev.multiframe.camera.pipeline.SceneAnalysis
import dev.multiframe.camera.pipeline.FocusPeaking
import dev.multiframe.camera.pipeline.Plane
import dev.multiframe.camera.pipeline.Lens
import dev.multiframe.camera.pipeline.LensCatalog
import dev.multiframe.camera.pipeline.MemoryPressure
import dev.multiframe.camera.pipeline.NativeMerge
import dev.multiframe.camera.pipeline.PressureResponse
import dev.multiframe.camera.pipeline.MosaicCapture
import dev.multiframe.camera.pipeline.MosaicPlanner
import dev.multiframe.camera.pipeline.MosaicProgress
import dev.multiframe.camera.pipeline.MosaicSession
import dev.multiframe.camera.pipeline.ImageSaver
import dev.multiframe.camera.pipeline.ManualSettings
import dev.multiframe.camera.pipeline.MemoryBudget
import dev.multiframe.camera.pipeline.RawBurstCapture
import dev.multiframe.camera.pipeline.RawCapture
import dev.multiframe.camera.pipeline.RawRingBudget
import dev.multiframe.camera.pipeline.RecentCapture
import dev.multiframe.camera.pipeline.RecentShot
import dev.multiframe.camera.pipeline.ZslCapture
import dev.multiframe.camera.pipeline.ZslDecision
import dev.multiframe.camera.pipeline.ZslPolicy
import dev.multiframe.camera.pipeline.ZslRawStream
import dev.multiframe.camera.pipeline.Merger
import dev.multiframe.camera.pipeline.OrientationTracker
import dev.multiframe.camera.pipeline.Attitude
import dev.multiframe.camera.pipeline.LevelSensor
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import dev.multiframe.camera.ui.Ink
import dev.multiframe.camera.ui.AboutSheet
import dev.multiframe.camera.ui.GuideMode
import dev.multiframe.camera.ui.Guides
import dev.multiframe.camera.ui.Histogram
import dev.multiframe.camera.ui.Peaking
import dev.multiframe.camera.ui.SweepMap
import dev.multiframe.camera.ui.ControlsPanel
import dev.multiframe.camera.ui.ActionStrip
import dev.multiframe.camera.ui.ControlBar
import dev.multiframe.camera.ui.ControlBarState
import dev.multiframe.camera.ui.ControlRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val TAG = "Multiframe"
/** Frames requested per shutter press; the ring shrinks this if the heap cannot hold it. */
private const val DESIRED_BURST = 12

/** Side of the focus reticle, in pixels. */
private const val RETICLE_PX = 180f

/**
 * Size of the luma view peaking runs on.
 *
 * Small on purpose: peaking a twelve-megapixel frame several times a second
 * would cost more than the rest of the viewfinder together, and edges survive
 * downscaling perfectly well.
 */
private const val PEAK_W = 320
private const val PEAK_H = 240

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Restored before any state that depends on it, so the first composition
    // already shows what the user left the app in rather than the defaults.
    val restored = remember { AppSettings.load(context) }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("") }
    var mergeEnabled by remember { mutableStateOf(restored.mergeEnabled) }
    var abMode by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var rawCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var characteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    // DngCreator needs the capture metadata that produced the frame, which
    // CameraX does not surface directly.
    val lastCaptureResult = remember { AtomicReference<TotalCaptureResult?>(null) }
    var burstFrames by remember { mutableIntStateOf(restored.burstFrames) }
    var busy by remember { mutableStateOf(false) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var caps by remember { mutableStateOf<CameraCapabilities?>(null) }

    var settings by remember { mutableStateOf(restored.manual) }

    // Zero-shutter-lag raw streaming. The decision is taken from what the
    // hardware reports, not from what this device happens to do, so a camera
    // that stalls on raw simply never offers the mode.
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraId by remember { mutableStateOf<String?>(null) }
    var zslDecision by remember { mutableStateOf<ZslDecision?>(null) }
    var zslWanted by remember { mutableStateOf(restored.zslEnabled) }
    var zslSurface by remember { mutableStateOf<Surface?>(null) }
    var zslStream by remember { mutableStateOf<ZslRawStream?>(null) }

    // Every physical lens the device has, not a zoom ratio on one of them. A
    // raw pipeline needs to know which sensor produced a frame, because black
    // level, colour filter arrangement and calibration are all per lens.
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    var lens by remember { mutableStateOf<Lens?>(null) }

    // Expose for the highlights and let the merge pay for the shadows. On by
    // default: it is the whole reason for capturing a burst, and it costs
    // nothing on a scene that does not need it.
    var highlightGuard by remember { mutableStateOf(restored.highlightGuard) }
    var guardPull by remember { mutableFloatStateOf(0f) }

    // What the user is photographing. The right settings for a night scene and
    // a moving subject are opposites, and only the user knows which it is.
    var captureMode by remember { mutableStateOf(restored.captureMode) }
    var scene by remember { mutableStateOf<SceneAnalysis?>(null) }

    // Self-timer, and the last shot taken.
    var timerSeconds by remember { mutableIntStateOf(restored.timerSeconds) }
    var countdown by remember { mutableIntStateOf(0) }
    // Held so the self-timer can be called off. Only the countdown is
    // cancellable: once frames are captured the work is a few long native
    // calls with nowhere to suspend, and a control that appears to do nothing
    // for several seconds is worse than not offering one.
    var captureJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastShot by remember { mutableStateOf<RecentShot?>(null) }
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Tap to focus and pinch to zoom. Table stakes for a camera: without them
    // the app cannot be pointed at a subject that is not in the middle.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusAtMillis by remember { mutableLongStateOf(0L) }
    var digitalZoom by remember { mutableFloatStateOf(1f) }

    // Telephoto mosaic: cover this framing with a longer lens's detail.
    var sweepRequested by remember { mutableStateOf(false) }
    var sweepProgress by remember { mutableStateOf<MosaicProgress?>(null) }
    // Read off the session rather than carried on MosaicProgress: an array
    // inside a data class compares by identity, which would quietly make every
    // progress update unequal to the last one for every other reader of it.
    var sweepGrid by remember { mutableStateOf<Array<BooleanArray>?>(null) }
    var sweepAspect by remember { mutableFloatStateOf(1f) }
    var sweepTargetLens by remember { mutableStateOf<Lens?>(null) }

    // Composition aids. One control cycles them rather than several toggles.
    var guides by remember {
        mutableStateOf(
            GuideMode.entries.firstOrNull { it.name == restored.guides } ?: GuideMode.OFF
        )
    }
    val level = remember { LevelSensor(context) }
    var attitude by remember { mutableStateOf<Attitude?>(null) }
    var histogram by remember { mutableStateOf<IntArray?>(null) }

    // Focus peaking, shown only while manual focus is engaged, since that is
    // the only time it answers a question the user is asking.
    var peakingMask by remember { mutableStateOf<ByteArray?>(null) }
    var focusScore by remember { mutableFloatStateOf(0f) }
    // The surface the running stream was built against. A SurfaceView is
    // recreated when its fixed size is applied, so "a surface exists" is not
    // the same question as "the session is targeting the live one".
    var zslOpenSurface by remember { mutableStateOf<Surface?>(null) }
    // Opening is slow and must not overlap itself: the effect relaunches when
    // the surface is replaced, and two concurrent opens would leave a second
    // camera session and a second pool behind the first.
    val zslLock = remember { Mutex() }

    val buffer = remember { BurstBuffer(DESIRED_BURST) }
    // Analysis resolution is chosen from the heap this process was actually
    // granted, not assumed from the development device.
    val analysisSize = remember {
        MemoryBudget.recommendedAnalysisSize(Runtime.getRuntime().maxMemory())
    }
    val orientation = remember { OrientationTracker(context) }

    DisposableEffect(Unit) {
        orientation.enable()
        onDispose { orientation.disable() }
    }

    // The last capture, refreshed whenever one completes.
    LaunchedEffect(busy) {
        if (busy) return@LaunchedEffect
        val shot = withContext(Dispatchers.IO) { RecentCapture.latest(context) }
        lastShot = shot
        thumbnail = if (shot == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    shot.uri, android.util.Size(160, 160), null,
                )
            }.onFailure { Log.w(TAG, "could not load the last shot", it) }.getOrNull()
        }
    }

    // What the chosen mode implies for this scene, recomputed as either moves.
    val capturePlan = remember(captureMode, scene, zslStream, burstFrames) {
        val stream = zslStream
        if (stream == null) null
        else CaptureModes.plan(
            mode = captureMode,
            analysis = scene ?: SceneAnalysis(0f, 0.5f, 0.1f, 3f),
            availableFrames = stream.maxBurst,
            requestedFrames = burstFrames,
        )
    }

    // The ring is the largest allocation in the app by a wide margin, and
    // Android does not warn twice about holding it: it kills the process. Giving
    // it back voluntarily degrades to sequential capture, which still takes
    // photographs, and is reversible the moment pressure lifts.
    DisposableEffect(context) {
        val callbacks = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when (MemoryPressure.responseTo(level)) {
                    PressureResponse.NONE -> Unit
                    PressureResponse.RELEASE_RING,
                    PressureResponse.RELEASE_ALL -> {
                        // The develop scratch is held between captures so its
                        // pages are faulted once rather than per shot. It is
                        // not worth holding while the system is struggling.
                        NativeMerge.releaseScratch()
                        if (zslWanted) {
                            Log.w(TAG, "memory pressure $level: releasing the raw ring")
                            zslWanted = false
                            status = "raw ring released: the system is short of memory"
                        }
                    }
                }
            }

            override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit

            @Deprecated("required by the interface")
            override fun onLowMemory() {
                onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    // The accelerometer only runs while the level is being shown; leaving it
    // registered would drain the battery for a display nobody asked for.
    DisposableEffect(guides) {
        if (guides.showsLevel) level.enable()
        onDispose { level.disable() }
    }

    LaunchedEffect(guides) {
        if (!guides.showsLevel) {
            attitude = null
            return@LaunchedEffect
        }
        while (true) {
            attitude = level.attitude
            kotlinx.coroutines.delay(60)
        }
    }

    // Focus peaking, from the sensor rather than the preview: the preview has
    // been through the ISP's sharpening and would show edges the optics never
    // produced.
    LaunchedEffect(zslStream, settings.manualFocus) {
        val stream = zslStream
        if (stream == null || !settings.manualFocus) {
            peakingMask = null
            focusScore = 0f
            return@LaunchedEffect
        }
        val luma = ByteArray(PEAK_W * PEAK_H)
        val mask = ByteArray(PEAK_W * PEAK_H)
        while (true) {
            val ready = withContext(Dispatchers.Default) {
                if (!stream.luma(luma, PEAK_W, PEAK_H)) return@withContext false
                val plane = Plane(PEAK_W, PEAK_H, luma)
                FocusPeaking.detect(plane, mask)
                focusScore = FocusPeaking.focusScore(plane)
                true
            }
            peakingMask = if (ready) mask.copyOf() else null
            kotlinx.coroutines.delay(120)
        }
    }

    // Live histogram, read in place from the ring so it costs no frames.
    LaunchedEffect(zslStream, guides) {
        val stream = zslStream
        if (stream == null || guides == GuideMode.OFF) {
            histogram = null
            return@LaunchedEffect
        }
        val bins = IntArray(48)
        while (true) {
            val ok = withContext(Dispatchers.Default) { stream.histogram(bins) }
            histogram = if (ok) bins.copyOf() else null
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(lifecycleOwner, zslWanted) {
        // The camera admits one client. Engaging the raw ring means handing
        // the device over to the Camera2 session, so CameraX is unbound first.
        if (zslWanted) {
            runCatching { provider?.unbindAll() }
            surfaceRequest = null
            camera = null
            return@LaunchedEffect
        }

        // Handing the camera back the other way. Both effects can close the
        // stream, but only this ordering is guaranteed: CameraX cannot open a
        // device the Camera2 session still holds, and effect launch order is
        // not a strong enough guarantee to rely on.
        zslStream?.close()
        zslStream = null

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(analysisSize.width, analysisSize.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                    try {
                        buffer.offer(image)
                    } finally {
                        image.close()
                    }
                }
            }

        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context)
                .await(ContextCompat.getMainExecutor(context))
            provider = cameraProvider

            // Not every Android device has a rear camera; some tablets and
            // desktop-class devices only expose a front one.
            val selector = listOf(
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.DEFAULT_FRONT_CAMERA,
            ).firstOrNull { runCatching { cameraProvider.hasCamera(it) }.getOrDefault(false) }

            if (selector == null) {
                status = "No camera available on this device"
                return@LaunchedEffect
            }

            cameraProvider.unbindAll()

            // RAW+JPEG adds a third stream, which not every camera can run
            // alongside preview and analysis. Try it, and fall back rather than
            // losing the camera entirely.
            val dngCapture = runCatching {
                val b = ImageCapture.Builder()
                    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                Camera2Interop.Extender(b).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: Camera2Request,
                            result: TotalCaptureResult,
                        ) {
                            lastCaptureResult.set(result)
                        }
                    }
                )
                b.build()
            }.getOrNull()

            val bound = runCatching {
                requireNotNull(dngCapture)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, analysis, dngCapture,
                ).also { rawCapture = dngCapture }
            }.getOrElse { e ->
                Log.w(TAG, "RAW+JPEG stream unavailable, continuing without it", e)
                rawCapture = null
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
            camera = bound

            val capabilities = CameraCapabilities.from(bound.cameraInfo)
            caps = capabilities
            Log.i(TAG, "Capabilities: ${capabilities.summary()}")
            Log.i(
                TAG,
                "Heap max ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB, " +
                    "analysis ${analysisSize.width}x${analysisSize.height}, " +
                    "outputFormats=${capabilities.supportedOutputFormats}, " +
                    "dng=${capabilities.supportsDng}, rawStream=${rawCapture != null}",
            )
            if (!capabilities.supportsDng) rawCapture = null

            // What raw actually costs to stream decides whether a continuous
            // zero-shutter-lag ring is feasible or a fantasy. A non-zero stall
            // duration means a raw frame blocks the other streams, so the ring
            // is never offered and the sequential path stays in charge.
            runCatching {
                val id = Camera2CameraInfo.from(bound.cameraInfo).cameraId
                val ch = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(id)
                cameraId = id
                characteristics = ch

                val catalog = LensCatalog.enumerate(
                    context.getSystemService(CameraManager::class.java)
                )
                lenses = catalog
                if (lens == null) {
                    // A stored lens can name hardware this device does not
                    // have, so it is checked against the catalog rather than
                    // trusted.
                    lens = restored.lensId?.let { id ->
                        catalog.firstOrNull { it.cameraId == id }
                    } ?: LensCatalog.default(catalog)
                }
                Log.i(TAG, "lenses: " + LensCatalog.rear(catalog).joinToString {
                    "${it.label}/${it.zoomLabel}"
                })

                val configs = ZslRawStream.rawConfigs(ch)
                configs.forEach { Log.i(TAG, "RAW stream $it") }
                val decision = ZslPolicy.evaluate(capabilities.supportsRaw, configs)
                zslDecision = decision
                Log.i(TAG, "ZSL decision: ${decision.describe}")
            }.onFailure {
                Log.w(TAG, "raw stream probe failed", it)
                zslDecision = ZslDecision.Fallback("raw capabilities unreadable")
            }

            // Anything restored has to be checked against this camera: a
            // setting the hardware cannot honour has the whole capture request
            // rejected, taking every other setting with it.
            settings = restored.copy(manual = settings)
                .reconcile(capabilities, lenses)
                .manual

            // Start about a stop under, so highlights stay off the clip point.
            if (restored.manual == ManualSettings() && capabilities.supportsExposureCompensation) {
                val index = (-1.0f / capabilities.evStep).roundToInt()
                    .coerceIn(capabilities.evMin, capabilities.evMax)
                settings = settings.copy(evIndex = index)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            status = "Camera unavailable: ${e.message}"
        }
    }

    // One effect owns the stream's whole life. Opening needs three things to
    // line up -- the user asking, the hardware allowing, and a preview surface
    // to render into -- and the surface arrives asynchronously, so anything
    // going away has to tear the stream down again through the same path.
    LaunchedEffect(zslWanted, zslSurface, cameraId, caps, lens) {
        val surface = zslSurface
        val chosen = lens
        val c = caps

        // Stream configuration and sensor profile both have to come from the
        // lens actually being used, not from whichever camera happened to be
        // probed at startup.
        val ch = chosen?.let {
            runCatching {
                context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(it.cameraId)
            }.getOrNull()
        }
        val decision = ch?.let {
            ZslPolicy.evaluate(chosen.supportsRaw, ZslRawStream.rawConfigs(it))
        }

        val canStream = zslWanted && decision is ZslDecision.Stream &&
            surface != null && chosen != null && ch != null && c != null

        // Neither opening nor closing may be abandoned half way: a cancellation
        // between the camera opening and the handle being stored would leave
        // the device held by nothing that can close it.
        withContext(NonCancellable) {
            zslLock.withLock {
                val existing = zslStream

                if (!canStream) {
                    // Covers the user switching it off and the surface being
                    // destroyed underneath a running stream, which must not
                    // keep a dead Surface as a capture target.
                    existing?.close()
                    zslStream = null
                    zslOpenSurface = null
                    return@withLock
                }
                if (existing != null && zslOpenSurface === surface &&
                    existing.lensId == chosen!!.cameraId
                ) {
                    return@withLock
                }

                // Reached with a live stream only when the surface was replaced.
                // The old session is still targeting the dead one, so it goes
                // before the new session is built.
                existing?.close()
                zslStream = null
                zslOpenSurface = null

                status = "starting raw stream…"
                // The pool is native, so the Dalvik cap does not bound it --
                // physical memory does. Depth comes from what is actually free.
                val memory = ActivityManager.MemoryInfo().also {
                    context.getSystemService(ActivityManager::class.java)
                        .getMemoryInfo(it)
                }
                val config = (decision as ZslDecision.Stream).config
                // Sized for the deepest ring the device can afford rather than
                // for the burst currently selected, so changing the frame count
                // is a UI choice rather than a camera session rebuild.
                val depth = RawRingBudget.capacityFor(
                    config.width, config.height,
                    memory.availMem, memory.totalMem,
                    RawRingBudget.MAX_CAPACITY,
                )
                // Checked before allocating rather than only after failing: an
        // allocation that succeeds and then gets the process killed is worse
        // than one that was never attempted.
        val required = RawRingBudget.frameBytes(config.width, config.height) * depth
        if (depth >= RawRingBudget.MIN_CAPACITY &&
            !MemoryPressure.canAffordRing(memory.availMem, required)
        ) {
            zslWanted = false
            status = "not enough free memory for a %.0f MB ring".format(
                required / (1024.0 * 1024.0),
            )
            Log.w(
                TAG,
                "ZSL declined: ${memory.availMem / (1024 * 1024)}MB free, " +
                    "ring needs ${required / (1024 * 1024)}MB",
            )
            return@withLock
        }
        if (depth < RawRingBudget.MIN_CAPACITY) {
                    zslWanted = false
                    status = "not enough free memory for a raw ring"
                    Log.w(TAG, "ZSL declined: ${memory.availMem / (1024 * 1024)}MB free")
                    return@withLock
                }

                val opened = ZslRawStream.open(
                    manager = context.getSystemService(CameraManager::class.java),
                    lens = chosen!!,
                    characteristics = ch!!,
                    previewSurface = surface!!,
                    config = config,
                    ringDepth = depth,
                    settings = settings,
                    caps = c!!,
                )

                if (opened == null) {
                    // Falling back is a decision, not a crash: the sequential
                    // path is still a working camera.
                    zslWanted = false
                    status = "raw stream unavailable, back to sequential capture"
                } else {
                    zslStream = opened
                    zslOpenSurface = surface
                    status = "%s ZSL %s, ring %d (%.0f MB), burst %d".format(
                        chosen.label, config, opened.ringCapacity,
                        opened.ringReservedBytes / (1024.0 * 1024.0), opened.maxBurst,
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            zslStream?.close()
            zslStream = null
        }
    }

    // Push manual settings whenever they change. Camera2Interop.Extender only
    // applies at use-case build time, so live updates go through
    // Camera2CameraControl instead.
    LaunchedEffect(settings, camera, caps, zslStream) {
        val c = caps ?: return@LaunchedEffect
        zslStream?.applySettings(settings, c)
        val cam = camera ?: return@LaunchedEffect
        try {
            Camera2CameraControl.from(cam.cameraControl)
                .setCaptureRequestOptions(settings.toCaptureRequestOptions(c))
            if (settings.evActive && c.supportsExposureCompensation) {
                cam.cameraControl.setExposureCompensationIndex(settings.effectiveEvIndex(c))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Applying manual settings failed", e)
        }
    }

    // Outside the ZSL path there is no physical-lens selection to be had:
    // CameraX binds a logical camera and offers no way to choose which sensor
    // behind it serves a stream. Zoom ratio is the closest equivalent, and on a
    // multi-camera phone it is what makes the device switch lenses. The raw
    // path does it properly.
    LaunchedEffect(lens, camera, zslStream) {
        val cam = camera ?: return@LaunchedEffect
        if (zslStream != null) return@LaunchedEffect
        val target = lens?.zoomFactor ?: return@LaunchedEffect
        runCatching {
            val range = cam.cameraInfo.zoomState.value
            val clamped = target.coerceIn(
                range?.minZoomRatio ?: target,
                range?.maxZoomRatio ?: target,
            )
            cam.cameraControl.setZoomRatio(clamped)
            Log.i(TAG, "CameraX zoom set to %.2fx for ${lens?.label}".format(clamped))
        }.onFailure { Log.w(TAG, "zoom for lens selection failed", it) }
    }

    // The guard has to run continuously. In a zero-shutter-lag camera the
    // frames already exist when the shutter is pressed, so an exposure decision
    // taken then would apply to the next photograph rather than this one.
    LaunchedEffect(zslStream, highlightGuard, burstFrames, caps, captureMode) {
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        // Night mode deliberately does not protect highlights: a dark scene has
        // none worth protecting, and pulling exposure would spend the shadow
        // detail the mode exists to gather.
        val modeAllows = capturePlan?.protectHighlights ?: true
        if (!highlightGuard || !modeAllows) {
            stream.clearHighlightProtection(c, settings)
            guardPull = 0f
            return@LaunchedEffect
        }
        while (true) {
            withContext(Dispatchers.Default) {
                stream.protectHighlights(burstFrames, c, settings)
            }
            guardPull = stream.pullStops
            // Slower than the frame rate on purpose: auto-exposure needs time
            // to settle after a change, and chasing it faster oscillates.
            kotlinx.coroutines.delay(700)
        }
    }

    // The sweep. Waits for the stream to actually be running on the capture
    // lens before starting, because switching lens tears the session down and
    // builds a new one, and a mosaic assembled across that boundary would mix
    // two focal lengths.
    LaunchedEffect(sweepRequested, zslStream, lens) {
        if (!sweepRequested) return@LaunchedEffect
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        val target = sweepTargetLens ?: return@LaunchedEffect
        if (stream.lensId != lens?.cameraId) return@LaunchedEffect

        val plan = MosaicPlanner.plan(
            target = target,
            capture = stream.lens,
            tileWidth = stream.config.width,
            tileHeight = stream.config.height,
        )
        if (plan == null) {
            status = "no useful mosaic for this pair of lenses"
            sweepRequested = false
            return@LaunchedEffect
        }

        val session = MosaicSession.start(plan, stream.config.width, stream.config.height)
        if (session == null) {
            status = "not enough memory for a %.0f MP canvas".format(plan.megapixels)
            sweepRequested = false
            return@LaunchedEffect
        }

        // The map is drawn at the shape of the canvas being built, not the
        // shape of the viewfinder, which during a sweep is the telephoto's.
        sweepAspect = plan.canvasWidth.toFloat() / plan.canvasHeight

        busy = true
        status = "sweep: pan slowly across the scene"
        val result = withContext(Dispatchers.Default) {
            session.use {
                MosaicCapture.run(
                    context = context,
                    stream = stream,
                    session = it,
                    settings = settings,
                    caps = c,
                    onProgress = { p ->
                        sweepProgress = p
                        sweepGrid = it.coverageGrid()
                    },
                    shouldContinue = { sweepRequested },
                )
            }
        }
        Log.i(TAG, "mosaic result: $result")
        status = "%s  %.0f MP  %.1fs".format(
            result.message, result.megapixels, result.elapsedMillis / 1000.0,
        )
        sweepProgress = null
        sweepGrid = null
        sweepRequested = false
        // Back to the framing the user was composing with.
        lens = target
        sweepTargetLens = null
        busy = false
    }

    // Written whenever anything worth remembering changes. Cheap: a dozen
    // short strings, and only on an actual change rather than per frame.
    LaunchedEffect(
        settings, burstFrames, lens, mergeEnabled, highlightGuard, zslWanted, captureMode,
        timerSeconds, guides,
    ) {
        AppSettings.save(
            context,
            AppSettings(
                manual = settings,
                burstFrames = burstFrames,
                lensId = lens?.cameraId,
                mergeEnabled = mergeEnabled,
                highlightGuard = highlightGuard,
                zslEnabled = zslWanted,
                captureMode = captureMode,
                timerSeconds = timerSeconds,
                guides = guides.name,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .onSizeChanged { viewSize = it }
            .pointerInput(zslStream, camera, caps) {
                detectTapGestures { offset ->
                    val c = caps ?: return@detectTapGestures
                    if (viewSize.width == 0 || viewSize.height == 0) return@detectTapGestures
                    val nx = offset.x / viewSize.width
                    val ny = offset.y / viewSize.height
                    focusPoint = offset
                    focusAtMillis = System.currentTimeMillis()

                    val stream = zslStream
                    if (stream != null) {
                        stream.focusAt(nx, ny, settings, c)
                    } else {
                        // CameraX exposes metering through its own point
                        // factory, which already knows the preview geometry.
                        camera?.let { cam ->
                            runCatching {
                                val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                                    viewSize.width.toFloat(), viewSize.height.toFloat(),
                                )
                                cam.cameraControl.startFocusAndMetering(
                                    androidx.camera.core.FocusMeteringAction.Builder(
                                        factory.createPoint(offset.x, offset.y)
                                    ).build()
                                )
                            }.onFailure { Log.w(TAG, "focus tap failed", it) }
                        }
                    }
                }
            }
            .pointerInput(zslStream, camera, caps) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    if (gestureZoom == 1f) return@detectTransformGestures
                    val c = caps ?: return@detectTransformGestures
                    val stream = zslStream
                    if (stream != null) {
                        digitalZoom = (digitalZoom * gestureZoom)
                            .coerceIn(1f, stream.maxZoom)
                        stream.setZoom(digitalZoom, settings, c)
                    } else {
                        camera?.let { cam ->
                            val state = cam.cameraInfo.zoomState.value ?: return@let
                            digitalZoom = (digitalZoom * gestureZoom)
                                .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            runCatching { cam.cameraControl.setZoomRatio(digitalZoom) }
                        }
                    }
                }
            }
    ) {
        if (zslWanted) {
            // SurfaceView, not TextureView. Camera2 tags preview buffers with
            // the sensor-to-display rotation and SurfaceFlinger honours that
            // hint for a SurfaceView; a TextureView ignores it and would show
            // the viewfinder on its side until given an explicit matrix.
            val previewSize = remember(zslDecision, characteristics) {
                val d = zslDecision
                val ch = characteristics
                if (d is ZslDecision.Stream && ch != null) {
                    ZslRawStream.previewSizeFor(ch, d.config)
                } else null
            }
            AndroidExternalSurface(
                modifier = Modifier.fillMaxSize(),
                surfaceSize = previewSize
                    ?.let { IntSize(it.width, it.height) } ?: IntSize.Zero,
                zOrder = AndroidExternalSurfaceZOrder.Behind,
            ) {
                onSurface { surface, _, _ ->
                    zslSurface = surface
                    surface.onDestroyed { zslSurface = null }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
            surfaceRequest?.let { request ->
                CameraXViewfinder(
                    surfaceRequest = request,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Guides(mode = guides, attitude = attitude)

        Peaking(mask = peakingMask, maskWidth = PEAK_W, maskHeight = PEAK_H)

        if (settings.manualFocus && focusScore > 0f) {
            Text(
                text = "FOCUS %.0f".format(focusScore * 1000),
                color = Ink.Amber,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .background(Ink.Readout, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }

        histogram?.let { bins ->
            Histogram(
                bins = bins,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 130.dp)
                    .size(width = 132.dp, height = 44.dp)
                    .background(Ink.Faint, RoundedCornerShape(4.dp))
                    .padding(3.dp),
            )
        }

        // Ground for the controls, and only for the controls. Chips floating
        // directly on the photograph are unreadable over a bright sky and read
        // as a debug overlay over anything else; a gradient gives the row
        // somewhere to sit without putting a bar across the frame. It also
        // makes the chip that runs off the edge look deliberate, which it is --
        // the row scrolls.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(156.dp)
                .background(
                    Brush.verticalGradient(listOf(Ink.ScrimTop, Color.Transparent)),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(232.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Ink.ScrimBottom)),
                ),
        )

        // Only where a longer lens exists to sweep with.
        val sweepPlan = remember(lenses, lens, zslStream) {
            val here = lens
            val stream = zslStream
            if (here == null || stream == null) null
            else MosaicPlanner.bestPairing(
                lenses, here, stream.config.width, stream.config.height,
            )
        }

        // What the controls are is decided in one place and what they do in
        // another, joined by an id. That is what lets `ControlBar` be plain
        // data -- and so lets a phone without raw support, which this one is
        // not, be tested on the JVM instead of held in the hand.
        val barState = ControlBarState(
            busy = busy,
            mergeEnabled = mergeEnabled,
            abMode = abMode,
            burstFrames = burstFrames,
            timerSeconds = timerSeconds,
            guidesLabel = guides.valueLabel,
            guidesOn = guides != GuideMode.OFF,
            proOpen = showControls,
            aboutOpen = showAbout,
            captureModeLabel = if (zslStream == null) null else {
                val plan = capturePlan
                if (captureMode == CaptureMode.AUTO &&
                    plan != null && plan.resolved != CaptureMode.AUTO
                ) {
                    // Say what AUTO settled on, not just that it is AUTO.
                    "AUTO/${plan.resolved.label}"
                } else {
                    captureMode.label
                }
            },
            captureModeActive = captureMode != CaptureMode.AUTO,
            highlightGuardOffered = zslStream != null,
            highlightGuardOn = highlightGuard,
            guardPull = guardPull,
            // Only offered where the hardware said yes. On a camera that stalls
            // on raw the control never appears and nothing changes.
            zslOffered = zslDecision is ZslDecision.Stream,
            zslOn = zslWanted,
            sweepOffered = sweepPlan != null,
            sweepRunning = sweepRequested,
            rawBurstOffered = rawCapture != null && characteristics != null,
            dngOffered = rawCapture != null,
        )

        val handleControl: (String) -> Unit = { id ->
            when (id) {
                ControlBar.MERGE -> mergeEnabled = !mergeEnabled
                ControlBar.AB -> abMode = !abMode
                ControlBar.FRAMES -> {
                    // Only counts the heap-sized ring can actually hold.
                    val ceiling = zslStream?.maxBurst ?: buffer.capacity
                    burstFrames = ControlBar.nextBurst(burstFrames, ceiling)
                }
                ControlBar.CAPTURE_MODE -> captureMode = captureMode.next()
                ControlBar.ZSL -> {
                    zslWanted = !zslWanted
                    status = if (zslWanted) "engaging raw ring…"
                    else "sequential capture"
                }
                ControlBar.GUARD -> highlightGuard = !highlightGuard
                ControlBar.TIMER -> timerSeconds = ControlBar.nextTimer(timerSeconds)
                ControlBar.GUIDES -> guides = guides.next()
                ControlBar.PRO -> showControls = !showControls
                ControlBar.ABOUT -> showAbout = true

                ControlBar.SWEEP -> {
                    if (sweepRequested) {
                        sweepRequested = false
                    } else if (sweepPlan != null) {
                        sweepTargetLens = lens
                        lens = sweepPlan.captureLens
                        sweepRequested = true
                        status = "switching to ${sweepPlan.captureLens.label}…"
                    }
                }

                ControlBar.RAW_BURST -> {
                    busy = true
                    status = "raw burst…"
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            RawBurstCapture.captureAndMerge(
                                context = context,
                                imageCapture = rawCapture!!,
                                characteristics = characteristics!!,
                                captureResult = lastCaptureResult.get(),
                                frameCount = burstFrames,
                                // The equivalent focal length is the
                                // catalogue's to know, not the capture
                                // result's.
                                lens = lens,
                                rotationDegrees = caps?.let {
                                    orientation.captureRotation(it.sensorOrientation)
                                } ?: 0,
                                onProgress = { },
                            )
                        }
                        Log.i(TAG, "raw burst result: $r")
                        Log.i(
                            TAG,
                            ("capture %dms  merge %dms  develop %dms  " +
                                "write %dms").format(
                                r.captureMillis, r.mergeMillis,
                                r.developMillis, r.writeMillis,
                            ),
                        )
                        status = r.stats?.let {
                            CaptureReadout.of(it.framesMerged, it.meanContribution)
                        } ?: r.message
                        busy = false
                    }
                }

                ControlBar.DNG -> {
                    busy = true
                    status = "DNG + JPEG…"
                    RawCapture.capture(
                        imageCapture = rawCapture!!,
                        context = context,
                    ) { message ->
                        status = message
                        busy = false
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ControlRow(ControlBar.modes(barState), onControl = handleControl)

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(Ink.Readout, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Lens strip. Labelled in millimetres, because that is what tells a
            // photographer what the frame will look like; the zoom factor is
            // there for anyone who thinks in phone terms.
            val rearLenses = remember(lenses) { LensCatalog.rear(lenses) }
            if (rearLenses.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    rearLenses.forEach { option ->
                        LensChip(
                            lens = option,
                            selected = option.cameraId == lens?.cameraId,
                            enabled = !busy,
                        ) { lens = option }
                    }
                }
            }

            if (showControls) {
                ControlsPanel(
                    settings = settings,
                    caps = caps,
                    onChange = { settings = it },
                    abMode = abMode,
                    onAbMode = { if (!busy) abMode = it },
                    highlightGuard = if (zslStream != null) highlightGuard else null,
                    onHighlightGuard = { if (!busy) highlightGuard = it },
                    onReset = {
                        // Back to what the app ships with. The save effect above
                        // is watching every one of these, so persisting it needs
                        // no separate step.
                        val fresh = AppSettings()
                        settings = fresh.manual
                        burstFrames = fresh.burstFrames
                        mergeEnabled = fresh.mergeEnabled
                        highlightGuard = fresh.highlightGuard
                        zslWanted = fresh.zslEnabled
                        captureMode = fresh.captureMode
                        timerSeconds = fresh.timerSeconds
                        guides = GuideMode.OFF
                        abMode = false
                        status = "settings reset"
                    },
                )
            }

            // The captures that are not the shutter. Squared off rather than
            // pilled, and here rather than in the row at the top, because every
            // one of them writes a photograph the moment it is touched and the
            // things up there do not.
            ActionStrip(
                specs = ControlBar.actions(barState),
                onControl = handleControl,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            ShutterButton(
                busy = busy,
                counting = countdown > 0,
                modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
                onClick = {
                    if (countdown > 0) {
                        // Called off before anything was locked.
                        captureJob?.cancel()
                        captureJob = null
                        countdown = 0
                        busy = false
                        status = "timer cancelled"
                        return@ShutterButton
                    }
                    if (busy) return@ShutterButton

                    // Zero shutter lag: the frames already exist, so this press
                    // locks them rather than starting a capture.
                    val stream = zslStream
                    if (stream != null) {
                        val plan = capturePlan
                        val frames = plan?.frames ?: burstFrames
                        busy = true
                        status = "%s: %d frames…".format(
                            plan?.resolved?.label ?: "ZSL", frames,
                        )
                        val rot = caps?.let {
                            orientation.captureRotation(it.sensorOrientation)
                        } ?: 0
                        captureJob = scope.launch {
                            // The timer runs before anything is locked, so the
                            // frames captured are the ones from the moment the
                            // countdown ends rather than when it began.
                            for (remaining in timerSeconds downTo 1) {
                                countdown = remaining
                                status = "$remaining… tap the shutter to cancel"
                                kotlinx.coroutines.delay(1000)
                            }
                            countdown = 0

                            val r = withContext(Dispatchers.Default) {
                                ZslCapture.captureAndMerge(
                                    context, stream, frames, rot,
                                )
                            }
                            Log.i(TAG, "ZSL result: $r")
                            r.streamStats?.let { Log.i(TAG, "stream health: $it") }
                            Log.i(
                                TAG,
                                ("handover %.2fms  span %dms  merge %dms  " +
                                    "develop %dms  write %dms").format(
                                    r.handoverMicros / 1000.0, r.burstSpanMillis,
                                    r.mergeMillis, r.developMillis, r.writeMillis,
                                ),
                            )
                            status = r.stats?.let {
                                CaptureReadout.of(it.framesMerged, it.meanContribution)
                            } ?: r.message
                            busy = false
                        }
                        return@ShutterButton
                    }

                    busy = true
                    status = if (abMode) "A/B capture, $burstFrames frames…"
                    else "Capturing $burstFrames frames…"

                    val rotation = caps?.let { orientation.captureRotation(it.sensorOrientation) } ?: 0

                    scope.launch {
                        val label = if (abMode) "ab" else if (mergeEnabled) "merged" else "single"
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(System.currentTimeMillis())

                        val result = withContext(Dispatchers.Default) {
                            // One snapshot drives every output, so an A/B pair is
                            // guaranteed to be the same scene at the same instant.
                            val frames = buffer.snapshot(
                                if (mergeEnabled || abMode) {
                                    burstFrames.coerceAtMost(buffer.capacity)
                                } else 1
                            )
                            if (frames.isEmpty()) return@withContext null

                            fun save(mergeIt: Boolean, suffix: String): Pair<Any?, Any?> {
                                val out = Merger.process(frames, mergeIt)
                                val bmp = OrientationTracker.rotate(out.bitmap, rotation)
                                val uri = ImageSaver.saveJpeg(
                                    context, bmp, "MF_${stamp}_$suffix.jpg",
                                )
                                bmp.recycle()
                                return out.stats to uri
                            }

                            if (abMode) {
                                save(false, "ab_single")
                                save(true, "ab_merged")
                            } else {
                                save(mergeEnabled, label)
                            }
                        }

                        status = if (result == null) {
                            "No frames buffered yet"
                        } else {
                            val stats = result.first as dev.multiframe.camera.pipeline.MergeStats
                            // The engineering detail stays in the log, where it
                            // has always been read from. What reaches the screen
                            // is what the merge bought, which is the one thing
                            // this camera does that the phone's own does not --
                            // and which until now only logcat ever saw.
                            Log.i(
                                TAG,
                                "Saved ${result.second}  $stats  rot=$rotation  " +
                                    "align ${stats.alignMillis}ms merge ${stats.mergeMillis}ms",
                            )
                            CaptureReadout.of(stats.framesUsed, stats.meanContribution)
                        }
                        busy = false
                    }
                },
            )
        }

        focusPoint?.let { point ->
            var visible by remember(focusAtMillis) { mutableStateOf(true) }
            LaunchedEffect(focusAtMillis) {
                kotlinx.coroutines.delay(1400)
                visible = false
            }
            if (visible) {
                with(androidx.compose.ui.platform.LocalDensity.current) {
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (point.x - RETICLE_PX / 2).toDp(),
                                y = (point.y - RETICLE_PX / 2).toDp(),
                            )
                            .size(RETICLE_PX.toDp())
                            .border(1.5.dp, Ink.Amber, RoundedCornerShape(4.dp))
                            .semantics { contentDescription = "Focus point" },
                    )
                }
            }
        }

        if (digitalZoom > 1.02f) {
            Text(
                text = "%.1fx".format(digitalZoom),
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .background(Ink.Readout, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        sweepProgress?.let { progress ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Ink.Dialogue, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                sweepGrid?.let { grid ->
                    SweepMap(
                        grid = grid,
                        aspect = sweepAspect,
                        modifier = Modifier
                            .width(132.dp)
                            .padding(bottom = 12.dp),
                    )
                }
                Text(
                    text = "${progress.placed} tiles",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "%.0f%% covered".format(progress.coverage * 100),
                    color = Ink.Amber,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "pan slowly",
                    color = Ink.Subtle,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (countdown > 0) {
            Text(
                text = countdown.toString(),
                color = Color.White,
                fontSize = 84.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = "Timer $countdown" },
            )
        }

        // The last shot, which is also the way into the gallery.
        thumbnail?.let { image ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 44.dp)
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.5.dp, Ink.Edge, RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) {
                        lastShot?.let { shot ->
                            // Handed to whatever the user views photographs
                            // with, rather than this app growing a gallery.
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW, shot.uri,
                                    ).addFlags(
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                )
                            }.onFailure { Log.w(TAG, "nothing can view that image", it) }
                        }
                    }
                    .semantics { contentDescription = "Last shot" },
            ) {
                androidx.compose.foundation.Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (showAbout) {
            AboutSheet(onDismiss = { showAbout = false })
        }
    }
}

/**
 * One lens in the strip.
 *
 * Shows the equivalent focal length above the zoom factor: the first is what a
 * photographer reasons about, the second is what phone cameras have trained
 * everyone to look for.
 */
@Composable
private fun LensChip(
    lens: Lens,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(if (selected) Ink.Bone else Ink.Pane, RoundedCornerShape(18.dp))
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, Ink.Hairline, RoundedCornerShape(18.dp))
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "${lens.label} lens" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = lens.label,
            color = if (selected) Ink.OnBone else Ink.Bone,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = lens.zoomLabel,
            color = if (selected) Ink.OnBoneMuted else Ink.Subtle,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ShutterButton(
    busy: Boolean,
    modifier: Modifier = Modifier,
    counting: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(
                width = 3.dp,
                color = if (busy) Ink.Amber else Ink.Bone,
                shape = CircleShape,
            )
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (busy) Ink.Amber else Ink.Bone)
            // Live during a countdown, which is the one moment a press means
            // stop rather than go.
            .clickable(enabled = !busy || counting, onClick = onClick)
            .semantics { contentDescription = if (counting) "Cancel timer" else "Shutter" },
    )
}