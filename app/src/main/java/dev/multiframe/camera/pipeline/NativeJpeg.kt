package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log

private const val TAG = "Multiframe"

/**
 * JPEG encoding across every core, rather than one.
 *
 * `Bitmap.compress` reaches the same library this does -- Skia's copy of
 * libjpeg-turbo -- but through an interface with no handle on it, and a 12.5 MP
 * capture costs about 240 ms there on one thread while seven others sit idle.
 * Restart markers make a JPEG's entropy data divisible, so the image can be cut
 * into strips, encoded at once and stitched. `Jpeg.cpp` has the details.
 *
 * Every entry point returns null or false rather than throwing, and the caller
 * falls back to `Bitmap.compress`. The output is an ordinary baseline JPEG;
 * `JpegEncodeParityTest` holds it to the framework's, decoded and compared
 * pixel by pixel.
 */
object NativeJpeg {

    /**
     * Encodes [bitmap] into [fd].
     *
     * Returns false if the bitmap is not 8-bit RGBA, if libjpeg refuses it, or
     * if the descriptor would not take the whole file -- in every case the
     * caller should fall back rather than treat it as a failed capture.
     */
    fun encodeToFd(bitmap: Bitmap, fd: Int, quality: Int = 95, threads: Int = 0): Boolean {
        if (!NativeMerge.isAvailable()) return false
        return runCatching { nEncodeToFd(bitmap, quality, fd, threads) }
            .onFailure { Log.w(TAG, "native jpeg encode failed", it) }
            .getOrDefault(false)
    }

    /** The same encode into a byte array, for callers with no descriptor. */
    fun encodeToArray(bitmap: Bitmap, quality: Int = 95, threads: Int = 0): ByteArray? {
        if (!NativeMerge.isAvailable()) return null
        return runCatching { nEncodeToArray(bitmap, quality, threads) }
            .onFailure { Log.w(TAG, "native jpeg encode failed", it) }
            .getOrNull()
    }

    private external fun nEncodeToFd(
        bitmap: Bitmap, quality: Int, fd: Int, threads: Int,
    ): Boolean

    private external fun nEncodeToArray(bitmap: Bitmap, quality: Int, threads: Int): ByteArray?
}
