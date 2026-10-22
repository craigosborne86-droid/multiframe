package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Multiframe"

/**
 * The opt-in sky segmentation model (Labs) and the data that will train it.
 *
 * ### Provenance
 *
 * The model is Multiframe's own, trained on pairs this app produces: the
 * reference frame of a tripod astro capture and the *measured* sky mask
 * ([SkyMask]) the stack itself derived. No third-party dataset, so no licence
 * question; and the model learns to reproduce what the sensor proved, from one
 * frame, which is what lets it help where the measured mask is weak (a
 * featureless foreground, a short span). Pairs are collected only when the
 * user switches collection on in Labs; they stay in the app's own external
 * folder until copied off by hand. `tools/sky_segmenter/` trains and exports.
 *
 * ### The contract ([SkyModelContract])
 *
 * Input: one 256×192 single-channel float tensor, the reference proxy
 * area-averaged down and stretched so its 99th percentile is 1. Output: the
 * same shape, sky probability 0..1. The export and the inference share the
 * preprocessing below, so the model sees on the phone exactly what it was
 * trained on.
 */
object SkySegmenters {
    const val MODEL_ASSET = "sky_segmenter.tflite"

    @Volatile private var cached: SkySegmenter? = null

    fun available(context: Context): Boolean =
        runCatching { context.assets.openFd(MODEL_ASSET).close(); true }.getOrDefault(false)

    @Synchronized
    fun obtain(context: Context): SkySegmenter? = cached ?: runCatching {
        if (!available(context)) return null
        LiteRtSkySegmenter(context).also { cached = it }
    }.onFailure { Log.w(TAG, "sky segmenter unavailable", it) }.getOrNull()

    /** Saves one training pair (preprocessed input, measured mask) where collection is on. */
    fun collect(context: Context, proxy: Plane, measured: FloatArray) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "sky-training").apply { mkdirs() }
            val input = SkyModelContract.preprocess(proxy)
            val label = SkyModelContract.downscaleMask(measured, proxy.width, proxy.height)
            val stamp = System.currentTimeMillis()
            // A tiny self-describing format: magic, width, height, input bytes, label bytes.
            File(dir, "pair_$stamp.skp").outputStream().use { out ->
                val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                header.put("SKP1".toByteArray()).putInt(SkyModelContract.WIDTH).putInt(SkyModelContract.HEIGHT)
                out.write(header.array())
                out.write(ByteArray(input.size) { (input[it] * 255f).toInt().coerceIn(0, 255).toByte() })
                out.write(ByteArray(label.size) { (label[it] * 255f).toInt().coerceIn(0, 255).toByte() })
            }
            Log.i(TAG, "sky training pair saved: ${dir.absolutePath}/pair_$stamp.skp")
        }.onFailure { Log.w(TAG, "sky training pair not saved", it) }
    }
}

/** What the model eats and returns; shared by inference and the training export. */
object SkyModelContract {
    const val WIDTH = 256
    const val HEIGHT = 192

    /** The proxy area-averaged to WIDTH×HEIGHT and stretched so its 99th percentile is 1. */
    fun preprocess(proxy: Plane): FloatArray {
        val small = area(FloatArray(proxy.data.size) { (proxy.data[it].toInt() and 0xFF).toFloat() }, proxy.width, proxy.height)
        val sorted = small.copyOf().also { it.sort() }
        val p99 = max(1f, sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)])
        return FloatArray(small.size) { min(1f, small[it] / p99) }
    }

    fun downscaleMask(mask: FloatArray, width: Int, height: Int): FloatArray = area(mask, width, height)

    /** The model's WIDTH×HEIGHT output, bilinearly back to the proxy's size. */
    fun upscale(small: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * HEIGHT / height - 0.5f).coerceIn(0f, HEIGHT - 1f)
            val y0 = fy.toInt(); val y1 = min(HEIGHT - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) * WIDTH / width - 0.5f).coerceIn(0f, WIDTH - 1f)
                val x0 = fx.toInt(); val x1 = min(WIDTH - 1, x0 + 1); val wx = fx - x0
                val top = small[y0 * WIDTH + x0] * (1 - wx) + small[y0 * WIDTH + x1] * wx
                val bottom = small[y1 * WIDTH + x0] * (1 - wx) + small[y1 * WIDTH + x1] * wx
                out[y * width + x] = (top * (1 - wy) + bottom * wy).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun area(src: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(WIDTH * HEIGHT)
        for (oy in 0 until HEIGHT) {
            val y0 = oy * height / HEIGHT; val y1 = max(y0 + 1, (oy + 1) * height / HEIGHT)
            for (ox in 0 until WIDTH) {
                val x0 = ox * width / WIDTH; val x1 = max(x0 + 1, (ox + 1) * width / WIDTH)
                var s = 0f; var n = 0
                for (y in y0 until min(y1, height)) for (x in x0 until min(x1, width)) { s += src[y * width + x]; n++ }
                out[oy * WIDTH + ox] = if (n > 0) s / n else 0f
            }
        }
        return out
    }
}

/** The bundled model, run by LiteRT on the CPU. */
private class LiteRtSkySegmenter(context: Context) : SkySegmenter {
    override val name = "Multiframe sky segmenter (on-device)"
    private val interpreter: org.tensorflow.lite.Interpreter

    init {
        val fd = context.assets.openFd(SkySegmenters.MODEL_ASSET)
        val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = org.tensorflow.lite.Interpreter(model, org.tensorflow.lite.Interpreter.Options().setNumThreads(2))
    }

    override fun skyMask(proxy: Plane): FloatArray {
        val input = SkyModelContract.preprocess(proxy)
        val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        input.forEach { inBuf.putFloat(it) }
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        val small = FloatArray(input.size) { outBuf.getFloat() }
        return SkyModelContract.upscale(small, proxy.width, proxy.height)
    }
}
