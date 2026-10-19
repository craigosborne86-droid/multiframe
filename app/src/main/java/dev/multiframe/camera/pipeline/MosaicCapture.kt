package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "Multiframe"

/** How a mosaic ended. */
data class MosaicResult(
    val tiles: Int,
    val rejected: Int,
    val coverage: Float,
    val megapixels: Double,
    val savedName: String?,
    val elapsedMillis: Long,
    val message: String,
)

/** One node as the screen should draw it. */
data class NodeView(
    val node: MosaicNode,
    val state: MosaicGuide.NodeState,
    /**
     * Where the node's centre falls in the current frame, as fractions of the
     * sensor frame's width and height, or null when the phone's attitude is
     * unknown or the node is behind the camera. May lie outside 0..1: a node
     * off the edge of the view is drawn at the edge, pointing the way.
     */
    val frameX: Float?,
    val frameY: Float?,
)

/** Everything the screen needs to draw the guidance, once per poll. */
data class MosaicGuideView(
    val nodes: List<NodeView>,
    val captured: Int,
    val total: Int,
    /** Whether the reticle's place on the canvas is known this poll: the attitude has been read. */
    val located: Boolean,
    /** Fraction of the canvas covered, as the assembler counts it. */
    val coverage: Float,
    /**
     * The canvas's four corners in the current frame, as fractions of the
     * frame's width and height, top-left, top-right, bottom-right,
     * bottom-left -- the outline of the photograph being composed. Under a
     * rotation it is a quadrilateral, not a rectangle. Null when the attitude
     * is unknown or a corner is behind the camera.
     */
    val canvasCorners: List<Pair<Float, Float>>?,
    /** How far the hold on the locking node has progressed, 0 to 1. */
    val holdProgress: Float = 0f,
    /** Whether the last poll read as still, on both of the gate's measures. */
    val steady: Boolean = false,
    /** Why it did not, for the hint; null when it did or when nothing is locking. */
    val unsteady: MosaicGuide.Unsteady? = null,
    /** The lens taking the tiles, for the screen to say so. */
    val captureLensLabel: String = "",
    /** Whether the mosaic has anchored; before it, the outline is where the anchor would put it. */
    val anchored: Boolean = true,
    /** The one node to take the reticle to now, by index; -1 when every node is taken. */
    val activeNode: Int = -1,
    /**
     * How far through the latch -- the pulse that says a tile was just taken
     * -- the screen is, 0 to 1, or -1 when no latch is being drawn. Drawn at
     * the reticle, where the capture happened.
     */
    val latch: Float = -1f,
    /**
     * How far the burst at the capturing node has come: the fresh frames
     * exposed since the hold was confirmed over the frames the tile wants,
     * 0 to 1; -1 when no burst is being exposed. The ring the screen fills
     * a second time, so the hand is held through the exposure.
     */
    val exposeProgress: Float = -1f,
)

/** Moments the screen answers with a haptic or a flash. */
sealed interface MosaicEvent {
    /** The anchor frame was taken; the nodes are laid out. */
    data object Anchored : MosaicEvent

    /**
     * The reticle has held on [node], its burst -- fresh frames, exposed
     * after the hold was confirmed -- is in hand, and the node is done.
     * This is the moment the photograph was taken, whatever the canvas
     * makes of it afterwards; the guidance moves on.
     */
    data class Locked(val node: MosaicNode) : MosaicEvent

    /**
     * [node]'s tile is on the canvas -- placed by its pixels where they
     * registered, or by the attitude where they did not ([byAttitude]).
     */
    data class Placed(val node: MosaicNode, val byAttitude: Boolean) : MosaicEvent

    /**
     * [node]'s burst could not become a tile at all -- the ring was empty, the
     * develop failed, the canvas would not take it -- and it is pending again.
     * Never for a registration that found nothing: that tile is placed by
     * the attitude instead.
     */
    data class Rejected(val node: MosaicNode, val reason: String) : MosaicEvent

    /** The pan was too fast for a sharp frame. */
    data object TooFast : MosaicEvent

    /** Every node is taken and every tile is on the canvas. */
    data object Complete : MosaicEvent
}

/**
 * Drives a guided telephoto guide.
 *
 * ### The shape of it
 *
 * The first frame after the mosaic starts is the anchor: wherever the user is
 * pointing becomes the centre of the canvas, and the plan's nodes -- one per
 * tile -- are laid out around it as directions in the world, fixed from the
 * phone's attitude at that moment. Then, thirty times a second, the attitude
 * is read and [ImuGuidance] says where the reticle points on the canvas and
 * where each node falls in the frame; [MosaicGuide] turns that into node
 * states and, when the reticle has held on a node, the decision to capture. A
 * capture is a burst from the ring, merged and developed like any other
 * photograph, corrected for the lens, and composited at the place its
 * reference frame *registers* to against the tiles already placed. The node
 * says where to point, the attitude says where the phone points, and the
 * frame's own pixels say where the tile actually is -- and the attitude, in
 * turn, is what a registration has to agree with to be believed.
 *
 * ### Why the guidance is inertial and the placement is visual
 *
 * The first version registered every preview frame to draw the dots. On a
 * lens seven degrees across, a pan of a few degrees carries the anchor's
 * features out of the frame and the registration fails or clings; the dots
 * swam, stuck and vanished, and a hand cannot follow a dot that does that.
 * The attitude never fails to answer -- it does not look at the scene -- and
 * it answers in a millisecond. The dots it places are static: directions in
 * the world, fixed at the anchor, never moved by anything that happens
 * afterwards.
 *
 * ### A node is never refused for what the pixels say
 *
 * The third field test's fix judged every registration against the attitude
 * and refused the tile when they disagreed -- and the fourth, in the evening
 * on the telephoto at 1/250 s, found that a dark frame registers against a
 * dark anchor not at all, so every node was refused and the user was held
 * in a loop of retakes. A camera that will not take a photograph in the dark
 * is not a camera. So the node is done the moment its burst is in the
 * ring's hand: the checkmark, the haptic, the guidance moving on. Placing
 * the tile is a separate job on a separate thread, and it has two tiers.
 * Registration first, because it is exact when it works; and where it finds
 * nothing, or fewer than [MIN_VISUAL_INLIERS], or a fit that disagrees with
 * the attitude by more than [MAX_ATTITUDE_DISAGREEMENT] of a tile or a sixth
 * of its size, the tile is placed by the attitude alone -- the homography a
 * rotation of the sensor makes on the canvas plane ([ImuGuidance.tileTransform]).
 * A tile placed by the attitude is a few percent of a tile from exact, inside
 * the overlap and under the blend; a tile refused is a hole and a user
 * trapped.
 *
 * ### The burst is exposed after the lock, not before it
 *
 * The lock confirms the hand: a hundred and fifty milliseconds still inside
 * the radius. The first four field tests then took the ring's newest frames
 * -- and the newest frames in a zero-shutter-lag ring at the moment of the
 * lock were exposed a frame or three *before* it, while the hand was still
 * arriving. The capture felt instantaneous because it was: nothing was
 * exposed for it. The tiles carried the approach's blur however still the
 * hand was afterwards. So the lock begins the burst instead. The frames in
 * the ring are left there; the runner waits for [tileFrames] frames whose
 * sensor timestamp is at or after the lock, with the gyroscope's gate held
 * through the wait -- a turn past the ceiling discards the frames exposed
 * during it and starts the count again, the reticle leaving the radius
 * gives the node back -- and takes those. Two hundred milliseconds the
 * hand holds for on purpose, drawn as the ring filling a second time, and
 * the haptic after the last of them.
 *
 * ### Why only bursts are developed
 *
 * Developing a full-resolution tile costs around half a second. Ten frames a
 * second are registered and only the ones a node asks for are developed, so
 * a mosaic of nine tiles develops nine bursts and not the hundreds of frames
 * the user panned through.
 */
object MosaicCapture {

    /**
     * The thread the mosaic runs on: one of its own, a step below the
     * default priority.
     *
     * The guidance polls ten times a second and a node's burst is merged,
     * developed and composited across every core; both were running on the
     * shared default dispatcher at the same priority as the threads that draw
     * the viewfinder. Native threads inherit the nice value of the thread that
     * spawns them, so a runner one step less favourable puts every merge and
     * develop thread a step behind the preview and the camera's own threads,
     * and a tile takes a little longer while the viewfinder keeps its frames.
     * Not the background group -- that is confined to the little cores and a
     * merge there would take seconds.
     */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                r.run()
            }, "mosaic").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * The registration view's size: what a burst's reference frame and the
     * anchor are reduced to before they are registered. Registration at this
     * size costs about 25 ms (see `ZslStreamDeviceTest.trackingIsPricedAgainstAnIdleStream`).
     */
    const val TRACK_WIDTH = 320
    const val TRACK_HEIGHT = 240

    /** How often the attitude is read and the dots redrawn: the viewfinder's own rate. */
    const val POLL_MILLIS = 33L

    /** How often to look for the anchor frame before the mosaic has one. */
    private const val ANCHOR_POLL_MILLIS = 90L

    /**
     * Frames per tile: the burst setting, held to this range. Fewer than four
     * frames is grain -- a single frame from a small telephoto sensor at high
     * ISO has no signal to speak of -- and more than six is a hold longer
     * than a hand keeps: the frames are exposed after the lock, one frame
     * interval each, while the hand holds.
     */
    const val MIN_TILE_FRAMES = 4
    const val MAX_TILE_FRAMES = 6
    fun tileFrames(burstFrames: Int, ringBurst: Int): Int =
        burstFrames.coerceIn(MIN_TILE_FRAMES, MAX_TILE_FRAMES).coerceAtMost(ringBurst.coerceAtLeast(1))

    /**
     * How long a node's fresh burst may take to come together before the
     * node is given back: the frames want a fifth of a second; a hand that
     * keeps turning past the gate restarts the count each time, and after
     * this long it has not held.
     */
    const val COLLECT_TIMEOUT_MILLIS = 2_500L

    /**
     * A burst being exposed at a node: the frames are the ring's newest
     * with a timestamp at or after [sinceNs], which is reset whenever the
     * gyroscope reads a turn -- the frames exposed during it are not taken.
     */
    private class Collecting(val node: MosaicNode, var sinceNs: Long, val startedAtMillis: Long) {
        var restarts = 0
    }

    /**
     * The thread tiles are placed on: merged, registered, developed and
     * composited, while the guidance thread keeps the dots moving. The same
     * priority as the guidance, a step below the viewfinder's, for the same
     * reason; its own thread so a tile's second of work never freezes the
     * dots.
     */
    val placementDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                r.run()
            }, "mosaic-place").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** A burst taken for a node, waiting to become a tile. */
    private class PendingTile(
        val node: MosaicNode,
        val burst: ZslBurst,
        /** The attitude at the reference frame's exposure; null where the sensor had not reported. */
        val rotation: FloatArray?,
    )


    /** How long to wait for the camera's controls to settle before locking them. */
    private const val CONVERGENCE_TIMEOUT_MILLIS = 3_000L

    /**
     * Runs a guided mosaic until every node is captured, the user stops, or
     * nothing can be registered.
     *
     * [frames] is the burst length per node. [shouldContinue] is polled so the
     * user can stop early and keep what they have.
     */
    suspend fun run(
        context: Context,
        stream: ZslRawStream,
        session: MosaicSession,
        guide: MosaicGuide,
        settings: ManualSettings,
        caps: CameraCapabilities,
        frames: Int,
        lensProfile: LensProfile?,
        onView: (MosaicGuideView) -> Unit,
        onEvent: (MosaicEvent) -> Unit,
        shouldContinue: () -> Boolean,
        /**
         * The inertial sensors: the attitude for the guidance and the
         * gyroscope for the gate. The mode is not offered on a phone without
         * an attitude sensor, so a null here or a sensor that reports none is
         * a refusal, not a fallback.
         */
        motion: MotionSensor? = null,
    ): MosaicResult {
        val started = System.currentTimeMillis()
        if (motion == null || !motion.hasAttitude) {
            return MosaicResult(0, 0, 0f, session.plan.megapixels, null, 0L, "no attitude sensor for the guidance")
        }
        val profile = SensorProfile.from(stream.characteristics)
        val captureLabel = stream.lens.label
        val sensorOrientation = stream.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION,
        ) ?: 90
        // Whether the camera stamps frames on the sensors' clock; where it
        // does, a frame is paired with the attitude it was exposed at.
        val realtimeStamps = stream.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
        ) == android.hardware.camera2.CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        // The sensors first, before anything is waited on: the rotation
        // vector's fusion starts from nowhere and takes most of a second to
        // converge, and an anchor taken before it has lays the canvas tens of
        // degrees from where the phone points. The screen usually has them
        // running already, from the moment the mode was armed; this is a
        // no-op then.
        motion.start()

        // The camera's controls have to have settled before they are pinned.
        // Locked on a fresh stream -- a lens the user just switched to -- they
        // froze wherever they were, which on the phone was unity white balance,
        // and the whole mosaic came out the green of unbalanced raw. Waiting is
        // a second or so; the alternative was the field test's result.
        val converged = awaitConvergence(stream, shouldContinue)
        if (!converged) Log.w(TAG, "camera controls did not converge; locking anyway")

        // Everything pinned for the duration. Auto-exposure drifting between
        // tiles leaves each a different brightness, and feathering turns a hard
        // seam into a soft gradient rather than removing it. A hand that is
        // still now -- under four degrees a second over the last half second
        // -- is allowed a shutter floor stretched for the dark; see
        // `StabilityGate.sweepExposure`.
        val steadyNow = motion.peakSpeedSince(motion.nowNs() - 500_000_000L).let {
            !it.isNaN() && it < StabilityGate.LOW_LIGHT_ANGULAR_SPEED
        }
        stream.lockForSweep(settings, caps, steady = steadyNow)
        val pinned = stream.sweepExposureOrNull

        // The stability gate's ceiling, from the exposure the lock holds and
        // the lens's focal length in pixels. Logged, because it is the number
        // that decides how still a hand has to be. A stretched shutter is
        // paid for with a tighter ceiling.
        val exposureNs = pinned?.exposureNs ?: stream.latestExposure()?.second ?: 0L
        val pixelsPerRadian = pixelsPerRadian(stream)
        Log.i(TAG, "guidance focal length: %.0f px/rad (%s)".format(
            pixelsPerRadian,
            if (stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.getOrNull(0)?.let { it > 0f } == true) "intrinsic calibration"
            else "focal length over sensor pitch",
        ))
        val maxSpeed = StabilityGate.maxAngularSpeed(exposureNs, pixelsPerRadian).let {
            if (pinned?.stretched == true) minOf(it, StabilityGate.LOW_LIGHT_ANGULAR_SPEED) else it
        }
        Log.i(
            TAG,
            "stability gate: exposure %.1f ms%s, %.0f px/rad, ceiling %.3f rad/s (%.1f deg/s), gyro %s".format(
                exposureNs / 1e6, if (pinned?.stretched == true) " (stretched for the light)" else "",
                pixelsPerRadian, maxSpeed, Math.toDegrees(maxSpeed.toDouble()),
                if (motion.isAvailable) "present" else "absent",
            ),
        )
        // The burst per tile, and the hold that covers it.
        val tileFrames = tileFrames(frames, stream.maxBurst)
        Log.i(TAG, "tiles: $tileFrames frames each (setting $frames, ring burst ${stream.maxBurst})")
        guide.configureGate(
            maxAngularSpeed = maxSpeed,
            settleMillis = MosaicGuide.DEFAULT_SETTLE_MILLIS,
            canvasPixelsPerRadian = pixelsPerRadian * session.plan.tileScale,
        )
        val imu = ImuGuidance(session.plan, pixelsPerRadian, sensorOrientation)

        var lastPollNs = motion.nowNs()
        var anchorAttempts = 0
        var settleAttempts = 0
        var lastGateLog = 0L

        // The registration view's scale: full frame to registration pixels.
        val scale = stream.config.width.toFloat() / TRACK_WIDTH

        // What the placement thread has done, for the guidance thread to
        // draw: the session's own counters are not read across the two.
        val coverage = java.util.concurrent.atomic.AtomicReference(0f)
        // The burst being exposed at a node, between its lock and its frames.
        var collecting: Collecting? = null
        // Tiles the worker could not make: handed back to the guidance thread,
        // which owns the guide, to put the node back to pending.
        val failed = java.util.concurrent.ConcurrentLinkedQueue<Pair<MosaicNode, String>>()
        fun drainFailures() {
            while (true) {
                val (node, reason) = failed.poll() ?: break
                guide.rejected(node, System.currentTimeMillis())
                onEvent(MosaicEvent.Rejected(node, reason))
            }
        }

        coroutineScope {
            val tiles = Channel<PendingTile>(Channel.UNLIMITED)
            // The placement worker: one tile at a time, in the order taken.
            val worker = launch(placementDispatcher) {
                for (pending in tiles) {
                    val outcome = runCatching {
                        placeTile(stream, session, profile, lensProfile, scale, imu, pending)
                    }.onFailure { Log.w(TAG, "node ${pending.node.index} could not be placed", it) }
                        .getOrElse { PlaceOutcome.Failed(it.message ?: "placement failed") }
                    when (outcome) {
                        is PlaceOutcome.Placed -> {
                            coverage.set(session.progress.coverage)
                            onEvent(MosaicEvent.Placed(pending.node, outcome.byAttitude))
                        }
                        is PlaceOutcome.Failed -> {
                            // Not a registration refusal -- those are placed by
                            // the attitude. The develop failed or the canvas
                            // would not take it: the node goes back to pending,
                            // on the thread that owns the guide.
                            failed.add(pending.node to outcome.reason)
                        }
                    }
                }
            }

            try {
                while (shouldContinue() && !guide.complete) {
                    if (!imu.anchored) {
                        // The colour and the exposure every tile will be developed
                        // with, and the origin. The gain is measured from the anchor
                        // frame the way a single capture would measure it, and then
                        // held: each tile developed with its own automatic gain is
                        // a mosaic of nine exposures. The frame's own result has to
                        // carry the white balance gains, or there is nothing to
                        // develop with; a frame without them is waited past.
                        val burst = stream.snapshot(1)
                        // The shared ring no longer substitutes a neighbouring result.
                        // Await this frame's callback before using its colour/attitude.
                        try { burst.awaitResults() } catch (e: Exception) { burst.close(); throw e }
                        val result = burst.referenceResult
                        val color = ColorProfile.calibrated(stream.characteristics, result)
                        val buffer = burst.frames.buffer(0)
                        // And the attitude has to be reporting and settled, or
                        // there is nothing to anchor the directions to. Waited
                        // for up to a few seconds; a fusion that never settles is
                        // anchored anyway and said so.
                        val settled = motion.attitudeSettled() || settleAttempts++ >= MAX_SETTLE_WAIT_POLLS
                        val rotation = frameRotation(motion, result, realtimeStamps)
                        if (burst.count == 0 || buffer == null || result == null || rotation == null || !settled ||
                            (color.isNeutralGains && anchorAttempts++ < MAX_ANCHOR_WAIT_POLLS)
                        ) {
                            burst.close()
                            delay(ANCHOR_POLL_MILLIS)
                            continue
                        }
                        if (color.isNeutralGains) Log.w(TAG, "anchoring with unity white balance: the result carried no gains")
                        if (!motion.attitudeSettled()) Log.w(TAG, "anchoring on an attitude that has not settled")
                        val stride = burst.frames.rowStride
                        session.lockColour(color, ShadingMap.from(result, profile))
                        NativeMerge.create(stream.config.width, stream.config.height, profile)?.use { m ->
                            m.setReference(buffer, stride)
                            val (merged, _) = m.finish()
                            session.lockExposure(m.autoExposure(merged, color), exposureProduct(result))
                        }
                        // The anchor's proxy is the frame the result describes,
                        // read through the same sampling as the registration view.
                        val anchorTrack = ByteArray(TRACK_WIDTH * TRACK_HEIGHT)
                        RawRing.trackingLumaOf(buffer, stream.config.width, stream.config.height, stride,
                            anchorTrack, TRACK_WIDTH, TRACK_HEIGHT, profile)
                        burst.close()
                        session.anchor(Plane(TRACK_WIDTH, TRACK_HEIGHT, anchorTrack))
                        // The directions of the nodes are fixed from the attitude
                        // the anchor frame was exposed at.
                        imu.anchor(rotation)
                        Log.i(TAG, "anchored: gains %s, develop gain %.2f".format(
                            color.gains.joinToString(",") { "%.2f".format(it) }, session.developGain))
                        onEvent(MosaicEvent.Anchored)
                        onView(imuView(guide, imu, rotation, captureLabel, System.currentTimeMillis(), coverage.get()))
                        lastPollNs = motion.nowNs()
                        delay(POLL_MILLIS)
                        continue
                    }

                    // Anything the worker could not place goes back to pending
                    // before this poll decides anything.
                    drainFailures()

                    // The attitude now, and the gyroscope's peak since the last
                    // poll: the fastest the phone turned in between.
                    val rotation = motion.rotation()
                    val nowNs = motion.nowNs()
                    val peak = motion.peakSpeedSince(lastPollNs)
                    lastPollNs = nowNs

                    val now = System.currentTimeMillis()
                    val reticle = rotation?.let { imu.reticleOnCanvas(it) }
                    val decision = guide.observe(reticle?.first, reticle?.second, now, peak)

                    // The burst at the locked node, coming together from
                    // frames exposed after the lock and under the gate.
                    var exposeProgress = -1f
                    collecting?.let { c ->
                        val onTarget = reticle != null &&
                            kotlin.math.hypot(c.node.canvasX - reticle.first, c.node.canvasY - reticle.second) <= guide.lockRadius
                        when {
                            !onTarget -> {
                                // The reticle left before the frames came: the
                                // node is pending again, no burst taken.
                                Log.i(TAG, "node ${c.node.index}: reticle left before the burst; node given back")
                                guide.release(c.node)
                                collecting = null
                            }
                            !peak.isNaN() && peak > guide.maxAngularSpeed -> {
                                // A turn: whatever was exposed during it is not a
                                // frame of this tile. The count starts again.
                                c.sinceNs = freshSince(motion, stream, realtimeStamps)
                                c.restarts++
                                if (now - c.startedAtMillis > COLLECT_TIMEOUT_MILLIS) {
                                    Log.i(TAG, "node ${c.node.index}: the hand did not hold for the burst (${c.restarts} restarts)")
                                    guide.rejected(c.node, now)
                                    onEvent(MosaicEvent.Rejected(c.node, "hold still for the burst"))
                                    collecting = null
                                } else {
                                    exposeProgress = 0f
                                }
                            }
                            else -> {
                                val fresh = stream.readyFramesSince(c.sinceNs)
                                exposeProgress = (fresh.toFloat() / tileFrames).coerceIn(0f, 1f)
                                if (fresh >= tileFrames) {
                                    val burst = stream.snapshot(tileFrames)
                                    val hasResults = try { burst.awaitResults() }
                                        catch (e: Exception) { burst.close(); throw e }
                                    if (burst.count == 0 || !hasResults) {
                                        burst.close()
                                        guide.rejected(c.node, now)
                                        onEvent(MosaicEvent.Rejected(c.node, if (burst.count == 0) "ring empty" else "missing frame metadata"))
                                    } else {
                                        // The photograph is taken. The node is done, the
                                        // haptic fires -- the frames a haptic could blur are
                                        // in the ring's hand -- and the guidance moves on.
                                        // The tile is the worker's from here.
                                        val atFrame = frameRotation(motion, burst.results.getOrNull(0) ?: burst.referenceResult, realtimeStamps)
                                        guide.captured(c.node, now)
                                        val oldest = burst.frames.timestampsNs.minOrNull() ?: c.sinceNs
                                        Log.i(
                                            TAG,
                                            "node ${c.node.index}: burst of ${burst.count} fresh frames in hand, " +
                                                "first exposed %.0f ms after the lock%s".format(
                                                    (oldest - c.sinceNs) / 1e6,
                                                    if (c.restarts > 0) ", after ${c.restarts} restarts" else "",
                                                ),
                                        )
                                        onEvent(MosaicEvent.Locked(c.node))
                                        tiles.send(PendingTile(c.node, burst, atFrame))
                                    }
                                    collecting = null
                                    exposeProgress = -1f
                                } else if (now - c.startedAtMillis > COLLECT_TIMEOUT_MILLIS) {
                                    Log.i(TAG, "node ${c.node.index}: only $fresh fresh frames in ${COLLECT_TIMEOUT_MILLIS} ms; node given back")
                                    guide.rejected(c.node, now)
                                    onEvent(MosaicEvent.Rejected(c.node, "the ring gave no frames"))
                                    collecting = null
                                }
                            }
                        }
                    }
                    onView(imuView(guide, imu, rotation, captureLabel, now, coverage.get(), exposeProgress))
                    // The gate's readings, once a second: what the hold is waiting on.
                    if (now - lastGateLog > 1000L) {
                        lastGateLog = now
                        Log.i(
                            TAG,
                            "gate: reticle %s, gyro peak %.4f rad/s of %.4f, %s%s, hold %.2f".format(
                                reticle?.let { "(%.0f, %.0f)".format(it.first, it.second) } ?: "off-canvas",
                                peak, guide.maxAngularSpeed,
                                if (guide.steady) "steady" else "unsteady",
                                guide.unsteadyReason?.let { " ($it)" } ?: "",
                                guide.holdProgress(now),
                            ),
                        )
                    }

                    when (decision) {
                        is MosaicGuide.Decision.TooFast -> onEvent(MosaicEvent.TooFast)
                        is MosaicGuide.Decision.Capture -> {
                            // The hold is confirmed. Not a frame in the ring is
                            // taken for it: they were exposed while the hand
                            // arrived. The burst is the next `tileFrames` frames
                            // the sensor exposes, from now, with the gate held
                            // through them; see the note on the class.
                            collecting = Collecting(decision.node, freshSince(motion, stream, realtimeStamps), now)
                            Log.i(TAG, "node ${decision.node.index}: hold confirmed; exposing $tileFrames fresh frames")
                            onView(imuView(guide, imu, rotation, captureLabel, now, coverage.get(), 0f))
                        }
                        MosaicGuide.Decision.Idle -> Unit
                    }
                    delay(POLL_MILLIS)
                }
            } finally {
                // Whatever ended the loop: no more bursts, and the stream is
                // given back its exposure, its white balance and its focus
                // while the last tiles are still being placed. The sensors are
                // the caller's -- the screen keeps them running while the mode
                // is armed, so the next run anchors on an attitude that has
                // long since settled. A burst begun and not taken is no burst:
                // its node is pending, not capturing, in the final view.
                collecting?.let { guide.release(it.node) }
                tiles.close()
                stream.unlock(settings, caps)
            }
            // The tiles already taken are finished and composited before the
            // canvas is written: a burst in hand is a photograph owed. A tile
            // that failed after the loop ended leaves its node pending in a
            // run that is over -- the result says how many tiles there are.
            worker.join()
            drainFailures()
            if (guide.complete) onEvent(MosaicEvent.Complete)
            onView(imuView(guide, imu, motion.rotation(), captureLabel, System.currentTimeMillis(), session.progress.coverage))
        }

        val progress = session.progress
        val name = if (progress.placed > 0) {
            session.save(context, "MF_${stamp()}_mosaic_${progress.placed}t.jpg")
        } else null

        val elapsed = System.currentTimeMillis() - started
        return MosaicResult(
            tiles = progress.placed,
            rejected = progress.rejected,
            coverage = progress.coverage,
            megapixels = session.plan.megapixels,
            savedName = name,
            elapsedMillis = elapsed,
            message = when {
                progress.placed == 0 -> "no tiles were captured"
                name == null -> "captured ${progress.placed} tiles but could not save"
                guide.complete -> "mosaic complete: ${progress.placed} of ${guide.total} tiles"
                else -> "partial mosaic: ${progress.placed} of ${guide.total} tiles"
            },
        )
    }

    /**
     * The screen's view of a running mosaic: every node's state and, from the
     * attitude, where each falls in the frame and where the canvas's outline
     * is. With no attitude yet, the states alone.
     */
    private fun imuView(
        guide: MosaicGuide,
        imu: ImuGuidance,
        rotation: FloatArray?,
        captureLabel: String,
        nowMillis: Long,
        coverage: Float,
        exposeProgress: Float = -1f,
    ): MosaicGuideView {
        val positions = rotation?.let { imu.nodesInFrame(it) }
        val nodes = guide.nodes.mapIndexed { i, node ->
            val p = positions?.getOrNull(i)
            NodeView(node = node, state = guide.stateOf(node), frameX = p?.first, frameY = p?.second)
        }
        return MosaicGuideView(
            nodes = nodes,
            captured = guide.captured,
            total = guide.total,
            located = rotation != null,
            coverage = coverage,
            canvasCorners = rotation?.let { imu.cornersInFrame(it) },
            holdProgress = guide.holdProgress(nowMillis),
            steady = guide.steady,
            unsteady = if (guide.lockingNodeOrNull != null) guide.unsteadyReason else null,
            captureLensLabel = captureLabel,
            anchored = true,
            activeNode = guide.activeNode?.index ?: -1,
            latch = latchAt(guide, nowMillis),
            exposeProgress = exposeProgress,
        )
    }

    /**
     * The moment from which a frame counts as exposed for the burst: now,
     * on the sensors' clock, where the camera stamps frames on it -- a frame
     * whose exposure began before the lock is not fresh however soon it
     * lands -- and otherwise just after the newest frame the ring holds,
     * which admits the frames in flight but none the ring already has.
     */
    private fun freshSince(motion: MotionSensor, stream: ZslRawStream, realtimeStamps: Boolean): Long =
        if (realtimeStamps) motion.nowNs() else stream.newestFrameTimestampNs() + 1

    /** How far through its [MosaicGuide.LATCH_MILLIS] the latch is, or -1 when none is being drawn. */
    private fun latchAt(guide: MosaicGuide, nowMillis: Long): Float {
        if (guide.lastCapturedAt <= 0L) return -1f
        val age = nowMillis - guide.lastCapturedAt
        return if (age in 0 until MosaicGuide.LATCH_MILLIS) age.toFloat() / MosaicGuide.LATCH_MILLIS else -1f
    }

    /**
     * The attitude the phone had when [result]'s frame was exposed, where the
     * camera stamps frames on the sensors' clock; the current attitude where
     * it does not. Null before the attitude sensor has reported.
     */
    private fun frameRotation(
        motion: MotionSensor,
        result: android.hardware.camera2.TotalCaptureResult?,
        realtimeStamps: Boolean,
    ): FloatArray? {
        val stamp = result?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
        val exposure = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        // The stamp is the start of exposure; the middle is the attitude the
        // frame's centre of mass was taken at.
        return if (realtimeStamps && stamp != null) motion.rotationAt(stamp + exposure / 2) else motion.rotation()
    }

    /**
     * The screen's view of the mosaic: every node's state and, when the frame
     * registered, where each falls in it, and the canvas's outline.
     */
    fun viewOf(
        guide: MosaicGuide,
        plan: MosaicPlan,
        transform: Homography?,
        captureLabel: String,
        nowMillis: Long,
        coverage: Float = 0f,
        anchored: Boolean,
    ): MosaicGuideView {
        val inverse = transform?.invert()
        val w = plan.tileWidthPx.toFloat()
        val h = plan.tileHeightPx.toFloat()
        // Canvas to frame fractions: through the frame's registration when
        // there is one, else as the anchor would place the frame -- centred.
        fun toFrame(cx: Float, cy: Float): Pair<Float, Float>? {
            if (w <= 0f || h <= 0f) return null
            if (inverse != null) {
                val p = inverse.apply(cx, cy)
                if (p[0].isNaN() || p[1].isNaN()) return null
                return p[0] / w to p[1] / h
            }
            if (anchored) return null
            val originX = (plan.canvasWidth - plan.placedTileWidth) / 2f
            val originY = (plan.canvasHeight - plan.placedTileHeight) / 2f
            return (cx - originX) / plan.placedTileWidth to (cy - originY) / plan.placedTileHeight
        }
        val nodes = guide.nodes.map { node ->
            val p = toFrame(node.canvasX, node.canvasY)
            NodeView(node = node, state = guide.stateOf(node), frameX = p?.first, frameY = p?.second)
        }
        val cw = plan.canvasWidth.toFloat()
        val ch = plan.canvasHeight.toFloat()
        val corners = listOf(0f to 0f, cw to 0f, cw to ch, 0f to ch).map { (x, y) -> toFrame(x, y) }
        return MosaicGuideView(
            nodes = nodes,
            captured = guide.captured,
            total = guide.total,
            located = transform != null,
            coverage = coverage,
            canvasCorners = if (corners.all { it != null }) corners.map { it!! } else null,
            holdProgress = guide.holdProgress(nowMillis),
            steady = guide.steady,
            unsteady = if (guide.lockingNodeOrNull != null) guide.unsteadyReason else null,
            captureLensLabel = captureLabel,
            anchored = anchored,
            activeNode = if (anchored) guide.activeNode?.index ?: -1 else -1,
            latch = latchAt(guide, nowMillis),
        )
    }

    /** The view before the mosaic begins: the outline and the nodes as the anchor would place them. */
    fun armedView(plan: MosaicPlan, captureLabel: String): MosaicGuideView =
        viewOf(MosaicGuide(plan), plan, null, captureLabel, System.currentTimeMillis(), anchored = false)

    /**
     * Waits for the camera's controls to converge, up to a bound. True when
     * they did; false when the wait ran out or the user stopped.
     */
    private suspend fun awaitConvergence(stream: ZslRawStream, shouldContinue: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + CONVERGENCE_TIMEOUT_MILLIS
        while (shouldContinue() && System.currentTimeMillis() < deadline) {
            if (stream.controlsConverged() == true) return true
            delay(50)
        }
        return stream.controlsConverged() == true
    }

    /**
     * The lens's focal length in the frame's pixels, from what the camera
     * publishes about itself, most calibrated source first: the intrinsic
     * calibration's `f_x`, in active-array pixels, scaled to the frame; then
     * the focal length in millimetres over the sensor's physical pitch; then
     * the 35mm equivalent. This is the `f` of `f · tan(Δyaw)`: one pixel of
     * it wrong is one pixel the dot lands off the doorknob at a degree.
     */
    internal fun pixelsPerRadian(stream: ZslRawStream): Float {
        val c = stream.characteristics
        val array = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val intrinsics = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (intrinsics != null && intrinsics.size >= 2 && array != null && array.width() > 0 && intrinsics[0] > 0f) {
            // [f_x, f_y, c_x, c_y, s] in active-array pixels; the frame is
            // the active array at the stream's size.
            return intrinsics[0] * (stream.config.width.toFloat() / array.width())
        }
        val focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physical = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        // The physical size is of the whole sensor and the frame is the active
        // array; the ratio scales the width the frame actually spans.
        val fullPx = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: array?.width() ?: 0
        val frameMm = if (physical != null && fullPx > 0 && array != null) physical.width * (array.width().toFloat() / fullPx) else 0f
        val fromPhysical = StabilityGate.pixelsPerRadian(focal, frameMm, stream.config.width)
        return if (fromPhysical > 0f) fromPhysical
        else StabilityGate.pixelsPerRadianFromEquivalent(stream.lens.equivalent35mm, stream.config.width)
    }

    /** Polls to wait for a frame whose result carries white balance gains before anchoring without. */
    private const val MAX_ANCHOR_WAIT_POLLS = 30

    /** Polls to wait for the attitude to settle before anchoring on it as it is: about four seconds. */
    private const val MAX_SETTLE_WAIT_POLLS = 45

    /** What became of a burst on the placement thread. */
    private sealed interface PlaceOutcome {
        class Placed(val byAttitude: Boolean) : PlaceOutcome
        class Failed(val reason: String) : PlaceOutcome
    }

    /**
     * Turns a node's burst into a tile on the canvas: merged, developed with
     * the mosaic's locked colour, corrected for the lens, and composited --
     * where its pixels registered if they registered well, and where the
     * attitude says the phone was pointing if they did not. The two tiers.
     *
     * Runs on [placementDispatcher] while the guidance thread keeps polling.
     * Each of the ring's slots is released the moment the merge has read it
     * ([RingSnapshot.release]), so the ring refills under the merge and the
     * next node's fresh frames have somewhere to land.
     */
    private fun placeTile(
        stream: ZslRawStream,
        session: MosaicSession,
        profile: SensorProfile,
        lensProfile: LensProfile?,
        proxyScale: Float,
        imu: ImuGuidance,
        pending: PendingTile,
    ): PlaceOutcome {
        val burst = pending.burst
        val frameCount = burst.count
        val merger = NativeMerge.create(stream.config.width, stream.config.height, profile)
            ?: run { burst.close(); return PlaceOutcome.Failed("no memory for the merge") }

        return merger.use { m ->
            val stride = burst.frames.rowStride
            // The newest frame is the reference: it is the one the user saw
            // when the lock fired, and the one the attitude was read for.
            val refIndex = 0
            val referenceResult = burst.results.getOrNull(refIndex) ?: burst.referenceResult
            val refBuffer = burst.frames.buffer(refIndex)
                ?: run { burst.close(); return@use PlaceOutcome.Failed("reference unreadable") }
            m.setReference(refBuffer, stride)
            val refProxy = m.lumaProxy(refBuffer, stride)
            // The registration view of the reference frame, read through the
            // same sampling as the anchor's; then its slot goes back.
            val smallData = ByteArray(TRACK_WIDTH * TRACK_HEIGHT)
            RawRing.trackingLumaOf(refBuffer, stream.config.width, stream.config.height, stride,
                smallData, TRACK_WIDTH, TRACK_HEIGHT, profile)
            val small = Plane(TRACK_WIDTH, TRACK_HEIGHT, smallData)
            burst.frames.release(refIndex)

            val tilesX = maxOf(1, m.proxyWidth / TILE_TARGET)
            val tilesY = maxOf(1, m.proxyHeight / TILE_TARGET)
            for (i in 0 until burst.count) {
                if (i == refIndex) continue
                val buffer = burst.frames.buffer(i) ?: continue
                val proxy = m.lumaProxy(buffer, stride)
                val field = Aligner.alignNative(refProxy, proxy, tilesX, tilesY)
                    ?: Aligner.align(Aligner.buildPyramid(refProxy), Aligner.buildPyramid(proxy), tilesX, tilesY)
                m.addFrame(buffer, stride, field)
                burst.frames.release(i)
            }
            burst.close()

            // Tier one: the pixels. Tier two: the attitude. The attitude's
            // transform is computed either way, because it is what tier one
            // is judged against.
            val plan = session.plan
            val w = stream.config.width.toFloat()
            val h = stream.config.height.toFloat()
            val attitude = pending.rotation?.let { imu.tileTransform(it) }
            val located = session.locate(small, proxyScale)
            val visual = located?.takeIf { fit ->
                if (fit.inliers < MIN_VISUAL_INLIERS) {
                    Log.i(TAG, "node ${pending.node.index}: registration found only ${fit.inliers} inliers")
                    return@takeIf false
                }
                if (attitude == null) return@takeIf true
                val actual = fit.transform.apply(w / 2f, h / 2f)
                val predicted = attitude.apply(w / 2f, h / 2f)
                val residual = kotlin.math.hypot(actual[0] - predicted[0], actual[1] - predicted[1])
                val fittedWidth = kotlin.math.hypot(
                    fit.transform.apply(w, h / 2f)[0] - fit.transform.apply(0f, h / 2f)[0],
                    fit.transform.apply(w, h / 2f)[1] - fit.transform.apply(0f, h / 2f)[1],
                )
                val scaleError = fittedWidth / plan.placedTileWidth
                Log.i(TAG, "node ${pending.node.index}: registered %.0f px from where the attitude put it, %d inliers, %.2fx".format(
                    residual, fit.inliers, scaleError))
                residual <= plan.placedTileWidth * MAX_ATTITUDE_DISAGREEMENT &&
                    scaleError in (1f - MAX_SCALE_ERROR)..(1f + MAX_SCALE_ERROR)
            }
            val transform = visual?.transform ?: attitude
                ?: return@use PlaceOutcome.Failed(
                    if (located == null) "could not register the burst and the attitude was unknown"
                    else "registration disagreed with an unknown attitude",
                )
            val byAttitude = visual == null
            if (byAttitude) Log.i(TAG, "node ${pending.node.index}: placed by the attitude" +
                (session.lastLocateFailure?.let { " ($it)" } ?: ""))

            val (merged, stats) = m.finish()
            Log.i(TAG, "node ${pending.node.index}: merged %d of %d frames, mean contribution %.2f".format(
                stats.framesMerged, frameCount, stats.meanContribution))
            val color = session.color ?: ColorProfile.NEUTRAL
            // The anchor's gain, scaled by how this burst's exposure differed
            // from the anchor's -- which under the mosaic's lock should be not
            // at all, and this is what holds if it was.
            val gain = session.gainFor(exposureProduct(referenceResult))
            // Adapted to the ISO the lock pinned: a tile at ISO 3200 is not
            // sharpened, and its colour speckle is smoothed wider.
            val iso = referenceResult?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
            var tile = m.develop(
                merged, color, DevelopParams(exposureGain = gain).adaptedTo(iso), shading = session.shading,
            ) ?: return@use PlaceOutcome.Failed("develop failed")

            // The lens's geometry, before the tile is placed by a homography
            // that assumes a rectilinear image. Distortion left in would make
            // every seam a mismatch of curves. The correction crops the
            // border it could not read (LensDistortion.validInset); the
            // placement was fitted to the whole frame, so the cropped tile's
            // origin is moved to where its first pixel was.
            var inset = LensDistortion.Inset.NONE
            opticsFor(lensProfile, tile)?.let { params ->
                val corrected = Optics.applyCropped(tile, params)
                if (corrected.bitmap !== tile) {
                    tile.recycle()
                    tile = corrected.bitmap
                    inset = corrected.inset
                }
            }

            val placed = session.place(tile, small, transform, visual?.inliers ?: 0, visual?.inlierRatio ?: 0f, inset)
            tile.recycle()
            if (placed == null) PlaceOutcome.Failed("the canvas would not take it") else PlaceOutcome.Placed(byAttitude)
        }
    }

    private const val TILE_TARGET = 32

    /**
     * How far a registration may put a tile from where the attitude says the
     * phone was pointing, as a fraction of a placed tile's width, before the
     * attitude places it instead. The attitude's own error over a capture --
     * drift, the frame's timing, the anchor -- is a few percent; a quarter is
     * a wrong fit.
     */
    const val MAX_ATTITUDE_DISAGREEMENT = 0.25f

    /** How far a registration's fitted tile width may differ from a tile's before the attitude places it instead. */
    const val MAX_SCALE_ERROR = 0.17f

    /** Fewer inliers than this is a fit onto noise, and the attitude places the tile instead. */
    const val MIN_VISUAL_INLIERS = 10

    /** ISO times exposure, the sensor's own measure of how much light a frame gathered. */
    private fun exposureProduct(result: android.hardware.camera2.TotalCaptureResult?): Double {
        val iso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return 0.0
        val ns = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return 0.0
        return iso.toDouble() * ns.toDouble()
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
