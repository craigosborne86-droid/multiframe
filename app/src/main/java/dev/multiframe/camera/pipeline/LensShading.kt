package dev.multiframe.camera.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/**
 * Per-channel gain across the frame, correcting the lens's own falloff.
 *
 * ### What this fixes
 *
 * Every lens delivers less light to the corners than to the centre. On a phone,
 * where the optics are tiny and the rays strike the sensor at steep angles near
 * the edge, the falloff is severe -- typically one to two stops -- and it is not
 * neutral: the colour filters and microlenses respond differently by angle, so
 * the corners are not merely darker but a different colour. Left alone that
 * reads as a dingy, colour-shifted border on every photograph.
 *
 * ### Why the app has to do it
 *
 * The ISP corrects this for JPEG output, but **raw is defined as uncorrected**.
 * A raw-first pipeline therefore inherits the problem: the burst merge, the
 * demosaic and the tone curve all operate on data that is a stop and a half
 * down in the corners. The DNG is right to stay uncorrected, since converters
 * apply the map themselves from metadata, but the JPEG this app develops has to
 * apply it or it will look worse than the camera's own output for a reason that
 * has nothing to do with the merge.
 *
 * The camera reports the map per capture, on a coarse grid -- typically about
 * 17 by 13 cells -- which is interpolated across the frame.
 */
class ShadingMap(
    val columns: Int,
    val rows: Int,
    /** Row-major cells, four channels each, in the sensor's CFA order. */
    val gains: FloatArray,
) {

    init {
        require(gains.size == columns * rows * 4) { "map size does not match its grid" }
    }

    /** Gain for one channel at a grid cell. */
    fun cell(column: Int, row: Int, channel: Int): Float {
        val c = column.coerceIn(0, columns - 1)
        val r = row.coerceIn(0, rows - 1)
        return gains[(r * columns + c) * 4 + channel.coerceIn(0, 3)]
    }

    /**
     * Gain at a pixel, bilinearly interpolated.
     *
     * Interpolation is not a nicety. A 17-cell grid stretched across 4080
     * pixels means each cell covers 240 pixels; sampling it as nearest
     * neighbour would put visible steps of several percent brightness straight
     * across the sky.
     */
    fun gainAt(x: Int, y: Int, width: Int, height: Int, channel: Int): Float {
        if (width <= 1 || height <= 1) return 1f

        // Cell centres sit at the middle of each cell, so a pixel maps to the
        // grid offset by half a cell.
        val fx = (x.toFloat() / (width - 1)) * (columns - 1)
        val fy = (y.toFloat() / (height - 1)) * (rows - 1)

        val x0 = fx.toInt().coerceIn(0, columns - 1)
        val y0 = fy.toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(columns - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)

        val top = cell(x0, y0, channel) * (1f - tx) + cell(x1, y0, channel) * tx
        val bottom = cell(x0, y1, channel) * (1f - tx) + cell(x1, y1, channel) * tx
        return top * (1f - ty) + bottom * ty
    }

    /**
     * Channel index for a pixel position.
     *
     * The map's four channels follow the sensor's own CFA order, so the channel
     * for a pixel is simply its position within the 2x2 cell.
     */
    fun channelFor(x: Int, y: Int): Int = (y and 1) * 2 + (x and 1)

    /**
     * The map with three gains per cell rather than four, for an image whose
     * pixels are colours rather than sites.
     *
     * The camera reports the four channels in the order red, green on even
     * rows, green on odd rows, blue. The sub-pixel canvas has folded both greens
     * into one channel, so the green gain is their mean -- they differ by the
     * sensor's green imbalance, which is a fraction of a percent and well below
     * what shading correction is for.
     */
    fun rgbGrid(): FloatArray {
        val out = FloatArray(columns * rows * 3)
        for (cell in 0 until columns * rows) {
            out[cell * 3] = gains[cell * 4]
            out[cell * 3 + 1] = 0.5f * (gains[cell * 4 + 1] + gains[cell * 4 + 2])
            out[cell * 3 + 2] = gains[cell * 4 + 3]
        }
        return out
    }

    /** Largest gain anywhere, which is how strong the vignetting is. */
    val peakGain: Float get() = gains.max()

    /** Corner falloff in stops, for reporting. */
    val falloffStops: Float
        get() {
            val centre = cell(columns / 2, rows / 2, 0).coerceAtLeast(1e-3f)
            return (kotlin.math.ln((peakGain / centre).toDouble()) /
                kotlin.math.ln(2.0)).toFloat()
        }

    companion object {
        /**
         * [gainAt] on a three-channel grid from [rgbGrid]. The same arithmetic
         * as the four-channel version, and the reference the native linear
         * develop's shadingGain3 is held to.
         */
        fun rgbGainAt(
            grid: FloatArray, columns: Int, rows: Int,
            x: Int, y: Int, width: Int, height: Int, channel: Int,
        ): Float {
            if (columns <= 0 || rows <= 0 || width <= 1 || height <= 1) return 1f
            val fx = (x.toFloat() / (width - 1)) * (columns - 1)
            val fy = (y.toFloat() / (height - 1)) * (rows - 1)
            val x0 = fx.toInt().coerceIn(0, columns - 1)
            val y0 = fy.toInt().coerceIn(0, rows - 1)
            val x1 = minOf(x0 + 1, columns - 1)
            val y1 = minOf(y0 + 1, rows - 1)
            val tx = (fx - x0).coerceIn(0f, 1f)
            val ty = (fy - y0).coerceIn(0f, 1f)
            fun cell(c: Int, r: Int) = grid[(r * columns + c) * 3 + channel]
            val top = cell(x0, y0) * (1f - tx) + cell(x1, y0) * tx
            val bottom = cell(x0, y1) * (1f - tx) + cell(x1, y1) * tx
            return top * (1f - ty) + bottom * ty
        }

        /** A map that changes nothing, for devices that report none. */
        fun identity(columns: Int = 3, rows: Int = 3) =
            ShadingMap(columns, rows, FloatArray(columns * rows * 4) { 1f })

        /**
         * Reads the map the camera measured for this capture, or null when the
         * device does not report one.
         *
         * Null is a legitimate answer: shading map reporting is optional in the
         * Camera2 spec, and a device without it should render an uncorrected
         * frame rather than a wrongly corrected one.
         */
        fun from(result: TotalCaptureResult?): ShadingMap? {
            val map = result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
                ?: return null
            val columns = map.columnCount
            val rows = map.rowCount
            if (columns <= 0 || rows <= 0) return null

            val gains = FloatArray(columns * rows * 4)
            for (r in 0 until rows) {
                for (c in 0 until columns) {
                    for (channel in 0 until 4) {
                        gains[(r * columns + c) * 4 + channel] =
                            map.getGainFactor(channel, c, r)
                    }
                }
            }
            return ShadingMap(columns, rows, gains)
        }
    }
}
