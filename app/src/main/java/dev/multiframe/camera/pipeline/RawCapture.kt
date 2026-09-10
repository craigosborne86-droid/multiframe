package dev.multiframe.camera.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "Multiframe"

/**
 * Single-frame RAW capture.
 *
 * CameraX writes the DNG itself, wrapping the sensor data with the
 * `CameraCharacteristics` and `CaptureResult` metadata a raw converter needs.
 *
 * Note this is deliberately NOT the merge pipeline: the DNG produced here is
 * one unprocessed frame. Merging in the Bayer domain needs a raw burst, which
 * CameraX cannot deliver through ImageAnalysis.
 */
object RawCapture {

    private const val MIME_DNG = "image/x-adobe-dng"

    fun capture(
        imageCapture: ImageCapture,
        context: Context,
        onResult: (String) -> Unit,
    ) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        fun options(name: String, mime: String): ImageCapture.OutputFileOptions {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/Multiframe",
                )
            }
            return ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ).build()
        }

        val saved = mutableListOf<String>()

        imageCapture.takePicture(
            options("MF_${stamp}_raw.dng", MIME_DNG),
            options("MF_${stamp}_raw.jpg", "image/jpeg"),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val raw = output.imageFormat == ImageFormat.RAW_SENSOR
                    val kind = if (raw) "dng" else "jpeg"
                    saved.add(kind)
                    Log.i(TAG, "RAW capture saved $kind ${output.savedUri}")

                    // CameraX writes these itself rather than going through
                    // ImageSaver, so the gallery has to be told here or a
                    // capture taken by this path is invisible to it.
                    output.savedUri?.let { uri ->
                        val name = "MF_${stamp}_raw." + if (raw) "dng" else "jpg"
                        CaptureLog.record(context, uri, name)
                        // The JPEG is the thumbnail whenever there is one, for
                        // the reason RawBurstCapture records: a DNG has no
                        // preview this app can cheaply decode.
                        if (!raw) RecentCapture.remember(context, uri, name)
                    }

                    // The callback fires once per file written.
                    onResult("saved ${saved.joinToString(" + ")} (single frame)")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "RAW capture failed", exception)
                    onResult("DNG failed: ${exception.message}")
                }
            },
        )
    }
}
