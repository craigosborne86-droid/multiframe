package dev.multiframe.camera.pipeline

import kotlin.math.max
import kotlin.math.min

/** Where a tile ended up, and how confident the registration was. */
data class Placement(
    val index: Int,
    /** Maps full-resolution tile coordinates into canvas coordinates. */
    val transform: Homography,
    val inliers: Int,
    val inlierRatio: Float,
)

/** Why a frame was not used. */
enum class RejectionReason {
    /** Could not be related to anything already placed. */
    UNREGISTRABLE,

    /** Lands almost entirely on ground already covered. */
    REDUNDANT,

    /** Would fall outside the canvas the plan allocated. */
    OUT_OF_BOUNDS,
}

sealed interface OfferResult {
    data class Placed(val placement: Placement) : OfferResult
    data class Rejected(val reason: RejectionReason) : OfferResult
}

/**
 * Decides where each frame of a sweep belongs.
 *
 * Registration runs on small luma proxies rather than full frames -- matching
 * twelve-megapixel images would be pointlessly slow, and a homography estimated
 * at a sixth of the resolution is just as accurate once scaled up, because it
 * is fitted to dozens of correspondences rather than read off one pixel.
 *
 * ### Chaining, and its limit
 *
 * Each frame registers against the previously accepted one and its transform is
 * composed onto that one's placement. This is the natural thing to do during a
 * sweep, where consecutive frames overlap heavily and distant ones may not
 * overlap at all.
 *
 * It also accumulates error: every pairwise fit contributes its own small
 * mistake, and the chain integrates them, so the far end of a long sweep can
 * drift. At 7x7 that drift stays under the feathered seam width and is
 * invisible. At 28x28 it would not be, and the answer there is bundle
 * adjustment -- optimising every transform jointly against every overlap rather
 * than chaining -- which is deliberately not built yet, because it would be
 * machinery in service of a sweep nobody should be taking.
 */
class MosaicAssembler(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    /**
     * How far the camera must move, as a fraction of the frame, before another
     * frame is worth keeping.
     *
     * A sweep produces far more frames than the mosaic needs, so most are
     * refused. Measured as distance between tile centres rather than as newly
     * covered area: on a coarse coverage grid a two-pixel shift can push the
     * tile's bounding box across a cell boundary and appear to cover a whole
     * new row, which made an area test reject and accept almost at random.
     * Distance is what the question actually is.
     *
     * Set low on purpose. The asymmetry matters: accepting a frame that was not
     * strictly needed costs one registration and one composite, while rejecting
     * one that was needed leaves a hole in the mosaic that cannot be filled
     * without sweeping again. How many frames are *needed* is the planner's
     * overlap figure; this only refuses near-duplicates.
     */
    private val minStepFraction: Float = 0.12f,
    /**
     * Scale applied to every tile as it is placed.
     *
     * Less than one when the canvas has been capped for memory: the canvas is
     * then smaller than the sweep's true extent, and a tile placed at native
     * size would claim a larger share of it than it saw.
     */
    private val tileScale: Float = 1f,
) {

    private val placements = ArrayList<Placement>()
    private var previousProxy: Plane? = null
    private var previousTransform: Homography? = null

    /**
     * Proxies of placed tiles, kept so a new frame can be registered against
     * every neighbour rather than only against the one before it.
     *
     * They are small -- a few hundred pixels square -- so a whole sweep's worth
     * is a few megabytes, which is nothing beside the canvas.
     */
    private val placedProxies = ArrayList<Plane>()

    /**
     * Coverage tracked on a coarse grid rather than per pixel. The question
     * being asked is "has the user swept here yet", which does not need
     * megapixel resolution and would be slow at it.
     */
    private val gridColumns = 32
    private val gridRows = 32
    private val covered = BooleanArray(gridColumns * gridRows)

    val placed: List<Placement> get() = placements

    /** Fraction of the canvas swept so far. */
    val coverage: Float
        get() = covered.count { it }.toFloat() / covered.size

    /**
     * Offers one frame to the mosaic.
     *
     * [proxy] is a downscaled luma view of the frame; [proxyScale] is how many
     * full-resolution pixels each proxy pixel represents.
     */
    fun offer(proxy: Plane, proxyScale: Float): OfferResult {
        val previous = previousProxy
        val previousPlacement = previousTransform

        val transform: Homography
        var inliers = 0
        var ratio = 1f

        if (previous == null || previousPlacement == null) {
            // The first frame defines the origin: placed centred, with the rest
            // of the mosaic growing around it.
            val scaled = tileWidth * tileScale
            val scaledHeight = tileHeight * tileScale
            transform = Homography.translation(
                (canvasWidth - scaled) / 2.0,
                (canvasHeight - scaledHeight) / 2.0,
            ).times(scaleTransform(tileScale))
        } else {
            val proxyToTile = scaleTransform(proxyScale)
            val tileToProxy = proxyToTile.invert()
                ?: return OfferResult.Rejected(RejectionReason.UNREGISTRABLE)

            // Registered against every placed tile that overlaps, not only the
            // one before it. A sweep gives each frame several independent
            // measurements -- the tile beside it and the tile above both saw
            // it -- and chaining uses one and discards the rest, which is how a
            // long sweep drifts while every individual join looks perfect.
            val predictions = ArrayList<Homography>()
            var bestInliers = 0
            var bestRatio = 0f

            for (candidate in registrationCandidates()) {
                val fit = FeatureMatcher.register(placedProxies[candidate], proxy) ?: continue
                val inverse = fit.homography.invert() ?: continue
                predictions.add(
                    placements[candidate].transform
                        .times(proxyToTile).times(inverse).times(tileToProxy)
                )
                if (fit.inliers.size > bestInliers) {
                    bestInliers = fit.inliers.size
                    bestRatio = fit.inlierRatio
                }
            }

            if (predictions.isEmpty()) {
                return OfferResult.Rejected(RejectionReason.UNREGISTRABLE)
            }
            transform = consensus(predictions)
                ?: return OfferResult.Rejected(RejectionReason.UNREGISTRABLE)
            inliers = bestInliers
            ratio = bestRatio
        }

        if (!landsOnCanvas(transform)) {
            return OfferResult.Rejected(RejectionReason.OUT_OF_BOUNDS)
        }

        val cells = cellsUnder(transform)
        if (cells.isEmpty()) {
            return OfferResult.Rejected(RejectionReason.OUT_OF_BOUNDS)
        }

        // Has the camera moved enough for this frame to be worth anything?
        val centre = transform.apply(tileWidth / 2f, tileHeight / 2f)
        val minStep = minStepFraction * min(tileWidth, tileHeight)
        val tooClose = placements.any { existing ->
            val other = existing.transform.apply(tileWidth / 2f, tileHeight / 2f)
            val dx = centre[0] - other[0]
            val dy = centre[1] - other[1]
            dx * dx + dy * dy < minStep * minStep
        }
        if (tooClose) {
            // Accepting it would spend a registration and a composite on ground
            // already swept.
            return OfferResult.Rejected(RejectionReason.REDUNDANT)
        }

        for (cell in cells) covered[cell] = true
        val placement = Placement(placements.size, transform, inliers, ratio)
        placements.add(placement)
        previousProxy = proxy
        previousTransform = transform
        placedProxies.add(proxy)
        return OfferResult.Placed(placement)
    }

    /**
     * Which placed tiles are worth registering a new frame against.
     *
     * The most recent one always, since consecutive frames overlap most. Beyond
     * that, whichever placed tiles are near enough to share ground -- which is
     * where loop closures come from, and they are the measurements that pull a
     * drifting sweep back.
     *
     * Capped, because registration is the expensive part and the nearest few
     * neighbours carry almost all the information.
     */
    private fun registrationCandidates(): List<Int> {
        if (placements.isEmpty()) return emptyList()
        val newest = placements.size - 1
        val reach = tileWidth * tileScale * NEIGHBOUR_REACH

        val centre = placements[newest].transform
            .apply(tileWidth / 2f, tileHeight / 2f)

        val nearby = placements.indices
            .filter { it != newest }
            .map { index ->
                val other = placements[index].transform
                    .apply(tileWidth / 2f, tileHeight / 2f)
                val dx = centre[0] - other[0]
                val dy = centre[1] - other[1]
                index to (dx * dx + dy * dy)
            }
            .filter { it.second < reach * reach }
            .sortedBy { it.second }
            .take(MAX_NEIGHBOURS - 1)
            .map { it.first }

        return listOf(newest) + nearby
    }

    /**
     * Averages several predicted placements into one.
     *
     * The predictions' corner positions are averaged and a transform refitted
     * from them, rather than the matrices being averaged directly: homographies
     * do not average meaningfully element by element, but the points they
     * predict do.
     */
    private fun consensus(predictions: List<Homography>): Homography? {
        if (predictions.size == 1) return predictions.first()

        val corners = listOf(
            0f to 0f,
            tileWidth.toFloat() to 0f,
            0f to tileHeight.toFloat(),
            tileWidth.toFloat() to tileHeight.toFloat(),
        )
        val matches = ArrayList<Match>(corners.size)
        corners.forEach { (x, y) ->
            var sumX = 0f
            var sumY = 0f
            var used = 0
            for (prediction in predictions) {
                val p = prediction.apply(x, y)
                if (p[0].isNaN() || p[1].isNaN()) continue
                sumX += p[0]; sumY += p[1]; used++
            }
            if (used > 0) matches.add(Match(x, y, sumX / used, sumY / used))
        }
        if (matches.size < 4) return predictions.first()
        return Homography.fit(matches) ?: predictions.first()
    }

    /** Scales a transform between proxy and full-resolution coordinates. */
    private fun scaleTransform(scale: Float): Homography = Homography(
        doubleArrayOf(
            scale.toDouble(), 0.0, 0.0,
            0.0, scale.toDouble(), 0.0,
            0.0, 0.0, 1.0,
        )
    )

    /** Corners of the tile, projected into canvas space. */
    private fun corners(transform: Homography): List<FloatArray> = listOf(
        transform.apply(0f, 0f),
        transform.apply(tileWidth.toFloat(), 0f),
        transform.apply(0f, tileHeight.toFloat()),
        transform.apply(tileWidth.toFloat(), tileHeight.toFloat()),
    )

    private fun landsOnCanvas(transform: Homography): Boolean {
        val c = corners(transform)
        if (c.any { it[0].isNaN() || it[1].isNaN() }) return false
        val minX = c.minOf { it[0] }
        val maxX = c.maxOf { it[0] }
        val minY = c.minOf { it[1] }
        val maxY = c.maxOf { it[1] }
        // Some part of it has to be on the canvas to be worth compositing.
        return maxX > 0 && maxY > 0 && minX < canvasWidth && minY < canvasHeight
    }

    private fun cellsUnder(transform: Homography): List<Int> {
        val c = corners(transform)
        val minX = c.minOf { it[0] }.coerceAtLeast(0f)
        val maxX = c.maxOf { it[0] }.coerceAtMost(canvasWidth.toFloat())
        val minY = c.minOf { it[1] }.coerceAtLeast(0f)
        val maxY = c.maxOf { it[1] }.coerceAtMost(canvasHeight.toFloat())
        if (maxX <= minX || maxY <= minY) return emptyList()

        val c0 = (minX / canvasWidth * gridColumns).toInt().coerceIn(0, gridColumns - 1)
        val c1 = (maxX / canvasWidth * gridColumns).toInt().coerceIn(0, gridColumns - 1)
        val r0 = (minY / canvasHeight * gridRows).toInt().coerceIn(0, gridRows - 1)
        val r1 = (maxY / canvasHeight * gridRows).toInt().coerceIn(0, gridRows - 1)

        val out = ArrayList<Int>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (r in min(r0, r1)..max(r0, r1)) {
            for (col in min(c0, c1)..max(c0, c1)) {
                out.add(r * gridColumns + col)
            }
        }
        return out
    }

    /** Coverage as a grid, for drawing the sweep guide. */
    fun coverageGrid(): Array<BooleanArray> = Array(gridRows) { r ->
        BooleanArray(gridColumns) { c -> covered[r * gridColumns + c] }
    }

    fun reset() {
        placements.clear()
        placedProxies.clear()
        previousProxy = null
        previousTransform = null
        covered.fill(false)
    }

    private companion object {
        /** How far away, in tile widths, a placed tile can still share ground. */
        const val NEIGHBOUR_REACH = 1.4f

        /**
         * Tiles a new frame is registered against at most.
         *
         * Registration is the expensive part of accepting a frame, and the
         * nearest few neighbours carry almost all the information.
         */
        const val MAX_NEIGHBOURS = 4
    }
}
