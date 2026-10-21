package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.OutputStream
import java.nio.ByteBuffer

private const val TAG = "Multiframe"

/**
 * The SDR picture and its gain map, written as one Ultra HDR JPEG.
 *
 * Through the platform: `Gainmap` on the bitmap, then `Bitmap.compress`,
 * which writes the primary picture, the map as a second JPEG behind it, and
 * the metadata that ties them -- the Adobe `hdrgm` XMP and the MPF index on
 * Android 14, and the ISO 21496-1 block besides from Android 15. Nothing
 * third-party, and on API 33 nothing at all: the picture is today's SDR JPEG.
 *
 * The strip encoder (`NativeJpeg`) is not used for these pictures. It could
 * encode both images -- the map is a small grey JPEG -- but the container
 * around them would be ours to write and to keep in step with every
 * decoder's reading of two specifications, and the platform already does
 * it. The cost is the single-threaded encode (about 240 ms on 12.5 MP) on
 * the shots that ask for HDR.
 */
object UltraHdr {

    /** `Gainmap` and `Bitmap.setGainmap` arrived in API 34. */
    val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Compresses [bitmap] with [map] attached into [out]. Returns false
     * where the platform cannot or will not; the bitmap is then left as it
     * was, with no gain map, for the caller's ordinary encode.
     */
    fun compress(bitmap: Bitmap, map: HdrGainMap.Encoded, quality: Int, out: OutputStream): Boolean {
        if (!isAvailable) return false
        return runCatching { compressWithGainmap(bitmap, map, quality, out) }
            .onFailure { Log.w(TAG, "Ultra HDR encode failed", it) }
            .getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun compressWithGainmap(bitmap: Bitmap, map: HdrGainMap.Encoded, quality: Int, out: OutputStream): Boolean {
        val contents = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ALPHA_8)
        // Row by row into the bitmap's own stride, which need not be its width.
        val stride = contents.rowBytes
        val padded = ByteArray(stride * map.height)
        for (y in 0 until map.height) System.arraycopy(map.codes, y * map.width, padded, y * stride, map.width)
        contents.copyPixelsFromBuffer(ByteBuffer.wrap(padded))

        // The map's code is log2(ratio) / log2(max) -- HdrGainMap.encode --
        // so the ratio runs from one to the max with a gamma of one, and the
        // ratio is of the pictures themselves: no offsets.
        val gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(map.maxRatio, map.maxRatio, map.maxRatio)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            // The whole map where the display has the scene's headroom, and
            // the SDR picture where it has none.
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(map.maxRatio)
        }
        bitmap.gainmap = gainmap
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (!ok) bitmap.gainmap = null
        contents.recycle()
        return ok
    }
}
