package dev.multiframe.camera.pipeline

/**
 * One measured relationship between two tiles.
 *
 * [transform] maps a point in tile [from]'s coordinates to the same scene point
 * in tile [to]'s coordinates, which is what [FeatureMatcher.register] produces.
 */
data class Constraint(
    val from: Int,
    val to: Int,
    val transform: Homography,
    /** How much this measurement is trusted, from the inlier count that made it. */
    val weight: Float = 1f,
)

/**
 * Reconciles a mosaic's tile placements with every measurement at once.
 *
 * ### The problem with chaining
 *
 * [MosaicAssembler] places each frame by registering it against the previous
 * one and composing onto that placement. During a sweep that is the natural
 * thing to do: consecutive frames overlap heavily and distant ones may not
 * overlap at all.
 *
 * It also integrates error. Every pairwise fit carries a small mistake, and a
 * chain adds them all up, so the far end of a long sweep drifts. Worse, the
 * drift is invisible while it happens: each individual join looks perfect,
 * because each was fitted to look perfect. It only shows when the sweep comes
 * back around and the two ends of the same row disagree about where a building
 * is.
 *
 * ### Reconciling instead
 *
 * A sweep does not only produce consecutive overlaps. The tile above and the
 * tile to the left of a given frame both overlap it, and each is an independent
 * measurement of where it belongs. Chaining uses one and throws the rest away.
 *
 * This uses all of them: each constraint predicts where a tile's corners should
 * land on the canvas, the predictions are averaged, and the tile's transform is
 * refitted to the consensus. Repeating that spreads the disagreement evenly
 * across the mosaic instead of piling it at one end.
 *
 * It is a relaxation rather than a true bundle adjustment -- there is no joint
 * non-linear solve -- but it addresses the same error and needs no optimiser.
 * For a sweep of a few dozen tiles the difference does not show.
 *
 * ### Why one tile is held still
 *
 * Every constraint is relative, so the whole mosaic can slide, rotate or scale
 * without violating any of them. Something has to be fixed or the solution
 * drifts freely. The first tile is the natural choice, being the one the user
 * framed on.
 */
object MosaicRefiner {

    /** Enough to converge on a sweep of this size; more changes nothing. */
    const val DEFAULT_ITERATIONS = 12

    /**
     * How far a tile moves toward the consensus each pass.
     *
     * Below one on purpose. Moving all the way makes each tile chase its
     * neighbours' current positions, which are themselves about to move, and
     * the mosaic oscillates rather than settling.
     */
    const val RELAXATION = 0.5f

    /**
     * Refines [placements] so they agree with every constraint as far as
     * possible.
     *
     * Returns transforms in the same order. Tiles with no constraints are
     * returned untouched, since there is nothing to reconcile them against.
     */
    fun refine(
        placements: List<Homography>,
        constraints: List<Constraint>,
        tileWidth: Int,
        tileHeight: Int,
        iterations: Int = DEFAULT_ITERATIONS,
        anchor: Int = 0,
    ): List<Homography> {
        if (placements.size < 2 || constraints.isEmpty()) return placements

        val corners = listOf(
            0f to 0f,
            tileWidth.toFloat() to 0f,
            0f to tileHeight.toFloat(),
            tileWidth.toFloat() to tileHeight.toFloat(),
        )

        // Constraints indexed by the tile they say something about, in both
        // directions: a measurement between two tiles constrains each of them.
        val incoming = HashMap<Int, MutableList<Pair<Int, Homography>>>()
        for (c in constraints) {
            if (c.from !in placements.indices || c.to !in placements.indices) continue
            val inverse = c.transform.invert() ?: continue
            // A point in `from` is at transform(p) in `to`, so `to`'s placement
            // should equal `from`'s placement composed with the inverse.
            incoming.getOrPut(c.to) { mutableListOf() }.add(c.from to inverse)
            incoming.getOrPut(c.from) { mutableListOf() }.add(c.to to c.transform)
        }

        var current = placements.toMutableList()

        repeat(iterations) {
            val next = current.toMutableList()
            for (tile in current.indices) {
                if (tile == anchor) continue
                val related = incoming[tile] ?: continue
                if (related.isEmpty()) continue

                // Where each neighbour says this tile's corners should land.
                val averaged = FloatArray(corners.size * 2)
                var contributions = 0
                for ((other, relation) in related) {
                    val predicted = current[other].times(relation)
                    var usable = true
                    val buffer = FloatArray(corners.size * 2)
                    corners.forEachIndexed { i, (x, y) ->
                        val p = predicted.apply(x, y)
                        if (p[0].isNaN() || p[1].isNaN()) usable = false
                        buffer[i * 2] = p[0]
                        buffer[i * 2 + 1] = p[1]
                    }
                    if (!usable) continue
                    for (i in averaged.indices) averaged[i] += buffer[i]
                    contributions++
                }
                if (contributions == 0) continue
                for (i in averaged.indices) averaged[i] /= contributions

                // Blended with where it already is, then refitted from the
                // corner correspondences. Refitting rather than averaging the
                // matrices directly: homographies do not average meaningfully
                // element by element, but the points they predict do.
                val matches = ArrayList<Match>(corners.size)
                corners.forEachIndexed { i, (x, y) ->
                    val now = current[tile].apply(x, y)
                    if (now[0].isNaN() || now[1].isNaN()) return@forEachIndexed
                    matches.add(
                        Match(
                            x, y,
                            now[0] + (averaged[i * 2] - now[0]) * RELAXATION,
                            now[1] + (averaged[i * 2 + 1] - now[1]) * RELAXATION,
                        )
                    )
                }
                if (matches.size < 4) continue
                Homography.fit(matches)?.let { next[tile] = it }
            }
            current = next
        }
        return current
    }

    /**
     * Total disagreement between the placements and the constraints, in pixels.
     *
     * The number refinement is trying to reduce, and the only honest way to say
     * whether it worked.
     */
    fun residual(
        placements: List<Homography>,
        constraints: List<Constraint>,
        tileWidth: Int,
        tileHeight: Int,
    ): Float {
        val corners = listOf(
            0f to 0f,
            tileWidth.toFloat() to 0f,
            0f to tileHeight.toFloat(),
            tileWidth.toFloat() to tileHeight.toFloat(),
        )
        var total = 0.0
        var counted = 0
        for (c in constraints) {
            if (c.from !in placements.indices || c.to !in placements.indices) continue
            val inverse = c.transform.invert() ?: continue
            val predicted = placements[c.from].times(inverse)
            for ((x, y) in corners) {
                val a = predicted.apply(x, y)
                val b = placements[c.to].apply(x, y)
                if (a[0].isNaN() || b[0].isNaN()) continue
                val dx = a[0] - b[0]
                val dy = a[1] - b[1]
                total += kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                counted++
            }
        }
        return if (counted == 0) 0f else (total / counted).toFloat()
    }
}
