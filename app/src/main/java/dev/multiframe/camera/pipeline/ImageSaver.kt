package dev.multiframe.camera.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

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
    ): Uri? {
        val values = pendingEntry(displayName)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        val tCompress = System.currentTimeMillis()
        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: return null
        val compressMillis = System.currentTimeMillis() - tCompress

        // Split because "encode and save" was measured at 284-375 ms without
        // anyone knowing which half it was -- and it was measured on a disk
        // that was 98% full, where the write and the publish are both suspect.
        val tPublish = System.currentTimeMillis()
        val published = publish(context, uri, values, metadata, displayName)
        Log.i(
            TAG,
            "saveJpeg: compress+write %dms, publish %dms".format(
                compressMillis, System.currentTimeMillis() - tPublish,
            ),
        )
        return published
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
