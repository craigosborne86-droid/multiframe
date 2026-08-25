package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * Reconciling a mosaic against every measurement rather than a chain of them.
 *
 * The scenario throughout is the one that matters: a sweep whose joins each look
 * perfect but whose ends disagree, which is what accumulated error looks like
 * from the inside.
 */
class MosaicRefinerTest {

    private val tile = 400
    private val step = 260.0

    private fun translation(dx: Double, dy: Double) = Homography.translation(dx, dy)

    /**
     * A row of tiles placed by chaining, with a small error added at each join
     * -- which is exactly how pairwise registration fails.
     */
    private fun driftedRow(count: Int, errorPerJoin: Double): List<Homography> {
        var x = 0.0
        return (0 until count).map { i ->
            val placement = translation(x, 0.0)
            x += step + if (i == 0) 0.0 else errorPerJoin
            placement
        }
    }

    /** Consecutive overlaps, which chaining already uses. */
    private fun chainConstraints(count: Int): List<Constraint> =
        (0 until count - 1).map { i ->
            // Tile i's points appear in tile i+1 shifted back by one step.
            Constraint(i, i + 1, translation(-step, 0.0))
        }

    // ------------------------------------------------------------------

    @Test
    fun `nothing to reconcile leaves the placements alone`() {
        val placements = driftedRow(4, errorPerJoin = 0.0)

        val refined = MosaicRefiner.refine(placements, emptyList(), tile, tile)

        assertThat(refined).isEqualTo(placements)
    }

    @Test
    fun `the anchor never moves`() {
        // Every constraint is relative, so without a fixed tile the whole
        // mosaic can slide, rotate or scale freely without violating any of
        // them.
        val placements = driftedRow(5, errorPerJoin = 4.0)

        val refined = MosaicRefiner.refine(placements, chainConstraints(5), tile, tile)

        val before = placements[0].apply(0f, 0f)
        val after = refined[0].apply(0f, 0f)
        assertThat(after[0]).isWithin(1e-3f).of(before[0])
        assertThat(after[1]).isWithin(1e-3f).of(before[1])
    }

    @Test
    fun `disagreement is reduced`() {
        // The number refinement exists to lower, and the only honest way to say
        // whether it worked.
        val placements = driftedRow(6, errorPerJoin = 5.0)
        val constraints = chainConstraints(6)

        val before = MosaicRefiner.residual(placements, constraints, tile, tile)
        val refined = MosaicRefiner.refine(placements, constraints, tile, tile)
        val after = MosaicRefiner.residual(refined, constraints, tile, tile)

        println("residual %.2f -> %.2f px".format(before, after))
        assertThat(after).isLessThan(before)
    }

    @Test
    fun `a loop closure pulls the far end back`() {
        // The case chaining cannot see. A sweep that returns near its start
        // gives a measurement between two tiles that are far apart in the
        // chain, and only a joint reconciliation can use it.
        val count = 6
        val drift = 6.0
        val placements = driftedRow(count, errorPerJoin = drift)
        val chain = chainConstraints(count)
        // The last tile genuinely overlaps the first, five steps along.
        val closure = Constraint(0, count - 1, translation(-step * (count - 1), 0.0))

        val withoutClosure = MosaicRefiner.refine(placements, chain, tile, tile)
        val withClosure = MosaicRefiner.refine(placements, chain + closure, tile, tile)

        val truth = (step * (count - 1)).toFloat()
        val openEnd = withoutClosure.last().apply(0f, 0f)[0]
        val closedEnd = withClosure.last().apply(0f, 0f)[0]

        println("far end: truth %.1f, chained %.1f, closed %.1f"
            .format(truth, openEnd, closedEnd))
        assertThat(kotlin.math.abs(closedEnd - truth))
            .isLessThan(kotlin.math.abs(openEnd - truth))
    }

    @Test
    fun `a two dimensional sweep is reconciled in both directions`() {
        // A grid gives every interior tile two independent measurements -- from
        // the tile beside it and the tile above -- and chaining throws one away.
        val columns = 3
        val rows = 3
        val placements = ArrayList<Homography>()
        val rnd = Random(3)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                // Placed with a little noise, as pairwise fitting would.
                placements.add(
                    translation(
                        c * step + rnd.nextDouble(-4.0, 4.0),
                        r * step + rnd.nextDouble(-4.0, 4.0),
                    )
                )
            }
        }
        val constraints = ArrayList<Constraint>()
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val here = r * columns + c
                if (c + 1 < columns) {
                    constraints.add(Constraint(here, here + 1, translation(-step, 0.0)))
                }
                if (r + 1 < rows) {
                    constraints.add(
                        Constraint(here, here + columns, translation(0.0, -step))
                    )
                }
            }
        }

        val before = MosaicRefiner.residual(placements, constraints, tile, tile)
        val refined = MosaicRefiner.refine(placements, constraints, tile, tile)
        val after = MosaicRefiner.residual(refined, constraints, tile, tile)

        println("grid residual %.2f -> %.2f px".format(before, after))
        assertThat(after).isLessThan(before / 2f)
    }

    @Test
    fun `more passes do not make it worse`() {
        // Relaxation that overshoots would oscillate, and the mosaic would get
        // steadily more wrong the longer it ran.
        val placements = driftedRow(6, errorPerJoin = 5.0)
        val constraints = chainConstraints(6)

        var previous = MosaicRefiner.residual(placements, constraints, tile, tile)
        for (passes in listOf(1, 2, 4, 8, 16, 32)) {
            val refined = MosaicRefiner.refine(placements, constraints, tile, tile, passes)
            val residual = MosaicRefiner.residual(refined, constraints, tile, tile)
            assertThat(residual).isAtMost(previous + 0.01f)
            previous = residual
        }
        println("residual after 32 passes: %.3f px".format(previous))
    }

    @Test
    fun `a tile nobody measured is left where it was`() {
        // There is nothing to reconcile it against, and moving it would be a
        // guess dressed up as a correction.
        val placements = driftedRow(4, errorPerJoin = 5.0)
        // No constraint mentions tile 3.
        val constraints = listOf(
            Constraint(0, 1, translation(-step, 0.0)),
            Constraint(1, 2, translation(-step, 0.0)),
        )

        val refined = MosaicRefiner.refine(placements, constraints, tile, tile)

        val before = placements[3].apply(0f, 0f)
        val after = refined[3].apply(0f, 0f)
        assertThat(after[0]).isWithin(1e-3f).of(before[0])
    }

    @Test
    fun `a constraint naming a tile that does not exist is ignored`() {
        val placements = driftedRow(3, errorPerJoin = 2.0)
        val constraints = listOf(
            Constraint(0, 1, translation(-step, 0.0)),
            Constraint(1, 99, translation(-step, 0.0)),
        )

        val refined = MosaicRefiner.refine(placements, constraints, tile, tile)

        assertThat(refined).hasSize(3)
    }

    @Test
    fun `a singular constraint is ignored rather than propagated`() {
        val placements = driftedRow(3, errorPerJoin = 2.0)
        val degenerate = Homography(
            doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0)
        )
        val constraints = listOf(
            Constraint(0, 1, translation(-step, 0.0)),
            Constraint(1, 2, degenerate),
        )

        val refined = MosaicRefiner.refine(placements, constraints, tile, tile)

        for (h in refined) {
            val p = h.apply(0f, 0f)
            assertThat(p[0].isFinite()).isTrue()
            assertThat(p[1].isFinite()).isTrue()
        }
    }

    @Test
    fun `a single tile needs no reconciliation`() {
        val single = listOf(translation(0.0, 0.0))

        assertThat(MosaicRefiner.refine(single, emptyList(), tile, tile)).isEqualTo(single)
    }
}
