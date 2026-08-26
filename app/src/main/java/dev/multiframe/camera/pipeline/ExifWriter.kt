package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "Multiframe"

/**
 * How the frames behind one photograph were combined.
 *
 * Worth distinguishing because the two are opposite trades: a merge spends
 * frames on signal-to-noise at one framing, a mosaic spends them on resolution
 * across many. Calling a sweep's tiles "merged" would describe the wrong
 * photograph.
 */
enum class CaptureKind { MERGE, MOSAIC }

/** What the camera recorded about a capture, in a form EXIF understands. */
data class CaptureMetadata(
    val exposureTimeNs: Long?,
    val iso: Int?,
    val apertureF: Float?,
    val focalLengthMm: Float?,
    val equivalent35mm: Int?,
    val frames: Int,
    val takenAtMillis: Long,
    val lensLabel: String?,
    val kind: CaptureKind = CaptureKind.MERGE,
)

/**
 * Writes the capture's own metadata into the JPEG.
 *
 * `Bitmap.compress` writes none at all, so every photograph this app produced
 * arrived in a library with no camera, no lens, no exposure and no date beyond
 * the file's own timestamp. For an app whose whole argument is that it produces
 * a photographer's result, that is a conspicuous omission: the first thing
 * anyone does with a picture they care about is look at how it was taken.
 *
 * The DNG needs none of this. `DngCreator` writes full metadata from the same
 * capture result already.
 */
object ExifWriter {

    /** What the software tag says. */
    const val SOFTWARE = "Multiframe"

    /**
     * Reads what the camera reported for this frame.
     *
     * Every field is optional because every one of them is optional in the
     * Camera2 specification, and a device that reports no aperture should
     * produce a photograph with no aperture tag rather than a wrong one.
     */
    fun from(
        result: TotalCaptureResult?,
        characteristics: CameraCharacteristics?,
        lens: Lens?,
        frames: Int,
        takenAtMillis: Long = System.currentTimeMillis(),
        kind: CaptureKind = CaptureKind.MERGE,
    ): CaptureMetadata = CaptureMetadata(
        exposureTimeNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        iso = result?.get(CaptureResult.SENSOR_SENSITIVITY),
        apertureF = result?.get(CaptureResult.LENS_APERTURE)
            ?: characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull(),
        focalLengthMm = result?.get(CaptureResult.LENS_FOCAL_LENGTH)
            ?: lens?.focalLengthMm,
        equivalent35mm = lens?.equivalent35mm?.takeIf { it > 0 },
        frames = frames,
        takenAtMillis = takenAtMillis,
        lensLabel = lens?.label,
        kind = kind,
    )

    /**
     * An exposure time as the framework's ExifInterface wants it: seconds, as a
     * decimal string.
     *
     * Not "1/250", which is what EXIF stores and what a photographer reads.
     * `ExifInterface` converts a decimal to the rational itself and silently
     * discards anything already written as one -- measured on device, "1/250"
     * and "4/1000" both read back as null while "0.004" survives. The tag was
     * simply missing from every photograph until that was tracked down.
     */
    fun exposureSeconds(nanoseconds: Long): String? {
        if (nanoseconds <= 0L) return null
        return decimal(nanoseconds / 1_000_000_000.0, maxDecimals = 6)
    }

    /**
     * A value as a rational string, which is what the focal length tag wants.
     *
     * The framework's ExifInterface is not consistent about this, and the
     * inconsistency is not documented anywhere. Measured on device, writing and
     * reading back the same file:
     *
     *     TAG_EXPOSURE_TIME   "0.004"   kept      "4/1000"   discarded
     *     TAG_F_NUMBER        "1.7"     kept      "170/100"  discarded
     *     TAG_FOCAL_LENGTH    "6.9"     discarded "690/100"  kept
     *
     * Two of the three want a decimal and the third wants a rational, and a
     * value in the wrong form is dropped silently rather than rejected. There
     * is a device test pinning exactly this, so an OS change that fixes the
     * inconsistency is noticed rather than quietly removing the tags again.
     */
    fun rationalValue(value: Float, precision: Int = 100): String? {
        if (!value.isFinite() || value <= 0f) return null
        return "${(value * precision).roundToInt()}/$precision"
    }

    /** A value as a decimal string, which is what exposure and aperture want. */
    fun decimalValue(value: Float): String? {
        if (!value.isFinite() || value <= 0f) return null
        return decimal(value.toDouble(), maxDecimals = 2)
    }

    /**
     * Trims trailing zeros, so a four-second exposure reads as "4" rather than
     * "4.000000" and an eight-thousandth keeps every digit it needs.
     */
    private fun decimal(value: Double, maxDecimals: Int): String {
        val text = String.format(Locale.US, "%.${maxDecimals}f", value)
        return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /**
     * The shutter speed the way a photographer says it.
     *
     * Only for the human-readable description; the tag itself has to be a
     * decimal.
     */
    fun shutterLabel(nanoseconds: Long): String? {
        if (nanoseconds <= 0L) return null
        val seconds = nanoseconds / 1_000_000_000.0
        return if (seconds >= 0.4) {
            "${decimal(seconds, 1)}s"
        } else {
            "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
        }
    }

    /**
     * A note describing how the picture was made.
     *
     * Worth recording because it is the one thing about these files that is not
     * obvious from the other tags: a merged burst and a single frame at the
     * same settings are otherwise indistinguishable in a library.
     */
    fun describeCapture(metadata: CaptureMetadata): String = buildString {
        append(SOFTWARE)
        val n = metadata.frames
        when {
            metadata.kind == CaptureKind.MOSAIC ->
                append(": $n ${if (n == 1) "tile" else "tiles"} stitched")
            n > 1 -> append(": $n frames merged")
            else -> append(": single frame")
        }
        metadata.lensLabel?.let { append(", $it") }
        metadata.exposureTimeNs?.let { ns ->
            shutterLabel(ns)?.let { append(", $it") }
        }
        metadata.iso?.let { append(", ISO $it") }
    }

    /** Writes the metadata onto an already-saved image. */
    fun write(context: Context, uri: Uri, metadata: CaptureMetadata): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            apply(exif, metadata)
            exif.saveAttributes()
            true
        } ?: false
    } catch (e: Exception) {
        // Metadata is worth having and not worth losing a photograph over.
        Log.w(TAG, "could not write EXIF", e)
        false
    }

    /** Sets the tags, without saving. Separated so it can be tested directly. */
    fun apply(exif: ExifInterface, metadata: CaptureMetadata) {
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, describeCapture(metadata))

        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            .format(metadata.takenAtMillis)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)

        metadata.exposureTimeNs?.let { ns ->
            exposureSeconds(ns)?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, it) }
        }
        metadata.iso?.let {
            // The framework class still calls this by its older name; it is the
            // same EXIF tag AndroidX exposes as PhotographicSensitivity.
            @Suppress("DEPRECATION")
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString())
        }
        metadata.apertureF?.let { f ->
            decimalValue(f)?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it) }
        }
        metadata.focalLengthMm?.let { mm ->
            // A rational, unlike the two tags above. See rationalValue.
            rationalValue(mm)?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it) }
        }
        metadata.equivalent35mm?.let {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString())
        }

        // The pixels are rotated upright before the file is written, so the
        // orientation tag has to say "normal". Recording the device's rotation
        // here as well would turn the picture a second time in every viewer.
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString(),
        )
    }
}
