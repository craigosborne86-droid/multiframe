package dev.multiframe.camera.pipeline

import kotlin.math.max

/**
 * Which frame the merge aligns the others to (Labs, opt-in).
 *
 * The default is the newest frame, the one nearest the press ([ZslCapture]).
 * A hand that jerked during that one exposure blurs the reference, and every
 * other frame is then aligned onto a blurred picture -- the merge can reject
 * what moved, but it cannot sharpen the frame everything is measured against.
 * The choice here looks at the newest [CANDIDATES] frames and takes the
 * sharpest, and keeps the newest unless another is clearly sharper, so a
 * steady burst behaves exactly as the default does.
 *
 *  - [ReferenceChoice.SHARPEST], deterministic: the gradient energy (the
 *    Tenengrad) of the luma proxy's centre, over its mean brightness squared
 *    so a frame at another gain is judged on its detail, not its level.
 *  - [ReferenceChoice.MODEL]: a model's estimate of each frame's blur in
 *    proxy pixels, from the proxy's centre at full resolution
 *    ([AnalysisTask.FRAME]). Its training label is the gyro's measured
 *    rotation over the exposure times the lens's pixels per radian.
 *
 * Not under dual conversion gain, where the reference is the lowest-ISO frame
 * for its highlights, nor for a long exposure or astro, whose reference is the
 * first frame by design.
 */
object ReferenceChooser {
    const val CANDIDATES = 3
    /** Another frame must have this much more gradient energy than the newest. */
    const val SHARPER_BY = 1.15f
    /** Or, under the model, this many proxy pixels less blur. */
    const val LESS_BLUR_PX = 0.5f

    /** Gradient energy over the central four fifths, sampled every other pixel, over mean². */
    fun sharpness(p: Plane): Float {
        val x0 = p.width / 10; val x1 = p.width - x0 - 1
        val y0 = p.height / 10; val y1 = p.height - y0 - 1
        var energy = 0.0; var level = 0.0; var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            val row = y * p.width
            while (x < x1) {
                val v = p.data[row + x].toInt() and 0xFF
                val gx = (p.data[row + x + 1].toInt() and 0xFF) - v
                val gy = (p.data[row + p.width + x].toInt() and 0xFF) - v
                energy += gx * gx + gy * gy
                level += v
                n++
                x += 2
            }
            y += 2
        }
        if (n == 0) return 0f
        val mean = level / n
        return (energy / n / max(1.0, mean * mean)).toFloat()
    }

    /** Index of the sharpest by [SHARPER_BY] over index 0, which wins ties. */
    fun bySharpness(scores: FloatArray): Int {
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[0] * SHARPER_BY && scores[i] > scores[best]) best = i
        return best
    }

    /** Index of the least blurred by [LESS_BLUR_PX] under index 0, which wins ties. */
    fun byBlur(blurPx: FloatArray): Int {
        var best = 0
        for (i in 1 until blurPx.size) if (blurPx[i] < blurPx[0] - LESS_BLUR_PX && blurPx[i] < blurPx[best]) best = i
        return best
    }

    /** The frame model's input: the proxy's central WIDTH × HEIGHT at full resolution, stretched. */
    object FrameContract {
        const val WIDTH = 256
        const val HEIGHT = 192

        fun input(p: Plane): FloatArray {
            val ox = max(0, (p.width - WIDTH) / 2); val oy = max(0, (p.height - HEIGHT) / 2)
            val crop = FloatArray(WIDTH * HEIGHT) { i ->
                val x = (ox + i % WIDTH).coerceAtMost(p.width - 1); val y = (oy + i / WIDTH).coerceAtMost(p.height - 1)
                (p.data[y * p.width + x].toInt() and 0xFF).toFloat()
            }
            return ModelImage.stretch(crop, 1f)
        }
    }
}
