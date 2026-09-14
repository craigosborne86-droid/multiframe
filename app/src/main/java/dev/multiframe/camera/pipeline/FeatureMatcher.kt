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

    /** A corner has to be at least this fraction of the frame's strongest to be one. */
    private const val RELATIVE_FLOOR = 0.02f

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
     * The corner response at every pixel, in linear time.
     *
     * [cornerStrength] sums the gradient tensor over a 5x5 window afresh for
     * each pixel, twenty-five gradient pairs a pixel, four reads each. Called
     * at every pixel of a 320x240 tracking frame that is eight million reads
     * for the response alone, and the guidance registered every frame that
     * way ten times a second. The response is separable: the three tensor
     * terms are computed once per pixel and box-summed in two passes, so each
     * pixel costs a handful of operations however wide the window. The sum is
     * the same sum in a different order, which floating point makes a
     * last-place matter and nothing more.
     */
    internal fun strengthMap(p: Plane, window: Int = 2): FloatArray {
        val w = p.width
        val h = p.height
        val n = w * h
        val ixx = FloatArray(n)
        val iyy = FloatArray(n)
        val ixy = FloatArray(n)
        val d = p.data
        for (y in 0 until h) {
            val yu = if (y > 0) y - 1 else 0
            val yd = if (y < h - 1) y + 1 else h - 1
            val row = y * w
            val rowU = yu * w
            val rowD = yd * w
            for (x in 0 until w) {
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < w - 1) x + 1 else w - 1
                val gx = ((d[row + xr].toInt() and 0xFF) - (d[row + xl].toInt() and 0xFF)) * 0.5f
                val gy = ((d[rowD + x].toInt() and 0xFF) - (d[rowU + x].toInt() and 0xFF)) * 0.5f
                ixx[row + x] = gx * gx
                iyy[row + x] = gy * gy
                ixy[row + x] = gx * gy
            }
        }
        // Box sums over the window, horizontally then vertically, as sliding
        // sums: one add and one subtract per pixel whatever the window. The
        // edges are clamped as cornerStrength clamps its reads, so the sum at
        // the border counts the border pixel for every tap past it.
        boxSumInPlace(ixx, w, h, window)
        boxSumInPlace(iyy, w, h, window)
        boxSumInPlace(ixy, w, h, window)
        val out = ixx  // reused: the response replaces the summed xx term
        for (i in 0 until n) {
            val sxx = ixx[i]
            val syy = iyy[i]
            val sxy = ixy[i]
            val trace = sxx + syy
            val det = sxx * syy - sxy * sxy
            val disc = max(0f, trace * trace * 0.25f - det)
            out[i] = trace * 0.5f - sqrt(disc)
        }
        return out
    }

    /** Separable sliding box sum of radius [window], clamped at the edges, in place. */
    private fun boxSumInPlace(a: FloatArray, w: Int, h: Int, window: Int) {
        val span = maxOf(w, h)
        val line = FloatArray(span)
        // Rows.
        for (y in 0 until h) {
            val row = y * w
            System.arraycopy(a, row, line, 0, w)
            var sum = 0f
            for (dx in -window..window) sum += line[dx.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                a[row + x] = sum
                sum += line[(x + window + 1).coerceIn(0, w - 1)] - line[(x - window).coerceIn(0, w - 1)]
            }
        }
        // Columns.
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = a[y * w + x]
            var sum = 0f
            for (dy in -window..window) sum += line[dy.coerceIn(0, h - 1)]
            for (y in 0 until h) {
                a[y * w + x] = sum
                sum += line[(y + window + 1).coerceIn(0, h - 1)] - line[(y - window).coerceIn(0, h - 1)]
            }
        }
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
        val strength = strengthMap(p)

        // A floor relative to the frame's strongest response, as well as the
        // absolute one. On a frame with real corners the strongest response
        // is in the thousands and a response of a few tens is the noise on a
        // flat wall -- a "corner" that is in a different place in the next
        // frame, matches something by chance, and hands RANSAC a wrong pair.
        // A static phone on a plain desk registered against its own anchor
        // one poll in three until this: two hundred and fifty corners, seventy
        // matches, a dozen inliers. On a frame with no real corners the
        // strongest response is itself noise and the floor stays at one, so
        // a flat scene still offers what it has rather than nothing.
        var strongest = 0f
        for (v in strength) if (v > strongest) strongest = v
        val floor = max(1f, strongest * RELATIVE_FLOOR)

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
                //
                // But only the local maxima are candidates. The first version
                // made a Corner of every pixel whose response cleared one and
                // sorted them: on a textured frame that is most of the cell,
                // tens of thousands of objects a frame, and it was 44 ms of the
                // 73 the guidance spent per poll. A corner is a peak of the
                // response; a pixel on the shoulder of a peak is the same
                // corner a pixel off, and minSeparation was discarding it
                // anyway after it had been allocated and sorted.
                val w = p.width
                val cell = ArrayList<Corner>(64)
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val s = strength[row + x]
                        if (s <= floor) continue
                        if (s < strength[row + x - 1] || s < strength[row + x + 1] ||
                            s < strength[row - w + x] || s < strength[row + w + x] ||
                            s < strength[row - w + x - 1] || s < strength[row - w + x + 1] ||
                            s < strength[row + w + x - 1] || s < strength[row + w + x + 1]
                        ) continue
                        cell.add(Corner(x, y, s))
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
     * A frame's corners and their descriptors, extracted once.
     *
     * Registration used to detect and describe both frames on every call.
     * For a tile that has been placed that is the same answer every time,
     * and for the frame the guidance is tracking it is the same answer for
     * every tile it is registered against. Extract once, match many.
     */
    class Features internal constructor(
        val width: Int,
        val height: Int,
        internal val corners: List<Corner>,
        internal val descriptors: List<FloatArray>,
        /** Each descriptor's [COARSE] x [COARSE] block means, unit-norm, for the shortlist. */
        internal val coarse: List<FloatArray>,
    ) {
        val size: Int get() = corners.size
    }

    fun extract(p: Plane): Features {
        val corners = ArrayList<Corner>()
        val descriptors = ArrayList<FloatArray>()
        val coarse = ArrayList<FloatArray>()
        for (c in detect(p)) {
            val d = describe(p, c) ?: continue
            corners.add(c)
            descriptors.add(d)
            coarse.add(coarsen(d))
        }
        return Features(p.width, p.height, corners, descriptors, coarse)
    }

    /** Side of the coarse summary; the 15x15 patch in 3x3 blocks of 5x5. */
    private const val COARSE = 3

    /** Candidates per corner that get the full correlation. */
    private const val SHORTLIST = 12

    private fun coarsen(d: FloatArray): FloatArray {
        val block = PATCH_SPAN / COARSE
        val out = FloatArray(COARSE * COARSE)
        for (by in 0 until COARSE) for (bx in 0 until COARSE) {
            var sum = 0f
            for (dy in 0 until block) for (dx in 0 until block) {
                sum += d[(by * block + dy) * PATCH_SPAN + bx * block + dx]
            }
            out[by * COARSE + bx] = sum
        }
        var norm = 0f
        for (v in out) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-6f) for (i in out.indices) out[i] /= norm
        return out
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
    ): List<Match> = match(extract(a), extract(b), searchRadius, minCorrelation)

    /** [match] on features already extracted. */
    fun match(
        a: Features,
        b: Features,
        searchRadius: Int = Int.MAX_VALUE,
        minCorrelation: Float = 0.75f,
    ): List<Match> {
        if (a.size == 0 || b.size == 0) return emptyList()

        // Two stages. Correlating every pair of 225-value descriptors was
        // 15 million multiply-adds a frame and 20 ms of the guidance's poll;
        // the coarse summaries -- nine values, the patch in 3x3 blocks -- pick
        // a shortlist for each corner at a twenty-fifth of the cost, and the
        // full correlation runs on the shortlist alone. A candidate whose
        // blocks do not resemble the corner's is not one the full patch would
        // have matched either; what the shortlist can miss is the runner-up
        // the ratio test wants, when the runner-up is a look-alike at fine
        // scale only, and a look-alike at fine scale is coarse-alike too.
        val matches = ArrayList<Match>(a.size)
        val shortlistIdx = IntArray(SHORTLIST)
        val shortlistScore = FloatArray(SHORTLIST)
        for (ia in a.corners.indices) {
            val corner = a.corners[ia]
            val descriptor = a.descriptors[ia]
            val coarseA = a.coarse[ia]

            // The shortlist: the SHORTLIST best coarse scores, unordered.
            var listed = 0
            for (ib in b.corners.indices) {
                val candidate = b.corners[ib]
                if (searchRadius != Int.MAX_VALUE) {
                    if (abs(candidate.x - corner.x) > searchRadius ||
                        abs(candidate.y - corner.y) > searchRadius
                    ) continue
                }
                val c = correlation(coarseA, b.coarse[ib])
                if (listed < SHORTLIST) {
                    shortlistIdx[listed] = ib
                    shortlistScore[listed] = c
                    listed++
                } else {
                    var worst = 0
                    for (k in 1 until SHORTLIST) if (shortlistScore[k] < shortlistScore[worst]) worst = k
                    if (c > shortlistScore[worst]) {
                        shortlistIdx[worst] = ib
                        shortlistScore[worst] = c
                    }
                }
            }

            var best = -2f
            var second = -2f
            var bestCorner: Corner? = null

            for (k in 0 until listed) {
                val ib = shortlistIdx[k]
                val candidate = b.corners[ib]
                val score = correlation(descriptor, b.descriptors[ib])
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
    fun register(a: Plane, b: Plane, threshold: Float = 2.5f): HomographyFit? =
        register(extract(a), extract(b), threshold)

    /** [register] on features already extracted. */
    fun register(a: Features, b: Features, threshold: Float = 2.5f): HomographyFit? {
        val matches = match(a, b)
        if (matches.size < 12) return null
        return Homography.ransac(matches, threshold = threshold, iterations = 600)
            ?.takeIf { it.isReliable }
    }
}
