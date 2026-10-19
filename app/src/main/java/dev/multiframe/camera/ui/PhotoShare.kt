package dev.multiframe.camera.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import dev.multiframe.camera.pipeline.CaptureFile
import java.io.FileNotFoundException

/** Hands over one existing MediaStore output, unchanged, with temporary read access. */
internal object PhotoShare {
    /** Provider calls must run on IO; the returned chooser is launched on main. */
    fun prepare(context: Context, file: CaptureFile): Intent {
        val uri = file.uri?.takeIf { it.scheme == "content" }
            ?: throw FileNotFoundException("Not a shareable content URI")
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            if (it.length == 0L) throw FileNotFoundException("Empty capture")
        } ?: throw FileNotFoundException("Capture no longer readable")
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: when (file.displayName.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
                "dng" -> "image/x-adobe-dng"
                "heic", "heif" -> "image/heif"
                "jpg", "jpeg" -> "image/jpeg"
                else -> throw IllegalArgumentException("Unsupported saved output")
            }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.displayName)
            clipData = ClipData(file.displayName, arrayOf(mime), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).apply {
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
