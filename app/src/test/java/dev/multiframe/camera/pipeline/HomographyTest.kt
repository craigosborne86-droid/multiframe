package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Projective registration.
 *
 * Every case here is one a telephoto sweep actually produces: rotation about
 * the optical centre, perspective from tilting, and a proportion of wrong
 * matches from repeated structure in the scene.
 */
class HomographyTest {

    /** Applies a known transform to build correspondences from it. */
    private fun correspondences(
        h: Homography,
        points: List<Pair<Float, Float>>,
    ): List<Match> = points.map { (x, y) ->
        val p = h.apply(x, y)
        Match(x, y, p[0], p[1])
    }

    private val grid = buildList {
        for (gy in 0 until 5) for (gx in 0 until 5) {
            add(120f + gx * 190f to 100f + gy * 150f)
        }
    }

    // ------------------------------------------------------------------
    // Recovering known transforms.
    // ------------------------------------------------------------------

    @Test
    fun `recovers a pure translation`() {
        val truth = Homography.translation(37.0, -21.0)

        val fitted = Homography.fit(correspondences(truth, grid))!!

        for ((x, y) in grid) {
            val expected = truth.apply(x, y)
            val actual = fitted.apply(x, y)
            assertThat(actual[0]).isWithin(0.01f).of(expected[0])
            assertThat(actual[1]).isWithin(0.01f).of(expected[1])
        }
    }

    @Test
    fun `recovers a rotation about the frame centre`() {
        // The motion a hand-held sweep produces most of.
        val angle = 0.14
        val cx = 600.0
        val cy = 400.0
        val truth = Homography(
            doubleArrayOf(
                cos(angle), -sin(angle), cx - cx * cos(angle) + cy * sin(angle),
                sin(angle), cos(angle), cy - cx * sin(angle) - cy * cos(angle),
                0.0, 0.0, 1.0,
            )
        )

        val fitted = Homography.fit(correspondences(truth, grid))!!

        for ((x, y) in grid) {
            val expected = truth.apply(x, y)
            val actual = fitted.apply(x, y)
            assertThat(actual[0]).isWithin(0.02f).of(expected[0])
            assertThat(actual[1]).isWithin(0.02f).of(expected[1])
        }
    }

    @Test
    fun `recovers a genuine perspective transform`() {
        // The case translation-only alignment cannot represent at all: the
        // bottom of the frame scaled differently from the top.
        val truth = Homography(
            doubleArrayOf(
                1.04, 0.06, -18.0,
                -0.03, 0.98, 25.0,
                8.0e-5, 4.0e-5, 1.0,
            )
        )

        val fitted = Homography.fit(correspondences(truth, grid))!!

        for ((x, y) in grid) {
            val expected = truth.apply(x, y)
            val actual = fitted.apply(x, y)
            assertThat(actual[0]).isWithin(0.05f).of(expected[0])
            assertThat(actual[1]).isWithin(0.05f).of(expected[1])
        }
    }

    @Test
    fun `four points are enough`() {
        val truth = Homography(
            doubleArrayOf(1.02, 0.04, -10.0, -0.02, 1.01, 14.0, 5e-5, 2e-5, 1.0)
        )
        val four = grid.take(2) + grid.takeLast(2)

        val fitted = Homography.fit(correspondences(truth, four))!!

        for ((x, y) in four) {
            val expected = truth.apply(x, y)
            val actual = fitted.apply(x, y)
            assertThat(actual[0]).isWithin(0.05f).of(expected[0])
        }
    }

    // ------------------------------------------------------------------
    // Refusing to fit.
    // ------------------------------------------------------------------

    @Test
    fun `too few points is refused rather than guessed`() {
        assertThat(Homography.fit(emptyList())).isNull()
        assertThat(Homography.fit(correspondences(Homography.IDENTITY, grid.take(3)))).isNull()
    }

    @Test
    fun `collinear points are refused`() {
        // Four points on a line do not determine a plane's transform. Returning
        // some arbitrary solution would tear the mosaic rather than fail it.
        val line = (0 until 6).map { 100f + it * 50f to 200f }
        val truth = Homography.translation(10.0, 5.0)

        assertThat(Homography.fit(correspondences(truth, line))).isNull()
    }

    @Test
    fun `identical points are refused`() {
        val same = List(6) { 300f to 300f }

        assertThat(Homography.fit(correspondences(Homography.IDENTITY, same))).isNull()
    }

    // ------------------------------------------------------------------
    // Algebra.
    // ------------------------------------------------------------------

    @Test
    fun `inverting a transform undoes it`() {
        val h = Homography(
            doubleArrayOf(1.05, 0.03, -12.0, -0.02, 0.99, 20.0, 6e-5, 3e-5, 1.0)
        )
        val inverse = h.invert()!!

        for ((x, y) in grid) {
            val there = h.apply(x, y)
            val back = inverse.apply(there[0], there[1])
            assertThat(back[0]).isWithin(0.01f).of(x)
            assertThat(back[1]).isWithin(0.01f).of(y)
        }
    }

    @Test
    fun `composition matches applying both in turn`() {
        val a = Homography.translation(15.0, -8.0)
        val b = Homography(
            doubleArrayOf(1.03, 0.02, 5.0, -0.01, 1.02, -4.0, 3e-5, 1e-5, 1.0)
        )
        val composed = b.times(a)

        for ((x, y) in grid) {
            val stepwise = a.apply(x, y).let { b.apply(it[0], it[1]) }
            val direct = composed.apply(x, y)
            assertThat(direct[0]).isWithin(0.01f).of(stepwise[0])
            assertThat(direct[1]).isWithin(0.01f).of(stepwise[1])
        }
    }

    @Test
    fun `a singular transform reports that it cannot be inverted`() {
        val degenerate = Homography(
            doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0)
        )

        assertThat(degenerate.invert()).isNull()
    }

    // ------------------------------------------------------------------
    // Surviving bad matches, which real scenes always produce.
    // ------------------------------------------------------------------

    @Test
    fun `a single wild match does not drag the fit`() {
        // A least-squares fit has no defence against this. It is the reason
        // stitching needs RANSAC rather than a straight solve.
        val truth = Homography.translation(40.0, -15.0)
        val good = correspondences(truth, grid)
        val poisoned = good + Match(500f, 500f, 50f, 900f)

        val naive = Homography.fit(poisoned)!!
        val robust = Homography.ransac(poisoned)!!

        val naiveError = naive.reprojectionError(good[0])
        val robustError = robust.homography.reprojectionError(good[0])

        println("one outlier: least squares off by %.1fpx, RANSAC off by %.2fpx"
            .format(naiveError, robustError))

        assertThat(robustError).isLessThan(1f)
        assertThat(robustError).isLessThan(naiveError)
    }

    @Test
    fun `survives a third of the matches being wrong`() {
        // Repeated windows on a building, foliage and reflections routinely
        // produce this proportion of confident nonsense.
        val truth = Homography(
            doubleArrayOf(1.02, 0.05, -30.0, -0.04, 1.01, 18.0, 4e-5, 2e-5, 1.0)
        )
        val rnd = Random(4)
        val good = correspondences(truth, grid)
        val wrong = (0 until 12).map {
            Match(
                rnd.nextInt(0, 1000).toFloat(), rnd.nextInt(0, 800).toFloat(),
                rnd.nextInt(0, 1000).toFloat(), rnd.nextInt(0, 800).toFloat(),
            )
        }

        val fit = Homography.ransac(good + wrong, threshold = 2f, iterations = 800)!!

        println("with ${wrong.size} of ${good.size + wrong.size} matches wrong: " +
            "%d inliers, ratio %.2f".format(fit.inliers.size, fit.inlierRatio))

        assertThat(fit.isReliable).isTrue()
        assertThat(fit.inliers.size).isAtLeast(good.size - 2)
        for ((x, y) in grid) {
            val expected = truth.apply(x, y)
            val actual = fit.homography.apply(x, y)
            assertThat(actual[0]).isWithin(1f).of(expected[0])
        }
    }

    @Test
    fun `nothing but noise is reported as unreliable rather than fitted`() {
        // The important failure mode. Stitching on a confident wrong transform
        // produces a torn image; refusing produces an honest message.
        val rnd = Random(9)
        val noise = (0 until 40).map {
            Match(
                rnd.nextInt(0, 1000).toFloat(), rnd.nextInt(0, 800).toFloat(),
                rnd.nextInt(0, 1000).toFloat(), rnd.nextInt(0, 800).toFloat(),
            )
        }

        val fit = Homography.ransac(noise, threshold = 2f)

        assertThat(fit == null || !fit.isReliable).isTrue()
    }

    @Test
    fun `normalisation keeps large pixel coordinates accurate`() {
        // Without Hartley normalisation the DLT matrix mixes terms of order 1
        // with terms of order 10^7 at these coordinates, and the fit degrades
        // long before it fails outright. Full-resolution frames are this big.
        val big = buildList {
            for (gy in 0 until 4) for (gx in 0 until 4) {
                add(200f + gx * 1200f to 150f + gy * 900f)
            }
        }
        val truth = Homography(
            doubleArrayOf(1.01, 0.02, -40.0, -0.015, 1.005, 60.0, 2e-6, 1e-6, 1.0)
        )

        val fitted = Homography.fit(correspondences(truth, big))!!

        for ((x, y) in big) {
            val expected = truth.apply(x, y)
            val actual = fitted.apply(x, y)
            assertThat(actual[0]).isWithin(0.5f).of(expected[0])
            assertThat(actual[1]).isWithin(0.5f).of(expected[1])
        }
    }
}
