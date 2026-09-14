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
 * ### The lock rule, and the stability gate
 *
 * A node locks when the reticle has been within the lock radius of it, *and
 * still*, for [settleMillis]. Still is measured two ways on every poll and
 * both have to pass: the frame's centre moved less than [stillDistance] on
 * the canvas since the last poll -- the registration's own reading of
 * motion -- and the gyroscope's peak angular speed since the last poll was
 * under [maxAngularSpeed], the threshold [StabilityGate] derives from the
 * exposure and the lens so that the blur it permits is a couple of pixels.
 * A poll that fails either resets the hold; a hold needs at least
 * [StabilityGate.MIN_STEADY_POLLS] steady polls in a row as well as the time,
 * so a slow tracker cannot satisfy the clock with two observations.
 *
 * The first version measured a hold as "still inside the radius" and reset
 * it only for a drift of a whole radius, and the field test fired on every
 * approach: a hand decelerating onto a target is inside 5% of the frame for
 * a quarter of a second while still turning a degree a second, and the
 * tracker of the time polled every 300 ms, so the second observation
 * satisfied the clock. Every tile was smeared. The radius says where the
 * reticle is; only speed says whether the frame will be sharp. The radius is
 * PRD §2.3.3's 5% of the frame.
 */
class MosaicGuide(
    val plan: MosaicPlan,
    /** How close, in canvas pixels, the reticle must be to a node to lock. */
    val lockRadius: Float = plan.placedTileWidth * LOCK_FRACTION,
    /** How long the reticle must hold still inside the radius before the burst fires. */
    settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    /** How far, in canvas pixels, the frame's centre may move between two polls and still be still. */
    val stillDistance: Float = plan.placedTileWidth * StabilityGate.STILL_FRACTION,
    /** The gyroscope's ceiling for a steady poll, in radians per second. */
    maxAngularSpeed: Float = StabilityGate.DEFAULT_ANGULAR_SPEED,
) {
    init {
        require(plan.nodes.isNotEmpty()) { "a guided mosaic needs a plan with nodes" }
    }

    var settleMillis: Long = settleMillis
        private set
    var maxAngularSpeed: Float = maxAngularSpeed
        private set

    /**
     * Canvas pixels per radian of turn, when the runner knows the lens: with
     * it the reticle's allowed movement per poll is what the angular ceiling
     * would move it, so the two measures of stillness agree. Zero means the
     * fixed [stillDistance] applies.
     */
    var canvasPixelsPerRadian: Float = 0f
        private set

    /**
     * Sets the gate from what the runner learns once the stream is locked:
     * the exposure decides the angular ceiling, the burst length the settle
     * time, the lens the reticle's allowance. Before any node has locked, so
     * nothing in progress is moved.
     */
    fun configureGate(maxAngularSpeed: Float, settleMillis: Long, canvasPixelsPerRadian: Float = 0f) {
        this.maxAngularSpeed = maxAngularSpeed
        this.settleMillis = settleMillis
        this.canvasPixelsPerRadian = canvasPixelsPerRadian
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
        data class Capture(val node: MosaicNode) : Decision

        /** The frame's centre moved more than a tile since the last poll. */
        data object TooFast : Decision
    }

    private val states = Array(plan.nodes.size) { NodeState.PENDING }
    private val rejectedUntil = LongArray(plan.nodes.size)
    private var lockingNode = -1
    private var lockingSince = 0L
    private var steadyPolls = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastNow = 0L
    private var capturing = -1

    /** Whether the last observation read as still, on both measures. */
    var steady: Boolean = false
        private set

    /** Why the last observation was not steady, for the screen's hint; null when it was. */
    var unsteadyReason: Unsteady? = null
        private set

    enum class Unsteady {
        /** The frame's centre moved more than [stillDistance] since the last poll. */
        DRIFTING,

        /** The gyroscope read above [maxAngularSpeed] since the last poll. */
        TURNING,
    }

    /**
     * How far the hold on the locking node has progressed, 0 to 1, for the
     * ring the screen draws. Zero when nothing is locking.
     */
    fun holdProgress(nowMillis: Long): Float {
        if (lockingNode < 0 || lockingSince == 0L) return 0f
        return ((nowMillis - lockingSince).toFloat() / settleMillis).coerceIn(0f, 1f)
    }

    /** The node currently locking, or null. */
    val lockingNodeOrNull: MosaicNode? get() = plan.nodes.getOrNull(lockingNode)

    val nodes: List<MosaicNode> get() = plan.nodes
    fun stateOf(node: MosaicNode): NodeState = states[node.index]

    val captured: Int get() = states.count { it == NodeState.DONE }
    val total: Int get() = states.size
    val complete: Boolean get() = captured == total

    /** The node the reticle is nearest to that still needs capturing, or null when all are done. */
    fun nearestPending(x: Float, y: Float): MosaicNode? =
        plan.nodes.filter { states[it.index] != NodeState.DONE && states[it.index] != NodeState.CAPTURING }
            .minByOrNull { distance(it, x, y) }

    private fun distance(node: MosaicNode, x: Float, y: Float): Float {
        val dx = node.canvasX - x
        val dy = node.canvasY - y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Tells the mosaic where the frame's centre is on the canvas now, or null
     * when the frame could not be registered, and how fast the phone turned
     * since the last poll -- the gyroscope's peak, in radians per second, or
     * NaN where there is no gyroscope and the registration is the whole gate.
     */
    fun observe(reticleX: Float?, reticleY: Float?, nowMillis: Long, angularSpeed: Float = Float.NaN): Decision {
        if (capturing >= 0) return Decision.Idle
        if (reticleX == null || reticleY == null) {
            // Lost. Whatever was locking is no longer known to be.
            clearLocking()
            lastX = Float.NaN
            lastY = Float.NaN
            lastNow = nowMillis
            steady = false
            unsteadyReason = Unsteady.DRIFTING
            return Decision.Idle
        }

        val hasPrevious = !lastX.isNaN()
        var tooFast = false
        var moved = 0f
        if (hasPrevious) {
            moved = sqrt((reticleX - lastX) * (reticleX - lastX) + (reticleY - lastY) * (reticleY - lastY))
            tooFast = moved > plan.placedTileWidth * TOO_FAST_FRACTION
        }
        // Still, on both measures. The distance is per poll, so a slow poll
        // is allowed proportionately more -- a tracker at 300 ms cannot be
        // held to a tenth of a second's jitter -- but never more than a
        // radius, which is the point where it is not a hold at all. A first
        // observation has nothing to be still relative to and is not still.
        val elapsed = if (hasPrevious) (nowMillis - lastNow).coerceAtLeast(1L) else NOMINAL_POLL_MILLIS
        val allowed = if (canvasPixelsPerRadian > 0f) {
            // What the angular ceiling would move the reticle in this poll.
            (maxAngularSpeed * (elapsed / 1000f) * canvasPixelsPerRadian).coerceIn(1f, lockRadius)
        } else {
            (stillDistance * (elapsed.toFloat() / NOMINAL_POLL_MILLIS)).coerceAtMost(lockRadius)
        }
        val drifting = hasPrevious && moved > allowed
        val turning = !angularSpeed.isNaN() && angularSpeed > maxAngularSpeed
        steady = hasPrevious && !drifting && !turning
        unsteadyReason = when {
            turning -> Unsteady.TURNING
            drifting -> Unsteady.DRIFTING
            else -> null
        }
        lastX = reticleX
        lastY = reticleY
        lastNow = nowMillis

        // Every pending node's state follows from its distance; only one can
        // be locking, and it is the nearest. A node that is already locking
        // keeps the lock a little past the radius -- the magnet: once the
        // reticle is on the dot, the tremor that carries it a few pixels over
        // the edge does not lose it. The reticle has to leave by a margin.
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
            val holding = lockingNode == node.index && s == NodeState.LOCKING
            val radius = if (holding) lockRadius * MAGNET_MULTIPLE else lockRadius
            states[node.index] = when {
                node === nearest && d <= radius -> NodeState.LOCKING
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
            // Arrived. The hold starts with the first steady poll, not now.
            lockingNode = locking.index
            lockingSince = 0L
            steadyPolls = 0
        }

        // A hold is steady polls, in a row, for the settle time. One poll that
        // is not steady -- a drift, a turn -- starts it over.
        if (!steady) {
            lockingSince = 0L
            steadyPolls = 0
            return if (tooFast) Decision.TooFast else Decision.Idle
        }
        steadyPolls++
        if (lockingSince == 0L) lockingSince = nowMillis
        if (steadyPolls >= StabilityGate.MIN_STEADY_POLLS && nowMillis - lockingSince >= settleMillis) {
            states[locking.index] = NodeState.CAPTURING
            capturing = locking.index
            clearLocking()
            return Decision.Capture(locking)
        }
        return Decision.Idle
    }

    /** The burst at [node] was captured and composited. */
    fun captured(node: MosaicNode) {
        states[node.index] = NodeState.DONE
        if (capturing == node.index) capturing = -1
    }

    /** The burst at [node] could not be used; the node will be asked for again. */
    fun rejected(node: MosaicNode, nowMillis: Long = System.currentTimeMillis()) {
        states[node.index] = NodeState.REJECTED
        rejectedUntil[node.index] = nowMillis + REJECTED_MILLIS
        if (capturing == node.index) capturing = -1
    }

    private fun clearLocking() {
        lockingNode = -1
        lockingSince = 0L
        steadyPolls = 0
    }

    companion object {
        /** PRD §2.3.3: five percent of the frame. */
        const val LOCK_FRACTION = 0.05f

        /** Within this many radii the dot reads as approached. */
        const val NEAR_MULTIPLE = 3f

        /** A locking node keeps its lock out to this many radii: the magnet's reach. */
        const val MAGNET_MULTIPLE = 1.5f

        /**
         * Hold this long, still, inside the radius before the burst fires.
         * Was 250 ms; 170 is what a hand holds without the ring seeming to
         * stall, and the settle time still stretches to cover the burst.
         */
        const val DEFAULT_SETTLE_MILLIS = 170L

        /** The poll interval the still distance is stated at. */
        const val NOMINAL_POLL_MILLIS = 100L

        /**
         * The settle time for a burst of [frames] at [frameIntervalNs]: long
         * enough that every frame the ring hands over was taken during the
         * hold, plus a margin, and never under the default. A burst is the
         * newest frames, so the hold *is* the burst's exposure window.
         */
        fun settleFor(frames: Int, frameIntervalNs: Long): Long {
            val span = (frames.coerceAtLeast(1) * frameIntervalNs) / 1_000_000L
            return maxOf(DEFAULT_SETTLE_MILLIS, span + 40L)
        }

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
