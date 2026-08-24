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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.multiframe.camera.pipeline.BurstBuffer
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.Lens
import dev.multiframe.camera.pipeline.LensCatalog
import dev.multiframe.camera.pipeline.ImageSaver
import dev.multiframe.camera.pipeline.ManualSettings
import dev.multiframe.camera.pipeline.MemoryBudget
import dev.multiframe.camera.pipeline.RawBurstCapture
import dev.multiframe.camera.pipeline.RawCapture
import dev.multiframe.camera.pipeline.RawRingBudget
import dev.multiframe.camera.pipeline.ZslCapture
import dev.multiframe.camera.pipeline.ZslDecision
import dev.multiframe.camera.pipeline.ZslPolicy
import dev.multiframe.camera.pipeline.ZslRawStream
import dev.multiframe.camera.pipeline.Merger
import dev.multiframe.camera.pipeline.OrientationTracker
import dev.multiframe.camera.ui.AboutSheet
import dev.multiframe.camera.ui.ControlsPanel
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

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("") }
    var mergeEnabled by remember { mutableStateOf(true) }
    var abMode by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var rawCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var characteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    // DngCreator needs the capture metadata that produced the frame, which
    // CameraX does not surface directly.
    val lastCaptureResult = remember { AtomicReference<TotalCaptureResult?>(null) }
    var burstFrames by remember { mutableIntStateOf(8) }
    var busy by remember { mutableStateOf(false) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var caps by remember { mutableStateOf<CameraCapabilities?>(null) }
    var settings by remember { mutableStateOf(ManualSettings()) }

    // Zero-shutter-lag raw streaming. The decision is taken from what the
    // hardware reports, not from what this device happens to do, so a camera
    // that stalls on raw simply never offers the mode.
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraId by remember { mutableStateOf<String?>(null) }
    var zslDecision by remember { mutableStateOf<ZslDecision?>(null) }
    var zslWanted by remember { mutableStateOf(false) }
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
    var highlightGuard by remember { mutableStateOf(true) }
    var guardPull by remember { mutableFloatStateOf(0f) }
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
                if (lens == null) lens = LensCatalog.default(catalog)
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

            // Start about a stop under, so highlights stay off the clip point.
            if (capabilities.supportsExposureCompensation) {
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
    LaunchedEffect(zslStream, highlightGuard, burstFrames, caps) {
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        if (!highlightGuard) {
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

    Box(modifier = modifier.fillMaxSize()) {
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

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                Chip(if (mergeEnabled) "MERGE ON" else "MERGE OFF", mergeEnabled) {
                    if (!busy) mergeEnabled = !mergeEnabled
                }
                Chip("A/B", abMode) { if (!busy) abMode = !abMode }
                Chip("$burstFrames FRAMES", false) {
                    if (!busy) {
                        // Only offer counts the heap-sized ring can actually hold.
                        val ceiling = zslStream?.maxBurst ?: buffer.capacity
                        val steps = listOf(1, 2, 4, 8, 12, 16, 24, 28, 32)
                            .filter { it <= ceiling }
                        val here = steps.indexOf(burstFrames).coerceAtLeast(0)
                        burstFrames = steps[(here + 1) % steps.size]
                    }
                }
                Chip("PRO", showControls) { showControls = !showControls }
                if (zslStream != null) {
                    Chip(
                        if (guardPull < -0.05f) "GUARD %.1f".format(guardPull) else "GUARD",
                        highlightGuard,
                    ) {
                        if (!busy) highlightGuard = !highlightGuard
                    }
                }
                // Only offered where the hardware said yes. On a camera that
                // stalls on raw the chip never appears and nothing changes.
                if (zslDecision is ZslDecision.Stream) {
                    Chip(if (zslWanted) "ZSL ON" else "ZSL", zslWanted) {
                        if (!busy) {
                            zslWanted = !zslWanted
                            status = if (zslWanted) "engaging raw ring…"
                            else "sequential capture"
                        }
                    }
                }
                if (rawCapture != null && characteristics != null) {
                    Chip("RAW x$burstFrames", false) {
                        if (!busy) {
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
                                        rotationDegrees = caps?.let {
                                            orientation.captureRotation(it.sensorOrientation)
                                        } ?: 0,
                                        onProgress = { },
                                    )
                                }
                                Log.i(TAG, "raw burst result: $r")
                                status = ("%s  capture %dms  merge %dms  " +
                                    "develop %dms  write %dms").format(
                                    r.message, r.captureMillis, r.mergeMillis,
                                    r.developMillis, r.writeMillis,
                                )
                                busy = false
                            }
                        }
                    }
                }
                if (rawCapture != null) {
                    Chip("DNG", false) {
                        if (!busy) {
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
                Chip("i", showAbout) { showAbout = true }
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
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
                )
            }

            ShutterButton(
                busy = busy,
                modifier = Modifier.padding(vertical = 28.dp),
                onClick = {
                    if (busy) return@ShutterButton

                    // Zero shutter lag: the frames already exist, so this press
                    // locks them rather than starting a capture.
                    val stream = zslStream
                    if (stream != null) {
                        busy = true
                        status = "ZSL $burstFrames frames…"
                        val rot = caps?.let {
                            orientation.captureRotation(it.sensorOrientation)
                        } ?: 0
                        scope.launch {
                            val r = withContext(Dispatchers.Default) {
                                ZslCapture.captureAndMerge(
                                    context, stream, burstFrames, rot,
                                )
                            }
                            Log.i(TAG, "ZSL result: $r")
                            r.streamStats?.let { Log.i(TAG, "stream health: $it") }
                            status = ("%s  handover %.2fms  span %dms  " +
                                "merge %dms  develop %dms  write %dms").format(
                                r.message, r.handoverMicros / 1000.0,
                                r.burstSpanMillis, r.mergeMillis,
                                r.developMillis, r.writeMillis,
                            )
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
                            Log.i(TAG, "Saved ${result.second}  $stats  rot=$rotation")
                            "%s  %d frames  align %dms  merge %dms  rot %d".format(
                                label, stats.framesUsed, stats.alignMillis,
                                stats.mergeMillis, rotation,
                            )
                        }
                        busy = false
                    }
                },
            )
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
            .background(
                if (selected) Color(0xFF4A9EFF) else Color(0xCC000000),
                RoundedCornerShape(18.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "${lens.label} lens" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = lens.label,
            color = if (selected) Color(0xFF06121F) else Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = lens.zoomLabel,
            color = if (selected) Color(0xCC06121F) else Color(0x99FFFFFF),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Color(0xFF06121F) else Color.White,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(
                if (active) Color(0xFF4A9EFF) else Color(0xCC000000),
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun ShutterButton(busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(width = 3.dp, color = Color.White, shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (busy) Color(0xFF4A9EFF) else Color.White)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = "Shutter" },
    )
}