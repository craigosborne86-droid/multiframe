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
class MosaicGuideTest {

    private fun lens(equivalent: Int, id: String) =
        Lens(id, true, 1f, equivalent, 1f, true, 1.7f, 50f)

    private val plan = MosaicPlanner.planGrid(
        target = lens(24, "0"), capture = lens(110, "4"),
        tileWidth = 4080, tileHeight = 3072, columns = 2, rows = 2,
    )!!

    private fun sweep(settle: Long = 250L) = MosaicGuide(plan, settleMillis = settle)

    private fun MosaicGuide.at(node: MosaicNode, dx: Float = 0f, dy: Float = 0f, t: Long) =
        observe(node.canvasX + dx, node.canvasY + dy, t)

    @Test
    fun `the plan carries four nodes and the radius is five percent of a tile`() {
        val s = sweep()
        assertThat(s.total).isEqualTo(4)
        assertThat(s.lockRadius).isWithin(0.5f).of(4080 * 0.05f)
        assertThat(s.captured).isEqualTo(0)
        assertThat(s.complete).isFalse()
        assertThat(s.nodes.all { s.stateOf(it) == MosaicGuide.NodeState.PENDING }).isTrue()
    }

    @Test
    fun `a still hold inside the radius locks and captures`() {
        val s = sweep()
        val node = plan.nodes[0]
        // Approaching: outside the radius but within reach reads as near.
        assertThat(s.at(node, dx = s.lockRadius * 2f, t = 0)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.NEAR)
        // Inside, and the poll after arriving is the first the hold can count
        // -- the arrival itself moved by more than the still distance.
        assertThat(s.at(node, dx = 10f, t = 100)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.LOCKING)
        assertThat(s.holdProgress(100)).isEqualTo(0f)
        assertThat(s.at(node, dx = 12f, t = 200)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.steady).isTrue()
        assertThat(s.holdProgress(300)).isWithin(0.01f).of(0.4f)
        assertThat(s.at(node, dx = 8f, t = 300)).isEqualTo(MosaicGuide.Decision.Idle)
        // Three steady polls and the settle time: capture.
        val decision = s.at(node, dx = 9f, t = 460)
        assertThat(decision).isEqualTo(MosaicGuide.Decision.Capture(node))
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.CAPTURING)
        // While capturing, nothing else is decided.
        assertThat(s.at(plan.nodes[1], t = 500)).isEqualTo(MosaicGuide.Decision.Idle)
        s.captured(node)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.DONE)
        assertThat(s.captured).isEqualTo(1)
    }

    @Test
    fun `sweeping through a node without stopping does not capture`() {
        val s = sweep()
        val node = plan.nodes[0]
        // One poll inside the radius, then gone: no dwell, no capture.
        assertThat(s.at(node, dx = 5f, t = 0)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, dx = s.lockRadius * 4f, t = 100)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, dx = s.lockRadius * 8f, t = 300)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.captured).isEqualTo(0)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.PENDING)
    }

    /**
     * The field test's failure. A hand decelerating onto the dot is inside the
     * radius for the whole settle time and still moving; the old rule fired,
     * this one does not until the movement stops.
     */
    @Test
    fun `a hand still settling inside the radius does not fire`() {
        val s = sweep()
        val node = plan.nodes[0]
        val r = s.lockRadius
        // Inside the radius throughout, moving half of it each poll: two and
        // a half percent of the tile in a tenth of a second, five degrees a
        // second on the telephoto, and a smeared frame.
        var t = 0L
        var dx = -r * 0.9f
        var decision: MosaicGuide.Decision = MosaicGuide.Decision.Idle
        for (i in 0 until 3) {
            decision = s.at(node, dx = dx, t = t)
            assertThat(decision).isEqualTo(MosaicGuide.Decision.Idle)
            assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.LOCKING)
            assertThat(s.steady).isFalse()
            // The first poll has nothing to be still relative to; every one
            // after it is seen moving.
            if (i > 0) assertThat(s.unsteadyReason).isEqualTo(MosaicGuide.Unsteady.DRIFTING)
            dx += r * 0.5f
            t += 100
        }
        assertThat(s.holdProgress(t)).isEqualTo(0f)
        // Then it stops, still inside the radius: the hold begins from the
        // first still poll.
        assertThat(dx).isLessThan(r)
        assertThat(s.at(node, dx = dx, t = t)).isEqualTo(MosaicGuide.Decision.Idle)          // arrives, moved
        assertThat(s.at(node, dx = dx + 5f, t = t + 100)).isEqualTo(MosaicGuide.Decision.Idle) // steady 1
        assertThat(s.at(node, dx = dx + 8f, t = t + 200)).isEqualTo(MosaicGuide.Decision.Idle) // steady 2
        assertThat(s.at(node, dx = dx + 6f, t = t + 360)).isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `the gyroscope can veto a hold the registration would allow`() {
        val s = MosaicGuide(plan, settleMillis = 250L, maxAngularSpeed = 0.02f)
        val node = plan.nodes[0]
        // The reticle reads still; the phone is turning at three degrees a
        // second -- the frame will be smeared whatever the tracker says.
        s.observe(node.canvasX, node.canvasY, 0, angularSpeed = 0.05f)
        s.observe(node.canvasX + 2f, node.canvasY, 100, angularSpeed = 0.05f)
        s.observe(node.canvasX + 1f, node.canvasY, 200, angularSpeed = 0.05f)
        assertThat(s.observe(node.canvasX + 2f, node.canvasY, 400, angularSpeed = 0.05f))
            .isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.steady).isFalse()
        assertThat(s.unsteadyReason).isEqualTo(MosaicGuide.Unsteady.TURNING)
        // Still now, on both measures: the hold runs.
        s.observe(node.canvasX + 2f, node.canvasY, 500, angularSpeed = 0.01f)
        s.observe(node.canvasX + 1f, node.canvasY, 600, angularSpeed = 0.012f)
        assertThat(s.observe(node.canvasX + 2f, node.canvasY, 760, angularSpeed = 0.008f))
            .isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `a jerk during the hold starts it over`() {
        val s = MosaicGuide(plan, settleMillis = 250L, maxAngularSpeed = 0.02f)
        val node = plan.nodes[0]
        s.observe(node.canvasX, node.canvasY, 0, 0.01f)
        s.observe(node.canvasX + 2f, node.canvasY, 100, 0.01f)
        assertThat(s.holdProgress(200)).isGreaterThan(0f)
        // The jerk.
        s.observe(node.canvasX + 3f, node.canvasY, 200, 0.2f)
        assertThat(s.holdProgress(200)).isEqualTo(0f)
        // Back to still: the clock restarts, three polls and the settle time again.
        s.observe(node.canvasX + 2f, node.canvasY, 300, 0.01f)
        s.observe(node.canvasX + 2f, node.canvasY, 400, 0.01f)
        assertThat(s.observe(node.canvasX + 3f, node.canvasY, 500, 0.01f)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.observe(node.canvasX + 2f, node.canvasY, 560, 0.01f)).isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `without a gyroscope the registration is the whole gate`() {
        val s = sweep()
        val node = plan.nodes[0]
        s.at(node, t = 0)
        s.at(node, dx = 3f, t = 100)
        s.at(node, dx = 2f, t = 200)
        assertThat(s.at(node, dx = 4f, t = 360)).isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `a slow tracker is allowed proportionately more jitter, never more than a radius`() {
        val s = sweep()
        val node = plan.nodes[0]
        // Polls 300 ms apart: three times the nominal interval, so up to
        // three times the still distance is still -- but never more than a
        // radius, whatever the interval.
        s.at(node, t = 0)
        s.at(node, dx = s.stillDistance * 2.5f, t = 300)
        assertThat(s.steady).isTrue()
        s.at(node, dx = s.stillDistance * 2.5f - s.lockRadius * 1.1f, t = 600)
        assertThat(s.steady).isFalse()
    }

    /**
     * The radius is the radius. A version gave a locking node a wider radius
     * to keep its lock -- a magnet -- and, drawn at the reticle, it read as a
     * target that moved. In and out at one radius, whatever the node was.
     */
    @Test
    fun `a locked node loses its lock the moment the reticle leaves the radius`() {
        val s = sweep()
        val node = plan.nodes[0]
        val r = s.lockRadius
        s.at(node, t = 0)
        s.at(node, dx = 2f, t = 100)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.LOCKING)
        // Just inside: still locking.
        s.at(node, dx = r * 0.95f, t = 200)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.LOCKING)
        // Just over the edge: near, and the hold is gone. No reach past the radius.
        s.at(node, dx = r * 1.05f, t = 300)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.NEAR)
        assertThat(s.lockingNodeOrNull).isNull()
    }

    /**
     * With the lens known the drift allowance is the gyroscope's ceiling
     * expressed in canvas pixels, so the two measures cannot disagree.
     */
    @Test
    fun `with the lens known the drift allowance is what the angular ceiling would move the reticle`() {
        val s = sweep()
        val node = plan.nodes[0]
        // 0.1 rad/s at 10,000 canvas px/rad: 100 px per 100 ms poll.
        s.configureGate(maxAngularSpeed = 0.1f, settleMillis = 170L, canvasPixelsPerRadian = 10_000f)
        s.at(node, t = 0)
        s.at(node, dx = 90f, t = 100)
        assertThat(s.steady).isTrue()
        s.at(node, dx = 90f + 110f, t = 200)
        assertThat(s.steady).isFalse()
        assertThat(s.unsteadyReason).isEqualTo(MosaicGuide.Unsteady.DRIFTING)
        // A poll a third as long is allowed a third as far.
        s.at(node, dx = 90f + 110f, t = 300)
        s.at(node, dx = 90f + 110f + 40f, t = 333)
        assertThat(s.steady).isFalse()
    }

    @Test
    fun `the settle time covers the burst`() {
        // Eight frames at 33 ms is 267 ms; the hold has to be longer than the
        // burst it will hand over, so that every frame was taken during it.
        // Three frames is 100 ms, under the 170 ms floor.
        assertThat(MosaicGuide.settleFor(8, 33_333_333L)).isEqualTo(306L)
        assertThat(MosaicGuide.settleFor(3, 33_333_333L)).isEqualTo(MosaicGuide.DEFAULT_SETTLE_MILLIS)
        assertThat(MosaicGuide.settleFor(0, 33_333_333L)).isEqualTo(MosaicGuide.DEFAULT_SETTLE_MILLIS)
        assertThat(MosaicGuide.DEFAULT_SETTLE_MILLIS).isIn(150L..180L)
    }

    @Test
    fun `drifting across the radius restarts the dwell`() {
        val s = sweep()
        val node = plan.nodes[0]
        val r = s.lockRadius
        // Enters at one side, crosses to the other: inside the whole time but
        // moving, so the hold has not begun.
        s.at(node, dx = -r * 0.9f, t = 0)
        s.at(node, dx = 0f, t = 100)
        assertThat(s.at(node, dx = r * 0.9f, t = 300)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.holdProgress(300)).isEqualTo(0f)
        // Now still: the dwell counts from the first still poll.
        assertThat(s.at(node, dx = r * 0.9f, t = 400)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, dx = r * 0.85f, t = 500)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, dx = r * 0.86f, t = 660)).isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `a rejected capture puts the node back and it can be taken again`() {
        val s = sweep()
        val node = plan.nodes[0]
        s.at(node, t = 0)
        s.at(node, t = 100)
        s.at(node, t = 200)
        assertThat(s.at(node, t = 360)).isEqualTo(MosaicGuide.Decision.Capture(node))
        s.rejected(node, nowMillis = 360)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.REJECTED)
        // It reads as rejected for a beat, long enough to be seen and felt...
        s.at(node, t = 400)
        assertThat(s.stateOf(node)).isEqualTo(MosaicGuide.NodeState.REJECTED)
        assertThat(s.at(node, t = 700)).isEqualTo(MosaicGuide.Decision.Idle)
        // ...then is pending, and a fresh hold captures again.
        s.at(node, t = 1000)
        assertThat(s.stateOf(node)).isNotEqualTo(MosaicGuide.NodeState.REJECTED)
        s.at(node, t = 1100)
        s.at(node, t = 1200)
        assertThat(s.at(node, t = 1400)).isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `a lost frame clears the lock`() {
        val s = sweep()
        val node = plan.nodes[0]
        s.at(node, t = 0)
        s.at(node, t = 100)
        assertThat(s.observe(null, null, 200)).isEqualTo(MosaicGuide.Decision.Idle)
        // Back on the node, but the dwell starts over.
        assertThat(s.at(node, t = 300)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, t = 400)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, t = 500)).isEqualTo(MosaicGuide.Decision.Idle)
        assertThat(s.at(node, t = 660)).isEqualTo(MosaicGuide.Decision.Capture(node))
    }

    @Test
    fun `a pan of most of a tile between polls is too fast`() {
        val s = sweep()
        val a = plan.nodes[0]
        s.at(a, t = 0)
        val far = s.observe(a.canvasX + plan.placedTileWidth * 0.5f, a.canvasY, 100)
        assertThat(far).isEqualTo(MosaicGuide.Decision.TooFast)
    }

    @Test
    fun `the whole grid captured is complete`() {
        val s = sweep()
        var t = 0L
        for (node in plan.nodes) {
            s.at(node, t = t)
            t += 100
            s.at(node, t = t)
            t += 100
            s.at(node, t = t)
            t += 160
            assertThat(s.at(node, t = t)).isEqualTo(MosaicGuide.Decision.Capture(node))
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
        assertThat(s.stateOf(a)).isNotEqualTo(MosaicGuide.NodeState.LOCKING)
        assertThat(s.stateOf(b)).isNotEqualTo(MosaicGuide.NodeState.LOCKING)
        // A done node is never nearest.
        s.at(a, t = 100); s.at(a, t = 200); s.at(a, t = 300)
        assertThat(s.at(a, t = 500)).isEqualTo(MosaicGuide.Decision.Capture(a))
        s.captured(a)
        assertThat(s.nearestPending(a.canvasX, a.canvasY)).isNotEqualTo(a)
    }

    @Test
    fun `a plan without nodes cannot be swept`() {
        val bare = plan.copy(nodes = emptyList())
        var thrown = false
        try { MosaicGuide(bare) } catch (e: IllegalArgumentException) { thrown = true }
        assertThat(thrown).isTrue()
    }
}
