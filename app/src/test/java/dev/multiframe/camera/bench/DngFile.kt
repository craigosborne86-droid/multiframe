package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.BayerFrame
import dev.multiframe.camera.pipeline.ColorProfile
import dev.multiframe.camera.pipeline.ColorScience
import dev.multiframe.camera.pipeline.DngMetadata
import dev.multiframe.camera.pipeline.SensorProfile
import dev.multiframe.camera.pipeline.ShadingMap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A DNG the app wrote, read back on the host: the merged CFA, the sensor's
 * levels and pattern, the colour the camera reported, and the lens shading
 * from the opcode list. Enough to run the Kotlin develop on the desk against
 * the same raw the phone developed, which is the whole point of the bench.
 *
 * Uncompressed 16-bit strips only, which is what `DngCreator` writes. The
 * opcode list is big-endian by the DNG specification whatever the file's
 * order is.
 */
class DngFile private constructor(
    val name: String,
    val frame: BayerFrame,
    val sensor: SensorProfile,
    val color: ColorProfile,
    val shading: ShadingMap?,
    val iso: Int,
    val exposureNs: Long,
    val baselineExposure: Float,
    val sceneKelvin: Int?,
    /** The lens's focal length in millimetres, from EXIF, to pick its geometry. */
    val focalMm: Float,
) {
    companion object {
        private const val TAG_WIDTH = 256
        private const val TAG_HEIGHT = 257
        private const val TAG_BPS = 258
        private const val TAG_STRIP_OFFSETS = 273
        private const val TAG_ROWS_PER_STRIP = 278
        private const val TAG_STRIP_BYTES = 279
        private const val TAG_CFA_PATTERN = 33422
        private const val TAG_ISO = 34855
        private const val TAG_EXPOSURE = 33434
        private const val TAG_CFA_PLANE_COLOR = 50710
        private const val TAG_BLACK = 50714
        private const val TAG_WHITE = 50717
        private const val TAG_AS_SHOT_NEUTRAL = 50728
        private const val TAG_BASELINE = 50730
        private const val TAG_ILLUMINANT_1 = 50778
        private const val TAG_ILLUMINANT_2 = 50779
        private const val TAG_OPCODES_2 = 51009
        private const val TAG_FOCAL_LENGTH = 37386

        private data class Entry(val type: Int, val count: Int, val valueOffset: Long, val inline: ByteArray)

        fun read(file: File): DngFile {
            val bytes = file.readBytes()
            val order = if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val buf = ByteBuffer.wrap(bytes).order(order)
            val ifd = buf.getInt(4)
            val count = buf.getShort(ifd).toInt() and 0xFFFF
            val entries = HashMap<Int, Entry>()
            for (i in 0 until count) {
                val at = ifd + 2 + i * 12
                val tag = buf.getShort(at).toInt() and 0xFFFF
                val type = buf.getShort(at + 2).toInt() and 0xFFFF
                val n = buf.getInt(at + 4)
                val inline = ByteArray(4).also { buf.position(at + 8); buf.get(it) }
                entries[tag] = Entry(type, n, buf.getInt(at + 8).toLong() and 0xFFFFFFFFL, inline)
            }
            fun int(tag: Int): Int? = entries[tag]?.let { e ->
                when (e.type) {
                    3 -> ByteBuffer.wrap(e.inline).order(order).getShort(0).toInt() and 0xFFFF
                    4 -> e.valueOffset.toInt()
                    else -> null
                }
            }
            fun rationals(tag: Int, wanted: Int): FloatArray? = entries[tag]?.let { e ->
                if (e.count < wanted) return null
                val out = FloatArray(wanted)
                for (i in 0 until wanted) {
                    val base = (e.valueOffset + i * 8L).toInt()
                    val num = buf.getInt(base); val den = buf.getInt(base + 4)
                    out[i] = if (den == 0) 0f else num.toFloat() / den
                }
                out
            }

            val width = int(TAG_WIDTH)!!
            val height = int(TAG_HEIGHT)!!
            require(int(TAG_BPS) == 16) { "${file.name}: not 16-bit" }
            val stripEntry = entries[TAG_STRIP_OFFSETS]!!
            val rowsPerStrip = int(TAG_ROWS_PER_STRIP) ?: height
            val strips = stripEntry.count
            val offsets = LongArray(strips) { i ->
                if (strips == 1) stripEntry.valueOffset else buf.getInt((stripEntry.valueOffset + 4L * i).toInt()).toLong() and 0xFFFFFFFFL
            }
            val data = ShortArray(width * height)
            var row = 0
            for (s in 0 until strips) {
                val rows = minOf(rowsPerStrip, height - row)
                val src = ByteBuffer.wrap(bytes, offsets[s].toInt(), rows * width * 2).order(order).asShortBuffer()
                src.get(data, row * width, rows * width)
                row += rows
            }

            // The CFA: DNG's pattern indexes into CFAPlaneColor (0 red, 1 green, 2 blue).
            val pattern = entries[TAG_CFA_PATTERN]!!.inline.take(4).map { it.toInt() and 0xFF }
            val planeColor = entries[TAG_CFA_PLANE_COLOR]?.inline?.take(3)?.map { it.toInt() and 0xFF } ?: listOf(0, 1, 2)
            val cfa = IntArray(4) { planeColor[pattern[it]] }
            val black = rationals(TAG_BLACK, 4)?.map { Math.round(it) }?.toIntArray() ?: intArrayOf(64, 64, 64, 64)
            val white = int(TAG_WHITE) ?: 1023
            val sensor = SensorProfile(black, white, cfa)

            // Colour as the phone had it: the calibration in the DNG's matrices,
            // the neutral from AsShotNeutral, the same solve as ColorProfile.calibrated.
            val neutral = rationals(TAG_AS_SHOT_NEUTRAL, 3) ?: floatArrayOf(0.5f, 1f, 0.5f)
            val gains = floatArrayOf(1f / neutral[0], 1f, 1f, 1f / neutral[2])
            val illum1 = int(TAG_ILLUMINANT_1)?.let { ColorScience.illuminantKelvin(it) }
            val illum2 = int(TAG_ILLUMINANT_2)?.let { ColorScience.illuminantKelvin(it) }
            val calibration = DngMetadata.readCalibration(bytes)!!.copy(illuminant1Kelvin = illum1, illuminant2Kelvin = illum2)
            val blend = ColorScience.solveBlend(calibration, neutral)
            val matrix = ColorScience.cameraToSrgb(calibration, floatArrayOf(1f, 1f, 1f), blend)
            val color = ColorProfile(gains, matrix.m)
            val kelvin = ColorScience.cctForBlend(calibration, neutral, blend)

            val shading = entries[TAG_OPCODES_2]?.let { e -> readGainMaps(bytes, e.valueOffset.toInt(), e.count) }

            val exposure = rationals(TAG_EXPOSURE, 1)?.get(0) ?: 0f
            val baseline = entries[TAG_BASELINE]?.let { e ->
                val base = e.valueOffset.toInt(); val num = buf.getInt(base); val den = buf.getInt(base + 4)
                if (den == 0) 0f else num.toFloat() / den
            } ?: 0f
            return DngFile(
                file.nameWithoutExtension, BayerFrame(width, height, data), sensor, color, shading,
                int(TAG_ISO) ?: 0, (exposure * 1e9).toLong(), baseline, kelvin,
                rationals(TAG_FOCAL_LENGTH, 1)?.get(0) ?: 0f,
            )
        }

        /**
         * The GainMap opcodes (id 9) of an opcode list into a [ShadingMap]:
         * one plane per CFA site, all on the same grid, which is how
         * `DngCreator` writes the camera's lens shading map.
         */
        private fun readGainMaps(bytes: ByteArray, offset: Int, length: Int): ShadingMap? {
            val b = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
            val count = b.getInt()
            var columns = 0; var rows = 0
            val planes = HashMap<Int, FloatArray>()
            repeat(count) {
                val id = b.getInt(); b.getInt() /* version */; b.getInt() /* flags */
                val size = b.getInt()
                val start = b.position()
                if (id == 9) {
                    // DngCreator writes one GainMap per CFA site, each with
                    // plane 0 and a pitch of two: the site is the rectangle's
                    // top-left phase, not the plane field.
                    val top = b.getInt(); val left = b.getInt(); b.getInt(); b.getInt() // bottom, right
                    b.getInt(); b.getInt() // plane, planes
                    val plane = (top and 1) * 2 + (left and 1)
                    b.getInt(); b.getInt() // row pitch, col pitch
                    val pointsV = b.getInt(); val pointsH = b.getInt()
                    b.getDouble(); b.getDouble(); b.getDouble(); b.getDouble() // spacing, origin
                    val mapPlanes = b.getInt()
                    val values = FloatArray(pointsV * pointsH)
                    for (i in values.indices) { values[i] = b.getFloat(); for (p in 1 until mapPlanes) b.getFloat() }
                    columns = pointsH; rows = pointsV
                    planes[plane] = values
                }
                b.position(start + size)
            }
            if (planes.size != 4 || columns == 0) return null
            val gains = FloatArray(columns * rows * 4)
            for (r in 0 until rows) for (c in 0 until columns) for (ch in 0 until 4) {
                gains[(r * columns + c) * 4 + ch] = planes[ch]!![r * columns + c]
            }
            return ShadingMap(columns, rows, gains)
        }
    }

    /** Every other 2x2 cell: a quarter of the pixels, the same CFA, for judging tone quickly. */
    fun decimated(): DngFile {
        val w = frame.width / 4 * 2
        val h = frame.height / 4 * 2
        val out = ShortArray(w * h)
        for (y in 0 until h) {
            val sy = (y / 2) * 4 + (y and 1)
            for (x in 0 until w) {
                val sx = (x / 2) * 4 + (x and 1)
                out[y * w + x] = frame.data[sy * frame.width + sx]
            }
        }
        return DngFile(name, BayerFrame(w, h, out), sensor, color, shading, iso, exposureNs, baselineExposure, sceneKelvin, focalMm)
    }

    /** A crop at even offsets, keeping the CFA phase, for judging detail at full resolution. */
    fun crop(x0: Int, y0: Int, w: Int, h: Int): DngFile {
        val ex = x0 and 1.inv(); val ey = y0 and 1.inv()
        val out = ShortArray(w * h)
        for (y in 0 until h) System.arraycopy(frame.data, (ey + y) * frame.width + ex, out, y * w, w)
        // The shading map is over the whole frame; a crop keeps the whole-frame map and
        // the develop samples it by the crop's own size, which is wrong at the edges but
        // right enough in the middle, where crops are taken.
        return DngFile(name, BayerFrame(w, h, out), sensor, color, shading, iso, exposureNs, baselineExposure, sceneKelvin, focalMm)
    }
}
