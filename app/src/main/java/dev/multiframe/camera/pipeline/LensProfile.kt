package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import kotlin.math.hypot

private const val TAG = "LensProfile"

/**
 * Everything about one physical camera that is fixed for the life of the device.
 *
 * ### Why this is separate from [Lens]
 *
 * [Lens] answers the question the lens strip asks: what focal lengths exist,
 * which one is 1x, what should the chip say. It is deliberately small, because
 * it is built for every camera on the device every time the catalogue is
 * enumerated and most of it is thrown away.
 *
 * This answers the questions the *pipeline* asks, and they are a different
 * shape. Can this sensor deliver raw. What is its black level. Where does its
 * distortion put a straight line. What is its read noise at ISO 800. Those are
 * expensive to gather, unchanging once gathered, and needed only for the lens
 * actually being shot -- so they are a separate record, built on demand and
 * cached.
 *
 * ### On the optional fields
 *
 * `LENS_DISTORTION`, `LENS_INTRINSIC_CALIBRATION` and the pose keys are all
 * optional in the Camera2 specification, and a device is free to advertise raw
 * capture while publishing none of them. Every one of them is nullable here for
 * that reason, and every consumer has to have an answer for null that is not a
 * crash -- for the distortion correction that answer is "skip the correction",
 * not "assume zero coefficients", because those are different pictures.
 */
data class LensProfile(
    val cameraId: String,
    /** The logical camera this is reached through, or null if openable alone. */
    val logicalId: String?,

    // Optics
    val focalLengthMm: Float,
    val equivalent35mm: Int,
    val apertureF: Float,
    val facingBack: Boolean,

    // Sensor geometry
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,

    // Capabilities
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualFocus: Boolean,
    val hasOis: Boolean,
    val isoMin: Int?,
    val isoMax: Int?,
    val exposureMinNs: Long?,
    val exposureMaxNs: Long?,

    // Optical calibration, all optional in the Camera2 specification.
    /** Brown-Conrady radial and tangential terms: k1, k2, k3, p1, p2. */
    val distortion: FloatArray?,
    /** fx, fy, cx, cy, s -- the pinhole model this sensor actually is. */
    val intrinsics: FloatArray?,

    // Read-noise characterisation, filled in by [DcgCalibration]. Null until
    // the background profiling has run at least once on this camera.
    val dcgThresholdIso: Int?,
) {
    /**
     * Micrometres per photosite, which is the number that decides how much
     * light a pixel can collect and therefore what its noise floor is.
     */
    val pixelPitchMicrons: Float
        get() = if (activeArrayWidth <= 0 || sensorWidthMm <= 0f) 0f
        else (sensorWidthMm / activeArrayWidth) * 1000f

    val sensorDiagonalMm: Float get() = hypot(sensorWidthMm, sensorHeightMm)

    /** Whether this lens can bracket ISO, which is what DCG needs. */
    val supportsBracketing: Boolean
        get() = supportsManualSensor && supportsRaw && isoMin != null && isoMax != null

    /**
     * The bracket ratio to use, which is the measured gain threshold where one
     * was found and the software default where none was.
     *
     * A sensor with no analog gain switch still benefits from bracketing --
     * trading highlight headroom for shadow recovery is a real trade whether or
     * not the hardware has a discontinuity to exploit -- so the absence of a
     * threshold is not a reason to stop, only a reason not to claim one.
     */
    fun bracketRatio(default: Int): Int {
        val threshold = dcgThresholdIso ?: return default
        val base = isoMin ?: return default
        if (base <= 0) return default
        return (threshold / base).coerceIn(2, 8)
    }

    override fun equals(other: Any?): Boolean =
        other is LensProfile &&
            cameraId == other.cameraId &&
            logicalId == other.logicalId &&
            focalLengthMm == other.focalLengthMm &&
            equivalent35mm == other.equivalent35mm &&
            apertureF == other.apertureF &&
            facingBack == other.facingBack &&
            sensorWidthMm == other.sensorWidthMm &&
            sensorHeightMm == other.sensorHeightMm &&
            activeArrayWidth == other.activeArrayWidth &&
            activeArrayHeight == other.activeArrayHeight &&
            supportsRaw == other.supportsRaw &&
            supportsManualSensor == other.supportsManualSensor &&
            supportsManualFocus == other.supportsManualFocus &&
            hasOis == other.hasOis &&
            isoMin == other.isoMin &&
            isoMax == other.isoMax &&
            exposureMinNs == other.exposureMinNs &&
            exposureMaxNs == other.exposureMaxNs &&
            (distortion?.contentEquals(other.distortion) ?: (other.distortion == null)) &&
            (intrinsics?.contentEquals(other.intrinsics) ?: (other.intrinsics == null)) &&
            dcgThresholdIso == other.dcgThresholdIso

    override fun hashCode(): Int {
        var h = cameraId.hashCode()
        h = 31 * h + (logicalId?.hashCode() ?: 0)
        h = 31 * h + equivalent35mm
        h = 31 * h + activeArrayWidth
        h = 31 * h + activeArrayHeight
        h = 31 * h + (distortion?.contentHashCode() ?: 0)
        h = 31 * h + (intrinsics?.contentHashCode() ?: 0)
        h = 31 * h + (dcgThresholdIso ?: 0)
        return h
    }

    override fun toString(): String = buildString {
        append("${equivalent35mm}mm f/%.1f".format(apertureF))
        append(" id=$cameraId")
        logicalId?.let { append(" via $it") }
        append(" ${activeArrayWidth}x$activeArrayHeight")
        append(" %.2fum".format(pixelPitchMicrons))
        if (supportsRaw) append(" RAW")
        if (hasOis) append(" OIS")
        if (distortion != null) append(" distortion")
        if (intrinsics != null) append(" intrinsics")
        dcgThresholdIso?.let { append(" dcg@$it") }
    }
}

/**
 * Builds [LensProfile] records from what the hardware will admit to.
 *
 * Every read is guarded. The Camera2 specification marks most of these keys
 * optional, vendors disagree about which ones they publish, and several of them
 * read as absent to an app that has not been granted the camera permission --
 * which is indistinguishable from hardware that does not have them. So nothing
 * here throws and nothing here assumes: a key that is missing produces a null
 * field, and the consumer decides what to do about it.
 */
object LensProfiles {

    /**
     * Profiles for every lens in [lenses], keyed by camera id.
     *
     * Takes the catalogue rather than re-enumerating, so the profiles describe
     * exactly the lenses the interface is offering -- the deduplication has
     * already happened and the same optic is not characterised three times.
     */
    fun probe(manager: CameraManager, lenses: List<Lens>): Map<String, LensProfile> {
        val out = LinkedHashMap<String, LensProfile>(lenses.size)
        for (lens in lenses) {
            val profile = probeOne(manager, lens) ?: continue
            out[lens.cameraId] = profile
            Log.i(TAG, "profiled $profile")
        }
        return out
    }

    fun probeOne(manager: CameraManager, lens: Lens): LensProfile? {
        val ch = runCatching {
            manager.getCameraCharacteristics(lens.cameraId)
        }.getOrElse {
            // Xiaomi's MIUI has been seen refusing physical sub-camera
            // characteristics until the camera permission is granted, which
            // arrives as a security exception rather than a null.
            Log.w(TAG, "characteristics unavailable for ${lens.cameraId}", it)
            return null
        }
        return from(ch, lens)
    }

    /**
     * The pure half, so the parsing can be tested against characteristics this
     * device does not have.
     */
    fun from(ch: CameraCharacteristics, lens: Lens): LensProfile {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = caps.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )

        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        val minFocusDiopters =
            ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val oisModes = ch.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        ) ?: IntArray(0)

        return LensProfile(
            cameraId = lens.cameraId,
            logicalId = lens.logicalId,
            focalLengthMm = lens.focalLengthMm,
            equivalent35mm = lens.equivalent35mm,
            apertureF = lens.maxApertureF,
            facingBack = lens.facingBack,
            sensorWidthMm = size?.width ?: 0f,
            sensorHeightMm = size?.height ?: 0f,
            activeArrayWidth = activeArray?.width() ?: 0,
            activeArrayHeight = activeArray?.height() ?: 0,
            supportsRaw = lens.supportsRaw,
            supportsManualSensor = manualSensor,
            // A fixed-focus lens reports zero here, which means "focus at
            // infinity and nothing to set" rather than "focuses infinitely
            // close". Several ultra-wides are fixed-focus.
            supportsManualFocus = minFocusDiopters > 0f,
            hasOis = oisModes.any {
                it != CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            },
            isoMin = isoRange?.lower,
            isoMax = isoRange?.upper,
            exposureMinNs = exposureRange?.lower,
            exposureMaxNs = exposureRange?.upper,
            distortion = readDistortion(ch),
            intrinsics = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                ?.takeIf { it.size >= 5 }?.copyOf(),
            // Filled in later by the background noise profiling. A profile is
            // useful long before that has run.
            dcgThresholdIso = null,
        )
    }

    /**
     * The distortion terms, from whichever key this device publishes.
     *
     * `LENS_RADIAL_DISTORTION` was deprecated in API 28 in favour of
     * `LENS_DISTORTION`, which uses the same five terms in a different
     * order and a corrected sign convention. Devices in the field publish one,
     * the other, or neither. The newer key wins where both are present.
     *
     * Returned in the `LENS_DISTORTION` order -- k1, k2, k3, p1, p2 -- so
     * downstream has one convention to undistort against rather than two.
     */
    private fun readDistortion(ch: CameraCharacteristics): FloatArray? {
        ch.get(CameraCharacteristics.LENS_DISTORTION)
            ?.takeIf { it.size >= 5 }
            ?.let { return it.copyOf(5) }

        // The deprecated key is [kappa_0, kappa_1, kappa_2, kappa_3, kappa_4,
        // kappa_5], where the first is a scale term the new model folds into
        // the intrinsics. Dropping it and taking the next five is the documented
        // correspondence.
        @Suppress("DEPRECATION")
        ch.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
            ?.takeIf { it.size >= 6 }
            ?.let { old ->
                return floatArrayOf(old[1], old[2], old[3], old[4], old[5])
            }

        return null
    }
}
