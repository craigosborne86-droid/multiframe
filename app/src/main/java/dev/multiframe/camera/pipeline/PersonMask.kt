package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The person mask's two uses (Labs, opt-in; [AnalysisTask.PERSON]).
 *
 * ### Moving people from one frame ([mergeWeights], [blendTowardReference])
 *
 * The merge's motion rejection already keeps a moving subject from ghosting:
 * a sample outside its noise envelope loses its weight. On a person it can
 * fail softly -- a slow arm is inside the envelope in one frame and outside
 * in the next, and a soft double edge survives. Where the model marks a
 * person *and* the merged picture measurably differs from the reference
 * there (the motion evidence: the relative difference of the two pictures'
 * cells), the merged CFA is blended back toward the reference frame's own
 * samples. Those pixels then carry one frame's noise instead of the merge's,
 * which is the honest cost: every value is still a measured sample, from the
 * frame the photographer pressed for. A person who stood still is merged as
 * before.
 *
 * ### The simulated background blur ([PortraitBlur])
 *
 * See there; it is the one opt-in that changes the picture's look, and the
 * photograph says *simulated*.
 */
object PersonMask {
    const val WIDTH = 256
    const val HEIGHT = 192
    /** Relative cell difference over which the motion evidence goes from nothing to all. */
    const val MOTION_FROM = 0.04f
    const val MOTION_TO = 0.12f

    /** The model's input from camera-RGB cells: balanced, through the matrix, stretched and encoded. */
    fun input(cells: FloatArray, color: ColorProfile): FloatArray =
        CfaThumbnail.modelInput(CfaThumbnail.linearSrgb(cells, color))

    /**
     * The blend weight per cell: the person probability times the motion
     * evidence between the reference's and the merge's cells (both camera
     * RGB at WIDTH × HEIGHT).
     */
    fun mergeWeights(person: FloatArray, reference: FloatArray, merged: FloatArray): FloatArray =
        FloatArray(WIDTH * HEIGHT) { i ->
            val r = reference[i * 3] + reference[i * 3 + 1] + reference[i * 3 + 2]
            val m = merged[i * 3] + merged[i * 3 + 1] + merged[i * 3 + 2]
            val rel = abs(r - m) / max(1e-4f, 0.5f * (r + m))
            val t = ((rel - MOTION_FROM) / (MOTION_TO - MOTION_FROM)).coerceIn(0f, 1f)
            person[i].coerceIn(0f, 1f) * t * t * (3f - 2f * t)
        }

    /**
     * `merged ← w·reference + (1 − w)·merged` per CFA site, w bilinear from
     * [weights] (WIDTH × HEIGHT over the frame). Both 16-bit LE; [reference]
     * at [refStride] bytes a row, [merged] packed. The merge holds the
     * reference's scale (gain 1), so the two are the same units.
     */
    fun blendTowardReference(merged: ByteBuffer, reference: ByteBuffer, refStride: Int, width: Int, height: Int, weights: FloatArray) {
        val out = merged.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val ref = reference.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val xs = IntArray(width); val xw = FloatArray(width)
        for (x in 0 until width) {
            val fx = ((x + 0.5f) * WIDTH / width - 0.5f).coerceIn(0f, WIDTH - 1f)
            xs[x] = fx.toInt(); xw[x] = fx - xs[x]
        }
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * HEIGHT / height - 0.5f).coerceIn(0f, HEIGHT - 1f)
            val y0 = fy.toInt(); val y1 = min(HEIGHT - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until width) {
                val x0 = xs[x]; val x1 = min(WIDTH - 1, x0 + 1); val wx = xw[x]
                val w = (weights[y0 * WIDTH + x0] * (1 - wx) + weights[y0 * WIDTH + x1] * wx) * (1 - wy) +
                    (weights[y1 * WIDTH + x0] * (1 - wx) + weights[y1 * WIDTH + x1] * wx) * wy
                if (w <= 1e-3f) continue
                val i = (y * width + x) * 2
                val m = out.getShort(i).toInt() and 0xFFFF
                val r = ref.getShort(y * refStride + x * 2).toInt() and 0xFFFF
                out.putShort(i, (w * r + (1 - w) * m).roundToInt().coerceIn(0, 0xFFFF).toShort())
            }
        }
    }

    /** A copy of a strided raw frame, packed, for a blend after its ring slot has gone back. */
    fun copyPacked(frame: ByteBuffer, rowStride: Int, width: Int, height: Int): ByteBuffer {
        val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val src = frame.duplicate()
        for (y in 0 until height) {
            src.limit(y * rowStride + width * 2).position(y * rowStride)
            out.put(src)
            src.limit(src.capacity())
        }
        out.rewind()
        return out
    }
}

/**
 * The simulated background blur (Labs, off by default, present only with a
 * person model bundled).
 *
 * Outside the person mask each pixel becomes the average of its measured
 * neighbours -- three box passes, a near-Gaussian of radius [radiusFor] --
 * and inside it the pixel is untouched; the mask's soft edge mixes the two.
 * Nothing is invented: every output is a weighted mean of pixels the sensor
 * saw. But the blur is not the lens's -- a real shallow focus grows with
 * distance from the focal plane and this does not -- so the photograph's
 * report and its Content Credentials call it *simulated*
 * ([AnalysisUse.PORTRAIT_ROLE], a `c2pa.filtered` action).
 *
 * Position in the develop (REFERENCE §3.1): after the sharpen, before the
 * optics, on the developed picture in sensor orientation -- the frame the
 * mask was computed in. Blurred at a quarter size and sampled back
 * bilinearly, so the cost is a fraction of the picture's size in memory.
 */
object PortraitBlur {
    fun radiusFor(width: Int): Int = max(2, width / 150)

    fun apply(bitmap: Bitmap, mask: FloatArray, maskWidth: Int, maskHeight: Int) {
        val w = bitmap.width; val h = bitmap.height
        val sw = max(1, w / 4); val sh = max(1, h / 4)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val radius = max(1, radiusFor(w) / 4)
        val channels = Array(3) { c -> FloatArray(sw * sh) { ((px[it] shr (16 - 8 * c)) and 0xFF).toFloat() } }
        for (ch in channels) blurChannel(ch, sw, sh, radius)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, sh - 1f)
            val sy0 = fy.toInt(); val sy1 = min(sh - 1, sy0 + 1); val ty = fy - sy0
            val my = ((y + 0.5f) * maskHeight / h - 0.5f).coerceIn(0f, maskHeight - 1f)
            val my0 = my.toInt(); val my1 = min(maskHeight - 1, my0 + 1); val tmy = my - my0
            for (x in 0 until w) {
                val mx = ((x + 0.5f) * maskWidth / w - 0.5f).coerceIn(0f, maskWidth - 1f)
                val mx0 = mx.toInt(); val mx1 = min(maskWidth - 1, mx0 + 1); val tmx = mx - mx0
                val keep = ((mask[my0 * maskWidth + mx0] * (1 - tmx) + mask[my0 * maskWidth + mx1] * tmx) * (1 - tmy) +
                    (mask[my1 * maskWidth + mx0] * (1 - tmx) + mask[my1 * maskWidth + mx1] * tmx) * tmy).coerceIn(0f, 1f)
                if (keep >= 0.999f) continue
                val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, sw - 1f)
                val sx0 = fx.toInt(); val sx1 = min(sw - 1, sx0 + 1); val tx = fx - sx0
                val p = row[x]
                var out = p and 0xFF000000.toInt()
                for (c in 0 until 3) {
                    val ch = channels[c]
                    val blurred = (ch[sy0 * sw + sx0] * (1 - tx) + ch[sy0 * sw + sx1] * tx) * (1 - ty) +
                        (ch[sy1 * sw + sx0] * (1 - tx) + ch[sy1 * sw + sx1] * tx) * ty
                    val orig = (p shr (16 - 8 * c)) and 0xFF
                    val v = (keep * orig + (1 - keep) * blurred).roundToInt().coerceIn(0, 255)
                    out = out or (v shl (16 - 8 * c))
                }
                row[x] = out
            }
            bitmap.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** Three box passes in each direction: a near-Gaussian of the measured values, in place. */
    internal fun blurChannel(ch: FloatArray, w: Int, h: Int, radius: Int) =
        repeat(3) { boxH(ch, w, h, radius); boxV(ch, w, h, radius) }

    private fun boxH(ch: FloatArray, w: Int, h: Int, r: Int) {
        val line = FloatArray(w)
        for (y in 0 until h) {
            val o = y * w
            var s = 0f
            for (x in -r..r) s += ch[o + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                line[x] = s / (2 * r + 1)
                s += ch[o + (x + r + 1).coerceIn(0, w - 1)] - ch[o + (x - r).coerceIn(0, w - 1)]
            }
            System.arraycopy(line, 0, ch, o, w)
        }
    }

    private fun boxV(ch: FloatArray, w: Int, h: Int, r: Int) {
        val line = FloatArray(h)
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += ch[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                line[y] = s / (2 * r + 1)
                s += ch[(y + r + 1).coerceIn(0, h - 1) * w + x] - ch[(y - r).coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) ch[y * w + x] = line[y]
        }
    }
}
