package dev.multiframe.camera.pipeline

import kotlin.math.sqrt

/**
 * Where the mosaic's nodes are on the viewfinder, from the phone's attitude.
 *
 * ### Why the attitude and not the image
 *
 * The first guidance registered every preview frame against the tiles taken
 * so far and drew the dots where the registration said. On a 105mm lens the
 * frame is seven degrees across: a pan of a few degrees carries every feature
 * the anchor saw out of the frame, the registration clings to whatever is
 * left at the edge, then fails, and the dots stick, swim, snap and vanish.
 * Registration is the right tool for placing a tile that has been taken --
 * it is exact, and it is against the pixels -- and the wrong one for saying
 * where the phone points *now*, which is the question the dots answer.
 *
 * The rotation vector answers it directly. It is the device's attitude in
 * the world, fused by the platform from gyroscope and accelerometer at two
 * hundred hertz, continuous whatever the scene shows. A node is a direction
 * in the world, fixed at the anchor; where it falls in the current frame is
 * that direction expressed in the device's current axes and projected
 * through the lens. Nothing here looks at a pixel.
 *
 * ### The arithmetic
 *
 * The platform's rotation matrix R takes a vector in device axes to world
 * axes. A node at sensor-pixel offset (sx, sy) from the anchor frame's centre
 * is, in display axes, at (dx, dy), and its direction in the device frame is
 * `(dx, -dy, -f)` normalised -- x right, y up, the rear camera looking down
 * -z, f the focal length in pixels. Its world direction is `R_anchor · v`.
 * In the current frame it is `R_nowᵀ · R_anchor · v`, and projects back to
 * display pixels as `(f · x / -z, -f · y / -z)`. Roll comes for free: it is a
 * rotation like any other.
 *
 * ### The targets are static
 *
 * Nothing moves a node once it is anchored. A version corrected every dot by
 * each tile's registration residual, to absorb the yaw drift of a rotation
 * vector with no magnetometer; every correction was a jump of every dot, and
 * a dot that jumps is a target to chase. The drift over a capture is a
 * fraction of a degree -- a few percent of a tile, well inside the thirty
 * percent overlap -- and the tiles are placed by their pixels regardless. So
 * a node is a direction in the world, fixed at the anchor, and it stays over
 * the doorknob it was laid on. [predictedOffset] reports the residual for
 * the log and for judging a registration; it moves nothing.
 *
 * Pure. Every function here is arithmetic on floats, and the tests turn the
 * phone by known angles and read the dots.
 */
class ImuGuidance(
    private val plan: MosaicPlan,
    /** The lens's focal length in sensor pixels. */
    private val pixelsPerRadian: Float,
    private val sensorOrientation: Int,
) {
    private val sensorW = plan.tileWidthPx.toFloat()
    private val sensorH = plan.tileHeightPx.toFloat()
    private val displayW: Float
    private val displayH: Float

    init {
        val swaps = ((sensorOrientation % 360) + 360) % 360 % 180 != 0
        displayW = if (swaps) sensorH else sensorW
        displayH = if (swaps) sensorW else sensorH
    }

    /** Rotation matrix, row-major 3x3, device axes to world, at the anchor. Null until anchored. */
    private var anchor: FloatArray? = null

    /** The world directions of the nodes, and of the canvas's corners, fixed at the anchor. */
    private var nodeDirections: List<FloatArray> = emptyList()
    private var cornerDirections: List<FloatArray> = emptyList()

    val anchored: Boolean get() = anchor != null

    /** Fixes the anchor: the device's rotation matrix at the moment the anchor frame was taken. */
    fun anchor(rotation: FloatArray) {
        val r = rotation.copyOf()
        anchor = r
        val cx = plan.canvasWidth / 2f
        val cy = plan.canvasHeight / 2f
        nodeDirections = plan.nodes.map { n -> worldDirectionOfCanvas(r, n.canvasX, n.canvasY, cx, cy) }
        val cw = plan.canvasWidth.toFloat()
        val ch = plan.canvasHeight.toFloat()
        cornerDirections = listOf(0f to 0f, cw to 0f, cw to ch, 0f to ch)
            .map { (x, y) -> worldDirectionOfCanvas(r, x, y, cx, cy) }
    }

    /**
     * Where the frame's centre -- the reticle -- is on the canvas now, given
     * the device's current rotation matrix, or null when the phone has turned
     * past ninety degrees from the anchor and the canvas is behind it.
     */
    fun reticleOnCanvas(rotation: FloatArray): Pair<Float, Float>? {
        val r0 = anchor ?: return null
        // The current optical axis, in the anchor's device frame.
        val axisWorld = mul(rotation, floatArrayOf(0f, 0f, -1f))
        val inAnchor = mulTransposed(r0, axisWorld)
        val disp = projectToDisplay(inAnchor) ?: return null
        val (sx, sy) = displayOffsetToSensor(disp.first, disp.second)
        return (plan.canvasWidth / 2f + sx * plan.tileScale) to
            (plan.canvasHeight / 2f + sy * plan.tileScale)
    }

    /** Each node's position in the current frame, as sensor-frame fractions, or null where it is behind the camera. */
    fun nodesInFrame(rotation: FloatArray): List<Pair<Float, Float>?> =
        nodeDirections.map { inFrame(rotation, it) }

    /**
     * Where a whole tile taken at [rotation] lands on the canvas: the
     * homography from the tile's sensor pixels to canvas pixels, from the
     * attitude alone.
     *
     * This is the fallback placement. Registration places a tile by its
     * pixels and is exact when it works; in the dark, on a blank wall, on a
     * patch of sky, it finds nothing to fit and used to refuse the tile --
     * and a mode that refuses to take a photograph in the dark is not a
     * camera. The attitude always has an answer: the tile is a rectangle of
     * directions in the device frame, each direction is carried to the
     * anchor's frame by `R_anchorᵀ · R_now`, and where it meets the canvas
     * plane is where that pixel lands. A rotation between two views of a
     * plane is a homography exactly, so four corners fit it exactly; the
     * centre and the edge midpoints are added for conditioning. Null only
     * when a corner is behind the camera, which no grid reaches.
     */
    fun tileTransform(rotation: FloatArray): Homography? {
        if (anchor == null) return null
        val points = listOf(
            0f to 0f, sensorW to 0f, sensorW to sensorH, 0f to sensorH,
            sensorW / 2f to sensorH / 2f,
            sensorW / 2f to 0f, sensorW to sensorH / 2f, sensorW / 2f to sensorH, 0f to sensorH / 2f,
        )
        val matches = ArrayList<Match>(points.size)
        for ((px, py) in points) {
            val canvas = canvasOfSensorPixel(rotation, px, py) ?: return null
            matches.add(Match(px, py, canvas.first, canvas.second))
        }
        return Homography.fit(matches)
    }

    /** The canvas point a sensor pixel of a frame taken at [rotation] looks at, or null behind the camera. */
    private fun canvasOfSensorPixel(rotation: FloatArray, px: Float, py: Float): Pair<Float, Float>? {
        val r0 = anchor ?: return null
        val (dx, dy) = sensorOffsetToDisplay(px - sensorW / 2f, py - sensorH / 2f)
        val v = normalise(floatArrayOf(dx, -dy, -pixelsPerRadian))
        val world = mul(rotation, v)
        val inAnchor = mulTransposed(r0, world)
        val disp = projectToDisplay(inAnchor) ?: return null
        val (sx, sy) = displayOffsetToSensor(disp.first, disp.second)
        return (plan.canvasWidth / 2f + sx * plan.tileScale) to (plan.canvasHeight / 2f + sy * plan.tileScale)
    }

    /** The canvas's four corners in the current frame, top-left first, or null where any is behind the camera. */
    fun cornersInFrame(rotation: FloatArray): List<Pair<Float, Float>>? {
        val pts = cornerDirections.map { inFrame(rotation, it) }
        return if (pts.all { it != null }) pts.map { it!! } else null
    }

    private fun inFrame(rotation: FloatArray, world: FloatArray): Pair<Float, Float>? {
        val v = mulTransposed(rotation, world)
        val disp = projectToDisplay(v) ?: return null
        val (sx, sy) = displayOffsetToSensor(disp.first, disp.second)
        return (0.5f + sx / sensorW) to (0.5f + sy / sensorH)
    }

    /** A canvas point's world direction, from the anchor's rotation and the anchor frame centred on the canvas. */
    private fun worldDirectionOfCanvas(r0: FloatArray, canvasX: Float, canvasY: Float, cx: Float, cy: Float): FloatArray {
        val sx = (canvasX - cx) / plan.tileScale
        val sy = (canvasY - cy) / plan.tileScale
        val (dx, dy) = sensorOffsetToDisplay(sx, sy)
        val v = normalise(floatArrayOf(dx, -dy, -pixelsPerRadian))
        return mul(r0, v)
    }

    /** Device-frame direction to display-pixel offset from the frame's centre; null behind the camera. */
    private fun projectToDisplay(v: FloatArray): Pair<Float, Float>? {
        if (v[2] >= -1e-6f) return null
        val depth = -v[2]
        return (pixelsPerRadian * v[0] / depth) to (-pixelsPerRadian * v[1] / depth)
    }

    private fun sensorOffsetToDisplay(sx: Float, sy: Float): Pair<Float, Float> {
        val (fx, fy) = TouchFocus.toDisplayPoint(0.5f + sx / sensorW, 0.5f + sy / sensorH, sensorOrientation)
        return ((fx - 0.5f) * displayW) to ((fy - 0.5f) * displayH)
    }

    private fun displayOffsetToSensor(dx: Float, dy: Float): Pair<Float, Float> {
        val (fx, fy) = TouchFocus.toSensorFraction(0.5f + dx / displayW, 0.5f + dy / displayH, sensorOrientation)
        return ((fx - 0.5f) * sensorW) to ((fy - 0.5f) * sensorH)
    }

    companion object {
        /** `m · v` for a row-major 3x3. */
        fun mul(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        )

        /** `mᵀ · v`: the inverse of a rotation applied to v. */
        fun mulTransposed(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
            m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
            m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
            m[2] * v[0] + m[5] * v[1] + m[8] * v[2],
        )

        fun normalise(v: FloatArray): FloatArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return if (n < 1e-9f) v else floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }

        /** A rotation of [radians] about the device's y axis: a pan. Row-major. */
        fun yaw(radians: Float): FloatArray {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
        }

        /** A rotation of [radians] about the device's x axis: a tilt. */
        fun pitch(radians: Float): FloatArray {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
        }

        /** A rotation of [radians] about the device's z axis: a roll. */
        fun roll(radians: Float): FloatArray {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }

        /** `a · b` for row-major 3x3 matrices. */
        fun times(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(9)
            for (r in 0 until 3) for (c in 0 until 3) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
            return out
        }

        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
