package dev.multiframe.camera.pipeline

import kotlin.random.Random

/** Synthetic CFA imagery with known per-colour levels, so plane mixing is detectable. */
object TestBayer {

    val PROFILE = SensorProfile.DEFAULT           // GBRG, black 64, white 1023

    /** Distinct level per colour so any cross-contamination shows up as a shift. */
    val LEVEL = mapOf(0 to 320, 1 to 560, 2 to 210)   // red, green, blue

    fun cfaAt(x: Int, y: Int): Int =
        PROFILE.cfaPattern[(y and 1) * 2 + (x and 1)]

    /**
     * A scene with smooth texture (so alignment has something to lock onto)
     * modulating each colour around its own level.
     */
    fun scene(width: Int, height: Int, seed: Int = 11): BayerFrame {
        val rnd = Random(seed)
        var field = FloatArray(width * height) { rnd.nextFloat() }
        repeat(5) { field = blur(field, width, height) }

        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in field) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).coerceAtLeast(1e-4f)

        val data = ShortArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val t = (field[y * width + x] - lo) / span            // 0..1
            val base = LEVEL[cfaAt(x, y)]!!
            val v = base + ((t - 0.5f) * 260f)
            data[y * width + x] = v.toInt().coerceIn(64, 1023).toShort()
        }
        return BayerFrame(width, height, data)
    }

    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val sx = (x + dx).coerceIn(0, w - 1)
                val sy = (y + dy).coerceIn(0, h - 1)
                s += src[sy * w + sx]; n++
            }
            out[y * w + x] = s / n
        }
        return out
    }

    /** Shifts content by (sx, sy). Must be even to keep the CFA phase intact. */
    fun shift(f: BayerFrame, sx: Int, sy: Int): BayerFrame {
        val out = ShortArray(f.width * f.height)
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val srcX = (x - sx).coerceIn(0, f.width - 1)
            val srcY = (y - sy).coerceIn(0, f.height - 1)
            out[y * f.width + x] = f.data[srcY * f.width + srcX]
        }
        return BayerFrame(f.width, f.height, out)
    }

    fun addNoise(f: BayerFrame, sigma: Float, seed: Int): BayerFrame {
        val rnd = Random(seed)
        val out = ShortArray(f.data.size)
        for (i in f.data.indices) {
            val v = (f.data[i].toInt() and 0xFFFF) + gaussian(rnd) * sigma
            out[i] = v.toInt().coerceIn(0, 1023).toShort()
        }
        return BayerFrame(f.width, f.height, out)
    }

    private fun gaussian(rnd: Random): Float {
        var u: Float; var v: Float; var s: Float
        do {
            u = rnd.nextFloat() * 2f - 1f
            v = rnd.nextFloat() * 2f - 1f
            s = u * u + v * v
        } while (s <= 0f || s >= 1f)
        return u * kotlin.math.sqrt(-2f * kotlin.math.ln(s) / s)
    }

    /** Mean value at each CFA colour, used to detect plane mixing. */
    fun meansByColour(f: BayerFrame, inset: Int = 12): Map<Int, Double> {
        val sums = DoubleArray(3); val counts = LongArray(3)
        for (y in inset until f.height - inset) for (x in inset until f.width - inset) {
            val c = cfaAt(x, y)
            sums[c] += (f.data[y * f.width + x].toInt() and 0xFFFF).toDouble()
            counts[c]++
        }
        return (0..2).associateWith { if (counts[it] == 0L) 0.0 else sums[it] / counts[it] }
    }
}
