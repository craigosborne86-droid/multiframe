package dev.multiframe.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.WritableByteChannel

/**
 * Everything a DNG of ours says about its raw image, as the values the file
 * will hold. Rationals are interleaved numerator, denominator pairs, kept as
 * the camera gave them where it gave rationals, so a value is not rounded on
 * its way through. Optional fields are absent from the file when null.
 *
 * Built from the camera in [DngCamera2]; read back from a file by the tests.
 */
data class DngRecord(
    val width: Int,
    val height: Int,
    /** DNG CFAPattern over the 2x2 cell, 0 red 1 green 2 blue, as [SensorProfile.cfaPattern]. */
    val cfaPattern: IntArray,
    /** BlackLevel per 2x2 site, as rationals. */
    val blackLevel: LongArray,
    val whiteLevel: Long,
    val make: String,
    val model: String,
    val uniqueCameraModel: String,
    val software: String,
    /** EXIF form, `yyyy:MM:dd HH:mm:ss`. */
    val dateTime: String,
    val orientation: Int,
    val exposureTime: LongArray? = null,
    val fNumber: LongArray? = null,
    val iso: Int? = null,
    val focalLength: LongArray? = null,
    val colorMatrix1: LongArray? = null,
    val colorMatrix2: LongArray? = null,
    val cameraCalibration1: LongArray? = null,
    val cameraCalibration2: LongArray? = null,
    val forwardMatrix1: LongArray? = null,
    val forwardMatrix2: LongArray? = null,
    val illuminant1: Int? = null,
    val illuminant2: Int? = null,
    val asShotNeutral: LongArray? = null,
    /** BaselineExposure in stops, as a signed rational: the guard's pull, undone. */
    val baselineExposure: LongArray = longArrayOf(0, 1),
    /** ActiveArea: top, left, bottom, right. */
    val activeArea: IntArray = intArrayOf(0, 0, height, width),
    val defaultCropOrigin: IntArray = intArrayOf(CROP_MARGIN, CROP_MARGIN),
    val defaultCropSize: IntArray = intArrayOf(width - 2 * CROP_MARGIN, height - 2 * CROP_MARGIN),
    /** NoiseProfile: an (S, O) pair per colour plane, red, green, blue. */
    val noiseProfile: DoubleArray? = null,
    val opcodeList2: ByteArray? = null,
    val opcodeList3: ByteArray? = null,
    /** DNG 1.6 ProfileGainTableMap and 1.7 ProfileGainTableMap2: [ProfileGainTableMap]. */
    val profileGainTableMap: ByteArray? = null,
    val profileGainTableMap2: ByteArray? = null,
) {
    companion object {
        /** `DngCreator`'s default crop: eight pixels off every side, the demosaic's margin. */
        const val CROP_MARGIN = 8

        /** Hundredths, as `DngCreator` wrote black levels, apertures and focal lengths. */
        fun hundredths(value: Float): LongArray = longArrayOf(Math.round(value * 100.0), 100)
    }
}

/**
 * A DNG writer of our own: the merged CFA and its [DngRecord] into a
 * DNG 1.7.1 file, with no framework and no library in between.
 *
 * ### Why not `DngCreator`
 *
 * It writes DNG 1.4 with BaselineExposure zero and AsShotNeutral from the
 * HAL's neutral, and has no way to say otherwise; the app wrote a file and
 * then patched eight and twenty-four bytes of it in place. It cannot write
 * a gain table map at all, and the gain table map is how the local tone
 * operator reaches a converter. Owning the IFD costs a few hundred lines
 * and removes the patching.
 *
 * ### What it writes
 *
 * The tags `DngCreator` wrote on the September files, with the same types,
 * counts and value encodings, so a converter renders one of ours as it
 * rendered one of theirs -- held tag by tag by `DngWriterParityTest` on
 * every archived September DNG. The intended differences, all in
 * REFERENCE §3.9:
 *
 * - DNGVersion 1.7.1.0 rather than 1.4.0.0: the 1.7 gain table map is a 1.7
 *   tag. DNGBackwardVersion stays 1.1.0.0; the specification asks that
 *   optional tags not raise it.
 * - BaselineExposure and AsShotNeutral carry the develop's values from the
 *   start, rather than being written over.
 * - ProfileGainTableMap and ProfileGainTableMap2, when the shot was developed
 *   with the local operator.
 * - The image starts on a sixteen-byte boundary.
 *
 * ### The pixels
 *
 * [raw] is written as it is: little-endian 16-bit samples, `width` to a row,
 * a row to a strip, as `DngCreator` laid them out. Handed to the channel
 * whole, so the merge's direct buffer reaches the file without a Java copy.
 */
object DngWriter {

    private const val TAG_NEW_SUBFILE_TYPE = 254
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_IMAGE_DESCRIPTION = 270
    private const val TAG_MAKE = 271
    private const val TAG_MODEL = 272
    private const val TAG_ORIENTATION = 274
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_X_RESOLUTION = 282
    private const val TAG_Y_RESOLUTION = 283
    private const val TAG_PLANAR_CONFIGURATION = 284
    private const val TAG_RESOLUTION_UNIT = 296
    private const val TAG_SOFTWARE = 305
    private const val TAG_DATE_TIME = 306
    private const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_COPYRIGHT = 33432
    private const val TAG_EXPOSURE_TIME = 33434
    private const val TAG_F_NUMBER = 33437
    private const val TAG_ISO = 34855
    private const val TAG_DATE_TIME_ORIGINAL = 36867
    private const val TAG_FOCAL_LENGTH = 37386
    private const val TAG_TIFF_EP_STANDARD_ID = 37398
    private const val TAG_DNG_VERSION = 50706
    private const val TAG_DNG_BACKWARD_VERSION = 50707
    private const val TAG_UNIQUE_CAMERA_MODEL = 50708
    private const val TAG_CFA_PLANE_COLOR = 50710
    private const val TAG_CFA_LAYOUT = 50711
    private const val TAG_BLACK_LEVEL_REPEAT_DIM = 50713
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_DEFAULT_SCALE = 50718
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_COLOR_MATRIX_2 = 50722
    private const val TAG_CAMERA_CALIBRATION_1 = 50723
    private const val TAG_CAMERA_CALIBRATION_2 = 50724
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_BASELINE_EXPOSURE = 50730
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
    private const val TAG_ACTIVE_AREA = 50829
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_OPCODE_LIST_2 = 51009
    private const val TAG_OPCODE_LIST_3 = 51022
    private const val TAG_NOISE_PROFILE = 51041

    private const val PHOTOMETRIC_CFA = 32803

    /** DNG 1.7.1.0. */
    val DNG_VERSION = intArrayOf(1, 7, 1, 0)
    val DNG_BACKWARD_VERSION = intArrayOf(1, 1, 0, 0)

    /** The directory [record] describes; the strip tags are the writer's. */
    fun directory(record: DngRecord): TiffDirectory {
        val r = record
        require(r.cfaPattern.size == 4 && r.blackLevel.size == 8) { "a 2x2 CFA and a black level per site" }
        val d = TiffDirectory()
        d.longs(TAG_NEW_SUBFILE_TYPE, 0L)
        d.longs(TAG_WIDTH, r.width.toLong())
        d.longs(TAG_HEIGHT, r.height.toLong())
        d.shorts(TAG_BITS_PER_SAMPLE, 16)
        d.shorts(TAG_COMPRESSION, 1)
        d.shorts(TAG_PHOTOMETRIC, PHOTOMETRIC_CFA)
        d.ascii(TAG_IMAGE_DESCRIPTION, "")
        d.ascii(TAG_MAKE, r.make)
        d.ascii(TAG_MODEL, r.model)
        d.shorts(TAG_ORIENTATION, r.orientation)
        d.shorts(TAG_SAMPLES_PER_PIXEL, 1)
        d.rationals(TAG_X_RESOLUTION, longArrayOf(72, 1))
        d.rationals(TAG_Y_RESOLUTION, longArrayOf(72, 1))
        d.shorts(TAG_PLANAR_CONFIGURATION, 1)
        d.shorts(TAG_RESOLUTION_UNIT, 2)
        d.ascii(TAG_SOFTWARE, r.software)
        d.ascii(TAG_DATE_TIME, r.dateTime)
        d.shorts(TAG_CFA_REPEAT_PATTERN_DIM, 2, 2)
        d.bytes(TAG_CFA_PATTERN, *r.cfaPattern)
        d.ascii(TAG_COPYRIGHT, "")
        r.exposureTime?.let { d.rationals(TAG_EXPOSURE_TIME, it) }
        r.fNumber?.let { d.rationals(TAG_F_NUMBER, it) }
        r.iso?.let { d.shorts(TAG_ISO, it.coerceIn(0, 0xFFFF)) }
        d.ascii(TAG_DATE_TIME_ORIGINAL, r.dateTime)
        r.focalLength?.let { d.rationals(TAG_FOCAL_LENGTH, it) }
        d.bytes(TAG_TIFF_EP_STANDARD_ID, 1, 0, 0, 0)
        d.bytes(TAG_DNG_VERSION, *DNG_VERSION)
        d.bytes(TAG_DNG_BACKWARD_VERSION, *DNG_BACKWARD_VERSION)
        d.ascii(TAG_UNIQUE_CAMERA_MODEL, r.uniqueCameraModel)
        d.bytes(TAG_CFA_PLANE_COLOR, 0, 1, 2)
        d.shorts(TAG_CFA_LAYOUT, 1)
        d.shorts(TAG_BLACK_LEVEL_REPEAT_DIM, 2, 2)
        d.rationals(TAG_BLACK_LEVEL, r.blackLevel)
        d.longs(TAG_WHITE_LEVEL, r.whiteLevel)
        d.rationals(TAG_DEFAULT_SCALE, longArrayOf(1, 1, 1, 1))
        d.longs(TAG_DEFAULT_CROP_ORIGIN, *r.defaultCropOrigin)
        d.longs(TAG_DEFAULT_CROP_SIZE, *r.defaultCropSize)
        r.colorMatrix1?.let { d.srationals(TAG_COLOR_MATRIX_1, it) }
        r.colorMatrix2?.let { d.srationals(TAG_COLOR_MATRIX_2, it) }
        r.cameraCalibration1?.let { d.srationals(TAG_CAMERA_CALIBRATION_1, it) }
        r.cameraCalibration2?.let { d.srationals(TAG_CAMERA_CALIBRATION_2, it) }
        r.asShotNeutral?.let { d.rationals(TAG_AS_SHOT_NEUTRAL, it) }
        d.srationals(TAG_BASELINE_EXPOSURE, r.baselineExposure)
        r.illuminant1?.let { d.shorts(TAG_CALIBRATION_ILLUMINANT_1, it) }
        r.illuminant2?.let { d.shorts(TAG_CALIBRATION_ILLUMINANT_2, it) }
        d.longs(TAG_ACTIVE_AREA, *r.activeArea)
        r.forwardMatrix1?.let { d.srationals(TAG_FORWARD_MATRIX_1, it) }
        r.forwardMatrix2?.let { d.srationals(TAG_FORWARD_MATRIX_2, it) }
        r.opcodeList2?.let { d.undefined(TAG_OPCODE_LIST_2, it) }
        r.opcodeList3?.let { d.undefined(TAG_OPCODE_LIST_3, it) }
        r.noiseProfile?.let { d.doubles(TAG_NOISE_PROFILE, it) }
        r.profileGainTableMap?.let { d.undefined(ProfileGainTableMap.TAG, it) }
        r.profileGainTableMap2?.let { d.undefined(ProfileGainTableMap.TAG_2, it) }
        return d
    }

    /** Writes [record] and the [raw] CFA it describes to [channel]. */
    fun write(channel: WritableByteChannel, record: DngRecord, raw: ByteBuffer) {
        // The merge's buffer holds native-order samples; the file says "II".
        check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) { "the raw is written in the order it is held" }
        directory(record).write(channel, raw, record.height, record.width * 2)
    }
}
