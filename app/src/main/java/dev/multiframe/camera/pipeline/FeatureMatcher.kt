package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A corner worth trying to match, with how strong it is. */
data class Corner(val x: Int, val y: Int, val strength: Float)

/**
 * Finds correspondences between two overlapping frames.
 *
 * Feeds [Homography], which needs point pairs and has no opinion about where
 * they come from. Deliberately not a general-purpose feature framework: it is
 * built for the one case the mosaic produces, which is two frames of the same
 * scene taken seconds apart with substantial overlap and a few degrees of
 * rotation between them.
 *
 * ### The choices, and why
 *
 * **Shi-Tomasi corners rather than edges.** A point on an edge can slide along
 * it without the image changing, so it cannot be located in two dimensions --
 * the aperture problem. Corners are where both eigenvalues of the local
 * gradient structure are large, meaning the patch is pinned in both directions.
 * Taking the smaller eigenvalue as the score is exactly the statement "the
 * worse-constrained direction is still well constrained".
 *
 * **Spread across a grid.** Corner strength clusters: a tree or a brick wall
 * will produce thousands of strong corners in one part of the frame and
 * nothing elsewhere. A homography fitted to points from one corner of the
 * image is badly conditioned and extrapolates terribly across the rest. Taking
 * the best few from each cell of a grid costs nothing and fixes it.
 *
 * **Normalised patches rather than binary descriptors.** A zero-mean,
 * unit-norm patch compared by correlation is invariant to any brightness and
 * contrast change, which matters when auto-exposure has moved between frames.
 * Binary descriptors are faster and this runs on small proxies where that does
 * not matter.
 *
 * **A ratio test.** The single most valuable line here. If the best match is
 * barely better than the second best, the patch is ambiguous -- a repeated
 * window, one brick among many -- and the match is more likely wrong than
 * right. Discarding those before RANSAC ever sees them removes most bad
 * matches at their source.
 */
object FeatureMatcher {

    /**
     * Half-width of the descriptor patch.
     *
     * Large enough to reach past the feature onto its surroundings. A patch
     * that contains only the feature cannot distinguish one window of a
     * building from the next, or one leaf from another: every candidate
     * correlates almost perfectly and the match is decided by noise. Measured
     * on a scene of many similar blobs, a 9x9 patch matched 159 points of which
     * 7 agreed on a transform, while a 15x15 patch on the same pair agreed
     * almost unanimously. Context is what makes a feature identifiable.
     */
    private const val PATCH = 7
    private const val PATCH_SPAN = PATCH * 2 + 1
    private const val PATCH_AREA = PATCH_SPAN * PATCH_SPAN

    /** Cells the frame is divided into when spreading corners out. */
    private const val GRID = 8

    /**
     * A match must beat the runner-up by this much to be believed. Lowe's
     * original threshold for this test was 0.8, and the value transfers.
     */
    private const val RATIO_TEST = 0.8f

    /** Below this, the runner-up is as good as the winner and neither is trusted. */
    private const val AMBIGUITY_EPSILON = 1e-4f

    private fun at(p: Plane, x: Int, y: Int): Int =
        p.data[y.coerceIn(0, p.height - 1) * p.width + x.coerceIn(0, p.width - 1)].toInt() and 0xFF

    /**
     * Shi-Tomasi corner response: the smaller eigenvalue of the local gradient
     * structure tensor, which is how strongly the patch is pinned in its
     * worst-constrained direction.
     */
    fun cornerStrength(p: Plane, x: Int, y: Int, window: Int = 2): Float {
        var ixx = 0f
        var iyy = 0f
        var ixy = 0f
        for (dy in -window..window) {
            for (dx in -window..window) {
                val gx = (at(p, x + dx + 1, y + dy) - at(p, x + dx - 1, y + dy)) * 0.5f
                val gy = (at(p, x + dx, y + dy + 1) - at(p, x + dx, y + dy - 1)) * 0.5f
                ixx += gx * gx
                iyy += gy * gy
                ixy += gx * gy
            }
        }
        // Smaller eigenvalue of [[ixx, ixy], [ixy, iyy]].
        val trace = ixx + iyy
        val det = ixx * iyy - ixy * ixy
        val disc = max(0f, trace * trace * 0.25f - det)
        return trace * 0.5f - sqrt(disc)
    }

    /**
     * Strong corners spread across the frame, strongest first within each cell.
     */
    fun detect(p: Plane, perCell: Int = 4, minSeparation: Int = 6): List<Corner> {
        val margin = PATCH + 2
        if (p.width <= margin * 2 || p.height <= margin * 2) return emptyList()

        val cellW = max(1, (p.width - margin * 2) / GRID)
        val cellH = max(1, (p.height - margin * 2) / GRID)
        val found = ArrayList<Corner>(GRID * GRID * perCell)

        for (cy in 0 until GRID) {
            for (cx in 0 until GRID) {
                val x0 = margin + cx * cellW
                val y0 = margin + cy * cellH
                val x1 = min(x0 + cellW, p.width - margin)
                val y1 = min(y0 + cellH, p.height - margin)
                if (x1 <= x0 || y1 <= y0) continue

                // Every pixel, not every second one. Scanning on a stride
                // quantises corner positions to even coordinates, so any
                // displacement with an odd component puts the detected corner a
                // pixel away from the real one in one frame and not the other.
                // The descriptor is then sampled off-centre and correlates
                // worse with its true partner than with some unrelated
                // look-alike. Measured on a scene of similar features, a
                // ten-by-five pixel shift matched 145 points of which 8 agreed;
                // scanning every pixel fixed it. Real displacements are
                // arbitrary, so this was never going to show up only in tests.
                val cell = ArrayList<Corner>(64)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val s = cornerStrength(p, x, y)
                        if (s > 1f) cell.add(Corner(x, y, s))
                    }
                }
                cell.sortByDescending { it.strength }

                // Keep them apart, or a single strong feature contributes the
                // whole cell's quota from within a few pixels.
                val kept = ArrayList<Corner>(perCell)
                for (candidate in cell) {
                    if (kept.size >= perCell) break
                    if (kept.none {
                            abs(it.x - candidate.x) < minSeparation &&
                                abs(it.y - candidate.y) < minSeparation
                        }
                    ) {
                        kept.add(candidate)
                    }
                }
                found.addAll(kept)
            }
        }
        return found
    }

    /**
     * Zero-mean, unit-norm patch around a corner, or null where it is flat.
     *
     * Normalising is what makes the comparison immune to exposure and contrast
     * differences between frames.
     */
    fun describe(p: Plane, corner: Corner): FloatArray? {
        val out = FloatArray(PATCH_AREA)
        var mean = 0f
        var i = 0
        for (dy in -PATCH..PATCH) {
            for (dx in -PATCH..PATCH) {
                val v = at(p, corner.x + dx, corner.y + dy).toFloat()
                out[i++] = v
                mean += v
            }
        }
        mean /= PATCH_AREA

        var norm = 0f
        for (j in out.indices) {
            out[j] -= mean
            norm += out[j] * out[j]
        }
        norm = sqrt(norm)
        if (norm < 1e-3f) return null      // featureless patch
        for (j in out.indices) out[j] /= norm
        return out
    }

    /** Correlation of two unit-norm descriptors: 1 identical, 0 unrelated. */
    private fun correlation(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * Correspondences from [a] into [b].
     *
     * [searchRadius] bounds how far a feature may have moved. Leaving it
     * unbounded is both slower and worse: a distant patch that happens to look
     * similar is a plausible match only if you allow it to be.
     */
    fun match(
        a: Plane,
        b: Plane,
        searchRadius: Int = Int.MAX_VALUE,
        minCorrelation: Float = 0.75f,
    ): List<Match> {
        val cornersA = detect(a)
        val cornersB = detect(b)
        if (cornersA.isEmpty() || cornersB.isEmpty()) return emptyList()

        val describedB = cornersB.mapNotNull { c -> describe(b, c)?.let { c to it } }
        if (describedB.isEmpty()) return emptyList()

        val matches = ArrayList<Match>(cornersA.size)
        for (corner in cornersA) {
            val descriptor = describe(a, corner) ?: continue

            var best = -2f
            var second = -2f
            var bestCorner: Corner? = null

            for ((candidate, candidateDescriptor) in describedB) {
                if (searchRadius != Int.MAX_VALUE) {
                    if (abs(candidate.x - corner.x) > searchRadius ||
                        abs(candidate.y - corner.y) > searchRadius
                    ) continue
                }
                val score = correlation(descriptor, candidateDescriptor)
                if (score > best) {
                    second = best
                    best = score
                    bestCorner = candidate
                } else if (score > second) {
                    second = score
                }
            }

            val winner = bestCorner ?: continue
            if (best < minCorrelation) continue

            // The ratio test. An ambiguous match is worse than no match: it is
            // confident, wrong, and indistinguishable from a good one later.
            val distanceBest = 1f - best
            val distanceSecond = 1f - second
            // A runner-up as good as the winner means the feature is not
            // identifiable at all -- one window of a building among many, one
            // period of a fence. This case has to be *rejected*, and getting
            // the degenerate branch backwards is easy: guarding the ratio with
            // "only test when the runner-up is imperfect" quietly keeps exactly
            // the matches that are least trustworthy. A perfectly repeating
            // pattern then resolves to a confident transform that is not even a
            // multiple of its own period.
            if (distanceSecond <= AMBIGUITY_EPSILON) continue
            if (distanceBest / distanceSecond > RATIO_TEST) continue

            matches.add(
                Match(
                    corner.x.toFloat(), corner.y.toFloat(),
                    winner.x.toFloat(), winner.y.toFloat(),
                )
            )
        }
        return matches
    }

    /**
     * Registers [b] onto [a], or returns null when they cannot be related.
     *
     * Null is a real answer: two frames that do not overlap have no transform
     * between them, and inventing one produces a torn mosaic rather than a
     * failed one.
     */
    fun register(a: Plane, b: Plane, threshold: Float = 2.5f): HomographyFit? {
        val matches = match(a, b)
        if (matches.size < 12) return null
        return Homography.ransac(matches, threshold = threshold, iterations = 600)
            ?.takeIf { it.isReliable }
    }
}
