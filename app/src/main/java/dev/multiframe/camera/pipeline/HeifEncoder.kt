package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream

private const val TAG = "HeifEncoder"

/**
 * HEIF output, through the hardware HEVC encoder.
 *
 * ### What the Pixel 11 Pro actually offers, measured
 *
 * Two findings from `HeifDeviceTest` and `HevcProbeDeviceTest`, both of which
 * matter more than anything the documentation says:
 *
 *  * **`Bitmap.CompressFormat` has no HEIC entry on API 37.** The whole enum
 *    reads `[JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS]`. `HEIC` is documented
 *    as present from API 30 and is simply not here, which is why [FORMAT] is
 *    looked up by name and why [isAvailable] is false on this device.
 *  * **The hardware HEVC encoder tops out at 3840x3840.** Captures from this
 *    sensor are 4080x3064, so the frame is 240 pixels too wide to encode. The
 *    software encoder maxes at 512x512 and is not a fallback for anything.
 *
 * Together those mean HEIF cannot represent a full-resolution capture from this
 * phone by any route. The long path -- a HEVC `MediaCodec` fed through EGL into
 * a `MediaMuxer` opened as `MUXER_OUTPUT_HEIF`, which the muxer does accept --
 * would run into the same 3840 limit and could only produce a downscaled image.
 * Trading measured resolution for file size is the opposite of what this app is
 * for, so that path is not taken.
 *
 * What remains is this: the capability is detected honestly, the control is
 * hidden where it cannot work, and on a device that does offer HEIC the encoder
 * below uses it without further change.
 *
 * ### Why it is worth having
 *
 * At matched visual quality a HEIF is roughly half the size of the JPEG. For an
 * app whose whole argument is that you should shoot bursts and keep the raw
 * alongside, halving the size of every developed image is not a minor
 * convenience.
 *
 * It also encodes on dedicated silicon rather than on the CPU cores the merge
 * wants. The striped libjpeg-turbo path in [NativeJpeg] is fast because it uses
 * every core; that is exactly the resource a queued second capture needs.
 *
 * ### Why JPEG stays the default
 *
 * Compatibility. A HEIF will not open in a decade of older software, some
 * desktop tools still refuse it, and a photographer who cannot open their
 * pictures has been failed regardless of how small the files are. So this is
 * offered rather than imposed, and every failure falls back to JPEG rather than
 * to nothing.
 *
 * ### The failure modes it plans for
 *
 * The hardware encoder is a shared resource with real limits, and none of the
 * ways it declines are exceptional enough to treat as bugs:
 *
 *  * **Dimensions.** HEVC encoders have a maximum frame size, and a stitched
 *    mosaic can be sixty megapixels. Past the limit the encoder refuses.
 *  * **Contention.** Another app recording video may hold the only encoder
 *    instance.
 *  * **Vendor gaps.** A device may advertise HEIF and fail on particular sizes,
 *    typically odd dimensions.
 *
 * Every one of them returns false and the caller writes a JPEG instead.
 */
object HeifEncoder {

    /**
     * Reached through `Bitmap.compress` rather than by driving `MediaCodec`.
     *
     * `android.media.HeifWriter` is not public API -- the class is in AOSP but
     * not in the SDK -- and the AndroidX `heifwriter` artifact is an extra
     * dependency for a job the framework already does. `HEIC` as a compress
     * format goes through Skia to the same hardware HEVC encoder a hand-built
     * `MediaCodec` pipeline would reach, with the muxing and the colour
     * conversion already handled. The only thing the direct path buys is
     * control over the encoder instance, which matters for packing several
     * images into one container; nothing here writes more than one.
     *
     * ### Why it is looked up by name
     *
     * `CompressFormat.HEIC` has existed on devices since API 30, and it is
     * absent from the `compileSdk` this project builds against -- checked
     * across android-36.1, 37.0 and 37.1, none of which declare it. Naming it
     * in source therefore does not compile, however well supported it is on the
     * phone the code will run on.
     *
     * So the constant is resolved from the runtime's own enum, once, and its
     * absence is simply one more reason HEIF is unavailable -- indistinguishable
     * at the call site from an encoder that refused a dimension, and handled
     * the same way. This is the capability check the situation calls for rather
     * than a workaround: the question "can this device write HEIF" is exactly
     * what is being asked, and the device is the thing being asked.
     */
    private val FORMAT: Bitmap.CompressFormat? by lazy {
        runCatching { Bitmap.CompressFormat.valueOf("HEIC") }
            .onFailure { Log.i(TAG, "this runtime has no HEIC compress format") }
            .getOrNull()
    }

    /** Whether this device can write HEIF at all. */
    val isAvailable: Boolean get() = FORMAT != null

    /**
     * Encoders reject odd dimensions on several chipsets, and HEVC works in
     * macroblocks regardless. Both axes must be even.
     */
    fun dimensionsAcceptable(width: Int, height: Int): Boolean =
        isAvailable && width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0

    /**
     * Writes [bitmap] to [fd] as HEIF, returning whether it worked.
     *
     * A false return means nothing usable was written and the caller should
     * discard what it created and encode a JPEG instead. It is never a reason
     * to fail the capture.
     */
    fun encodeToFd(bitmap: Bitmap, fd: FileDescriptor, quality: Int): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val format = FORMAT ?: return false

        if (!dimensionsAcceptable(width, height)) {
            Log.i(TAG, "declining ${width}x$height: HEVC wants even dimensions")
            return false
        }

        return try {
            // Deliberately not closed: the descriptor belongs to the caller,
            // and closing the stream would close it early. Same contract as the
            // JPEG fallback in `ImageSaver`.
            val out = FileOutputStream(fd)
            val ok = bitmap.compress(format, quality.coerceIn(0, 100), out)
            out.flush()
            if (!ok) Log.i(TAG, "HEIF encoder refused ${width}x$height")
            ok
        } catch (e: Exception) {
            // Every documented failure arrives here: an unsupported size, an
            // encoder held by another app, a vendor refusing a dimension it
            // advertised. None is a bug in this app and all have one answer.
            Log.i(TAG, "HEIF encode declined for ${width}x$height; using JPEG", e)
            false
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "not enough memory to encode ${width}x$height as HEIF", e)
            false
        }
    }
}
