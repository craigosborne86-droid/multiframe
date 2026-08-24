package dev.multiframe.camera.pipeline

import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rectangle in sensor coordinates.
 *
 * Deliberately not android.graphics.Rect. Keeping the geometry in plain Kotlin
 * is what lets it be tested on the JVM at all -- the framework Rect is a stub
 * there whose every method returns zero, so a test written against it would
 * compare zeroes and pass while proving nothing. The same reason ZslPolicy
 * holds no Camera2 types.
 */
data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun from(rect: Rect) = SensorRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/**
 * Turning a tap on the viewfinder into a region the sensor understands.
 *
 * Three coordinate systems have to be reconciled and none of them agree:
 *
 *  * the **view**, in pixels from its top left, in whatever orientation the
 *    phone is being held;
 *  * the **sensor's active array**, in its own pixels, always in the sensor's
 *    native orientation, which on almost every phone is landscape while the
 *    user holds the device in portrait;
 *  * the **crop region**, which is where zoom has already restricted the
 *    sensor to a sub-rectangle, so a tap refers to a point inside that
 *    rectangle rather than inside the full array.
 *
 * Getting this wrong does not crash. It focuses somewhere else in the frame,
 * which reads as unreliable autofocus rather than as a bug, so the mapping is
 * worth testing rather than eyeballing.
 */
object TouchFocus {

    /** Half-width of the metering box, as a fraction of the frame. */
    const val REGION_FRACTION = 0.075f

    /** Camera2's maximum metering weight. */
    const val MAX_WEIGHT = MeteringRectangle.METERING_WEIGHT_MAX

    /**
     * Maps a normalised point on the *displayed image* to sensor coordinates.
     *
     * [normalisedX] and [normalisedY] are 0..1 from the top left of the image as
     * the user sees it. [sensorOrientation] is the clockwise rotation from the
     * sensor's native orientation to the device's natural one, as
     * `SENSOR_ORIENTATION` reports it.
     */
    fun toSensorPoint(
        normalisedX: Float,
        normalisedY: Float,
        sensorOrientation: Int,
        crop: SensorRect,
    ): Pair<Int, Int> {
        val x = normalisedX.coerceIn(0f, 1f)
        val y = normalisedY.coerceIn(0f, 1f)

        // Undo the rotation the preview applied to make the image upright.
        val (sx, sy) = when (((sensorOrientation % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }

        return (crop.left + sx * crop.width).roundToInt() to
            (crop.top + sy * crop.height).roundToInt()
    }

    /**
     * A metering rectangle around a tap, clamped inside the crop region.
     *
     * Clamping rather than centring: a tap near the edge of the frame must
     * still produce a valid rectangle, and the camera rejects one that leaves
     * the crop region.
     */
    fun regionAt(
        normalisedX: Float,
        normalisedY: Float,
        sensorOrientation: Int,
        crop: SensorRect,
        fraction: Float = REGION_FRACTION,
        weight: Int = MAX_WEIGHT,
    ): SensorRect {
        val (cx, cy) = toSensorPoint(normalisedX, normalisedY, sensorOrientation, crop)

        val halfW = max(1, (crop.width * fraction).roundToInt())
        val halfH = max(1, (crop.height * fraction).roundToInt())

        var left = cx - halfW
        var top = cy - halfH
        var right = cx + halfW
        var bottom = cy + halfH

        // Slide inside the crop rather than clipping, so the box keeps its size
        // and the camera still gets something it will accept.
        if (left < crop.left) { right += crop.left - left; left = crop.left }
        if (top < crop.top) { bottom += crop.top - top; top = crop.top }
        if (right > crop.right) { left -= right - crop.right; right = crop.right }
        if (bottom > crop.bottom) { top -= bottom - crop.bottom; bottom = crop.bottom }

        left = max(left, crop.left)
        top = max(top, crop.top)
        right = min(right, crop.right)
        bottom = min(bottom, crop.bottom)

        return SensorRect(left, top, max(left + 1, right), max(top + 1, bottom))
    }

    /** The same region as something the camera will take. */
    fun meteringRectangle(region: SensorRect, weight: Int = MAX_WEIGHT) =
        MeteringRectangle(region.left, region.top, region.width, region.height, weight)

    /**
     * The sensor rectangle a zoom ratio selects.
     *
     * Zoom is a crop: the sensor reads a smaller rectangle and the camera
     * scales it up. Centred, because that is what a zoom control means.
     */
    fun cropForZoom(activeArray: SensorRect, zoom: Float, maxZoom: Float): SensorRect {
        val clamped = zoom.coerceIn(1f, max(1f, maxZoom))
        val width = (activeArray.width / clamped).roundToInt()
        val height = (activeArray.height / clamped).roundToInt()
        val left = activeArray.left + (activeArray.width - width) / 2
        val top = activeArray.top + (activeArray.height - height) / 2
        return SensorRect(left, top, left + width, top + height)
    }
}
