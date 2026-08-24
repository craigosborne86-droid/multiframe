package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val TAG = "Multiframe"

/**
 * One physical camera on the device.
 *
 * Physical, not a zoom ratio. A logical camera hides which sensor is actually
 * exposing, and that is exactly the information a raw pipeline cannot do
 * without: every lens has its own black level, colour filter arrangement,
 * calibration and raw output sizes. Asking the logical camera for a raw frame
 * at 5x zoom gives no guarantee about which sensor produced it, or whether the
 * DNG metadata describes that sensor.
 */
data class Lens(
    val cameraId: String,
    val facingBack: Boolean,
    /** Physical focal length in millimetres, as the lens reports it. */
    val focalLengthMm: Float,
    /** The number a photographer recognises: full-frame equivalent. */
    val equivalent35mm: Int,
    /** Relative to the device's main camera, the way phone UIs count zoom. */
    val zoomFactor: Float,
    val supportsRaw: Boolean,
    val maxApertureF: Float,
    val sensorArea: Float,
    /**
     * The logical camera this lens is reached through, when it is not openable
     * on its own.
     *
     * On most multi-camera phones the ultra-wide and telephoto never appear in
     * the camera id list at all: they are physical sub-cameras of one logical
     * camera, and the only way to a stream from a specific one is to open the
     * logical camera and tag the output configuration with the physical id.
     */
    val logicalId: String? = null,
) {
    /** Whether this lens can be opened directly rather than through a parent. */
    val isDirectlyOpenable: Boolean get() = logicalId == null

    /** The camera id to actually open for this lens. */
    val openId: String get() = logicalId ?: cameraId

    /**
     * Labelled in millimetres rather than as a zoom multiplier.
     *
     * "24mm" and "120mm" say something a photographer can reason about --
     * compression, working distance, what the frame will look like. "1x" and
     * "5x" only describe a ratio between two of this phone's parts.
     */
    val label: String get() = "${equivalent35mm}mm"

    val zoomLabel: String
        get() = if (zoomFactor >= 1f) {
            if (abs(zoomFactor - zoomFactor.roundToInt()) < 0.05f) {
                "${zoomFactor.roundToInt()}x"
            } else "%.1fx".format(zoomFactor)
        } else "%.1fx".format(zoomFactor)

    override fun toString(): String =
        "$label ($zoomLabel, id=$cameraId%s, f/%.1f%s)".format(
            logicalId?.let { " via $it" } ?: "",
            maxApertureF,
            if (supportsRaw) ", RAW" else "",
        )
}

/**
 * Every lens the device actually has, discovered at runtime.
 *
 * Phones vary enormously here -- one camera, three, or a rear array plus a
 * depth sensor that must never be offered as a lens -- so nothing about the
 * development device is assumed.
 */
object LensCatalog {

    /** Diagonal of a 36x24mm frame, the reference every equivalent is against. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.267f

    /**
     * Converts a physical focal length to its full-frame equivalent.
     *
     * Pure so it can be tested against sensors this device does not have.
     */
    fun equivalent35mm(focalLengthMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Int {
        val diagonal = hypot(sensorWidthMm, sensorHeightMm)
        if (diagonal <= 0f || focalLengthMm <= 0f) return 0
        return (focalLengthMm * (FULL_FRAME_DIAGONAL_MM / diagonal)).roundToInt()
    }

    /**
     * Zoom factors relative to the main camera.
     *
     * The main camera is taken to be the rear lens with the largest sensor,
     * which is what "1x" means on every phone that has more than one: the wide
     * and the telephoto are both built around a smaller sensor than the
     * primary. Deriving it beats hard-coding a focal length, because the
     * primary is not always 24mm equivalent.
     */
    fun assignZoomFactors(lenses: List<Lens>): List<Lens> {
        val rear = lenses.filter { it.facingBack }
        val reference = rear.maxByOrNull { it.sensorArea }
            ?: lenses.maxByOrNull { it.sensorArea }
            ?: return lenses
        if (reference.equivalent35mm <= 0) return lenses

        return lenses.map {
            it.copy(
                zoomFactor = it.equivalent35mm.toFloat() / reference.equivalent35mm.toFloat(),
            )
        }
    }

    /**
     * Lenses worth offering, nearest to widest first.
     *
     * Filters to cameras that can actually take a picture. Depth and infrared
     * sensors appear in the camera list and would otherwise show up as a lens
     * that produces nothing usable.
     */
    fun enumerate(manager: CameraManager): List<Lens> {
        val found = ArrayList<Lens>()
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())

        for (id in ids) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            describe(manager, id, ch, logicalId = null)?.let { found.add(it) }

            // The interesting lenses are usually in here rather than in the id
            // list: a phone with three rear cameras typically advertises one
            // logical camera and hides the ultra-wide and telephoto behind it.
            val physicalIds = runCatching { ch.physicalCameraIds }
                .getOrDefault(emptySet())
            for (physicalId in physicalIds) {
                if (ids.contains(physicalId)) continue      // already listed
                if (found.any { it.cameraId == physicalId }) continue
                val physical = runCatching {
                    manager.getCameraCharacteristics(physicalId)
                }.getOrNull() ?: continue
                describe(manager, physicalId, physical, logicalId = id)
                    ?.let { found.add(it) }
            }
        }

        return assignZoomFactors(deduplicate(found)).sortedWith(
            compareByDescending<Lens> { it.facingBack }.thenBy { it.equivalent35mm }
        )
    }

    /**
     * Collapses entries that are the same optic seen more than once.
     *
     * A device advertises the same lens repeatedly: once as a standalone
     * camera, again as a physical member of a logical group, and sometimes
     * again as part of a second group. This device reports 24mm three times.
     * Grouping by facing and equivalent focal length and keeping one is what
     * turns that list into something a person can be shown.
     *
     * A directly openable entry wins, because a lens that can be opened on its
     * own needs none of the logical-camera machinery. Failing that, the largest
     * sensor wins, which is the highest quality path to the same field of view.
     */
    internal fun deduplicate(lenses: List<Lens>): List<Lens> =
        lenses.groupBy { it.facingBack }
            .values
            .flatMap { sameFacing ->
                // Clustered by ratio rather than by equal focal length. The
                // same optic is reported at 24mm and 25mm depending on which
                // entry you ask, so exact matching leaves visible duplicates in
                // the lens strip. A real second lens is never within 12% of
                // another; the gaps between actual optics are factors of two.
                val sorted = sameFacing.sortedBy { it.equivalent35mm }
                val clusters = ArrayList<MutableList<Lens>>()
                for (lens in sorted) {
                    val open = clusters.lastOrNull()
                    val widest = open?.first()?.equivalent35mm ?: 0
                    if (open != null && widest > 0 &&
                        lens.equivalent35mm.toFloat() / widest <= SAME_LENS_RATIO
                    ) {
                        open.add(lens)
                    } else {
                        clusters.add(mutableListOf(lens))
                    }
                }
                clusters.mapNotNull { cluster ->
                    cluster.sortedWith(
                        compareByDescending<Lens> { it.isDirectlyOpenable }
                            .thenByDescending { it.sensorArea }
                            .thenByDescending { it.supportsRaw }
                    ).firstOrNull()
                }
            }

    /** Two entries within this ratio of each other are the same piece of glass. */
    private const val SAME_LENS_RATIO = 1.12f

    /**
     * Turns one camera's characteristics into a lens, or null when it is not
     * one. Depth and infrared sensors appear in the camera list and would
     * otherwise be offered as lenses that produce nothing usable.
     */
    private fun describe(
        manager: CameraManager,
        id: String,
        ch: CameraCharacteristics,
        logicalId: String?,
    ): Lens? {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        // A physical sub-camera is not required to advertise backward
        // compatibility, so that filter only applies to cameras that stand
        // alone. For sub-cameras, having a focal length and a sensor size is
        // the evidence that it is a real imaging lens.
        if (logicalId == null &&
            !caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
        ) {
            Log.i(TAG, "lens $id skipped: not backward compatible")
            return null
        }

        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (size == null || focal <= 0f) {
            Log.i(TAG, "lens $id skipped: no focal length or sensor size")
            return null
        }

        return Lens(
            cameraId = id,
            facingBack = ch.get(CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_BACK,
            focalLengthMm = focal,
            equivalent35mm = equivalent35mm(focal, size.width, size.height),
            zoomFactor = 1f,
            supportsRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            maxApertureF = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 0f,
            sensorArea = size.width * size.height,
            logicalId = logicalId,
        )
    }

    /** Rear lenses, which is what the main capture UI offers. */
    fun rear(lenses: List<Lens>): List<Lens> = lenses.filter { it.facingBack }

    /** The lens a session should start on: the main rear camera. */
    fun default(lenses: List<Lens>): Lens? =
        rear(lenses).maxByOrNull { it.sensorArea } ?: lenses.firstOrNull()
}
