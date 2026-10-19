package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads camera profile tags out of a DNG.
 *
 * ### Why this exists
 *
 * A raw-capable camera publishes its colorimetric characterisation through
 * `CameraCharacteristics`: colour matrices under two illuminants, calibration
 * transforms and forward matrices. Where a device does, that is the route to
 * use and this is not needed.
 *
 * Some do not. The keys are optional in the Camera2 specification, and a device
 * can advertise RAW capability without them. `DngCreator` still writes correct
 * DNGs on such a device, because the framework reaches the data by a route an
 * application does not have — so the way to it is round the houses: have
 * `DngCreator` serialise the metadata into a tiny DNG, and read the tags back
 * out of the file it produced.
 *
 * ### A trap worth knowing about
 *
 * These keys read as absent to an app that does not hold the CAMERA permission,
 * even though the device publishes them perfectly well. That is a privacy
 * measure — a sensor's calibration is close to a fingerprint — and it looks
 * exactly like a device that withholds them. A probe run without the permission
 * on the development device reported all six as null and led to precisely that
 * wrong conclusion; with the permission held, every one of them is there.
 *
 * So this is a fallback for genuinely silent hardware, not the main path.
 */
object DngMetadata {

    // DNG tag numbers, from the specification.
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 0xC65A
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 0xC65B
    private const val TAG_COLOR_MATRIX_1 = 0xC621
    private const val TAG_COLOR_MATRIX_2 = 0xC622
    private const val TAG_CAMERA_CALIBRATION_1 = 0xC623
    private const val TAG_CAMERA_CALIBRATION_2 = 0xC624
    private const val TAG_ANALOG_BALANCE = 0xC627
    private const val TAG_FORWARD_MATRIX_1 = 0xC714
    private const val TAG_FORWARD_MATRIX_2 = 0xC715
    private const val TAG_BASELINE_EXPOSURE = 0xC62A
    private const val TAG_AS_SHOT_NEUTRAL = 0xC628

    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_SRATIONAL = 10

    /** One entry in a TIFF image file directory. */
    private data class Entry(val tag: Int, val type: Int, val count: Int, val valueOffset: Long)

    /**
     * Pulls the colour characterisation out of DNG bytes.
     *
     * Returns null when the file is not a TIFF, or carries no colour matrix,
     * which is a legitimate outcome rather than an error.
     */
    fun readCalibration(dng: ByteArray): ColorScience.Calibration? {
        val entries = readIfd0(dng) ?: return null

        fun matrix(tag: Int): Mat3? {
            val entry = entries[tag] ?: return null
            if (entry.count < 9) return null
            val values = readRationals(dng, entry, 9) ?: return null
            return Mat3(values)
        }

        val colour1 = matrix(TAG_COLOR_MATRIX_1)
        val colour2 = matrix(TAG_COLOR_MATRIX_2)
        if (colour1 == null && colour2 == null) return null

        val analog = entries[TAG_ANALOG_BALANCE]
            ?.let { readRationals(dng, it, 3) }
            ?.let { Mat3.diagonal(it[0], it[1], it[2]) }
            ?: Mat3.IDENTITY

        return ColorScience.Calibration(
            colorMatrix1 = colour1 ?: colour2!!,
            colorMatrix2 = colour2 ?: colour1!!,
            calibration1 = matrix(TAG_CAMERA_CALIBRATION_1) ?: Mat3.IDENTITY,
            calibration2 = matrix(TAG_CAMERA_CALIBRATION_2) ?: Mat3.IDENTITY,
            forward1 = matrix(TAG_FORWARD_MATRIX_1),
            forward2 = matrix(TAG_FORWARD_MATRIX_2),
            analogBalance = analog,
        )
    }

    /** The two illuminants the matrices were measured under, as EXIF light source codes. */
    fun readIlluminants(dng: ByteArray): Pair<Int, Int>? {
        val entries = readIfd0(dng) ?: return null
        val first = entries[TAG_CALIBRATION_ILLUMINANT_1]?.valueOffset?.toInt() ?: return null
        val second = entries[TAG_CALIBRATION_ILLUMINANT_2]?.valueOffset?.toInt() ?: first
        return first to second
    }

    private fun readIfd0(dng: ByteArray): Map<Int, Entry>? {
        if (dng.size < 8) return null
        val buffer = ByteBuffer.wrap(dng)

        // Byte order is declared by the first two bytes, and DNG allows either.
        val order = when {
            dng[0] == 'I'.code.toByte() && dng[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            dng[0] == 'M'.code.toByte() && dng[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        buffer.order(order)
        if (buffer.getShort(2).toInt() != 42) return null      // TIFF magic

        val ifdOffset = buffer.getInt(4).toLong() and 0xFFFFFFFFL
        if (ifdOffset <= 0 || ifdOffset + 2 > dng.size) return null

        val count = buffer.getShort(ifdOffset.toInt()).toInt() and 0xFFFF
        val entries = HashMap<Int, Entry>(count)
        for (i in 0 until count) {
            val position = ifdOffset + 2 + i * 12L
            if (position < 0L || position + 12L > dng.size.toLong()) break
            val at = position.toInt()
            entries[buffer.getShort(at).toInt() and 0xFFFF] = Entry(
                tag = buffer.getShort(at).toInt() and 0xFFFF,
                type = buffer.getShort(at + 2).toInt() and 0xFFFF,
                count = buffer.getInt(at + 4),
                // For values that do not fit in four bytes this is an offset
                // into the file; for those that do it is the value itself.
                valueOffset = buffer.getInt(at + 8).toLong() and 0xFFFFFFFFL,
            )
        }
        return entries.takeIf { it.isNotEmpty() }
    }

    /** Reads [wanted] rational values, which is how DNG stores every matrix. */
    private fun readRationals(dng: ByteArray, entry: Entry, wanted: Int): FloatArray? {
        if (entry.type != TYPE_RATIONAL && entry.type != TYPE_SRATIONAL) return null
        if (entry.count < wanted) return null
        // Eight bytes each, so they never fit inline and the field is always an
        // offset. Checked in long arithmetic: a hostile offset near the top of
        // the unsigned range overflows an int addition to a negative number,
        // which then passes a naive bounds check and reads off the end.
        val offset = entry.valueOffset
        if (offset <= 0L || offset + wanted.toLong() * 8L > dng.size.toLong()) return null

        val order = if (dng[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN
        else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(dng).order(order)

        val base = offset.toInt()
        val out = FloatArray(wanted)
        for (i in 0 until wanted) {
            val numerator = buffer.getInt(base + i * 8)
            val denominator = buffer.getInt(base + i * 8 + 4)
            out[i] = if (denominator == 0) 0f else numerator.toFloat() / denominator.toFloat()
        }
        return out
    }

    /**
     * Where a DNG's BaselineExposure value lives, or null where the file has
     * no such tag or it is not a single signed rational.
     *
     * ### Why the DNG carries the guard's pull
     *
     * The highlight guard exposes the burst a stop or more under the meter
     * and the develop's own exposure puts the midtones back; the JPEG shows
     * the scene as metered with the highlights the stop kept. The DNG is the
     * merged raw as exposed, and a viewer renders it at the exposure its
     * metadata describes -- a stop dark, which read as "the RAW files have
     * regressed". DNG has the word for this: BaselineExposure, in stops, is
     * what a converter adds to the as-shot exposure before its own tone
     * curve. `DngCreator` writes the tag as zero and gives no way to set it,
     * so the value is written after the fact, into the eight bytes it left.
     * The pixels are untouched; only the rendering intent is corrected.
     */
    fun baselineExposureOffset(header: ByteArray): Long? {
        val entry = readIfd0(header)?.get(TAG_BASELINE_EXPOSURE) ?: return null
        if (entry.type != TYPE_SRATIONAL || entry.count != 1) return null
        if (entry.valueOffset <= 0L) return null
        return entry.valueOffset
    }

    /** The eight bytes of a signed rational for [stops], in the file's byte order. */
    fun encodeBaselineExposure(header: ByteArray, stops: Float): ByteArray {
        val order = if (header.size >= 2 && header[0] == 'M'.code.toByte()) ByteOrder.BIG_ENDIAN
        else ByteOrder.LITTLE_ENDIAN
        val numerator = Math.round(stops * BASELINE_DENOMINATOR)
        return ByteBuffer.allocate(8).order(order).putInt(numerator).putInt(BASELINE_DENOMINATOR).array()
    }

    /** Hundredths of a stop, which is finer than any converter shows. */
    const val BASELINE_DENOMINATOR = 100

    /**
     * Where a DNG's AsShotNeutral value lives -- three unsigned rationals --
     * or null where the file has no such tag.
     *
     * ### Why the DNG carries the develop's white balance
     *
     * The HAL's neutral and its ISP white-balance gains can disagree (by a
     * fifth of a stop in blue on the Pixel 11 Pro telephoto). The JPEG now
     * uses the selected camera neutral in the Chapter 6 transform; the DNG
     * writes that same neutral rather than a reciprocal of the ISP gains.
     */
    fun asShotNeutralOffset(header: ByteArray): Long? {
        val entry = readIfd0(header)?.get(TAG_AS_SHOT_NEUTRAL) ?: return null
        if (entry.type != TYPE_RATIONAL || entry.count != 3) return null
        if (entry.valueOffset <= 0L) return null
        return entry.valueOffset
    }

    /**
     * The twenty-four bytes of AsShotNeutral for white balance [gains] --
     * red, the two greens, blue, as `ColorProfile.gains` -- in the file's
     * byte order: the reciprocal of each gain, with green at one.
     */
    fun encodeAsShotNeutral(header: ByteArray, gains: FloatArray): ByteArray {
        val green = ((gains[1] + gains[2]) / 2f).coerceAtLeast(1e-3f)
        val neutral = floatArrayOf(green / gains[0].coerceAtLeast(1e-3f), 1f, green / gains[3].coerceAtLeast(1e-3f))
        return encodeCameraNeutral(header, neutral)
    }

    /** Serializes the selected DNG camera neutral directly (Chapter 6, AsShotNeutral). */
    fun encodeCameraNeutral(header: ByteArray, neutral: FloatArray): ByteArray {
        require(neutral.size >= 3 && neutral.take(3).all { it > 0f })
        val order = if (header.size >= 2 && header[0] == 'M'.code.toByte()) ByteOrder.BIG_ENDIAN
        else ByteOrder.LITTLE_ENDIAN
        val out = ByteBuffer.allocate(24).order(order)
        for (i in 0..2) out.putInt(Math.round(neutral[i] * NEUTRAL_DENOMINATOR)).putInt(NEUTRAL_DENOMINATOR)
        return out.array()
    }

    /** A millionth: the gains are floats and a converter reads them to that. */
    const val NEUTRAL_DENOMINATOR = 1_000_000

    /** Exposed so a test can build a TIFF without a camera. */
    internal fun tagFor(name: String): Int = when (name) {
        "colorMatrix1" -> TAG_COLOR_MATRIX_1
        "colorMatrix2" -> TAG_COLOR_MATRIX_2
        "cameraCalibration1" -> TAG_CAMERA_CALIBRATION_1
        "cameraCalibration2" -> TAG_CAMERA_CALIBRATION_2
        "forwardMatrix1" -> TAG_FORWARD_MATRIX_1
        "forwardMatrix2" -> TAG_FORWARD_MATRIX_2
        "illuminant1" -> TAG_CALIBRATION_ILLUMINANT_1
        "illuminant2" -> TAG_CALIBRATION_ILLUMINANT_2
        "asShotNeutral" -> TAG_AS_SHOT_NEUTRAL
        else -> throw IllegalArgumentException("unknown tag $name")
    }

    internal const val RATIONAL = TYPE_SRATIONAL
    internal const val UNSIGNED_RATIONAL = TYPE_RATIONAL
    internal const val SHORT = TYPE_SHORT
}
