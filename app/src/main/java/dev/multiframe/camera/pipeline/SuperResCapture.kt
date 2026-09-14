package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "Multiframe"

/**
 * The 2x path's outputs: the canvas developed straight to a JPEG, and the
 * ordinary merge's DNG beside it.
 *
 * ### Why the DNG is the ordinary merge's
 *
 * A DNG is a raw file, and the canvas is not raw in the sense a DNG means: it
 * is three measured channels per cell, at a resolution the sensor does not
 * have, with holes filled from neighbours. `DngCreator` writes the sensor's
 * CFA and nothing else. So the DNG written beside a 2x JPEG is the merged CFA
 * of the same burst -- the same frames, the same alignment, accumulated the
 * ordinary way -- which is a raw file that is true, and which is why the 2x
 * path runs the ordinary merge as well rather than instead. The pair carry
 * one name and one gallery entry, as every other capture's pair do.
 *
 * ### Why nothing here is a Bitmap
 *
 * The canvas is fifty megapixels. Developed to RGBA that is 200 MB, which is
 * not a Bitmap this app can create beside a 256 MB heap already holding a
 * ring of raw frames. The develop runs in native memory from the canvas's own
 * pages to the file descriptor, and the pixels are written in the sensor's
 * orientation with the EXIF orientation tag saying which way is up, because
 * turning them would be a second copy.
 */
object SuperResCapture {

    /**
     * Writes the 2x JPEG from [canvas] and the DNG from [mergedBuffer].
     *
     * Consumes the canvas. Returns null if the develop declined, in which case
     * nothing has been published and the caller should write the ordinary
     * outputs from [mergedBuffer] instead -- the DNG is *not* written here on
     * that path, so the ordinary writer can write both halves under one name.
     */
    fun finishOutputs(
        context: Context,
        canvas: NativeSubPixel,
        mergedBuffer: ByteBuffer,
        mergedWidth: Int,
        mergedHeight: Int,
        profile: SensorProfile,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult?,
        rotationDegrees: Int,
        captured: Int,
        lens: Lens?,
        params: DevelopParams = DevelopParams(),
        quality: Int = 95,
        /** Whether the merged raw is written as a DNG beside the 2x JPEG. */
        writeDng: Boolean = true,
    ): Written? {
        val stamp = RawBurstCapture.stamp()
        val baseName = "MF_${stamp}${RecentCapture.SUPER_RES_TAG}${captured}f"

        val color = ColorProfile.calibrated(characteristics, captureResult)
        val shading = ShadingMap.from(captureResult)

        // The JPEG first, on this thread, because it is the one that can
        // decline. The DNG is written only once the 2x JPEG exists, so a
        // decline leaves nothing under this name for the ordinary path to
        // collide with.
        val tJpeg = System.currentTimeMillis()
        var stats: NativeSubPixel.DevelopStats? = null
        val jpegName = "$baseName.jpg"
        val jpegUri = ImageSaver.saveJpegFrom(
            context, jpegName,
            metadata = ExifWriter.from(
                result = captureResult,
                characteristics = characteristics,
                lens = lens,
                frames = captured,
                kind = CaptureKind.SUPER_RES,
                rotationDegrees = rotationDegrees,
            ),
        ) { fd ->
            stats = canvas.developToJpeg(fd, profile, color, params, shading, quality)
            stats != null
        }
        val jpegMillis = System.currentTimeMillis() - tJpeg
        if (jpegUri == null) {
            Log.w(TAG, "2x develop declined; the ordinary outputs will be written")
            return null
        }
        stats?.let {
            Log.i(
                TAG,
                ("2x develop: gain %.2f, exposure %.0fms, develop %.0fms, sharpen %.0fms, " +
                    "encode %.0fms, %dms wall including publish").format(
                    it.exposureGain, it.exposureMillis, it.developMillis, it.sharpenMillis,
                    it.encodeMillis, jpegMillis,
                ),
            )
        }

        val tDng = System.currentTimeMillis()
        val dngName = "$baseName.dng"
        val dngUri = if (writeDng && captureResult != null) {
            RawBurstCapture.writeDng(
                context, mergedBuffer.duplicate(), mergedWidth, mergedHeight,
                characteristics, captureResult, dngName,
            )
        } else {
            null
        }
        val dngMillis = System.currentTimeMillis() - tDng

        return Written(
            jpegName = jpegName,
            dngName = if (dngUri != null) dngName else null,
            width = canvas.width,
            height = canvas.height,
            jpegMillis = jpegMillis,
            dngMillis = dngMillis,
            exposureGain = stats?.exposureGain ?: 0f,
        )
    }

    class Written(
        val jpegName: String,
        val dngName: String?,
        val width: Int,
        val height: Int,
        val jpegMillis: Long,
        val dngMillis: Long,
        val exposureGain: Float,
    )
}
