package dev.multiframe.camera.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileOutputStream

private const val TAG = "Multiframe"

object ImageSaver {

    /**
     * Writes [bitmap] into DCIM/Multiframe so it appears in the system gallery.
     *
     * [metadata] is written into the file before it is published, because
     * `Bitmap.compress` writes none of its own. Optional: the paths that have
     * no capture to describe pass nothing and get a bare file, as before.
     */
    fun saveJpeg(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        quality: Int = 95,
        metadata: CaptureMetadata? = null,
        /**
         * Filled with the MediaStore publish alone, for a caller that needs it
         * separated from the encode. It is the volatile half by a long way --
         * measured across four consecutive shots the encode held at 63-98 ms
         * while the publish went 117, 82, 260, 495 -- so a figure that adds the
         * two together is mostly a statement about the phone's storage.
         */
        publishMillis: LongArray? = null,
    ): Uri? {
        var encoder = "strips"
        var compressMillis = 0L

        val tPublishStart = longArrayOf(0L)
        val uri = saveJpegWith(context, displayName, metadata) { pfd ->
            val tCompress = System.currentTimeMillis()
            // Every core rather than one. Falls back to the framework encoder,
            // which is the same library reached through Skia, if the strip
            // encoder declines the bitmap or fails partway.
            val done = NativeJpeg.encodeToFd(bitmap, pfd.fd, quality) || run {
                encoder = "framework"
                // Whatever the attempt managed to write is not a JPEG, and the
                // descriptor is still sitting wherever it stopped.
                Os.ftruncate(pfd.fileDescriptor, 0)
                Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                // Deliberately not closed: the descriptor belongs to the caller
                // and closing the stream would close it early.
                val out = FileOutputStream(pfd.fileDescriptor)
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.flush()
                ok
            }
            compressMillis = System.currentTimeMillis() - tCompress
            tPublishStart[0] = System.currentTimeMillis()
            done
        }

        // Split because "encode and save" was measured at 284-375 ms without
        // anyone knowing which half it was -- and it was measured on a disk
        // that was 98% full, where the write and the publish are both suspect.
        val published = System.currentTimeMillis() - tPublishStart[0]
        publishMillis?.set(0, published)
        Log.i(
            TAG,
            "saveJpeg: %s compress+write %dms, publish %dms".format(
                encoder, compressMillis, published,
            ),
        )
        return uri
    }

    /**
     * Saves an image whose pixels never become a [Bitmap].
     *
     * [write] is handed the open descriptor and returns whether it filled it.
     * The mosaic needs this: an 80 megapixel canvas cannot be turned into a
     * Bitmap on a phone that has 1.4 GB free, so it compresses out of its own
     * native memory and down this descriptor instead.
     *
     * Everything either side -- the pending entry, the metadata, the ordering
     * that makes MediaStore see it -- is the same as for any other photograph,
     * which is the reason this lives here rather than at the call site.
     */
    fun saveJpegFrom(
        context: Context,
        displayName: String,
        metadata: CaptureMetadata? = null,
        write: (fd: Int) -> Boolean,
    ): Uri? {
        val values = pendingEntry(displayName)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        val written = try {
            resolver.openFileDescriptor(uri, "w")?.use { write(it.fd) } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "could not open $displayName for writing", e)
            false
        }
        if (!written) {
            // A pending entry nothing was written into is not a photograph. It
            // would otherwise sit in the library as a zero-byte file forever.
            resolver.delete(uri, null, null)
            return null
        }

        return publish(context, uri, values, metadata, displayName)
    }

    /**
     * The same, handing over the descriptor itself rather than its number.
     *
     * A writer that can fail after writing something needs to be able to wind
     * the file back, and that takes the [ParcelFileDescriptor] rather than the
     * int inside it.
     */
    private fun saveJpegWith(
        context: Context,
        displayName: String,
        metadata: CaptureMetadata? = null,
        write: (pfd: ParcelFileDescriptor) -> Boolean,
    ): Uri? {
        val values = pendingEntry(displayName)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        val written = try {
            resolver.openFileDescriptor(uri, "w")?.use { write(it) } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "could not open $displayName for writing", e)
            false
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }

        return publish(context, uri, values, metadata, displayName)
    }

    private fun pendingEntry(displayName: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${Environment.DIRECTORY_DCIM}/Multiframe",
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    /** Writes the metadata, clears the pending flag and records the capture. */
    private fun publish(
        context: Context,
        uri: Uri,
        values: ContentValues,
        metadata: CaptureMetadata?,
        displayName: String,
    ): Uri {
        // Before the pending flag clears, not after. MediaStore scans the file
        // at the moment it is published and fills its own columns -- date
        // taken, orientation, exposure -- from what it finds then. Tags written
        // afterwards are in the file but absent from the columns a gallery
        // actually displays, which looks exactly like not writing them at all.
        if (metadata != null && !ExifWriter.write(context, uri, metadata)) {
            Log.w(TAG, "JPEG saved without metadata")
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        // Recorded as it is written, which is the only moment the app is
        // certain which image is its own without asking to read the whole
        // photo library.
        RecentCapture.remember(context, uri, displayName)
        return uri
    }
}
