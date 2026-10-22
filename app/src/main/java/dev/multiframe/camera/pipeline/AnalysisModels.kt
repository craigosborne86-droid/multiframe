package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

private const val TAG = "Multiframe"

/**
 * The opt-in analysis models (Labs), one registry for all of them.
 *
 * ### What a model may do here
 *
 * Every task below *analyses* measured data and returns a handful of numbers
 * or a mask: an illuminant, a subject's position, a frame's blur, which
 * region a pixel belongs to. None returns a pixel value. Each has a
 * deterministic default doing the same job, each is off until the user turns
 * it on, and each photograph it touches names it in its report and its
 * Content Credentials ([AnalysisUse]). CLAUDE.md, "Choice over defaults".
 *
 * ### Where the models come from
 *
 * Multiframe's own, trained by `tools/analysis_models/train.py` on pairs this
 * app collects on the phone when the user switches collection on: each pair
 * is an input exactly as the phone feeds the model and a *measured* label --
 * the stack's own sky mask, the white balance the photographer set by hand,
 * the point they tapped to focus, the gyro's blur over the exposure. A task
 * whose label the phone cannot measure (people, skin, foliage) says so in
 * [AnalysisTask.collectable] and trains from a dataset chosen by hand.
 *
 * A model is offered only when its file is bundled in `assets/`; until then
 * its Labs row is absent (CLAUDE.md, palette rule 6).
 */
enum class AnalysisTask(
    val code: String,
    val asset: String,
    val title: String,
    /** What the model's output chose, for the report and the Content Credentials. */
    val role: String,
    val input: Shape,
    val output: Shape,
    /** Whether the phone can measure this task's label and collect training pairs. */
    val collectable: Boolean,
) {
    SKY("sky", "sky_segmenter.tflite", "sky segmenter",
        "chose which alignment each pixel's measured samples got (sky or ground)",
        Shape(256, 192, 1), Shape(256, 192, 1), collectable = true),
    AWB("awb", "awb_illuminant.tflite", "white balance estimator",
        "estimated the scene's illuminant: three white-balance multipliers",
        Shape(AwbContract.BINS, AwbContract.BINS, 2), Shape(1, 1, 2), collectable = true),
    SUBJECT("subject", "subject_saliency.tflite", "subject finder",
        "chose where autofocus and exposure metered",
        Shape(256, 192, 1), Shape(64, 48, 1), collectable = true),
    FRAME("frame", "frame_sharpness.tflite", "frame sharpness estimator",
        "chose which measured frame the others were aligned to",
        Shape(256, 192, 1), Shape(1, 1, 1), collectable = true),
    REGIONS("regions", "region_segmenter.tflite", "region segmenter",
        "chose how much of its earned shadow lift each region of measured pixels took (sky, skin, foliage)",
        Shape(256, 192, 3), Shape(256, 192, 3), collectable = false),
    PERSON("person", "person_segmenter.tflite", "person segmenter",
        "chose where moving people came from one measured frame instead of the merge",
        Shape(256, 192, 3), Shape(256, 192, 1), collectable = false);

    data class Shape(val width: Int, val height: Int, val channels: Int) {
        val size: Int get() = width * height * channels
    }

    companion object {
        fun ofCode(code: String): AnalysisTask? = entries.firstOrNull { it.code == code }
    }
}

/** A model that ran on a photograph, as its report and Content Credentials name it. */
data class AnalysisUse(val task: AnalysisTask, val model: String, val role: String = task.role) {
    companion object {
        /** The role the portrait blur discloses: the one use whose output is a *simulated* optical effect. */
        const val PORTRAIT_ROLE = "chose where a simulated background blur (Labs) averaged measured neighbours; " +
            "not optical bokeh, and no pixel was invented"
    }
}

/** One bundled model, run by LiteRT on the CPU: a float tensor in, a float tensor out. */
class AnalysisModel internal constructor(
    val task: AnalysisTask,
    private val interpreter: org.tensorflow.lite.Interpreter,
    /** First eight hex digits of the file's SHA-256, so a photograph names the exact model. */
    val version: String,
) {
    val name: String get() = "Multiframe ${task.title} $version (on-device)"

    fun use(role: String = task.role) = AnalysisUse(task, name, role)

    @Synchronized
    fun run(input: FloatArray): FloatArray {
        require(input.size == task.input.size) { "${task.code}: input ${input.size}, contract ${task.input.size}" }
        val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        input.forEach { inBuf.putFloat(it) }
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(task.output.size * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        return FloatArray(task.output.size) { outBuf.getFloat() }
    }
}

object AnalysisModels {
    private val cache = HashMap<AnalysisTask, AnalysisModel>()

    fun available(context: Context, task: AnalysisTask): Boolean =
        runCatching { context.assets.openFd(task.asset).close(); true }.getOrDefault(false)

    @Synchronized
    fun obtain(context: Context, task: AnalysisTask): AnalysisModel? = cache[task] ?: runCatching {
        if (!available(context, task)) return null
        val fd = context.assets.openFd(task.asset)
        val mapped = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val digest = MessageDigest.getInstance("SHA-256").digest(ByteArray(mapped.remaining()).also { mapped.duplicate().get(it) })
        val version = digest.take(4).joinToString("") { "%02x".format(it) }
        val interpreter = org.tensorflow.lite.Interpreter(mapped, org.tensorflow.lite.Interpreter.Options().setNumThreads(2))
        AnalysisModel(task, interpreter, version).also { cache[task] = it }
    }.onFailure { Log.w(TAG, "${task.code} model unavailable", it) }.getOrNull()

    /** Where a task's training pairs are kept on the phone, until copied off by hand. */
    fun trainingDir(context: Context, task: AnalysisTask): File =
        File(context.getExternalFilesDir(null), "training/${task.code}")

    /** Saves one training pair (input as the model eats it, measured label). Never throws. */
    fun collect(context: Context, task: AnalysisTask, input: FloatArray, label: FloatArray) {
        runCatching {
            val dir = trainingDir(context, task).apply { mkdirs() }
            val file = File(dir, "pair_${System.currentTimeMillis()}.mfp")
            file.outputStream().use { TrainingPair.write(it, task, input, label) }
            Log.i(TAG, "${task.code} training pair saved: ${file.absolutePath}")
        }.onFailure { Log.w(TAG, "${task.code} training pair not saved", it) }
    }
}

/**
 * The training pair's file: `MFP1`, the task code (one length byte, ASCII),
 * the input's and the label's width, height and channels (six LE int32),
 * then both tensors as LE float32, row-major, channels last -- exactly the
 * tensors the phone feeds and expects. `tools/analysis_models/train.py`
 * reads it.
 */
object TrainingPair {
    const val MAGIC = "MFP1"

    fun write(out: java.io.OutputStream, task: AnalysisTask, input: FloatArray, label: FloatArray) {
        require(input.size == task.input.size && label.size == task.output.size) { "${task.code}: pair does not match the contract" }
        val code = task.code.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(4 + 1 + code.size + 24 + (input.size + label.size) * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII)).put(code.size.toByte()).put(code)
        for (s in listOf(task.input, task.output)) buf.putInt(s.width).putInt(s.height).putInt(s.channels)
        input.forEach { buf.putFloat(it) }
        label.forEach { buf.putFloat(it) }
        out.write(buf.array())
    }

    /** Reads a pair [write] wrote: the task, the input and the label. */
    fun read(bytes: ByteArray): Triple<AnalysisTask, FloatArray, FloatArray> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "not an MFP1 pair" }
        val code = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val task = requireNotNull(AnalysisTask.ofCode(String(code, Charsets.US_ASCII))) { "unknown task" }
        val dims = IntArray(6) { buf.getInt() }
        val input = FloatArray(dims[0] * dims[1] * dims[2]) { buf.getFloat() }
        val label = FloatArray(dims[3] * dims[4] * dims[5]) { buf.getFloat() }
        return Triple(task, input, label)
    }
}

/**
 * Shared shaping for the models whose input is a picture: area-average to the
 * contract's size, stretch so the 99th percentile is 1, and bring a small
 * mask back up bilinearly. The same arithmetic runs in the training export.
 */
object ModelImage {

    /** [src] (width × height, one channel) area-averaged to [ow] × [oh]. */
    fun area(src: FloatArray, width: Int, height: Int, ow: Int, oh: Int): FloatArray {
        val out = FloatArray(ow * oh)
        for (oy in 0 until oh) {
            val y0 = oy * height / oh; val y1 = maxOf(y0 + 1, (oy + 1) * height / oh)
            for (ox in 0 until ow) {
                val x0 = ox * width / ow; val x1 = maxOf(x0 + 1, (ox + 1) * width / ow)
                var s = 0f; var n = 0
                for (y in y0 until minOf(y1, height)) for (x in x0 until minOf(x1, width)) { s += src[y * width + x]; n++ }
                out[oy * ow + ox] = if (n > 0) s / n else 0f
            }
        }
        return out
    }

    /** Divides by the 99th percentile (at least [floor]) and clips at 1. */
    fun stretch(values: FloatArray, floor: Float = 1e-6f): FloatArray {
        val sorted = values.copyOf().also { it.sort() }
        val p99 = maxOf(floor, sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)])
        return FloatArray(values.size) { minOf(1f, values[it] / p99) }
    }

    /** A luma plane, area-averaged to [ow] × [oh] and stretched. */
    fun luma(plane: Plane, ow: Int, oh: Int): FloatArray =
        stretch(area(FloatArray(plane.data.size) { (plane.data[it].toInt() and 0xFF).toFloat() }, plane.width, plane.height, ow, oh), 1f)

    /** A [sw] × [sh] map bilinearly to [width] × [height], clamped to 0..1. */
    fun upscale(small: FloatArray, sw: Int, sh: Int, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * sh / height - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt(); val y1 = minOf(sh - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) * sw / width - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt(); val x1 = minOf(sw - 1, x0 + 1); val wx = fx - x0
                val top = small[y0 * sw + x0] * (1 - wx) + small[y0 * sw + x1] * wx
                val bottom = small[y1 * sw + x0] * (1 - wx) + small[y1 * sw + x1] * wx
                out[y * width + x] = (top * (1 - wy) + bottom * wy).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /** Channel [c] of a channels-last tensor. */
    fun channel(tensor: FloatArray, channels: Int, c: Int): FloatArray =
        FloatArray(tensor.size / channels) { tensor[it * channels + c] }
}
