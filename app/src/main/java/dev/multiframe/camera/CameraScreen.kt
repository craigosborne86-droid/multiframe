package dev.multiframe.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.multiframe.camera.pipeline.ImageSaver
import dev.multiframe.camera.pipeline.Merger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "Multiframe"

/** Upper bound on the ring buffer; the burst control selects how many are used. */
private const val RING_CAPACITY = 12

/** Analysis resolution. Deliberately below sensor resolution for the prototype. */
private val ANALYSIS_TARGET = Size(2048, 1536)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("") }
    var mergeEnabled by remember { mutableStateOf(true) }
    var burstFrames by remember { mutableIntStateOf(8) }
    var busy by remember { mutableStateOf(false) }
    var abMode by remember { mutableStateOf(false) }
    var exposureNote by remember { mutableStateOf("") }

    val buffer = remember { BurstBuffer(RING_CAPACITY) }
    val analysisExecutor = remember { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(lifecycleOwner) {
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    ANALYSIS_TARGET,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        // Suppress the ISP's cosmetic processing so the merge operates on data
        // that has not already been denoised and sharpened. These are advisory:
        // hardware that does not support the mode simply ignores the request.
        Camera2Interop.Extender(analysisBuilder)
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            )
            .setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_OFF,
            )

        val analysis = analysisBuilder.build().apply {
            setAnalyzer(analysisExecutor) { image ->
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
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )

            // Underexpose to keep highlights off the clip point; the merge and
            // tone curve recover the shadows afterwards.
            val exposureState = camera.cameraInfo.exposureState
            if (exposureState.isExposureCompensationSupported) {
                val step = exposureState.exposureCompensationStep.toFloat()
                val range = exposureState.exposureCompensationRange
                val desired = if (step > 0f) (-1.0f / step).toInt() else 0
                val index = desired.coerceIn(range.lower, range.upper)
                camera.cameraControl.setExposureCompensationIndex(index)
                exposureNote = "EV %.1f".format(index * step)
            } else {
                exposureNote = "EV n/a"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            status = "Camera unavailable: ${e.message}"
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
            Row(horizontalArrangement = Arrangement.Center) {
                Chip(
                    label = if (mergeEnabled) "MERGE ON" else "MERGE OFF",
                    active = mergeEnabled,
                ) { if (!busy) mergeEnabled = !mergeEnabled }

                Chip(label = "A/B", active = abMode) {
                    if (!busy) abMode = !abMode
                }

                Chip(label = "$burstFrames FRAMES", active = false) {
                    if (!busy) {
                        burstFrames = when (burstFrames) {
                            1 -> 2; 2 -> 4; 4 -> 8; 8 -> 12; else -> 1
                        }
                    }
                }

                if (exposureNote.isNotEmpty()) {
                    Chip(label = exposureNote, active = false) {}
                }
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

        ShutterButton(
            busy = busy,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
            onClick = {
                if (busy) return@ShutterButton
                busy = true
                status = if (abMode) "A/B capture, $burstFrames frames…"
                    else "Capturing $burstFrames frames…"

                scope.launch {
                    val label = if (abMode) "ab" else if (mergeEnabled) "merged" else "single"
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(System.currentTimeMillis())

                    val result = withContext(Dispatchers.Default) {
                        // One snapshot drives every output, so an A/B pair is
                        // guaranteed to be the same scene at the same instant.
                        val frames = buffer.snapshot(
                            if (mergeEnabled || abMode) burstFrames else 1
                        )
                        if (frames.isEmpty()) return@withContext null

                        if (abMode) {
                            // Reference frame alone, then the merge of that same
                            // burst. Identical tone curve, so the only difference
                            // is the merge itself.
                            val single = Merger.process(frames, mergeEnabled = false)
                            ImageSaver.saveJpeg(
                                context, single.bitmap, "MF_${stamp}_ab_single.jpg",
                            )
                            single.bitmap.recycle()

                            val both = Merger.process(frames, mergeEnabled = true)
                            val uri = ImageSaver.saveJpeg(
                                context, both.bitmap, "MF_${stamp}_ab_merged.jpg",
                            )
                            both.bitmap.recycle()
                            both.stats to uri
                        } else {
                            val merged = Merger.process(frames, mergeEnabled)
                            val uri = ImageSaver.saveJpeg(
                                context, merged.bitmap, "MF_${stamp}_$label.jpg",
                            )
                            merged.bitmap.recycle()
                            merged.stats to uri
                        }
                    }

                    status = if (result == null) {
                        "No frames buffered yet"
                    } else {
                        val (stats, uri) = result
                        Log.i(TAG, "Saved $uri  $stats")
                        "%s  %d frames  align %dms  merge %dms".format(
                            label, stats.framesUsed, stats.alignMillis, stats.mergeMillis,
                        )
                    }
                    busy = false
                }
            },
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
