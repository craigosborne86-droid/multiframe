package dev.multiframe.camera.pipeline

/** Physical clip evidence before shading, colour conversion or exposure.
 * All colours must approach white in sensor or balanced camera space;
 * a saturated red alone is not white.
 * Applied after the global linear tone chain so neither the meter nor local
 * compression can turn a fully clipped source grey. REFERENCE §3.1 step 7c.
 */
object SensorSaturation {
    const val START = 0.70f

    fun weight(minChannel: Float): Float {
        val t = ((minChannel - START) / (1f - START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A clipped channel plus near-white balanced companions is also unreliable colour.
     * Sensor peak gates this path, so WB alone can never declare a pixel clipped.
     */
    fun evidenceWeight(minChannel: Float, peak: Float, balancedMin: Float): Float {
        val t = ((peak - 0.90f) / 0.10f).coerceIn(0f, 1f)
        val clipped = t * t * (3f - 2f * t)
        return maxOf(weight(minChannel), clipped * weight(balancedMin))
    }

    /** A sliding CFA cell witnesses R, both greens and B without demosaicing. */
    fun cfaWeight(frame: BayerFrame, sensor: SensorProfile, x: Int, y: Int, color: ColorProfile = ColorProfile.NEUTRAL): Float {
        if (frame.width < 2 || frame.height < 2) return 0f
        val sx = minOf(x, frame.width - 2)
        val sy = minOf(y, frame.height - 2)
        var level = 1f
        var peak = 0f
        var balancedMin = 1f
        for (dy in 0..1) for (dx in 0..1) {
            val black = sensor.blackAt(sx + dx, sy + dy)
            val raw = frame.data[(sy + dy) * frame.width + sx + dx].toInt() and 0xffff
            val v = (raw - black).toFloat() / maxOf(1, sensor.whiteLevel - black)
            level = minOf(level, v)
            peak = maxOf(peak, v)
            val site = ((sy + dy) and 1) * 2 + ((sx + dx) and 1)
            balancedMin = minOf(balancedMin, v * color.gainFor(sensor.cfaPattern[site], sy + dy))
        }
        return evidenceWeight(level, peak, balancedMin)
    }

    fun canvasWeight(rgb16: ShortArray, offset: Int, black: FloatArray, white: Int, wb: FloatArray): Float {
        var level = 1f
        var peak = 0f
        var balancedMin = 1f
        for (c in 0..2) {
            val v = ((rgb16[offset + c].toInt() and 0xffff) - black[c]) / maxOf(1f, white - black[c])
            level = minOf(level, v)
            peak = maxOf(peak, v)
            balancedMin = minOf(balancedMin, v * wb[c])
        }
        return evidenceWeight(level, peak, balancedMin)
    }

    fun neutralise(rgb: FloatArray, weight: Float) {
        if (weight <= 0f) return
        for (c in 0..2) rgb[c] += (1f - rgb[c]) * weight
    }
}
