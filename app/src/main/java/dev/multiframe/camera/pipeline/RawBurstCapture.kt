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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

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
    /** Shutter press to frames in hand. The ZSL path's headline number. */
    val handoverMicros: Long = 0,
    /** Oldest to newest frame in the burst: how tight the capture window was. */
    val burstSpanMillis: Long = 0,
    /** Streaming health at the moment of capture, when the ring supplied it. */
    val streamStats: String? = null,
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

        if (!NativeMerge.isAvailable()) {
            return RawBurstResult(
                frameCount, 0, null, 0, 0, 0, 0, null, null,
                "native merge unavailable on this device",
            )
        }

        var merger: NativeMerge? = null
        var refPyramid: List<Plane>? = null
        var tilesX = 0
        var tilesY = 0
        var captured = 0
        var mergeMillis = 0L
        val captureStart = System.currentTimeMillis()

        try {
            for (i in 0 until frameCount) {
                onProgress("raw ${i + 1}/$frameCount")
                val ok = try {
                    withRawImage(context, imageCapture) { image ->
                        val t0 = System.currentTimeMillis()
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val stride = plane.rowStride

                        var m = merger
                        if (m == null) {
                            m = NativeMerge.create(image.width, image.height, profile)
                                ?: error("native accumulator allocation failed")
                            merger = m
                            tilesX = max(1, m.proxyWidth / TILE_TARGET)
                            tilesY = max(1, m.proxyHeight / TILE_TARGET)
                            m.setReference(buffer, stride)
                            refPyramid = Aligner.buildPyramid(m.lumaProxy(buffer, stride))
                        } else {
                            // Alignment runs on the small luma proxy in Kotlin,
                            // reusing the aligner that is already under test.
                            val field = Aligner.align(
                                refPyramid!!,
                                Aligner.buildPyramid(m.lumaProxy(buffer, stride)),
                                tilesX, tilesY,
                            )
                            m.addFrame(buffer, stride, field)
                        }
                        mergeMillis += System.currentTimeMillis() - t0
                        captured++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "raw frame ${i + 1} failed", e)
                    false
                }
                if (!ok) break
            }

            val captureMillis = System.currentTimeMillis() - captureStart - mergeMillis
            val m = merger
            if (m == null || captured == 0) {
                return RawBurstResult(
                    frameCount, 0, null, captureMillis, mergeMillis, 0, 0, null, null,
                    "raw burst failed: no frames",
                )
            }

            val t1 = System.currentTimeMillis()
            val (mergedBuffer, stats) = m.finish()
            mergeMillis += System.currentTimeMillis() - t1

            return finishOutputs(
                context, m, mergedBuffer, m.width, m.height, profile,
                characteristics, captureResult, rotationDegrees,
                frameCount, captured, stats, captureMillis, mergeMillis,
            )
        } finally {
            merger?.close()
        }
    }

    private const val TILE_TARGET = 32

    internal fun finishOutputs(
        context: Context,
        merger: NativeMerge,
        mergedBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        profile: SensorProfile,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult?,
        rotationDegrees: Int,
        frameCount: Int,
        captured: Int,
        stats: BayerMergeStats,
        captureMillis: Long,
        mergeMillis: Long,
        tag: String = "merged",
        handoverMicros: Long = 0,
        burstSpanMillis: Long = 0,
        streamStats: String? = null,
    ): RawBurstResult {
        val stamp = stamp()

        // One merged raw image feeds both outputs, so the DNG and the JPEG
        // carry identical merge benefit instead of coming from separate
        // pipelines. The DNG gets the raw data untouched; the JPEG is developed
        // from that same data.
        val t2 = System.currentTimeMillis()
        val dngName = "MF_${stamp}_${tag}_${captured}f.dng"
        // Written straight from the native buffer: no Java copy for the DNG.
        val dngOk = captureResult != null &&
            writeDng(context, mergedBuffer, width, height, characteristics, captureResult, dngName)
        val writeMillis = System.currentTimeMillis() - t2

        val t3 = System.currentTimeMillis()
        val color = ColorProfile.from(captureResult)
        // Native develop writes into the Bitmap's own pixels, so nothing here
        // touches the Java heap. Falls back to the Kotlin developer, which is
        // the implementation the unit tests cover, if native declines.
        var bitmap = merger.develop(mergedBuffer, color) ?: run {
            Log.w(TAG, "falling back to Kotlin develop")
            val shorts = ShortArray(width * height)
            mergedBuffer.rewind()
            mergedBuffer.asShortBuffer().get(shorts)
            RawDeveloper.developIntoBitmap(BayerFrame(width, height, shorts), profile, color)
        }
        bitmap = OrientationTracker.rotate(bitmap, rotationDegrees)
        val jpegName = "MF_${stamp}_${tag}_${captured}f.jpg"
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
            handoverMicros, burstSpanMillis, streamStats,
        )
    }

    /**
     * Runs [consume] while the raw image is still open, then closes it.
     *
     * The image is not copied. Its plane buffer is a direct buffer owned by the
     * camera, so native code reads the sensor data in place; copying it would
     * put 25 MB per frame on the Java heap for no reason.
     *
     * With OUTPUT_FORMAT_RAW_JPEG the callback fires twice, once per image, and
     * the JPEG usually arrives first. Only the RAW_SENSOR image is wanted.
     */
    private suspend fun withRawImage(
        context: Context,
        imageCapture: ImageCapture,
        consume: (ImageProxy) -> Unit,
    ): Boolean = withTimeoutOrNull(RAW_FRAME_TIMEOUT_MS) {
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
                                if (settled.compareAndSet(false, true)) {
                                    consume(image)
                                    cont.resume(true)
                                }
                            } else if (seen >= EXPECTED_IMAGES && !settled.get()) {
                                Log.w(TAG, "no RAW_SENSOR image in this capture")
                                if (settled.compareAndSet(false, true)) cont.resume(false)
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
    } ?: false

    /**
     * Writes the merged CFA straight from the native buffer. DngCreator takes a
     * ByteBuffer, so the raw payload never needs a Java-side copy.
     */
    private fun writeDng(
        context: Context,
        merged: java.nio.ByteBuffer,
        width: Int,
        height: Int,
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

        merged.rewind()
        DngCreator(characteristics, result).use { dng ->
            resolver.openOutputStream(uri)?.use { out ->
                dng.writeByteBuffer(out, Size(width, height), merged, 0)
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

    internal fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
