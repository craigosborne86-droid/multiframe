package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

/** A point correspondence between two images. */
data class Match(val ax: Float, val ay: Float, val bx: Float, val by: Float)

/**
 * A projective transform between two views of the same plane.
 *
 * ### Why the burst aligner is not enough
 *
 * [Aligner] estimates a translation per tile, which is the right model for a
 * handheld burst: over a third of a second the camera barely rotates, so the
 * scene shifts and does not otherwise change shape. Sweeping a telephoto across
 * a scene is a different problem entirely. The camera *rotates*, and rotation
 * about the optical centre maps the image plane through a projectivity: lines
 * stay straight but parallel lines converge, squares become trapezia, and
 * scale varies across the frame. No amount of translation fits that.
 *
 * Eight degrees of freedom is exactly the right model here, and it is exact
 * rather than approximate: for a camera rotating about its optical centre, the
 * mapping between two views is a homography with no residual, whatever the
 * scene geometry. That is the mathematical fact the whole feature rests on.
 *
 * The assumption it does need is that the camera rotates rather than
 * translates. Hand-held panning moves the centre by a few centimetres, which is
 * negligible against a distant subject and very much not negligible against a
 * near one. This is the same reason phone panoramas tear on close objects, and
 * it is a property of the world rather than of the implementation.
 */
class Homography(val m: DoubleArray) {

    init {
        require(m.size == 9) { "a homography is 3x3" }
    }

    /** Maps a point from the source image into the destination image. */
    fun apply(x: Float, y: Float): FloatArray {
        val w = m[6] * x + m[7] * y + m[8]
        if (abs(w) < 1e-12) return floatArrayOf(Float.NaN, Float.NaN)
        return floatArrayOf(
            ((m[0] * x + m[1] * y + m[2]) / w).toFloat(),
            ((m[3] * x + m[4] * y + m[5]) / w).toFloat(),
        )
    }

    /** Composition: applying [other] and then this. */
    fun times(other: Homography): Homography {
        val r = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) sum += m[i * 3 + k] * other.m[k * 3 + j]
                r[i * 3 + j] = sum
            }
        }
        return Homography(r)
    }

    fun invert(): Homography? {
        val a = m
        val det =
            a[0] * (a[4] * a[8] - a[5] * a[7]) -
                a[1] * (a[3] * a[8] - a[5] * a[6]) +
                a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val inv = DoubleArray(9)
        inv[0] = (a[4] * a[8] - a[5] * a[7]) / det
        inv[1] = (a[2] * a[7] - a[1] * a[8]) / det
        inv[2] = (a[1] * a[5] - a[2] * a[4]) / det
        inv[3] = (a[5] * a[6] - a[3] * a[8]) / det
        inv[4] = (a[0] * a[8] - a[2] * a[6]) / det
        inv[5] = (a[2] * a[3] - a[0] * a[5]) / det
        inv[6] = (a[3] * a[7] - a[4] * a[6]) / det
        inv[7] = (a[1] * a[6] - a[0] * a[7]) / det
        inv[8] = (a[0] * a[4] - a[1] * a[3]) / det
        return Homography(inv)
    }

    /** Distance between where [match] lands and where it should. */
    fun reprojectionError(match: Match): Float {
        val p = apply(match.ax, match.ay)
        if (p[0].isNaN()) return Float.MAX_VALUE
        val dx = p[0] - match.bx
        val dy = p[1] - match.by
        return sqrt(dx * dx + dy * dy)
    }

    companion object {

        val IDENTITY = Homography(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        )

        fun translation(dx: Double, dy: Double) = Homography(
            doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0)
        )

        /**
         * Least-squares fit through the direct linear transform.
         *
         * Coordinates are normalised first -- centroid to the origin, mean
         * distance to sqrt(2) -- and the result is un-normalised afterwards.
         * That step is not optional housekeeping: in raw pixel coordinates the
         * DLT matrix mixes terms of order 1 with terms of order 10^6, and the
         * resulting conditioning makes the fit visibly wrong long before it
         * fails outright.
         */
        fun fit(matches: List<Match>): Homography? {
            if (matches.size < 4) return null

            val na = normalise(matches.map { it.ax to it.ay }) ?: return null
            val nb = normalise(matches.map { it.bx to it.by }) ?: return null

            // Two rows per correspondence, with h33 fixed at 1.
            val rows = matches.size * 2
            val a = Array(rows) { DoubleArray(8) }
            val rhs = DoubleArray(rows)

            for (i in matches.indices) {
                val (x, y) = na.points[i]
                val (u, v) = nb.points[i]
                val r = i * 2

                a[r][0] = x; a[r][1] = y; a[r][2] = 1.0
                a[r][3] = 0.0; a[r][4] = 0.0; a[r][5] = 0.0
                a[r][6] = -u * x; a[r][7] = -u * y
                rhs[r] = u

                a[r + 1][0] = 0.0; a[r + 1][1] = 0.0; a[r + 1][2] = 0.0
                a[r + 1][3] = x; a[r + 1][4] = y; a[r + 1][5] = 1.0
                a[r + 1][6] = -v * x; a[r + 1][7] = -v * y
                rhs[r + 1] = v
            }

            val h = solveLeastSquares(a, rhs, 8) ?: return null
            val normalised = Homography(
                doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
            )

            // Undo both normalisations: H = Tb^-1 * Hn * Ta
            val inverseB = nb.transform.invert() ?: return null
            return inverseB.times(normalised).times(na.transform)
        }

        /**
         * Fits a transform that most correspondences agree with, ignoring the
         * ones that do not.
         *
         * Feature matching on real images always produces some wrong pairs --
         * repeated windows on a building, foliage, a reflection. A least-squares
         * fit has no defence against them: a single bad match at the edge of the
         * frame can drag the whole transform. Fitting to random minimal subsets
         * and keeping whichever fit the most points agree with is what makes
         * this survive contact with real scenes.
         */
        fun ransac(
            matches: List<Match>,
            threshold: Float = 3f,
            iterations: Int = 500,
            seed: Int = 1,
        ): HomographyFit? {
            if (matches.size < 4) return null
            val rnd = kotlin.random.Random(seed)

            var bestInliers: List<Match> = emptyList()
            var best: Homography? = null

            repeat(iterations) {
                val sample = pickFour(matches, rnd) ?: return@repeat
                val candidate = fit(sample) ?: return@repeat
                val inliers = matches.filter { candidate.reprojectionError(it) <= threshold }
                if (inliers.size > bestInliers.size) {
                    bestInliers = inliers
                    best = candidate
                }
            }

            if (best == null || bestInliers.size < 4) return null
            // Refit on everything that agreed: the minimal sample fixed the
            // model, but all the inliers together locate it more precisely.
            val refined = fit(bestInliers) ?: best!!
            val finalInliers = matches.filter { refined.reprojectionError(it) <= threshold }
            return if (finalInliers.size >= bestInliers.size) {
                HomographyFit(refined, finalInliers, matches.size)
            } else {
                HomographyFit(best!!, bestInliers, matches.size)
            }
        }

        private fun pickFour(matches: List<Match>, rnd: kotlin.random.Random): List<Match>? {
            if (matches.size < 4) return null
            val chosen = HashSet<Int>(4)
            var guard = 0
            while (chosen.size < 4 && guard < 64) {
                chosen.add(rnd.nextInt(matches.size))
                guard++
            }
            if (chosen.size < 4) return null
            return chosen.map { matches[it] }
        }

        private class Normalised(
            val points: List<Pair<Double, Double>>,
            val transform: Homography,
        )

        /** Hartley normalisation: centroid to origin, mean distance to sqrt(2). */
        private fun normalise(points: List<Pair<Float, Float>>): Normalised? {
            if (points.isEmpty()) return null
            var cx = 0.0
            var cy = 0.0
            for ((x, y) in points) { cx += x; cy += y }
            cx /= points.size
            cy /= points.size

            var mean = 0.0
            for ((x, y) in points) {
                mean += sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            }
            mean /= points.size
            if (mean < 1e-9) return null

            val scale = sqrt(2.0) / mean
            val transform = Homography(
                doubleArrayOf(scale, 0.0, -scale * cx, 0.0, scale, -scale * cy, 0.0, 0.0, 1.0)
            )
            return Normalised(
                points.map { (x, y) -> (x - cx) * scale to (y - cy) * scale },
                transform,
            )
        }

        /**
         * Solves an over-determined system through the normal equations, with
         * partial pivoting. Returns null when the system is rank deficient,
         * which is what four collinear points produce.
         */
        internal fun solveLeastSquares(
            a: Array<DoubleArray>,
            b: DoubleArray,
            unknowns: Int,
        ): DoubleArray? {
            val ata = Array(unknowns) { DoubleArray(unknowns + 1) }
            for (i in 0 until unknowns) {
                for (j in 0 until unknowns) {
                    var sum = 0.0
                    for (r in a.indices) sum += a[r][i] * a[r][j]
                    ata[i][j] = sum
                }
                var sum = 0.0
                for (r in a.indices) sum += a[r][i] * b[r]
                ata[i][unknowns] = sum
            }

            for (col in 0 until unknowns) {
                var pivot = col
                for (r in col + 1 until unknowns) {
                    if (abs(ata[r][col]) > abs(ata[pivot][col])) pivot = r
                }
                if (abs(ata[pivot][col]) < 1e-12) return null
                val tmp = ata[col]; ata[col] = ata[pivot]; ata[pivot] = tmp

                for (r in 0 until unknowns) {
                    if (r == col) continue
                    val factor = ata[r][col] / ata[col][col]
                    if (factor == 0.0) continue
                    for (c in col..unknowns) ata[r][c] -= factor * ata[col][c]
                }
            }

            val out = DoubleArray(unknowns)
            for (i in 0 until unknowns) {
                if (abs(ata[i][i]) < 1e-12) return null
                out[i] = ata[i][unknowns] / ata[i][i]
            }
            return out
        }
    }
}

/** A fitted transform together with how much of the evidence supported it. */
data class HomographyFit(
    val homography: Homography,
    val inliers: List<Match>,
    val totalMatches: Int,
) {
    val inlierRatio: Float
        get() = if (totalMatches == 0) 0f else inliers.size.toFloat() / totalMatches

    /**
     * Whether this fit should be trusted enough to stitch with.
     *
     * A homography can always be fitted to four points; whether it describes
     * the scene is a different question. Stitching on a confident wrong
     * transform produces a torn image rather than a failed one, which is worse.
     *
     * The test is the probabilistic one from Brown and Lowe's automatic
     * panorama work: a fit is believed when the inlier count exceeds
     * 5.9 + 0.22 * candidates. The shape of that expression is the point. A
     * fixed *ratio* is the intuitive rule and it is wrong, because it gets
     * harder to satisfy the more features a scene offers: a detailed frame
     * yields hundreds of candidate matches, most of them junk, and sixty
     * agreeing on one transform is overwhelming evidence that a ratio test
     * would reject as a third. The constant term is what makes a sparse scene
     * with few candidates still have to clear a real bar.
     */
    val isReliable: Boolean
        get() = inliers.size >= MIN_INLIERS &&
            inliers.size > 5.9f + 0.22f * totalMatches

    companion object {
        /** Below this a fit is not worth trusting however few candidates there were. */
        const val MIN_INLIERS = 12
    }
}
