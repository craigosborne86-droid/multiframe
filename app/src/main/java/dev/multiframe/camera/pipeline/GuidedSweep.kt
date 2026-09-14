package dev.multiframe.camera.pipeline

import kotlin.math.sqrt

/**
 * The reticle-and-dot guidance, as a state machine over the plan's nodes.
 *
 * ### What replaced free panning, and why
 *
 * The earlier sweep accepted whatever frames a slow pan produced and asked the
 * user to keep going until a coverage map filled in. It worked and it was
 * tiring: there was no way to know where to point next, no natural moment to
 * hold still, and a canvas that filled to 96% needed the user to find the
 * missing 4% on a thumbnail. PRD §2.3.3 replaces it with nodes -- one dot per
 * tile centre, drawn on the viewfinder where the tile should be -- and a
 * reticle fixed at the centre of the frame. Pan the reticle onto a dot, hold,
 * and the burst fires itself.
 *
 * ### What this class is, and is not
 *
 * Pure. It is told, poll by poll, where the frame's centre is on the canvas
 * (the runner registers each preview frame to find out) and it answers with
 * the state of every node and whether a burst should be taken now. It does no
 * registration, no capture, no drawing, and no haptics, all of which belong to
 * the runner and the screen -- which is what lets the lock rule be tested
 * against a scripted pan rather than a hand.
 *
 * ### The lock rule
 *
 * A node locks when the reticle has been within the lock radius of it for
 * [settleMillis], and has moved less than the radius in that time. Both parts
 * matter. The radius alone would fire on a pan that swept through the dot
 * without stopping, which is exactly the frame that is blurred; the dwell
 * alone would fire on a slow drift past. Together they fire on a hold, which
 * is what the burst needs. The radius is PRD §2.3.3's 5% of the frame.
 */
class GuidedSweep(
    val plan: MosaicPlan,
    /** How close, in canvas pixels, the reticle must be to a node to lock. */
    val lockRadius: Float = plan.placedTileWidth * LOCK_FRACTION,
    /** How long the reticle must hold inside the radius before the burst fires. */
    val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
) {
    init {
        require(plan.nodes.isNotEmpty()) { "a guided sweep needs a plan with nodes" }
    }

    enum class NodeState {
        /** Not yet captured; the reticle is elsewhere. */
        PENDING,

        /** The reticle is within reach and closing. */
        NEAR,

        /** Inside the radius; holding. */
        LOCKING,

        /** A burst is being taken here. */
        CAPTURING,

        /** Captured and composited. */
        DONE,

        /** A capture here failed; the node reads so for [REJECTED_MILLIS], then is pending again. */
        REJECTED,
    }

    /** What the runner should do after an observation. */
    sealed interface Decision {
        /** Nothing to do; the states say where things stand. */
        data object Idle : Decision

        /** The reticle has held on [node] long enough: take the burst. */
        data class Capture(val node: SweepNode) : Decision

        /** The frame's centre moved more than a tile since the last poll. */
        data object TooFast : Decision
    }

    private val states = Array(plan.nodes.size) { NodeState.PENDING }
    private val rejectedUntil = LongArray(plan.nodes.size)
    private var lockingNode = -1
    private var lockingSince = 0L
    private var lockingX = 0f
    private var lockingY = 0f
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var capturing = -1

    val nodes: List<SweepNode> get() = plan.nodes
    fun stateOf(node: SweepNode): NodeState = states[node.index]

    val captured: Int get() = states.count { it == NodeState.DONE }
    val total: Int get() = states.size
    val complete: Boolean get() = captured == total

    /** The node the reticle is nearest to that still needs capturing, or null when all are done. */
    fun nearestPending(x: Float, y: Float): SweepNode? =
        plan.nodes.filter { states[it.index] != NodeState.DONE && states[it.index] != NodeState.CAPTURING }
            .minByOrNull { distance(it, x, y) }

    private fun distance(node: SweepNode, x: Float, y: Float): Float {
        val dx = node.canvasX - x
        val dy = node.canvasY - y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Tells the sweep where the frame's centre is on the canvas now, or null
     * when the frame could not be registered.
     */
    fun observe(reticleX: Float?, reticleY: Float?, nowMillis: Long): Decision {
        if (capturing >= 0) return Decision.Idle
        if (reticleX == null || reticleY == null) {
            // Lost. Whatever was locking is no longer known to be.
            clearLocking()
            lastX = Float.NaN
            lastY = Float.NaN
            return Decision.Idle
        }

        var tooFast = false
        if (!lastX.isNaN()) {
            val moved = sqrt((reticleX - lastX) * (reticleX - lastX) + (reticleY - lastY) * (reticleY - lastY))
            tooFast = moved > plan.placedTileWidth * TOO_FAST_FRACTION
        }
        lastX = reticleX
        lastY = reticleY

        // Every pending node's state follows from its distance; only one can
        // be locking, and it is the nearest.
        val nearest = nearestPending(reticleX, reticleY)
        for (node in plan.nodes) {
            val s = states[node.index]
            if (s == NodeState.DONE || s == NodeState.CAPTURING) continue
            if (s == NodeState.REJECTED) {
                // A rejected node reads as such for a beat -- long enough to
                // be seen and felt -- then is pending again.
                if (nowMillis < rejectedUntil[node.index]) continue
                states[node.index] = NodeState.PENDING
            }
            val d = distance(node, reticleX, reticleY)
            states[node.index] = when {
                node === nearest && d <= lockRadius -> NodeState.LOCKING
                d <= lockRadius * NEAR_MULTIPLE -> NodeState.NEAR
                else -> NodeState.PENDING
            }
        }

        val locking = nearest?.takeIf { states[it.index] == NodeState.LOCKING }
        if (locking == null) {
            clearLocking()
            return if (tooFast) Decision.TooFast else Decision.Idle
        }

        if (lockingNode != locking.index) {
            lockingNode = locking.index
            lockingSince = nowMillis
            lockingX = reticleX
            lockingY = reticleY
            return if (tooFast) Decision.TooFast else Decision.Idle
        }

        // Still on the same node. A hold is a dwell without drift.
        val drift = sqrt((reticleX - lockingX) * (reticleX - lockingX) + (reticleY - lockingY) * (reticleY - lockingY))
        if (drift > lockRadius) {
            lockingSince = nowMillis
            lockingX = reticleX
            lockingY = reticleY
            return Decision.Idle
        }
        if (nowMillis - lockingSince >= settleMillis) {
            states[locking.index] = NodeState.CAPTURING
            capturing = locking.index
            clearLocking()
            return Decision.Capture(locking)
        }
        return Decision.Idle
    }

    /** The burst at [node] was captured and composited. */
    fun captured(node: SweepNode) {
        states[node.index] = NodeState.DONE
        if (capturing == node.index) capturing = -1
    }

    /** The burst at [node] could not be used; the node will be asked for again. */
    fun rejected(node: SweepNode, nowMillis: Long = System.currentTimeMillis()) {
        states[node.index] = NodeState.REJECTED
        rejectedUntil[node.index] = nowMillis + REJECTED_MILLIS
        if (capturing == node.index) capturing = -1
    }

    private fun clearLocking() {
        lockingNode = -1
        lockingSince = 0L
    }

    companion object {
        /** PRD §2.3.3: five percent of the frame. */
        const val LOCK_FRACTION = 0.05f

        /** Within this many radii the dot reads as approached. */
        const val NEAR_MULTIPLE = 3f

        /** Hold this long inside the radius before the burst fires. */
        const val DEFAULT_SETTLE_MILLIS = 250L

        /** How long a failed node reads as failed before it is asked for again. */
        const val REJECTED_MILLIS = 600L

        /**
         * A frame centre that moved more than this fraction of a tile between
         * two polls, a tenth of a second apart, is a pan too fast to have left
         * a sharp frame anywhere along it.
         */
        const val TOO_FAST_FRACTION = 0.4f
    }
}
