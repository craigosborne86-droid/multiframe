package dev.multiframe.camera.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Multiframe"

data class RawBurstResult(
    val framesRequested: Int,
    val framesCaptured: Int,
    val stats: BayerMergeStats?,
    val captureMillis: Long,
    val mergeMillis: Long,
    val writeMillis: Long,
    val savedName: String?,
    val message: String,
)

/**
 * Captures a burst of raw frames and merges them in the Bayer domain, so the
 * resulting DNG carries the benefit of the burst rather than being a single
 * unprocessed frame.
 *
 * Frames are requested sequentially through CameraX rather than as a hardware
 * burst. CameraX cannot stream RAW_SENSOR through ImageAnalysis, and a hardware
 * burst would mean running a parallel Camera2 session; sequential capture keeps
 * the whole app on one camera stack at the cost of a longer capture window.
 */
object RawBurstCapture {

    private const val MIME_DNG = "image/x-adobe-dng"

    suspend fun captureAndMerge(
        context: Context,
        imageCapture: ImageCapture,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult?,
        frameCount: Int,
        onProgress: (String) -> Unit,
    ): RawBurstResult {
        val profile = SensorProfile.from(characteristics)
        Log.i(TAG, "raw burst: profile=$profile")

        var accumulator: BayerAccumulator? = null
        var captured = 0
        val captureStart = System.currentTimeMillis()
        var mergeMillis = 0L

        for (i in 0 until frameCount) {
            onProgress("raw ${i + 1}/$frameCount")
            val frame = try {
                takeOne(context, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "raw frame ${i + 1} failed", e)
                break
            } ?: break

            val t0 = System.currentTimeMillis()
            if (accumulator == null) {
                accumulator = BayerAccumulator(frame.width, frame.height, profile)
                accumulator.setReference(frame)
            } else {
                accumulator.add(frame)
            }
            mergeMillis += System.currentTimeMillis() - t0
            captured++
        }
        val captureMillis = System.currentTimeMillis() - captureStart - mergeMillis

        if (accumulator == null || captured == 0) {
            return RawBurstResult(
                frameCount, 0, null, captureMillis, mergeMillis, 0, null,
                "raw burst failed: no frames",
            )
        }

        val t1 = System.currentTimeMillis()
        val (merged, stats) = accumulator.finish()
        mergeMillis += System.currentTimeMillis() - t1

        if (captureResult == null) {
            return RawBurstResult(
                frameCount, captured, stats, captureMillis, mergeMillis, 0, null,
                "merged $captured raw frames but no capture metadata for DNG",
            )
        }

        val t2 = System.currentTimeMillis()
        val name = "MF_${stamp()}_merged_${captured}f.dng"
        val ok = writeDng(context, merged, characteristics, captureResult, name)
        val writeMillis = System.currentTimeMillis() - t2

        return RawBurstResult(
            frameCount, captured, stats, captureMillis, mergeMillis, writeMillis,
            if (ok) name else null,
            if (ok) "merged DNG, $captured frames" else "merge ok, DNG write failed",
        )
    }

    /** One in-memory raw frame. Returns null if the delivered format is not raw. */
    private suspend fun takeOne(
        context: Context,
        imageCapture: ImageCapture,
    ): BayerFrame? = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        if (image.format != ImageFormat.RAW_SENSOR) {
                            Log.w(
                                TAG,
                                "expected RAW_SENSOR (${ImageFormat.RAW_SENSOR}) " +
                                    "but got format ${image.format} " +
                                    "${image.width}x${image.height}",
                            )
                            cont.resume(null)
                            return
                        }
                        cont.resume(BayerFrame.copyFrom(image))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }

    private fun writeDng(
        context: Context,
        frame: BayerFrame,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        displayName: String,
    ): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_DNG)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Multiframe",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")

        val buffer = ByteBuffer
            .allocateDirect(frame.data.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(frame.data)
        buffer.rewind()

        DngCreator(characteristics, result).use { dng ->
            resolver.openOutputStream(uri)?.use { out ->
                dng.writeByteBuffer(out, Size(frame.width, frame.height), buffer, 0)
            } ?: error("openOutputStream returned null")
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.i(TAG, "merged DNG written: $uri")
        true
    } catch (e: Exception) {
        Log.e(TAG, "merged DNG write failed", e)
        false
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
