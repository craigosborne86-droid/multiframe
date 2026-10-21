package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * The local tone operator of REFERENCE §3.5, written into the DNG as a gain
 * table map, so that a converter reading the raw can start from the lift the
 * JPEG was given rather than from a flat rendering.
 *
 * ### What the tag is
 *
 * DNG 1.6 `ProfileGainTableMap` (0xCD2D) and DNG 1.7 `ProfileGainTableMap2`
 * (0xCD40): a grid of one-dimensional gain tables over the image. At each
 * pixel the four nearest tables are interpolated bilinearly, the table is
 * indexed by a weighting of the pixel's linear RIMM colour after
 * BaselineExposure -- raised to `Gamma` in the 1.7 tag -- times
 * `MapPointsN`, and the gain found multiplies red, green and blue equally.
 *
 * ### Why the operator fits it
 *
 * The operator is already that shape. Its gain at a pixel is
 * `2 ^ lift(base)`, where the base is the bilateral grid sliced at the
 * pixel's position and its own luminance ([BilateralGrid.gainAt]): a function
 * of where the pixel is and how bright it is, and of nothing else. So one
 * table per grid node, sampled along the luminance axis, is the operator --
 * deterministic, from the same splats as the JPEG's, and no model of any
 * kind. The grid's nodes are the map's points: node `i` sits at pixel
 * `(i + 0.5) * cell`, which in the map's half-pixel relative coordinates is
 * an origin of half a cell and a spacing of one; past the outer nodes both
 * the grid and the map hold the edge.
 *
 * ### What it is not
 *
 * Exactly the JPEG's lift. The grid interpolates its sums and counts and
 * divides after; the map interpolates gains. The table samples the luminance
 * axis where the grid splats continuously. The JPEG's luminance is taken
 * after purity compression, which a converter does not do. The desk measures
 * the sum of these on the September DNGs (`ProfileGainTableMapTest`).
 *
 * ### The index
 *
 * The weights are the Y row of ROMM RGB to XYZ, so the index is luminance
 * -- the quantity the operator splats -- in RIMM space, whose white is the
 * same white as the develop's linear sRGB. A converter's linear values are
 * the develop's divided by `exposureGain / 2 ^ BaselineExposure`, so the
 * table at index `t` holds the operator's gain at luminance
 * `u(t) * exposureGain / 2 ^ BaselineExposure`. In the 1.7 tag the index is
 * `u ^ (1/4)`, which gives the shadows -- where the lift is -- the table's
 * resolution; the 1.6 tag has no gamma and indexes `u` itself.
 */
object ProfileGainTableMap {

    const val TAG = 0xCD2D
    const val TAG_2 = 0xCD40

    /** Y of ROMM RGB (ISO 22028-2) in XYZ: the luminance of a RIMM colour. */
    val RIMM_LUMINANCE = floatArrayOf(0.2880402f, 0.7118741f, 0.0000857f, 0f, 0f)

    /** Entries per table: 1/128 of the index between samples. */
    const val POINTS = 128

    /** The 1.7 tag's index is luminance to this power; the smallest the specification allows. */
    const val GAMMA = 0.25f

    /** DataType 3 of the 1.7 tag: 32-bit float gains. */
    private const val DATA_FLOAT32 = 3

    /** The tables, before they are serialised into either tag. */
    class Table(
        val pointsV: Int,
        val pointsH: Int,
        val spacingV: Double,
        val spacingH: Double,
        val originV: Double,
        val originH: Double,
        val pointsN: Int,
        val gamma: Float,
        /** `[v][h][n]`, row-major: the order the tag stores them in. */
        val gains: FloatArray,
    )

    /**
     * The operator's tables for an image of [width] by [height] pixels whose
     * grid is [grid], developed with [params] -- its exposure gain resolved --
     * and written with [baselineExposureStops] of BaselineExposure. [gamma]
     * is the index's exponent: [GAMMA] for the 1.7 tag, 1 for the 1.6 one.
     */
    fun tables(
        grid: BilateralGrid,
        width: Int,
        height: Int,
        params: DevelopParams,
        baselineExposureStops: Float,
        gamma: Float,
        points: Int = POINTS,
    ): Table {
        require(params.exposureGain > 0f) { "the develop's gain is resolved before its lift is written" }
        val toDevelop = params.exposureGain / 2f.pow(baselineExposureStops)
        // Index 0 is black, where the operator returns 1 for want of a
        // luminance; it is given the gain just above the operator's floor
        // instead, so the first interval does not ramp from 1. Below the
        // grid's darkest bin the operator still moves -- a pixel alone at
        // its luminance is its own base -- so nothing else is clamped.
        val floor = 2f * BilateralGrid.MIN_LUMINANCE
        val gains = FloatArray(grid.cellsY * grid.cellsX * points)
        var i = 0
        for (cy in 0 until grid.cellsY) for (cx in 0 until grid.cellsX) for (n in 0 until points) {
            val u = (n.toFloat() / points).pow(1f / gamma)
            val luminance = (u * toDevelop).coerceAtLeast(floor)
            gains[i++] = grid.gainAt(cx.toFloat(), cy.toFloat(), luminance, params.localTone, params.shadowLift, params.shadowLiftMax)
        }
        val cellV = grid.cell.toDouble() / height
        val cellH = grid.cell.toDouble() / width
        return Table(grid.cellsY, grid.cellsX, cellV, cellH, cellV / 2, cellH / 2, points, gamma, gains)
    }

    /**
     * The 1.6 tag's bytes (64 + 4 V H N), or, with [version2], the 1.7
     * tag's (80 + 4 V H N, float gains). Big-endian: the specification gives
     * the tag no byte order of its own, and its fields are the GainMap
     * opcode's, which is big-endian in every file.
     */
    fun encode(table: Table, version2: Boolean, order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteArray {
        require(version2 || table.gamma == 1f) { "the 1.6 tag has no gamma" }
        val header = if (version2) 80 else 64
        val b = ByteBuffer.allocate(header + 4 * table.gains.size).order(order)
        b.putInt(table.pointsV).putInt(table.pointsH)
        b.putDouble(table.spacingV).putDouble(table.spacingH)
        b.putDouble(table.originV).putDouble(table.originH)
        b.putInt(table.pointsN)
        RIMM_LUMINANCE.forEach { b.putFloat(it) }
        if (version2) {
            b.putInt(DATA_FLOAT32).putFloat(table.gamma)
            b.putFloat(0f).putFloat(0f) // GainMin, GainMax: unused for float gains
        }
        table.gains.forEach { b.putFloat(it) }
        return b.array()
    }

    /**
     * Both tags for one capture, or null when the develop has no local
     * operator. Built from [frame] -- the merged CFA, or every other cell of
     * it, which reads the same neighbourhoods ([BilateralGrid.cellFor]) --
     * through the same gain, grid and parameters as the develop.
     */
    fun forCapture(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        params: DevelopParams,
        shading: ShadingMap?,
        baselineExposureStops: Float,
    ): Pair<ByteArray, ByteArray>? {
        if (params.localTone <= 0f && params.shadowLift <= 0f) return null
        val resolved = params.withGain(RawDeveloper.resolveGain(frame, sensor, color, params))
        val grid = BilateralGrid.fromFrame(frame, sensor, color, resolved, shading)
        val linear = tables(grid, frame.width, frame.height, resolved, baselineExposureStops, gamma = 1f)
        val gamma = tables(grid, frame.width, frame.height, resolved, baselineExposureStops, gamma = GAMMA)
        return encode(linear, version2 = false) to encode(gamma, version2 = true)
    }

    /**
     * Every other 2x2 cell of the [width] by [height] CFA in [raw]: a quarter
     * of the pixels with the same pattern, which is all the grid needs.
     * Read through a view of the buffer, so the buffer's position is left alone.
     */
    fun halfScale(raw: ByteBuffer, width: Int, height: Int): BayerFrame {
        val samples = raw.duplicate().order(ByteOrder.nativeOrder()).also { it.clear() }.asShortBuffer()
        val w = width / 4 * 2
        val h = height / 4 * 2
        val out = ShortArray(w * h)
        for (y in 0 until h) {
            val sy = (y / 2) * 4 + (y and 1)
            for (x in 0 until w) {
                out[y * w + x] = samples.get(sy * width + (x / 2) * 4 + (x and 1))
            }
        }
        return BayerFrame(w, h, out)
    }
}
