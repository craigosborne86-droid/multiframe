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

/**
 * Ground-truth imagery for demosaic testing.
 *
 * A demosaic can only be judged against the full-colour image it is trying to
 * recover, so these build an RGB scene, sample it down to a CFA exactly as a
 * sensor would, and keep the original to compare against.
 */
object TestDemosaic {

    /** Linear RGB, three floats per pixel, values 0..1. */
    class Rgb(val width: Int, val height: Int, val data: FloatArray) {
        fun at(x: Int, y: Int, c: Int): Float = data[(y * width + x) * 3 + c]
    }

    /**
     * A scene built to punish a demosaic: a hard diagonal edge, a saturated
     * colour boundary, fine one-pixel lines near the sampling limit, and a
     * smooth gradient that must not gain texture.
     */
    fun scene(width: Int, height: Int): Rgb {
        val data = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                val fx = x.toFloat() / width
                val fy = y.toFloat() / height

                var r: Float; var g: Float; var b: Float
                if (x + y < (width + height) / 3) {
                    // Bright neutral field.
                    r = 0.72f; g = 0.72f; b = 0.72f
                } else if (fx > 0.62f && fy > 0.55f) {
                    // Saturated colour block: cross-channel errors show here.
                    r = 0.66f; g = 0.18f; b = 0.10f
                } else if (fy > 0.30f && fy < 0.40f && (x % 4) < 2) {
                    // Fine vertical bars, two pixels on, two off.
                    r = 0.58f; g = 0.60f; b = 0.55f
                } else {
                    // Smooth gradient.
                    val t = 0.12f + 0.5f * fx
                    r = t; g = t * 0.97f; b = t * 0.92f
                }
                data[i] = r; data[i + 1] = g; data[i + 2] = b
            }
        }
        return Rgb(width, height, data)
    }

    /** Samples a full-colour image through a CFA, exactly as a sensor does. */
    fun mosaic(rgb: Rgb, profile: SensorProfile = TestBayer.PROFILE): BayerFrame {
        val out = ShortArray(rgb.width * rgb.height)
        val range = profile.range.toFloat()
        for (y in 0 until rgb.height) {
            for (x in 0 until rgb.width) {
                val c = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val lin = rgb.at(x, y, c)
                val raw = profile.blackAt(x, y) + lin * range
                out[y * rgb.width + x] =
                    raw.toInt().coerceIn(0, profile.whiteLevel).toShort()
            }
        }
        return BayerFrame(rgb.width, rgb.height, out)
    }

    /**
     * Root-mean-square error against the truth, over the interior only so the
     * border fallback is not what is being measured.
     */
    fun rmse(
        truth: Rgb,
        frame: BayerFrame,
        profile: SensorProfile,
        inset: Int = 4,
        demosaic: (Int, Int, FloatArray) -> Unit,
    ): Double {
        val px = FloatArray(3)
        var sum = 0.0
        var n = 0L
        for (y in inset until truth.height - inset) {
            for (x in inset until truth.width - inset) {
                demosaic(x, y, px)
                for (c in 0 until 3) {
                    val d = px[c] - truth.at(x, y, c)
                    sum += d.toDouble() * d
                    n++
                }
            }
        }
        return if (n == 0L) 0.0 else kotlin.math.sqrt(sum / n)
    }
}
