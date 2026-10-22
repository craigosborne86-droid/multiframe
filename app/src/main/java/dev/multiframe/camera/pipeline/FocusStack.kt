package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Focus stacking: frames focused at different distances, merged so each part
 * of the picture comes from the frames where it is sharp (Labs).
 *
 * ### The measure
 *
 * Sharpness is local contrast energy on the half-resolution luma proxy (one
 * value per 2×2 CFA cell): the absolute Laplacian, box-averaged over a
 * (2R+1)² window so that a flat patch beside an edge inherits the edge's
 * verdict instead of flickering between frames. It is measured on each frame
 * *after* alignment, so a cell compares the same scene point across frames.
 *
 * ### The blend
 *
 * Every frame contributes to every cell with weight (S + ε)^p, p = 4: close to
 * choosing the sharpest frame where one clearly wins, a soft blend where two
 * are equally sharp, which keeps the seams between focus zones from showing
 * as halos. The weight is per CFA cell and applies to all four of its
 * samples, so red, green and blue keep their phase: offsets are the burst's
 * even CFA shifts (2 × the proxy offset), exactly as the merge uses them.
 *
 * ### Why not the merge
 *
 * The merge assumes every frame shows the same picture and rejects what
 * differs. Here frames differ on purpose -- in focus -- and the difference is
 * the information. Noise is still averaged where frames are equally sharp.
 *
 * Focus breathing (a slight scale change across the sweep) is absorbed by the
 * per-tile alignment: over a 32-pixel tile a 2% scale change is under a pixel.
 */
class FocusStack(
    val width: Int,
    val height: Int,
    private val profile: SensorProfile,
    private val radius: Int = DEFAULT_RADIUS,
) {
    /** One CFA frame, read in place: a ring slot's direct buffer, or a test's array. */
    fun interface CfaSource { fun at(x: Int, y: Int): Int }

    val proxyWidth = width / 2
    val proxyHeight = height / 2
    val tilesX = max(1, proxyWidth / TILE_TARGET)
    val tilesY = max(1, proxyHeight / TILE_TARGET)

    // Native memory: a twelve-megapixel float sum is 50 MB, more than the
    // Java heap should hold beside everything else.
    private val sum = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val weight = FloatArray(proxyWidth * proxyHeight)
    private var previous: Plane? = null
    private var previousField: AlignmentField? = null
    var frames = 0
        private set

    /** An 8-bit luma proxy: each 2×2 cell's mean above black, on the white level's scale. */
    fun proxy(src: CfaSource, gainScale: Float = 1f): Plane {
        val out = ByteArray(proxyWidth * proxyHeight)
        val scale = 255f / profile.range
        for (py in 0 until proxyHeight) for (px in 0 until proxyWidth) {
            val x = px * 2; val y = py * 2
            var s = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val b = profile.blackAt(x + dx, y + dy)
                s += (src.at(x + dx, y + dy) - b).coerceAtLeast(0) / gainScale
            }
            out[py * proxyWidth + px] = (s * 0.25f * scale).toInt().coerceIn(0, 255).toByte()
        }
        return Plane(proxyWidth, proxyHeight, out)
    }

    /**
     * Adds one frame. The first sets the geometry the rest are aligned to.
     *
     * Each frame is aligned to the one before it, not to the first, and the
     * offsets are chained: frames a focus step apart are sharp in mostly the
     * same places, while the first and the last of a sweep share almost
     * nothing sharp, and a block match between a blurred patch and a sharp
     * one is guesswork. The chained field is then median-filtered over each
     * tile's 3×3 neighbourhood: breathing makes a smooth field, so an
     * isolated tile that disagrees with all its neighbours is a mismatch.
     */
    fun add(src: CfaSource, gainScale: Float = 1f) {
        val p = proxy(src, gainScale)
        val prev = previous
        val field = if (prev == null) {
            AlignmentField(tilesX, tilesY, IntArray(tilesX * tilesY), IntArray(tilesX * tilesY))
        } else {
            val step = Aligner.alignNative(prev, p, tilesX, tilesY)
                ?: Aligner.align(Aligner.buildPyramid(prev), Aligner.buildPyramid(p), tilesX, tilesY)
            val base = previousField!!
            medianFiltered(AlignmentField(tilesX, tilesY,
                IntArray(step.dx.size) { base.dx[it] + step.dx[it] },
                IntArray(step.dy.size) { base.dy[it] + step.dy[it] }))
        }
        previous = p
        previousField = field
        val sharp = sharpness(p, field)
        accumulate(src, field, sharp, gainScale)
        frames++
    }

    /** Each tile's offset replaced by the median of its 3×3 neighbourhood (per axis). */
    internal fun medianFiltered(f: AlignmentField): AlignmentField {
        fun filter(v: IntArray) = IntArray(v.size) { i ->
            val tx = i % tilesX; val ty = i / tilesX
            val near = ArrayList<Int>(9)
            for (oy in -1..1) for (ox in -1..1) {
                val nx = tx + ox; val ny = ty + oy
                if (nx in 0 until tilesX && ny in 0 until tilesY) near.add(v[ny * tilesX + nx])
            }
            near.sort(); near[near.size / 2]
        }
        return AlignmentField(tilesX, tilesY, filter(f.dx), filter(f.dy))
    }

    /** Box-averaged |Laplacian| of the aligned proxy, raised to [POWER] (the per-cell weight). */
    internal fun sharpness(p: Plane, field: AlignmentField): FloatArray {
        val w = proxyWidth; val h = proxyHeight
        val tileW = max(1, w / tilesX); val tileH = max(1, h / tilesY)
        fun aligned(x: Int, y: Int): Int {
            val tx = min(tilesX - 1, x / tileW); val ty = min(tilesY - 1, y / tileH)
            val i = ty * tilesX + tx
            val sx = (x + field.dx[i]).coerceIn(0, w - 1); val sy = (y + field.dy[i]).coerceIn(0, h - 1)
            return p.data[sy * w + sx].toInt() and 0xFF
        }
        // Laplacian into an integral image, then the box mean.
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                val c = aligned(x, y)
                val lap = if (x in 1 until w - 1 && y in 1 until h - 1)
                    abs(4 * c - aligned(x - 1, y) - aligned(x + 1, y) - aligned(x, y - 1) - aligned(x, y + 1)).toDouble()
                else 0.0
                row += lap
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
            val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
            val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            val mean = (s / ((x1 - x0) * (y1 - y0))).toFloat() + EPSILON
            val m2 = mean * mean
            out[y * w + x] = m2 * m2 // POWER = 4
        }
        return out
    }

    private fun accumulate(src: CfaSource, field: AlignmentField, sharp: FloatArray, gainScale: Float) {
        val w = proxyWidth; val h = proxyHeight
        val tileW = max(1, w / tilesX); val tileH = max(1, h / tilesY)
        for (py in 0 until h) for (px in 0 until w) {
            val tx = min(tilesX - 1, px / tileW); val ty = min(tilesY - 1, py / tileH)
            val i = ty * tilesX + tx
            val cell = py * w + px
            val wt = sharp[cell]
            weight[cell] += wt
            // Even CFA shift: twice the proxy offset keeps every sample on its own colour.
            val sx = ((px + field.dx[i]).coerceIn(0, w - 1)) * 2
            val sy = ((py + field.dy[i]).coerceIn(0, h - 1)) * 2
            for (dy in 0..1) for (dx in 0..1) {
                val x = px * 2 + dx; val y = py * 2 + dy
                val b = profile.blackAt(x, y)
                val raw = src.at(sx + dx, sy + dy)
                val v = b + (raw - b) / gainScale
                val at = y * width + x
                sum.put(at, sum.get(at) + wt * v)
            }
        }
    }

    /** The stacked CFA, 16-bit little-endian, in the layout the develop reads. */
    fun finish(): ByteBuffer {
        val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val cell = min(proxyHeight - 1, y / 2) * proxyWidth + min(proxyWidth - 1, x / 2)
            val wt = weight[cell]
            val v = if (wt > 0f) sum.get(y * width + x) / wt else profile.blackAt(x, y).toFloat()
            out.putShort((y * width + x) * 2, v.toInt().coerceIn(0, profile.whiteLevel).toShort())
        }
        return out
    }

    companion object {
        const val DEFAULT_RADIUS = 6
        const val TILE_TARGET = 32
        const val POWER = 4
        private const val EPSILON = 0.5f

        /** Frames in a stack, and how they are spread: uniform in diopters, near to far. */
        const val FRAMES = 8

        /** Focus distances for a stack, nearest first, uniform in diopters (depth of field is roughly constant there). */
        fun distances(minFocusDiopters: Float, frames: Int = FRAMES): List<Float> {
            require(frames >= 2 && minFocusDiopters > 0f)
            return List(frames) { i -> minFocusDiopters * (1f - i.toFloat() / (frames - 1)) }
        }

        /** A ring slot's direct buffer as a [CfaSource]. */
        fun source(buffer: ByteBuffer, rowStride: Int): CfaSource {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            return CfaSource { x, y -> b.getShort(y * rowStride + x * 2).toInt() and 0xFFFF }
        }
    }
}
