package dev.multiframe.camera.pipeline

/** Sensor evidence of clipping, before shading, balance, matrix or exposure.
 * All colours must approach the ceiling; a saturated red alone is not white.
 * Applied after the global linear tone chain so neither the meter nor local
 * compression can turn a fully clipped source grey. REFERENCE §3.1 step 7d.
 */
object SensorSaturation {
    const val START = 0.70f

    fun weight(minChannel: Float): Float {
        val t = ((minChannel - START) / (1f - START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A sliding CFA cell witnesses R, both greens and B without demosaicing. */
    fun cfaWeight(frame: BayerFrame, sensor: SensorProfile, x: Int, y: Int): Float {
        if (frame.width < 2 || frame.height < 2) return 0f
        val sx = minOf(x, frame.width - 2)
        val sy = minOf(y, frame.height - 2)
        var level = 1f
        for (dy in 0..1) for (dx in 0..1) {
            val black = sensor.blackAt(sx + dx, sy + dy)
            val raw = frame.data[(sy + dy) * frame.width + sx + dx].toInt() and 0xffff
            level = minOf(level, (raw - black).toFloat() / maxOf(1, sensor.whiteLevel - black))
        }
        return weight(level)
    }

    fun canvasWeight(rgb16: ShortArray, offset: Int, black: FloatArray, white: Int): Float {
        var level = 1f
        for (c in 0..2) level = minOf(level,
            ((rgb16[offset + c].toInt() and 0xffff) - black[c]) / maxOf(1f, white - black[c]))
        return weight(level)
    }

    fun neutralise(rgb: FloatArray, weight: Float) {
        if (weight <= 0f) return
        for (c in 0..2) rgb[c] += (1f - rgb[c]) * weight
    }
}
