package dev.multiframe.camera

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Multiframe"

/**
 * Phase 1: live viewfinder + single-frame capture saved through MediaStore.
 * The burst/merge pipeline replaces the capture path in Phase 2.
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("") }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(lifecycleOwner) {
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        try {
            val provider = ProcessCameraProvider.getInstance(context)
                .await(ContextCompat.getMainExecutor(context))
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
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

        if (status.isNotEmpty()) {
            Text(
                text = status,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color(0xAA000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        ShutterButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
            onClick = {
                status = "Capturing…"
                capturePhoto(
                    imageCapture = imageCapture,
                    context = context,
                    onResult = { status = it },
                )
            },
        )
    }
}

@Composable
private fun ShutterButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(width = 3.dp, color = Color.White, shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Shutter" },
    )
}

private fun capturePhoto(
    imageCapture: ImageCapture,
    context: android.content.Context,
    onResult: (String) -> Unit,
) {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .format(System.currentTimeMillis())

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "MF_$stamp.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${Environment.DIRECTORY_DCIM}/Multiframe",
        )
    }

    val options = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values,
    ).build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.i(TAG, "Saved ${output.savedUri}")
                onResult("Saved MF_$stamp.jpg")
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                onResult("Capture failed: ${exception.message}")
            }
        },
    )
}

/**
 * Awaits a [ListenableFuture] without pulling in kotlinx-coroutines-guava.
 * CameraX 1.6.1 only exposes ProcessCameraProvider.getInstance() as a future.
 */
private suspend fun <T> ListenableFuture<T>.await(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            executor,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
