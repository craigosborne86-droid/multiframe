package dev.multiframe.camera.pipeline

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * How much of the framing a mosaic covers, chosen before it starts.
 *
 * The earlier design swept the whole of the wide lens's framing, which at
 * this device's pairing is forty-nine tiles -- a mosaic nobody finishes. These
 * are the sizes a person will actually hold a phone still for, and the grid
 * is fixed so the nodes can be drawn before the first frame is taken.
 */
enum class MosaicPreset {
    /** 2x2, four tiles: a handheld gain in detail over a framing near the centre. */
    DETAIL_BOOST,

    /** 3x3, nine tiles: a medium-format-sized canvas from a mosaic that still fits a minute. */
    LARGE_FORMAT,

    /** The grid that covers a rectangle the user drew on the wide preview. */
    CUSTOM;

    val label: String
        get() = when (this) {
            DETAIL_BOOST -> "2×2"
            LARGE_FORMAT -> "3×3"
            CUSTOM -> "CUSTOM"
        }

    /** Grid side for the fixed presets; zero for [CUSTOM], whose grid is planned from the region. */
    val gridSide: Int
        get() = when (this) {
            DETAIL_BOOST -> 2
            LARGE_FORMAT -> 3
            CUSTOM -> 0
        }

    fun next(): MosaicPreset = entries[(ordinal + 1) % entries.size]

    companion object {
        fun named(name: String?): MosaicPreset =
            entries.firstOrNull { it.name == name } ?: DETAIL_BOOST
    }
}

/**
 * One tile the mosaic asks for: where its centre sits on the canvas, and its
 * place in the grid. The reticle guidance draws one dot per node.
 */
data class MosaicNode(
    val index: Int,
    val column: Int,
    val row: Int,
    /** Canvas coordinates of the tile's centre. */
    val canvasX: Float,
    val canvasY: Float,
)

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
     * has been capped for memory: the canvas is then smaller than the mosaic's
     * true extent, so a tile placed at its native size would occupy a larger
     * share of the frame than it actually saw. Left uncorrected, a capped sweep
     * reports itself complete after two or three frames having covered a
     * fraction of the scene.
     */
    val tileScale: Float,
    /**
     * The grid a guided mosaic asks for, or empty for a mosaic planned to cover
     * the framing by free panning. Centred on the canvas: the anchor frame --
     * wherever the user is pointing when the mosaic begins -- defines the
     * canvas centre, and the nodes are laid out around it.
     */
    val nodes: List<MosaicNode> = emptyList(),
    /** The tile's size in sensor pixels, recorded so the node geometry follows from the plan alone. */
    val tileWidthPx: Int = 0,
    val tileHeightPx: Int = 0,
) {
    val tileCount: Int get() = columns * rows

    /** The tile's footprint on the canvas, in canvas pixels. */
    val placedTileWidth: Float get() = tileWidthPx * tileScale
    val placedTileHeight: Float get() = tileHeightPx * tileScale

    /** Distance between neighbouring node centres, in canvas pixels. */
    val stepX: Float get() = placedTileWidth * (1f - overlap)
    val stepY: Float get() = placedTileHeight * (1f - overlap)

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
     * Plans a mosaic of [capture] covering the framing of [target].
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
     * Plans a mosaic of a fixed [columns] x [rows] grid of [capture] tiles.
     *
     * The canvas is sized to the grid rather than to a framing: a 2x2 at the
     * default overlap is 1.65 tiles across, whatever the lenses. So the grid
     * needs no wider lens to be planned from, and the mode is offered on every
     * lens: on the telephoto itself the canvas is 1.65 telephoto frames across,
     * which is a 67mm-equivalent framing at 110mm detail. [target] is the
     * framing the user was composing with when they asked, recorded in the
     * file when it is wider than the capture lens; otherwise the plan records
     * the framing the grid covers, as a lens of that equivalent focal length.
     */
    fun planGrid(
        target: Lens?,
        capture: Lens,
        tileWidth: Int,
        tileHeight: Int,
        columns: Int,
        rows: Int,
        overlap: Float = DEFAULT_OVERLAP,
        maxMegapixels: Double = DEFAULT_MAX_MEGAPIXELS,
    ): MosaicPlan? {
        if (capture.equivalent35mm <= 0) return null
        if (tileWidth <= 0 || tileHeight <= 0 || columns <= 0 || rows <= 0) return null
        val clampedOverlap = overlap.coerceIn(0.05f, 0.8f)
        val step = (1f - clampedOverlap).toDouble()

        val fullWidth = ceil(tileWidth * (1.0 + (columns - 1) * step)).toInt()
        val fullHeight = ceil(tileHeight * (1.0 + (rows - 1) * step)).toInt()
        // The framing the grid covers, as an equivalent focal length: the
        // capture lens's over how many tiles the canvas spans.
        val coverage = fullWidth.toDouble() / tileWidth
        val covered = capture.copy(
            equivalent35mm = (capture.equivalent35mm / coverage).toInt().coerceAtLeast(1),
        )
        val framing = if (target != null && target.equivalent35mm in 1 until capture.equivalent35mm) target else covered
        var canvasWidth = fullWidth
        var canvasHeight = fullHeight
        var capped = false
        val megapixels = canvasWidth.toDouble() * canvasHeight / 1_000_000.0
        if (maxMegapixels > 0 && megapixels > maxMegapixels) {
            val scale = sqrt(maxMegapixels / megapixels)
            canvasWidth = (canvasWidth * scale).toInt().coerceAtLeast(tileWidth / 2)
            canvasHeight = (canvasHeight * scale).toInt().coerceAtLeast(tileHeight / 2)
            capped = true
        }
        val tileScale = canvasWidth.toFloat() / fullWidth

        // The gain over the framing is what the whole exercise is for, and is
        // the same number whether the mosaic covers the framing or a part of it.
        val targetHalfWidth = tan(Math.toRadians(horizontalFovDegrees(framing.equivalent35mm) / 2))
        val captureHalfWidth = tan(Math.toRadians(horizontalFovDegrees(capture.equivalent35mm) / 2))
        val linearGain = if (captureHalfWidth > 0) (targetHalfWidth / captureHalfWidth).toFloat() else 1f

        return MosaicPlan(
            captureLens = capture,
            targetLens = framing,
            columns = columns,
            rows = rows,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            linearGain = linearGain,
            overlap = clampedOverlap,
            capped = capped,
            tileScale = tileScale,
        ).withNodes(tileWidth, tileHeight)
    }

    /**
     * Plans the smallest grid that covers a rectangle of the target's framing.
     *
     * [regionWidth] and [regionHeight] are fractions of the target lens's
     * frame, as the user drew them on the wide preview. Where the rectangle
     * sits does not enter: the anchor -- where the user aims when the mosaic
     * begins -- is its centre, so the drawing chooses the grid's size and the
     * aim chooses its place.
     */
    fun planRegion(
        target: Lens,
        capture: Lens,
        tileWidth: Int,
        tileHeight: Int,
        regionWidth: Float,
        regionHeight: Float,
        overlap: Float = DEFAULT_OVERLAP,
        maxMegapixels: Double = DEFAULT_MAX_MEGAPIXELS,
    ): MosaicPlan? {
        if (regionWidth <= 0f || regionHeight <= 0f) return null
        val full = plan(target, capture, tileWidth, tileHeight, overlap, maxMegapixels = 0.0) ?: return null
        // The region in capture-lens tiles: the fraction of the framing it
        // spans, times how many tiles the framing spans.
        val step = (1.0 - full.overlap)
        val widthInTiles = regionWidth.coerceIn(0f, 1f) * full.canvasWidth / tileWidth
        val heightInTiles = regionHeight.coerceIn(0f, 1f) * full.canvasHeight / tileHeight
        val columns = (ceil((widthInTiles - 1.0) / step).toInt() + 1).coerceIn(1, MAX_REASONABLE_TILES)
        val rows = (ceil((heightInTiles - 1.0) / step).toInt() + 1).coerceIn(1, MAX_REASONABLE_TILES)
        if (columns * rows > MAX_REASONABLE_TILES) return null
        return planGrid(target, capture, tileWidth, tileHeight, columns, rows, overlap, maxMegapixels)
    }

    /**
     * The plan for a preset, or null where [MosaicPreset.CUSTOM] has no region
     * yet -- or has one but no wider lens to have drawn it on: a box drawn on
     * the capture lens's own frame is smaller than one tile.
     */
    fun planPreset(
        preset: MosaicPreset,
        target: Lens?,
        capture: Lens,
        tileWidth: Int,
        tileHeight: Int,
        customRegion: Pair<Float, Float>? = null,
        overlap: Float = DEFAULT_OVERLAP,
    ): MosaicPlan? = when (preset) {
        MosaicPreset.CUSTOM -> customRegion?.let { (w, h) ->
            if (target == null) null else planRegion(target, capture, tileWidth, tileHeight, w, h, overlap)
        }
        else -> planGrid(target, capture, tileWidth, tileHeight, preset.gridSide, preset.gridSide, overlap)
    }

    /**
     * The lens the mosaic is shot with: the longest rear lens that can deliver
     * raw. The mode is offered whatever lens the user is on; if they are on
     * this one already, no switch is needed.
     */
    fun captureLens(lenses: List<Lens>): Lens? =
        LensCatalog.rear(lenses).filter { it.supportsRaw && it.equivalent35mm > 0 }
            .maxByOrNull { it.equivalent35mm }

    /**
     * The node grid for a plan: tile centres laid out around the canvas centre
     * at the plan's step, row-major, top-left first.
     */
    fun MosaicPlan.withNodes(tileWidth: Int, tileHeight: Int): MosaicPlan {
        val sized = copy(tileWidthPx = tileWidth, tileHeightPx = tileHeight)
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        val nodes = ArrayList<MosaicNode>(columns * rows)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                nodes.add(
                    MosaicNode(
                        index = nodes.size,
                        column = c,
                        row = r,
                        canvasX = cx + (c - (columns - 1) / 2f) * sized.stepX,
                        canvasY = cy + (r - (rows - 1) / 2f) * sized.stepY,
                    ),
                )
            }
        }
        return sized.copy(nodes = nodes)
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
        // Most resolution, but not at the cost of an unreasonable mosaic.
        .filter { it.tileCount <= MAX_REASONABLE_TILES }
        .maxByOrNull { it.megapixels }

    /**
     * Beyond this a mosaic takes long enough that the light will have changed,
     * something will have moved, and the user will have given up.
     */
    const val MAX_REASONABLE_TILES = 64
}
