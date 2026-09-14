package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The lock rule, against scripted pans.
 *
 * The sweep is told where the reticle is and when, and answers with node
 * states and the moment to capture. These pans are the cases the rule exists
 * to tell apart: a hold, a sweep through, a drift, a return after a failure,
 * and a frame that could not be registered at all.
 */
class GuidedSweepTest {

    private fun lens(equivalent: Int, id: String) =
        Lens(id, true, 1f, equivalent, 1f, true, 1.7f, 50f)

    private val plan = MosaicPlanner.planGrid(
        target = lens(24, "0"), capture = lens(110, "4"),
        tileWidth = 4080, tileHeight = 3072, columns = 2, rows = 2,
    )!!

    private fun sweep(settle: Long = 250L) = GuidedSweep(plan, settleMillis = settle)

    private fun GuidedSweep.at(node: SweepNode, dx: Float = 0f, dy: Float = 0f, t: Long) =
        observe(node.canvasX + dx, node.canvasY + dy, t)

    @Test
    fun `the plan carries four nodes and the radius is five percent of a tile`() {
        val s = sweep()
        assertThat(s.total).isEqualTo(4)
        assertThat(s.lockRadius).isWithin(0.5f).of(4080 * 0.05f)
        assertThat(s.captured).isEqualTo(0)
        assertThat(s.complete).isFalse()
        assertThat(s.nodes.all { s.stateOf(it) == GuidedSweep.NodeState.PENDING }).isTrue()
    }

    @Test
    fun `a hold inside the radius locks and captures`() {
        val s = sweep()
        val node = plan.nodes[0]
        // Approaching: outside the radius but within reach reads as near.
        assertThat(s.at(node, dx = s.lockRadius * 2f, t = 0)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.NEAR)
        // Inside: locking, and the clock starts.
        assertThat(s.at(node, dx = 10f, t = 100)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.LOCKING)
        assertThat(s.at(node, dx = 12f, t = 200)).isEqualTo(GuidedSweep.Decision.Idle)
        // Held long enough: capture.
        val decision = s.at(node, dx = 8f, t = 360)
        assertThat(decision).isEqualTo(GuidedSweep.Decision.Capture(node))
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.CAPTURING)
        // While capturing, nothing else is decided.
        assertThat(s.at(plan.nodes[1], t = 400)).isEqualTo(GuidedSweep.Decision.Idle)
        s.captured(node)
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.DONE)
        assertThat(s.captured).isEqualTo(1)
    }

    @Test
    fun `sweeping through a node without stopping does not capture`() {
        val s = sweep()
        val node = plan.nodes[0]
        // One poll inside the radius, then gone: no dwell, no capture.
        assertThat(s.at(node, dx = 5f, t = 0)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.at(node, dx = s.lockRadius * 4f, t = 100)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.at(node, dx = s.lockRadius * 8f, t = 300)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.captured).isEqualTo(0)
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.PENDING)
    }

    @Test
    fun `drifting across the radius restarts the dwell`() {
        val s = sweep()
        val node = plan.nodes[0]
        val r = s.lockRadius
        // Enters at one side, crosses to the other: inside the whole time but
        // moved more than a radius, so the hold has not begun.
        s.at(node, dx = -r * 0.9f, t = 0)
        s.at(node, dx = 0f, t = 100)
        assertThat(s.at(node, dx = r * 0.9f, t = 300)).isEqualTo(GuidedSweep.Decision.Idle)
        // Now still: the dwell counts from the last restart.
        assertThat(s.at(node, dx = r * 0.9f, t = 400)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.at(node, dx = r * 0.85f, t = 560)).isEqualTo(GuidedSweep.Decision.Capture(node))
    }

    @Test
    fun `a rejected capture puts the node back and it can be taken again`() {
        val s = sweep()
        val node = plan.nodes[0]
        s.at(node, t = 0)
        assertThat(s.at(node, t = 300)).isEqualTo(GuidedSweep.Decision.Capture(node))
        s.rejected(node, nowMillis = 300)
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.REJECTED)
        // It reads as rejected for a beat, long enough to be seen and felt...
        s.at(node, t = 400)
        assertThat(s.stateOf(node)).isEqualTo(GuidedSweep.NodeState.REJECTED)
        assertThat(s.at(node, t = 700)).isEqualTo(GuidedSweep.Decision.Idle)
        // ...then is pending, and a fresh hold captures again.
        s.at(node, t = 1000)
        assertThat(s.stateOf(node)).isNotEqualTo(GuidedSweep.NodeState.REJECTED)
        s.at(node, t = 1100)
        assertThat(s.at(node, t = 1400)).isEqualTo(GuidedSweep.Decision.Capture(node))
    }

    @Test
    fun `a lost frame clears the lock`() {
        val s = sweep()
        val node = plan.nodes[0]
        s.at(node, t = 0)
        s.at(node, t = 100)
        assertThat(s.observe(null, null, 200)).isEqualTo(GuidedSweep.Decision.Idle)
        // Back on the node, but the dwell starts over.
        assertThat(s.at(node, t = 300)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.at(node, t = 400)).isEqualTo(GuidedSweep.Decision.Idle)
        assertThat(s.at(node, t = 600)).isEqualTo(GuidedSweep.Decision.Capture(node))
    }

    @Test
    fun `a pan of most of a tile between polls is too fast`() {
        val s = sweep()
        val a = plan.nodes[0]
        s.at(a, t = 0)
        val far = s.observe(a.canvasX + plan.placedTileWidth * 0.5f, a.canvasY, 100)
        assertThat(far).isEqualTo(GuidedSweep.Decision.TooFast)
    }

    @Test
    fun `the whole grid captured is complete`() {
        val s = sweep()
        var t = 0L
        for (node in plan.nodes) {
            s.at(node, t = t)
            t += 100
            s.at(node, t = t)
            t += 200
            assertThat(s.at(node, t = t)).isEqualTo(GuidedSweep.Decision.Capture(node))
            s.captured(node)
            t += 100
        }
        assertThat(s.captured).isEqualTo(4)
        assertThat(s.complete).isTrue()
        assertThat(s.nearestPending(0f, 0f)).isNull()
    }

    @Test
    fun `only the nearest pending node can be locking`() {
        val s = sweep()
        val a = plan.nodes[0]
        val b = plan.nodes[1]
        // Between the two, nearer a.
        val x = a.canvasX + (b.canvasX - a.canvasX) * 0.3f
        s.observe(x, a.canvasY, 0)
        assertThat(s.stateOf(a)).isNotEqualTo(GuidedSweep.NodeState.LOCKING)
        assertThat(s.stateOf(b)).isNotEqualTo(GuidedSweep.NodeState.LOCKING)
        // A done node is never nearest.
        s.at(a, t = 100); s.at(a, t = 200)
        assertThat(s.at(a, t = 500)).isEqualTo(GuidedSweep.Decision.Capture(a))
        s.captured(a)
        assertThat(s.nearestPending(a.canvasX, a.canvasY)).isNotEqualTo(a)
    }

    @Test
    fun `a plan without nodes cannot be swept`() {
        val bare = plan.copy(nodes = emptyList())
        var thrown = false
        try { GuidedSweep(bare) } catch (e: IllegalArgumentException) { thrown = true }
        assertThat(thrown).isTrue()
    }
}
