package dev.multiframe.camera.pipeline

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A plan for covering one lens's framing with another lens's detail.
 *
 * The whole idea in one sentence: a 110mm lens sees a quarter of the width that
 * a 24mm lens does, so sweeping it across the 24mm framing and stitching gives
 * that framing at roughly twenty times the pixel count -- without any invented
 * detail, because every pixel was measured by the sensor.
 */
data class MosaicPlan(
    val captureLens: Lens,
    val targetLens: Lens,
    val columns: Int,
    val rows: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Linear resolution gain over shooting the target lens directly. */
    val linearGain: Float,
    val overlap: Float,
    /** True when the canvas had to be reduced to stay within the memory budget. */
    val capped: Boolean,
    /**
     * How much a tile must be scaled when placed on the canvas.
     *
     * One when the canvas is the full size the geometry asks for. Less when it
     * has been capped for memory: the canvas is then smaller than the sweep's
     * true extent, so a tile placed at its native size would occupy a larger
     * share of the frame than it actually saw. Left uncorrected, a capped sweep
     * reports itself complete after two or three frames having covered a
     * fraction of the scene.
     */
    val tileScale: Float,
) {
    val tileCount: Int get() = columns * rows

    val megapixels: Double
        get() = canvasWidth.toDouble() * canvasHeight / 1_000_000.0

    /** Working set of the compositing canvas: RGB plus a coverage weight. */
    val canvasBytes: Long
        get() = canvasWidth.toLong() * canvasHeight.toLong() * BYTES_PER_PIXEL

    override fun toString(): String =
        "${captureLens.label} over ${targetLens.label}: $columns x $rows tiles, " +
            "%.0f MP (%.1fx linear), %.0f MB canvas".format(
                megapixels, linearGain, canvasBytes / (1024.0 * 1024.0),
            )

    companion object {
        /** 16-bit per channel plus a 16-bit weight, so blending stays precise. */
        const val BYTES_PER_PIXEL = 8L
    }
}

/**
 * Works out what a mosaic capture would involve, before committing to one.
 *
 * Pure geometry, so the answers can be checked without a camera. This is also
 * what tells the user up front that a given combination means forty-nine
 * exposures, which is a decision they should make knowingly.
 */
object MosaicPlanner {

    /** Width of a full-frame negative, the reference for equivalent focal lengths. */
    private const val FRAME_WIDTH_MM = 36.0
    private const val FRAME_HEIGHT_MM = 24.0

    /**
     * How much neighbouring tiles must share.
     *
     * Overlap is not redundancy. Registration needs common features to solve
     * against, blending needs room to hide the seam, and lenses are softest at
     * the edges, so the overlap is where the weaker part of each frame gets
     * replaced by the stronger part of its neighbour.
     */
    const val DEFAULT_OVERLAP = 0.35f

    /**
     * Ceiling on the canvas.
     *
     * The uncapped pairing on this device is 263 MP, which is a 2 GB canvas --
     * more than the phone had free. Eighty megapixels costs about 610 MB, still
     * enormous but within reach, and is already five times what the main camera
     * produces. Callers that know the real memory situation should pass their
     * own budget rather than trusting this.
     */
    const val DEFAULT_MAX_MEGAPIXELS = 80.0

    /** Horizontal field of view, in degrees, from a 35mm-equivalent focal length. */
    fun horizontalFovDegrees(equivalent35mm: Int): Double {
        if (equivalent35mm <= 0) return 0.0
        return Math.toDegrees(2.0 * atan(FRAME_WIDTH_MM / (2.0 * equivalent35mm)))
    }

    fun verticalFovDegrees(equivalent35mm: Int): Double {
        if (equivalent35mm <= 0) return 0.0
        return Math.toDegrees(2.0 * atan(FRAME_HEIGHT_MM / (2.0 * equivalent35mm)))
    }

    /**
     * Plans a sweep of [capture] covering the framing of [target].
     *
     * Returns null when the pairing makes no sense: the same lens, or a capture
     * lens wider than the target, neither of which buys any resolution.
     */
    fun plan(
        target: Lens,
        capture: Lens,
        tileWidth: Int,
        tileHeight: Int,
        overlap: Float = DEFAULT_OVERLAP,
        maxMegapixels: Double = DEFAULT_MAX_MEGAPIXELS,
    ): MosaicPlan? {
        if (target.equivalent35mm <= 0 || capture.equivalent35mm <= 0) return null
        if (tileWidth <= 0 || tileHeight <= 0) return null
        // A capture lens no longer than the target gains nothing.
        if (capture.equivalent35mm <= target.equivalent35mm) return null
        val clampedOverlap = overlap.coerceIn(0.05f, 0.8f)

        // Canvas size follows from projecting both fields of view onto the same
        // plane at the capture lens's sampling density: the ratio of tangents
        // of the half-angles. A rectilinear projection is linear in that
        // tangent, not in the angle itself, so sizing by the ratio of fields of
        // view undersizes the canvas by 14% at a 24mm-to-110mm pairing, with
        // the error growing as the target gets wider.
        val targetHalfWidth = tan(Math.toRadians(horizontalFovDegrees(target.equivalent35mm) / 2))
        val captureHalfWidth = tan(Math.toRadians(horizontalFovDegrees(capture.equivalent35mm) / 2))
        val targetHalfHeight = tan(Math.toRadians(verticalFovDegrees(target.equivalent35mm) / 2))
        val captureHalfHeight = tan(Math.toRadians(verticalFovDegrees(capture.equivalent35mm) / 2))
        if (captureHalfWidth <= 0.0 || captureHalfHeight <= 0.0) return null

        val widthGain = targetHalfWidth / captureHalfWidth
        val heightGain = targetHalfHeight / captureHalfHeight

        val fullWidth = ceil(tileWidth * widthGain).toInt()
        val fullHeight = ceil(tileHeight * heightGain).toInt()
        var canvasWidth = fullWidth
        var canvasHeight = fullHeight

        // Cap by area, scaling both axes together so framing is preserved.
        var capped = false
        val megapixels = canvasWidth.toDouble() * canvasHeight / 1_000_000.0
        if (maxMegapixels > 0 && megapixels > maxMegapixels) {
            val scale = sqrt(maxMegapixels / megapixels)
            canvasWidth = (canvasWidth * scale).toInt().coerceAtLeast(tileWidth / 2)
            canvasHeight = (canvasHeight * scale).toInt().coerceAtLeast(tileHeight / 2)
            capped = true
        }
        // Tiles have to shrink by the same factor the canvas did, or each one
        // claims more of the frame than it saw.
        val tileScale = canvasWidth.toFloat() / fullWidth

        // Tiles needed, given each one only contributes its non-overlapping part.
        val step = (1f - clampedOverlap).toDouble()
        val columns = ceil((widthGain - 1.0) / step).toInt() + 1
        val rows = ceil((heightGain - 1.0) / step).toInt() + 1

        return MosaicPlan(
            captureLens = capture,
            targetLens = target,
            columns = columns.coerceAtLeast(1),
            rows = rows.coerceAtLeast(1),
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            linearGain = min(widthGain, heightGain).toFloat(),
            overlap = clampedOverlap,
            capped = capped,
            tileScale = tileScale,
        )
    }

    /**
     * The pairing worth offering: the longest lens over the framing the user is
     * already composing with.
     *
     * Offering every combination would be a menu of arithmetic. There is one
     * obviously best answer for a given framing, which is the most detail
     * available for it.
     */
    fun bestPairing(
        lenses: List<Lens>,
        target: Lens,
        tileWidth: Int,
        tileHeight: Int,
    ): MosaicPlan? = LensCatalog.rear(lenses)
        .filter { it.supportsRaw && it.equivalent35mm > target.equivalent35mm }
        .mapNotNull { plan(target, it, tileWidth, tileHeight) }
        // Most resolution, but not at the cost of an unreasonable sweep.
        .filter { it.tileCount <= MAX_REASONABLE_TILES }
        .maxByOrNull { it.megapixels }

    /**
     * Beyond this a sweep takes long enough that the light will have changed,
     * something will have moved, and the user will have given up.
     */
    const val MAX_REASONABLE_TILES = 64
}
