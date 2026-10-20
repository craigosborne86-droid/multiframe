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
    /**
     * The MediaStore publish inside [developMillis], which is the part of a
     * shutter press that belongs to the phone's storage rather than to this
     * app. Split out because it is where nearly all the shot-to-shot variation
     * lives, and a consistency claim about the pipeline cannot be made through
     * it.
     */
    val publishMillis: Long = 0,
    /** Whether the burst alternated ISO levels for dual conversion gain. */
    val dcgActive: Boolean = false,
    /**
     * What the 2x path did with this burst, or null if it was not asked.
     *
     * A 2x output is a different claim about the photograph from a merge and
     * the readout says which was made; a decline is recorded with its reason
     * so the user learns why the picture they asked for is the ordinary one.
     */
    val superRes: SuperResOutcome? = null,
)

/** The 2x path's account of itself. See [SuperResCapture]. */
data class SuperResOutcome(
    /** True when the JPEG written is the 2x canvas. */
    val produced: Boolean,
    /** Predicted interior holes per channel, red, green, blue, from the alignment. */
    val predictedHoles: FloatArray,
    /** Measured interior holes per channel once accumulated, or null if it never ran. */
    val measuredHoles: FloatArray?,
    /** Why it declined, or null if it did not. */
    val reason: String?,
    /** The accumulation, in milliseconds, when it ran. */
    val accumulateMillis: Long = 0,
    /** The linear develop and encode, when they ran. */
    val developMillis: Long = 0,
)

/**
 * The optics correction a developed frame calls for, or null when there is
 * nothing to do.
 *
 * Sized against the *rotated* bitmap rather than the sensor, because that is
 * the image the correction will run on and the model has to describe its
 * geometry. A rotation of 90 or 270 degrees swaps the axes, which the model's
 * rescale handles by being given the dimensions it will actually see.
 */
internal fun opticsFor(
    profile: LensProfile?,
    bitmap: android.graphics.Bitmap,
): Optics.Params? =
    Optics.paramsFor(profile, bitmap.width, bitmap.height)?.takeIf { it.hasWork }

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

    internal suspend fun captureAndMerge(
        context: Context,
        imageCapture: ImageCapture,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult?,
        frameCount: Int,
        rotationDegrees: Int,
        lens: Lens? = null,
        /** Optical calibration for [lens], where the device published any. */
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        /** How the JPEG is rendered; the default is the PRD's global rendering. */
        develop: DevelopParams = DevelopParams(),
        /** Whether the merged raw is written as a DNG beside the JPEG. */
        writeDng: Boolean = true,
        /**
         * The still captures' results by sensor timestamp, for each frame's
         * gain scale. Null, or a frame whose result never arrives, merges
         * that frame unscaled, as before there was one.
         */
        frameResults: FrameMetadataStore<TotalCaptureResult>? = null,
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
        var refProxy: Plane? = null
        var tilesX = 0
        var tilesY = 0
        var captured = 0
        var mergeMillis = 0L
        // The first frame's light: the sequential burst's reference.
        var referenceLight = 0.0
        val captureStart = System.currentTimeMillis()

        try {
            for (i in 0 until frameCount) {
                onProgress("raw ${i + 1}/$frameCount")
                val ok = try {
                    val raw = takeRawImage(context, imageCapture)
                    raw?.use { image ->
                        // The frame's own result, by its sensor timestamp; never
                        // the newest one, which may be the next frame's.
                        val light = FrameLight.of(frameResults?.let { resultFor(it, image.imageInfo.timestamp) })
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
                            refProxy = m.lumaProxy(buffer, stride)
                            referenceLight = light
                        } else {
                            // A frame the meter moved is brought to the
                            // reference's scale by its light, black + (raw -
                            // black) / g, as the ZSL merge does (§3.2). One
                            // where either light is unknown: the merge unscaled.
                            val gainScale = FrameLight.gainScale(referenceLight, light)
                            // Alignment runs on the small luma proxy. Native
                            // where it can, which is where nearly all of the
                            // merge's time used to go; the Kotlin behind it is
                            // the reference the parity test holds it to.
                            val proxy = m.lumaProxy(buffer, stride, gainScale)
                            val field = Aligner.alignNative(refProxy!!, proxy, tilesX, tilesY)
                                ?: Aligner.align(
                                    Aligner.buildPyramid(refProxy!!),
                                    Aligner.buildPyramid(proxy),
                                    tilesX, tilesY,
                                )
                            m.addFrame(buffer, stride, field, gainScale)
                        }
                        mergeMillis += System.currentTimeMillis() - t0
                        captured++
                    } != null
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
                lens = lens,
                lensProfile = lensProfile,
                outputFormat = outputFormat,
                // The sequential camera has no guard and no pull: the meter's
                // exposure is the intent, and the develop stays near it.
                develop = if (develop.meteredGain == 0f) develop.copy(meteredGain = 1f) else develop,
                writeDng = writeDng,
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
        lens: Lens? = null,
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        handoverMicros: Long = 0,
        burstSpanMillis: Long = 0,
        streamStats: String? = null,
        dcgActive: Boolean = false,
        develop: DevelopParams = DevelopParams(),
        /** Whether the merged raw is written as a DNG beside the JPEG. A setting; on by default. */
        writeDng: Boolean = true,
        /** Stops the DNG tells a converter to add back: the guard's pull, undone. */
        baselineExposureStops: Float = 0f,
    ): RawBurstResult {
        val stamp = stamp()

        // One merged raw image feeds both outputs, so the DNG and the JPEG
        // carry identical merge benefit instead of coming from separate
        // pipelines. The DNG gets the raw data untouched; the JPEG is developed
        // from that same data.
        val t2 = System.currentTimeMillis()
        val dngName = "MF_${stamp}_${tag}_${captured}f.dng"

        // Written straight from the native buffer: no Java copy for the DNG.
        //
        // And written *while* the JPEG is being developed and encoded, because
        // the two have nothing to say to each other: both only read the merged
        // buffer, and the native develop reads it through its base address
        // rather than the buffer's position. Measured sequentially they were
        // about 300 ms of DNG followed by 600 ms of develop, and the encode
        // alone -- 240 ms of libjpeg on twelve and a half megapixels -- was the
        // largest single item in a capture.
        //
        // The write gets its own duplicate of the buffer. `writeByteBuffer`
        // advances the position it is given and the Kotlin develop fallback
        // rewinds the same buffer, so sharing one would have them fighting over
        // a cursor even though neither changes a pixel.
        // From the sensor's own characterisation rather than the ISP's
        // rendering matrix, which is tuned to the manufacturer's look. Read
        // before the DNG writer starts: the DNG carries the same white balance.
        val color = ColorProfile.calibrated(characteristics, captureResult)
        var dngUri: android.net.Uri? = null
        var dngMillis = 0L
        val dngWriter = if (writeDng && captureResult != null) {
            Thread {
                val started = System.currentTimeMillis()
                dngUri = writeDng(
                    context, mergedBuffer.duplicate(), width, height,
                    characteristics, captureResult, dngName,
                    baselineExposureStops = baselineExposureStops,
                    whiteBalanceNeutral = ColorProfile.cameraNeutral(captureResult, color.gains),
                    rotationDegrees = rotationDegrees,
                )
                dngMillis = System.currentTimeMillis() - started
            }.apply { name = "dng-write"; start() }
        } else {
            null
        }

        val t3 = System.currentTimeMillis()
        // The rendering, adapted to the gain the frame was taken at: no
        // sharpening of grain, wider colour smoothing, above ISO 800.
        val iso = captureResult?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
        val develop = develop.adaptedTo(iso)
        // Raw is uncorrected by definition, so the falloff the camera measured
        // for this capture has to be applied here or the corners stay dingy.
        val shading = ShadingMap.from(captureResult, profile)
        if (shading != null) {
            Log.i(TAG, "lens shading: %.2f stops of falloff".format(shading.falloffStops))
        }
        // The colour profile and the shading map are read from the capture
        // result rather than computed over the image, so this ought to be
        // nothing. Timed anyway, because "ought to be nothing" is how the JPEG
        // encode stayed hidden inside develop for as long as it did.
        val setupMillis = System.currentTimeMillis() - t3
        // Native develop writes into the Bitmap's own pixels, so nothing here
        // touches the Java heap. Falls back to the Kotlin developer, which is
        // the implementation the unit tests cover, if native declines.
        var bitmap = merger.develop(mergedBuffer, color, develop, shading = shading) ?: run {
            Log.w(TAG, "falling back to Kotlin develop")
            val shorts = ShortArray(width * height)
            mergedBuffer.rewind()
            mergedBuffer.asShortBuffer().get(shorts)
            RawDeveloper.developIntoBitmap(
                BayerFrame(width, height, shorts), profile, color, develop, shading = shading,
            )
        }
        // Optics before the rotation, and the ordering is not a preference.
        //
        // `LENS_INTRINSIC_CALIBRATION` gives the principal point and focal
        // length in *active array* pixels, which are sensor coordinates. A
        // rotated frame is not those coordinates: after 90 degrees the image is
        // 3064x4080 against an array of 4080x3064, and rescaling the intrinsics
        // to fit divides fx by 1.33 while multiplying fy by the same -- putting
        // the optical axis nowhere near the optical axis.
        //
        // Measured on the phone before this was moved: the 24mm main lens,
        // which is close to rectilinear, reported a peak displacement of
        // 75.5px. That was not the lens's distortion, it was the correction
        // being computed against a frame the model did not describe.
        //
        // Corrected in the orientation the model was written in, then turned.
        // The framing is a fraction of the frame the sensor read, so it is
        // placed on that frame before the optics take their border off, and
        // then carried into what is left. Placed on the cropped picture it
        // slid toward the far corner and cut twice.
        val readWidth = bitmap.width
        val readHeight = bitmap.height
        var inset = LensDistortion.Inset.NONE
        opticsFor(lensProfile, bitmap)?.let { params ->
            val tOptics = System.currentTimeMillis()
            // On the bitmap directly, so the native pass reads and writes the
            // locked buffers and nothing is copied through the Java heap.
            val corrected = Optics.applyCropped(bitmap, params)
            if (corrected.bitmap !== bitmap) {
                bitmap.recycle()
                bitmap = corrected.bitmap
            }
            inset = corrected.inset
            Log.i(TAG, "optics %dms".format(System.currentTimeMillis() - tOptics))
        }

        val framing = RenderCrop.from(characteristics, captureResult).pixels(readWidth, readHeight)
            .let { r ->
                val l = (r.left - inset.left).coerceIn(0, bitmap.width - 1)
                val t = (r.top - inset.top).coerceIn(0, bitmap.height - 1)
                SensorRect(l, t, (r.right - inset.left).coerceIn(l + 1, bitmap.width),
                    (r.bottom - inset.top).coerceIn(t + 1, bitmap.height))
            }
        if (framing.width != bitmap.width || framing.height != bitmap.height) {
            val cropped = android.graphics.Bitmap.createBitmap(bitmap, framing.left, framing.top, framing.width, framing.height)
            if (cropped !== bitmap) bitmap.recycle()
            bitmap = cropped
        }
        val tRotate = System.currentTimeMillis()
        bitmap = OrientationTracker.rotate(bitmap, rotationDegrees)
        val rotateMillis = System.currentTimeMillis() - tRotate

        val tEncode = System.currentTimeMillis()
        val publish = longArrayOf(0L)
        val baseName = "MF_${stamp}_${tag}_${captured}f"
        // Bitmap.compress writes no metadata at all, so without this every
        // photograph arrives in a library with no camera, no lens and no
        // exposure. The DNG needs none of it -- DngCreator has already written
        // full metadata from the same capture result.
        val saved = ImageSaver.saveImage(
            context, bitmap, baseName, outputFormat,
            metadata = ExifWriter.from(
                result = captureResult,
                characteristics = characteristics,
                lens = lens,
                frames = captured,
            ),
        )
        val jpegOk = saved != null
        val jpegName = saved?.second ?: "$baseName.jpg"
        bitmap.recycle()
        val encodeMillis = System.currentTimeMillis() - tEncode
        val developMillis = System.currentTimeMillis() - t3

        // Nothing may be reported until the DNG is actually on disk.
        dngWriter?.join()
        val dngOk = dngUri != null
        val writeMillis = dngMillis
        val outputsMillis = System.currentTimeMillis() - t2
        Log.i(
            TAG,
            "outputs: dng %dms alongside develop %dms, %dms wall".format(
                dngMillis, developMillis, outputsMillis,
            ),
        )

        // Decided once, now both are finished. A DNG has no preview this app
        // can cheaply decode, so the JPEG is the thumbnail whenever there is
        // one; the DNG only stands in when there is not.
        if (!jpegOk) {
            dngUri?.let { RecentCapture.remember(context, it, dngName) }
        }

        // "develop" has never only been develop. It spans the colour profile,
        // the native render, a full-resolution rotation, the JPEG encode and
        // the gallery publish -- and on a cool phone the native part is under
        // half of it. Naming the parts is what stops the next optimisation
        // being aimed at the wrong one, which has now happened twice.
        Log.i(
            TAG,
            ("develop breakdown: setup %dms, native %dms, rotate %dms, " +
                "encode %dms, publish %dms").format(
                setupMillis,
                developMillis - setupMillis - rotateMillis - encodeMillis,
                rotateMillis, encodeMillis - publish[0], publish[0],
            ),
        )

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
            publishMillis = publish[0],
            dcgActive = dcgActive,
        )
    }

    /**
     * The result whose sensor timestamp is [timestampNs], waited for briefly:
     * the image and its result arrive on separate callbacks in either order.
     */
    private suspend fun resultFor(store: FrameMetadataStore<TotalCaptureResult>, timestampNs: Long): TotalCaptureResult? {
        if (timestampNs <= 0) return null
        return store.retain(longArrayOf(timestampNs)).use { ticket ->
            if (!ticket.awaitComplete(RESULT_WAIT_MS)) {
                Log.w(TAG, "no capture result for raw frame at $timestampNs; merged unscaled")
            }
            ticket.values()[0]
        }
    }

    private const val RESULT_WAIT_MS = 2_000L

    /**
     * The next raw image, still open; the caller merges it and closes it.
     *
     * The image is not copied. Its plane buffer is a direct buffer owned by the
     * camera, so native code reads the sensor data in place; copying it would
     * put 25 MB per frame on the Java heap for no reason. Handed back rather
     * than consumed in the callback, so the merge runs on the caller's
     * dispatcher and not the main thread the callback arrives on.
     *
     * With OUTPUT_FORMAT_RAW_JPEG the callback fires twice, once per image, and
     * the JPEG usually arrives first. Only the RAW_SENSOR image is wanted.
     * Null when no raw image arrived in time.
     */
    private suspend fun takeRawImage(
        context: Context,
        imageCapture: ImageCapture,
    ): ImageProxy? = withTimeoutOrNull(RAW_FRAME_TIMEOUT_MS) {
        suspendCancellableCoroutine<ImageProxy?> { cont ->
            val settled = AtomicBoolean(false)
            var seen = 0

            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        seen++
                        if (image.format == ImageFormat.RAW_SENSOR && settled.compareAndSet(false, true)) {
                            // Closed by the caller, or here if the wait was abandoned.
                            cont.resume(image) { _, value, _ -> value.close() }
                            return
                        }
                        if (seen >= EXPECTED_IMAGES && settled.compareAndSet(false, true)) {
                            Log.w(TAG, "no RAW_SENSOR image in this capture")
                            cont.resume(null)
                        }
                        image.close()
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

    /**
     * Writes the merged CFA straight from the native buffer. DngCreator takes a
     * ByteBuffer, so the raw payload never needs a Java-side copy.
     */
    /**
     * Writes [stops] into the DNG's BaselineExposure tag in place. The tag
     * is found from the file's first pages; the file is otherwise untouched.
     * A DNG without the tag is left as it is, and says so in the log.
     */
    /**
     * Rewrites the tags `DngCreator` gives no way to set, in the bytes it
     * left for them: BaselineExposure with the guard's pull undone
     * ([DngMetadata.baselineExposureOffset]) and AsShotNeutral with the
     * camera neutral used by the JPEG matrix ([DngMetadata.asShotNeutralOffset]).
     * The pixels are untouched.
     */
    private fun patchTags(resolver: android.content.ContentResolver, uri: android.net.Uri, stops: Float, neutral: FloatArray?) {
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                // Positioned reads and writes on the descriptor itself: a
                // stream wrapped round it would close it a second time.
                val fd = pfd.fileDescriptor
                val header = java.nio.ByteBuffer.allocate(DNG_HEADER_BYTES)
                var position = 0L
                while (header.hasRemaining()) {
                    val n = android.system.Os.pread(fd, header, position)
                    if (n <= 0) break
                    position += n
                }
                val bytes = header.array().copyOf(header.position())
                fun write(at: Long, encoded: ByteArray) {
                    val value = java.nio.ByteBuffer.wrap(encoded)
                    var written = at
                    while (value.hasRemaining()) {
                        written += android.system.Os.pwrite(fd, value, written)
                    }
                }
                if (kotlin.math.abs(stops) >= 0.005f) {
                    val at = DngMetadata.baselineExposureOffset(bytes)
                    if (at == null) Log.w(TAG, "DNG has no BaselineExposure tag to carry the guard's %.2f stops".format(stops))
                    else write(at, DngMetadata.encodeBaselineExposure(bytes, stops))
                }
                if (neutral != null && neutral.size >= 3) {
                    val at = DngMetadata.asShotNeutralOffset(bytes)
                    if (at == null) Log.w(TAG, "DNG has no AsShotNeutral tag to carry the develop's white balance")
                    else write(at, DngMetadata.encodeCameraNeutral(bytes, neutral))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DNG tags not written", e)
        }
    }

    /** More than any `DngCreator` IFD0 and its values occupy: the raw data starts well past this. */
    private const val DNG_HEADER_BYTES = 64 * 1024

    internal fun writeDng(
        context: Context,
        merged: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        displayName: String,
        /**
         * Stops a converter should add to the as-shot exposure: the highlight
         * guard's pull, undone. Zero leaves the DNG as `DngCreator` wrote it.
         * See [DngMetadata.baselineExposureOffset].
         */
        baselineExposureStops: Float = 0f,
        /**
         * The camera-space scene white used by the JPEG matrix, written as
         * the DNG's AsShotNeutral so the two outputs agree; null
         * leaves `DngCreator`'s. See [DngMetadata.asShotNeutralOffset].
         */
        whiteBalanceNeutral: FloatArray? = null,
        rotationDegrees: Int = 0,
    ): android.net.Uri? = try {
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
            dng.setOrientation(CaptureOrientation.exif(rotationDegrees))
            resolver.openOutputStream(uri)?.use { out ->
                dng.writeByteBuffer(out, Size(width, height), merged, 0)
            } ?: error("openOutputStream returned null")
        }
        if (kotlin.math.abs(baselineExposureStops) >= 0.005f || whiteBalanceNeutral != null) {
            patchTags(resolver, uri, baselineExposureStops, whiteBalanceNeutral)
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // The thumbnail is not decided here any more. It used to be recorded
        // and then overwritten by the JPEG written straight afterwards, which
        // worked only while the two were sequential -- now that they overlap,
        // whichever finished last would win the race and a DNG thumbnail is a
        // blank square. The caller decides once both are done.
        //
        // The gallery log is not that decision and is written here. A DNG that
        // exists belongs in the gallery whether or not it is also the
        // thumbnail, and it pairs with its JPEG by name once both are in.
        CaptureLog.record(context, uri, displayName)
        Log.i(TAG, "merged DNG written: $uri")
        uri
    } catch (e: Exception) {
        Log.e(TAG, "merged DNG write failed", e)
        null
    }

    internal fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
