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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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
    val developMillis: Long,
    val writeMillis: Long,
    val savedDng: String?,
    val savedJpeg: String?,
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

    /** RAW_JPEG yields two images per capture: the DNG payload and a JPEG. */
    private const val EXPECTED_IMAGES = 2
    private const val RAW_FRAME_TIMEOUT_MS = 15_000L

    suspend fun captureAndMerge(
        context: Context,
        imageCapture: ImageCapture,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult?,
        frameCount: Int,
        rotationDegrees: Int,
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
                frameCount, 0, null, captureMillis, mergeMillis, 0, 0, null, null,
                "raw burst failed: no frames",
            )
        }

        val t1 = System.currentTimeMillis()
        val (merged, stats) = accumulator.finish()
        // 100 MB of accumulation buffers are dead once the merge is done, and
        // must go before the render buffers are allocated or the heap runs out.
        accumulator.release()
        mergeMillis += System.currentTimeMillis() - t1

        val stamp = stamp()

        // One merged raw image feeds both outputs, so the DNG and the JPEG
        // carry identical merge benefit instead of coming from separate
        // pipelines. The DNG gets the raw data untouched; the JPEG is developed
        // from that same data.
        val t2 = System.currentTimeMillis()
        val dngName = "MF_${stamp}_merged_${captured}f.dng"
        val dngOk = captureResult != null &&
            writeDng(context, merged, characteristics, captureResult, dngName)
        val writeMillis = System.currentTimeMillis() - t2

        val t3 = System.currentTimeMillis()
        val color = ColorProfile.from(captureResult)
        var bitmap = RawDeveloper.developIntoBitmap(merged, profile, color)
        bitmap = OrientationTracker.rotate(bitmap, rotationDegrees)
        val jpegName = "MF_${stamp}_merged_${captured}f.jpg"
        val jpegOk = ImageSaver.saveJpeg(context, bitmap, jpegName) != null
        bitmap.recycle()
        val developMillis = System.currentTimeMillis() - t3

        val parts = buildList {
            if (dngOk) add("DNG")
            if (jpegOk) add("JPEG")
        }
        return RawBurstResult(
            frameCount, captured, stats, captureMillis, mergeMillis,
            developMillis, writeMillis,
            if (dngOk) dngName else null,
            if (jpegOk) jpegName else null,
            if (parts.isEmpty()) "merge ok but nothing could be written"
            else "raw merge -> ${parts.joinToString(" + ")}, $captured frames",
        )
    }

    /**
     * One in-memory raw frame.
     *
     * With OUTPUT_FORMAT_RAW_JPEG the callback fires twice, once per image, and
     * the JPEG usually arrives first. Only the RAW_SENSOR image is wanted; the
     * companion is closed and ignored. The continuation is guarded because
     * resuming twice is fatal.
     */
    private suspend fun takeOne(
        context: Context,
        imageCapture: ImageCapture,
    ): BayerFrame? = withTimeoutOrNull(RAW_FRAME_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            var seen = 0

            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            seen++
                            if (image.format == ImageFormat.RAW_SENSOR) {
                                val frame = BayerFrame.copyFrom(image)
                                if (settled.compareAndSet(false, true)) cont.resume(frame)
                            } else if (seen >= EXPECTED_IMAGES && !settled.get()) {
                                // Both images arrived and neither was raw. If the
                                // raw already resumed this branch is not a failure,
                                // hence the settled check before warning.
                                Log.w(TAG, "no RAW_SENSOR image in this capture")
                                if (settled.compareAndSet(false, true)) cont.resume(null)
                            }
                        } catch (e: Exception) {
                            if (settled.compareAndSet(false, true)) {
                                cont.resumeWithException(e)
                            }
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (settled.compareAndSet(false, true)) {
                            cont.resumeWithException(exception)
                        }
                    }
                },
            )
        }
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
