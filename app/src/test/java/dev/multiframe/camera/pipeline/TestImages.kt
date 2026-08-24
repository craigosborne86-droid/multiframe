package dev.multiframe.camera.pipeline

import kotlin.random.Random

/** Deterministic synthetic imagery so the tests are reproducible. */
object TestImages {

    /** Random noise smoothed into blobs, giving texture at several scales. */
    fun texture(width: Int, height: Int, seed: Int = 7): ByteArray {
        val rnd = Random(seed)
        var buf = FloatArray(width * height) { rnd.nextFloat() * 255f }
        repeat(3) { buf = boxBlur(buf, width, height) }

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in buf) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).coerceAtLeast(1e-3f)

        return ByteArray(width * height) {
            (((buf[it] - lo) / span) * 200f + 28f).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val sy = (y + dy).coerceIn(0, h - 1)
                    sum += src[sy * w + sx]; n++
                }
                out[y * w + x] = sum / n
            }
        }
        return out
    }

    /** Returns a copy whose content is displaced by (sx, sy), edges clamped. */
    fun shift(src: ByteArray, w: Int, h: Int, sx: Int, sy: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val srcX = (x - sx).coerceIn(0, w - 1)
                val srcY = (y - sy).coerceIn(0, h - 1)
                out[y * w + x] = src[srcY * w + srcX]
            }
        }
        return out
    }

    fun addNoise(src: ByteArray, sigma: Float, seed: Int): ByteArray {
        val rnd = Random(seed)
        return ByteArray(src.size) {
            val g = gaussian(rnd) * sigma
            ((src[it].toInt() and 0xFF) + g).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun gaussian(rnd: Random): Float {
        var u = 0f
        var v = 0f
        var s = 0f
        while (s <= 0f || s >= 1f) {
            u = rnd.nextFloat() * 2f - 1f
            v = rnd.nextFloat() * 2f - 1f
            s = u * u + v * v
        }
        return u * kotlin.math.sqrt(-2f * kotlin.math.ln(s) / s)
    }

    fun frame(luma: ByteArray, w: Int, h: Int): YuvFrame {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val flat = ByteArray(cw * ch) { 128.toByte() }
        return YuvFrame(w, h, luma, flat, flat.copyOf(), 0L)
    }
}
