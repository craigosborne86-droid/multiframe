package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max

/**
 * The two opcode lists a DNG of ours carries: the camera's lens shading as
 * GainMap opcodes (OpcodeList2, applied to the raw before demosaic) and its
 * distortion as a WarpRectilinear (OpcodeList3, after). DNG 1.7.1 chapter 7;
 * opcode lists are big-endian whatever the file's byte order.
 *
 * Both are written as `DngCreator` wrote them, so that a converter renders
 * our file as it rendered the September ones: version 1.3.0.0, flagged
 * optional, one GainMap per CFA site in the order red, the green on even
 * rows, the green on odd rows, blue.
 */
object DngOpcodes {

    private const val ID_WARP_RECTILINEAR = 1
    private const val ID_GAIN_MAP = 9
    private const val VERSION_1_3 = 0x01030000
    private const val FLAG_OPTIONAL = 1

    /** An opcode list of [opcodes], each its id and its parameters. */
    fun list(opcodes: List<Pair<Int, ByteArray>>): ByteArray {
        val size = 4 + opcodes.sumOf { 16 + it.second.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        b.putInt(opcodes.size)
        for ((id, body) in opcodes) {
            b.putInt(id).putInt(VERSION_1_3).putInt(FLAG_OPTIONAL).putInt(body.size).put(body)
        }
        return b.array()
    }

    /**
     * OpcodeList2 for [shading] (site order, as [ShadingMap] keeps it) over
     * an image of [width] by [height] with CFA [cfa]: four GainMaps, each
     * over its own site with a pitch of two, the map's corners on the
     * image's. Null for a map too small to interpolate.
     */
    fun gainMaps(shading: ShadingMap, cfa: IntArray, width: Int, height: Int): ByteArray? {
        if (shading.columns < 2 || shading.rows < 2) return null
        val red = cfa.indexOf(0)
        val blue = cfa.indexOf(2)
        val evenGreen = (0..1).first { cfa[it] == 1 }
        val oddGreen = (2..3).first { cfa[it] == 1 }
        val ops = listOf(red, evenGreen, oddGreen, blue).map { site ->
            val points = shading.columns * shading.rows
            val b = ByteBuffer.allocate(76 + points * 4).order(ByteOrder.BIG_ENDIAN)
            b.putInt(site / 2).putInt(site % 2).putInt(height).putInt(width) // top, left, bottom, right
            b.putInt(0).putInt(1) // plane, planes
            b.putInt(2).putInt(2) // row pitch, column pitch
            b.putInt(shading.rows).putInt(shading.columns)
            b.putDouble(1.0 / (shading.rows - 1)).putDouble(1.0 / (shading.columns - 1))
            b.putDouble(0.0).putDouble(0.0) // origin
            b.putInt(1) // map planes
            for (r in 0 until shading.rows) for (c in 0 until shading.columns) {
                b.putFloat(shading.cell(c, r, site))
            }
            ID_GAIN_MAP to b.array()
        }
        return list(ops)
    }

    /**
     * The WarpRectilinear coefficients for a Camera2 lens model, in DNG's
     * normalised units: kr0..kr3, kt0, kt1, then the optical centre as a
     * fraction of the image. [k] is `LENS_DISTORTION` (k1, k2, k3, p1, p2);
     * [fx], [cx], [cy] are from `LENS_INTRINSIC_CALIBRATION` in the pixels of
     * the [width] by [height] array the raw was read from.
     *
     * Camera2's model is in units of the focal length, DNG's in units of the
     * distance from the centre to the farthest corner, `d`: with `m = d / fx`,
     * `kr_i = k_i m^(2i)` and `kt = p m`. Then the whole warp is scaled by a
     * zoom `kr0 <= 1`, the largest in steps of 0.002 that keeps the source of
     * every corner and edge midpoint on the array, so the correction reads no
     * pixel the sensor did not. `DngCreator` scales the same way; on 86 of
     * the 93 September files its zoom is this one and on the other 7 one step
     * smaller -- a fifth of a percent.
     */
    fun warpCoefficients(k: FloatArray, fx: Float, cx: Float, cy: Float, width: Int, height: Int): DoubleArray? {
        if (k.size < 5 || fx <= 0f || width <= 1 || height <= 1) return null
        val x0 = cx.toDouble(); val y0 = cy.toDouble()
        val d = max(max(hypot(x0, y0), hypot(width - x0, y0)), max(hypot(x0, height - y0), hypot(width - x0, height - y0)))
        if (d <= 0.0) return null
        val m = d / fx
        val m2 = m * m
        val kr = doubleArrayOf(1.0, k[0] * m2, k[1] * m2 * m2, k[2] * m2 * m2 * m2)
        val kt = doubleArrayOf(k[3] * m, k[4] * m)
        val corners = listOf(0.0 to 0.0, width.toDouble() to 0.0, 0.0 to height.toDouble(), width.toDouble() to height.toDouble(),
            0.0 to y0, width.toDouble() to y0, x0 to 0.0, x0 to height.toDouble())
        fun inside(zoom: Double): Boolean = corners.all { (px, py) ->
            val u = (px - x0) / d; val v = (py - y0) / d
            val r2 = u * u + v * v
            val radial = zoom * (kr[0] + kr[1] * r2 + kr[2] * r2 * r2 + kr[3] * r2 * r2 * r2)
            val sx = x0 + d * (u * radial + zoom * (2 * kt[0] * u * v + kt[1] * (r2 + 2 * u * u)))
            val sy = y0 + d * (v * radial + zoom * (kt[0] * (r2 + 2 * v * v) + 2 * kt[1] * u * v))
            val slack = EDGE_SLACK * d
            sx >= -slack && sy >= -slack && sx <= width + slack && sy <= height + slack
        }
        var steps = 0
        while (steps < MAX_ZOOM_STEPS && !inside(1.0 - steps * ZOOM_STEP)) steps++
        val zoom = 1.0 - steps * ZOOM_STEP
        return doubleArrayOf(zoom * kr[0], zoom * kr[1], zoom * kr[2], zoom * kr[3], zoom * kt[0], zoom * kt[1], x0 / width, y0 / height)
    }

    private const val ZOOM_STEP = 0.002
    private const val MAX_ZOOM_STEPS = 250
    /** A hundred-thousandth of the radius -- a fortieth of a pixel -- for the rounding of a corner that sits on the edge. */
    private const val EDGE_SLACK = 1e-5

    /** OpcodeList3: one WarpRectilinear for every plane, from [warpCoefficients]. */
    fun warpRectilinear(coefficients: DoubleArray): ByteArray {
        require(coefficients.size == 8)
        val b = ByteBuffer.allocate(4 + 8 * 8).order(ByteOrder.BIG_ENDIAN)
        b.putInt(1)
        coefficients.forEach { b.putDouble(it) }
        return list(listOf(ID_WARP_RECTILINEAR to b.array()))
    }
}
