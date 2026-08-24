package dev.multiframe.camera

import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.multiframe.camera.pipeline.BurstBuffer
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ImageSaver
import dev.multiframe.camera.pipeline.ManualSettings
import dev.multiframe.camera.pipeline.MemoryBudget
import dev.multiframe.camera.pipeline.Merger
import dev.multiframe.camera.pipeline.OrientationTracker
import dev.multiframe.camera.ui.AboutSheet
import dev.multiframe.camera.ui.ControlsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
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
    var burstFrames by remember { mutableIntStateOf(8) }
    var busy by remember { mutableStateOf(false) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var caps by remember { mutableStateOf<CameraCapabilities?>(null) }
    var settings by remember { mutableStateOf(ManualSettings()) }

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

    LaunchedEffect(lifecycleOwner) {
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
            val provider = ProcessCameraProvider.getInstance(context)
                .await(ContextCompat.getMainExecutor(context))

            // Not every Android device has a rear camera; some tablets and
            // desktop-class devices only expose a front one.
            val selector = listOf(
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.DEFAULT_FRONT_CAMERA,
            ).firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }

            if (selector == null) {
                status = "No camera available on this device"
                return@LaunchedEffect
            }

            provider.unbindAll()
            val bound = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                analysis,
            )
            camera = bound

            val capabilities = CameraCapabilities.from(bound.cameraInfo)
            caps = capabilities
            Log.i(TAG, "Capabilities: ${capabilities.summary()}")
            Log.i(
                TAG,
                "Heap max ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB, " +
                    "analysis ${analysisSize.width}x${analysisSize.height}",
            )

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

    // Push manual settings whenever they change. Camera2Interop.Extender only
    // applies at use-case build time, so live updates go through
    // Camera2CameraControl instead.
    LaunchedEffect(settings, camera, caps) {
        val cam = camera ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.fillMaxSize(),
            )
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
                        val steps = listOf(1, 2, 4, 8, 12).filter { it <= buffer.capacity }
                        val here = steps.indexOf(burstFrames).coerceAtLeast(0)
                        burstFrames = steps[(here + 1) % steps.size]
                    }
                }
                Chip("PRO", showControls) { showControls = !showControls }
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