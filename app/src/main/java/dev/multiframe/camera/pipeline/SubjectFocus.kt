package dev.multiframe.camera.pipeline

import kotlin.math.exp

/**
 * Where autofocus and metering look when the photographer has not tapped
 * (Labs, opt-in; [AnalysisTask.SUBJECT]).
 *
 * The default is the camera's own whole-frame autofocus and metering, and a
 * tap always outranks everything. With the subject model on, the newest
 * frame's luma ([ZslRawStream.trackingLuma], sensor orientation, the whole
 * array) is read about once a second, the model returns a heatmap of where a
 * subject is, and where its peak is confident the AF and AE regions are put
 * there -- the region a tap would have set, with no trigger, so continuous
 * autofocus follows it. The model chooses a rectangle the camera meters on;
 * it touches no pixel.
 *
 * ### The label
 *
 * The photographer's own taps: when collection is on, each tap-to-focus
 * saves the frame's luma as the model sees it and a Gaussian at the point
 * tapped. Where people tap is what the model learns to predict.
 */
object SubjectFocus {
    const val IN_W = 256
    const val IN_H = 192
    const val OUT_W = 64
    const val OUT_H = 48
    /** The heatmap's peak must reach this for the region to move. */
    const val CONFIDENT = 0.5f
    /** The label's spread, in heatmap cells. */
    const val LABEL_SIGMA = 2.5f
    const val INTERVAL_MS = 1000L

    /** The luma as read from the ring (bytes, IN_W × IN_H), stretched. */
    fun input(luma: ByteArray): FloatArray =
        ModelImage.stretch(FloatArray(IN_W * IN_H) { (luma[it].toInt() and 0xFF).toFloat() }, 1f)

    /**
     * The heatmap's peak after a 3×3 mean, as a fraction of the sensor array
     * (x, y in 0..1), or null where it is not confident.
     */
    fun peak(heat: FloatArray): Pair<Float, Float>? {
        var best = -1f; var bx = 0; var by = 0
        for (y in 0 until OUT_H) for (x in 0 until OUT_W) {
            var s = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until OUT_W && yy in 0 until OUT_H) { s += heat[yy * OUT_W + xx]; n++ }
            }
            val m = s / n
            if (m > best) { best = m; bx = x; by = y }
        }
        if (best < CONFIDENT) return null
        return Pair((bx + 0.5f) / OUT_W, (by + 0.5f) / OUT_H)
    }

    /** The training label for a tap at a fraction of the sensor array. */
    fun label(sensorX: Float, sensorY: Float): FloatArray {
        val cx = sensorX * OUT_W - 0.5f; val cy = sensorY * OUT_H - 0.5f
        val k = 1f / (2f * LABEL_SIGMA * LABEL_SIGMA)
        return FloatArray(OUT_W * OUT_H) { i ->
            val dx = i % OUT_W - cx; val dy = i / OUT_W - cy
            exp(-(dx * dx + dy * dy) * k)
        }
    }
}
