package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The learned white balance's contract (Labs, opt-in): what the model eats and
 * what its three numbers mean.
 *
 * ### Input: the scene's colours as a log-chroma histogram
 *
 * Each cell of the merged CFA's thumbnail ([CfaThumbnail], camera RGB, no
 * balance) is a point (u, v) = (ln g/r, ln g/b). Under a change of illuminant
 * every point moves by the same (Δu, Δv), so estimating the illuminant is
 * finding a translation of this histogram -- the observation behind
 * Barron's convolutional colour constancy, and why the histogram, not the
 * picture, is the input. Two channels, [BINS]² each:
 *
 *  0. the cells, weighted by the square root of their brightness (a bright
 *     neutral says more than a dark one), normalised to sum 1;
 *  1. the same of the cells' local *differences* to their right and lower
 *     neighbours -- the grey-edge cue: edges average to the illuminant's
 *     colour in most scenes even where the surfaces do not.
 *
 * Cells near black or with any channel near the clip are left out: neither
 * says anything about the light.
 *
 * ### Output: the illuminant
 *
 * (u, v) of the illuminant. The neutral is (e^-u, 1, e^-v) in camera
 * coordinates and the white-balance multipliers its reciprocal: three numbers,
 * the same three the camera's own AWB returns. Nothing else of the picture
 * changes; the colour matrix is re-solved for the new neutral exactly as it is
 * for the camera's ([ColorProfile.calibrated]).
 *
 * ### The label
 *
 * Where the photographer set the white balance by hand in PRO, the camera's
 * result reports that neutral: the photographer's own judgement of the light,
 * measured, collected when collection is on ([AnalysisTask.AWB]).
 */
object AwbContract {
    const val BINS = 64
    /** u and v of the histogram's first bin, and its width in log units. */
    const val LOG_MIN = -0.5f
    const val BIN_WIDTH = 1f / 32f
    const val THUMB_W = 128
    const val THUMB_H = 96

    private const val DARK = 0.004f
    private const val CLIP = 0.95f

    fun histogram(cells: FloatArray, ow: Int, oh: Int): FloatArray {
        val out = FloatArray(BINS * BINS * 2)
        fun splat(r: Float, g: Float, b: Float, weight: Float, channel: Int) {
            if (r <= 0f || g <= 0f || b <= 0f) return
            val u = ln(g / r); val v = ln(g / b)
            val iu = ((u - LOG_MIN) / BIN_WIDTH).toInt(); val iv = ((v - LOG_MIN) / BIN_WIDTH).toInt()
            if (iu !in 0 until BINS || iv !in 0 until BINS) return
            out[(iv * BINS + iu) * 2 + channel] += weight
        }
        fun usable(i: Int): Boolean {
            val r = cells[i * 3]; val g = cells[i * 3 + 1]; val b = cells[i * 3 + 2]
            return g > DARK && r < CLIP && g < CLIP && b < CLIP
        }
        for (y in 0 until oh) for (x in 0 until ow) {
            val i = y * ow + x
            if (!usable(i)) continue
            val r = cells[i * 3]; val g = cells[i * 3 + 1]; val b = cells[i * 3 + 2]
            splat(r, g, b, sqrt(g), 0)
            for (j in listOfNotNull(if (x + 1 < ow) i + 1 else null, if (y + 1 < oh) i + ow else null)) {
                if (!usable(j)) continue
                splat(abs(r - cells[j * 3]), abs(g - cells[j * 3 + 1]), abs(b - cells[j * 3 + 2]), 1f, 1)
            }
        }
        for (c in 0 until 2) {
            var s = 0f
            for (k in 0 until BINS * BINS) s += out[k * 2 + c]
            if (s > 0f) for (k in 0 until BINS * BINS) out[k * 2 + c] /= s
        }
        return out
    }

    /** The model's (u, v), kept inside the histogram's span, as a camera neutral (r/g, 1, b/g). */
    fun neutral(uv: FloatArray): FloatArray {
        val hi = LOG_MIN + BINS * BIN_WIDTH
        return floatArrayOf(exp(-uv[0].coerceIn(LOG_MIN, hi)), 1f, exp(-uv[1].coerceIn(LOG_MIN, hi)))
    }

    /** The label for a camera neutral: the illuminant's (u, v). */
    fun label(neutral: FloatArray): FloatArray = floatArrayOf(-ln(neutral[0] / neutral[1]), -ln(neutral[2] / neutral[1]))

    /** The model's neutral for a merged CFA, or null where it declined. */
    fun estimate(model: AnalysisModel, cells: FloatArray): FloatArray? = runCatching {
        neutral(model.run(histogram(cells, THUMB_W, THUMB_H)))
    }.getOrNull()
}
